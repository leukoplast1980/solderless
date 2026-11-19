/*
 * Solderless - ADC1 and ADC2, regular conversions (RM0440 chapter 21).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * The bring-up sequence is the chip's, with its waits: leave deep
 * power-down, start the voltage regulator and wait 20 us, calibrate,
 * enable and wait for ADRDY. The ADC needs a clock: by default
 * ADC12_CCR.CKMODE selects the asynchronous clock and RCC_CCIPR selects
 * none, so nothing happens until one of them is changed.
 *
 * Conversions take the sampling time plus the resolution's conversion
 * time in ADC clock cycles, and read the voltage on the channel's pin from
 * the board. Continuous mode without interrupts is not driven by events:
 * DR and ISR are brought up to date when read.
 *
 * Modelled: regular sequence (SQR1-4), single and continuous mode,
 * resolution, left/right alignment, sampling times, EOSMP/EOC/EOS/OVR with
 * OVRMOD, ADSTP, ADDIS, interrupts, the temperature sensor, VREFINT and
 * VBAT/3 channels. Not yet: injected conversions, external triggers,
 * oversampling, watchdogs, offsets, differential mode, DMA.
 */
#include "soc.h"

#include <string.h>

#define ISR   0x00u
#define IER   0x04u
#define CR    0x08u
#define CFGR  0x0Cu
#define SMPR1 0x14u
#define SMPR2 0x18u
#define SQR1  0x30u
#define DR    0x40u
#define CALFACT 0xB4u

#define ADRDY (1u << 0)
#define EOSMP (1u << 1)
#define EOC   (1u << 2)
#define EOS   (1u << 3)
#define OVR   (1u << 4)

#define ADEN    (1u << 0)
#define ADDIS   (1u << 1)
#define ADSTART (1u << 2)
#define ADSTP   (1u << 4)
#define ADVREGEN (1u << 28)
#define DEEPPWD (1u << 29)
#define ADCAL   (1u << 31)

#define CCR_COMMON 0x08u
#define REG_STARTUP_PS 20000000ull      /* tADCVREG_STUP: 20 us */

static uint32_t *R(struct sl_soc *s, struct sl_adc *a) { return s->regs[a->pi]; }

int adc_index_of_periph(struct sl_soc *s, int pi)
{
    for (int i = 0; i < SL_NUM_ADC; i++) if (s->adc[i].pi == pi) return i;
    return -1;
}

/* ADC clock in Hz, 0 if none */
static uint32_t adc_clock(struct sl_soc *s)
{
    uint32_t ccr = s->pi_adc_common >= 0 ? s->regs[s->pi_adc_common][CCR_COMMON / 4] : 0;
    uint32_t ckmode = (ccr >> 16) & 3u;
    if (ckmode) return s->hclk >> (ckmode - 1u);
    uint32_t sel = (s->regs[s->pi_rcc][0x88u / 4] >> 28) & 3u;
    uint32_t src = sel == 1u ? s->pllp : sel == 2u ? s->sysclk : 0u;
    static const uint16_t div[16] = { 1, 2, 4, 6, 8, 10, 12, 16, 32, 64, 128, 256, 256, 256, 256, 256 };
    return src / div[(ccr >> 18) & 15u];
}

static void no_clock_log(struct sl_soc *s, struct sl_adc *a)
{
    soc_log(s, LOG_ADC, "%s has no clock: ADC12_CCR.CKMODE selects the asynchronous clock and "
            "RCC_CCIPR.ADC12SEL selects none. Set CKMODE (HCLK/1, /2, /4) or ADC12SEL", a->name);
}

/* Conversion time of one channel in ADC clock half-cycles */
static uint32_t conv_half_cycles(struct sl_soc *s, struct sl_adc *a, int ch)
{
    static const uint16_t smp_half[8] = { 5, 13, 25, 49, 95, 185, 495, 1281 };   /* 2.5 .. 640.5 cycles */
    static const uint16_t res_half[4] = { 25, 21, 17, 13 };                      /* 12.5, 10.5, 8.5, 6.5 */
    uint32_t smpr = ch < 10 ? R(s, a)[SMPR1 / 4] >> (3 * ch) : R(s, a)[SMPR2 / 4] >> (3 * (ch - 10));
    uint32_t res = (R(s, a)[CFGR / 4] >> 3) & 3u;
    return smp_half[smpr & 7u] + res_half[res];
}

static int seq_len(struct sl_soc *s, struct sl_adc *a) { return (int)(R(s, a)[SQR1 / 4] & 15u) + 1; }

static int seq_channel(struct sl_soc *s, struct sl_adc *a, int k)
{
    /* SQ1-SQ4 in SQR1, SQ5-SQ9 in SQR2, SQ10-SQ14 in SQR3, SQ15-SQ16 in SQR4 */
    uint32_t reg, shift;
    if (k < 4) { reg = SQR1; shift = 6u + 6u * (uint32_t)k; }
    else { int j = k - 4; reg = SQR1 + 4u * (uint32_t)(1 + j / 5); shift = 6u * (uint32_t)(j % 5); }
    return (int)((R(s, a)[reg / 4] >> shift) & 31u);
}

static uint64_t conv_ps(struct sl_soc *s, struct sl_adc *a, int ch)
{
    uint32_t hz = adc_clock(s);
    if (!hz) return SL_NEVER;
    return soc_muldiv(SL_PS_PER_S, conv_half_cycles(s, a, ch), 2ull * hz);
}

/* Voltage on a channel in mV */
static int channel_mv(struct sl_soc *s, struct sl_adc *a, int ch)
{
    uint32_t common = s->regs[s->pi_adc_common][CCR_COMMON / 4];
    char want[8];
    if (a->idx == 0 && ch == 16) {                        /* temperature sensor at 25 degrees C */
        if (!((common >> 23) & 1u)) return 0;
        int cal1 = 0x0410, cal2 = 0x056C;                  /* at 30 and 130 C, VDDA = 3.0 V */
        int code30 = cal1 + (25 - 30) * (cal2 - cal1) / 100;
        return code30 * 3000 / 4095;
    }
    if (a->idx == 0 && ch == 18) return ((common >> 22) & 1u) ? 0x05F8 * 3000 / 4095 : 0;   /* VREFINT */
    if (a->idx == 0 && ch == 17) return ((common >> 24) & 1u) ? 3300 / 3 : 0;               /* VBAT/3 */
    want[0] = 'I'; want[1] = 'N';
    if (ch >= 10) { want[2] = (char)('0' + ch / 10); want[3] = (char)('0' + ch % 10); want[4] = 0; }
    else { want[2] = (char)('0' + ch); want[3] = 0; }
    for (int i = 0; i < sl_g431_nanalog; i++) {
        const struct sl_analog_info *e = &sl_g431_analog[i];
        if (strcmp(e->periph, a->name) || strcmp(e->signal, want)) continue;
        uint32_t mode = (s->regs[s->pi_gpio[e->port]][0] >> (2 * e->pin)) & 3u;
        if (mode != 3u)
            soc_log(s, LOG_ADC_PIN, "%s channel %d reads P%c%d, which is not in analog mode (GPIO%c_MODER): "
                    "the digital input stage disturbs the reading", a->name, ch, 'A' + e->port, e->pin, 'A' + e->port);
        int mv = board_pin_mv(s, e->port, e->pin);
        return mv < 0 ? 0 : mv;
    }
    return 0;
}

static uint32_t convert(struct sl_soc *s, struct sl_adc *a, int ch)
{
    uint32_t res = (R(s, a)[CFGR / 4] >> 3) & 3u, bits = 12u - 2u * res;
    int mv = channel_mv(s, a, ch);
    uint32_t code = (uint32_t)((mv * 4095 + 1650) / 3300);
    if (code > 4095u) code = 4095u;
    code >>= (12u - bits);
    if ((R(s, a)[CFGR / 4] >> 15) & 1u) code <<= (16u - bits);   /* left aligned */
    return code;
}

static void update_irq(struct sl_soc *s)
{
    int level = 0;
    for (int i = 0; i < SL_NUM_ADC; i++) {
        struct sl_adc *a = &s->adc[i];
        if (a->pi >= 0 && (R(s, a)[ISR / 4] & R(s, a)[IER / 4] & 0x7FFu)) level = 1;
    }
    soc_irq(s, 18, s->adc[0].pi, level);
}

/* Completes conversion number k of the running series. */
static void complete(struct sl_soc *s, struct sl_adc *a, int ch, int last_of_seq)
{
    uint32_t *r = R(s, a);
    uint32_t v = convert(s, a, ch);
    if (r[ISR / 4] & EOC) {
        r[ISR / 4] |= OVR;
        if ((r[CFGR / 4] >> 12) & 1u) a->dr = v;            /* OVRMOD: overwrite */
        if (!a->ovr_logged) {
            a->ovr_logged = 1;
            soc_log(s, LOG_ADC, "%s overrun: a conversion finished before DR was read", a->name);
        }
    } else {
        a->dr = v;
    }
    r[ISR / 4] |= EOSMP | EOC;
    if (last_of_seq) r[ISR / 4] |= EOS;
}

/* Brings the conversion series up to now. */
static void advance(struct sl_soc *s, struct sl_adc *a)
{
    if (!a->converting) return;
    uint64_t now = soc_now_ps(s);
    uint32_t *r = R(s, a);
    int n = seq_len(s, a), cont = (r[CFGR / 4] >> 13) & 1u;
    for (int guard = 0; guard < 64; guard++) {
        int ch = seq_channel(s, a, a->k);
        uint64_t len = conv_ps(s, a, ch);
        if (len == SL_NEVER || a->t_next + len > now) break;
        a->t_next += len;
        int last = a->k == n - 1;
        complete(s, a, ch, last);
        a->k = last ? 0 : a->k + 1;
        if (last && !cont) {
            a->converting = 0;
            r[CR / 4] &= ~ADSTART;
            break;
        }
        if (guard == 63) {
            /* Far behind (continuous mode, nobody looked): jump to now. */
            a->t_next = now;
        }
    }
    update_irq(s);
}

static void schedule(struct sl_soc *s, struct sl_adc *a)
{
    uint64_t at = SL_NEVER;
    uint32_t *r = R(s, a);
    if (a->cal_until) at = a->cal_until;
    if (a->rdy_at && a->rdy_at < at) at = a->rdy_at;
    if (a->converting) {
        int want = (r[IER / 4] & (EOSMP | EOC | EOS | OVR)) || a->polled || !((r[CFGR / 4] >> 13) & 1u);
        uint64_t len = conv_ps(s, a, seq_channel(s, a, a->k));
        if (want && len != SL_NEVER && a->t_next + len < at) at = a->t_next + len;
    }
    soc_sched(s, EV_ADC0 + a->idx, at);
}

void adc_event(struct sl_soc *s, int idx)
{
    struct sl_adc *a = &s->adc[idx];
    uint64_t now = soc_now_ps(s);
    uint32_t *r = R(s, a);
    if (a->cal_until && now >= a->cal_until) {
        a->cal_until = 0;
        r[CR / 4] &= ~ADCAL;
        r[CALFACT / 4] = 0x00000041u;                      /* a typical single-ended factor */
    }
    if (a->rdy_at && now >= a->rdy_at) {
        a->rdy_at = 0;
        if (r[CR / 4] & ADEN) r[ISR / 4] |= ADRDY;
    }
    advance(s, a);
    update_irq(s);
    schedule(s, a);
}

void adc_poll_hint(struct sl_soc *s, int idx)
{
    s->adc[idx].polled = 1;
    schedule(s, &s->adc[idx]);
}

static int regulator_ready(struct sl_soc *s, struct sl_adc *a)
{
    uint32_t cr = R(s, a)[CR / 4];
    return !(cr & DEEPPWD) && (cr & ADVREGEN) && a->reg_on_at != SL_NEVER
           && soc_now_ps(s) >= a->reg_on_at + REG_STARTUP_PS;
}

uint32_t adc_read(struct sl_soc *s, int idx, uint32_t off, int effects)
{
    struct sl_adc *a = &s->adc[idx];
    uint32_t *r = R(s, a);
    if (effects) advance(s, a);
    if (off == DR) {
        uint32_t v = a->dr;
        if (effects) { r[ISR / 4] &= ~EOC; update_irq(s); }
        return v;
    }
    return r[off / 4];
}

void adc_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask)
{
    struct sl_adc *a = &s->adc[idx];
    uint32_t *r = R(s, a);
    uint32_t wm = s->wmask[a->pi][off / 4] & mask;
    advance(s, a);
    a->polled = 0;
    uint64_t now = soc_now_ps(s);
    if (off == ISR) {
        r[ISR / 4] &= ~(v & wm);                            /* write 1 to clear */
    } else if (off == CR) {
        uint32_t was = r[CR / 4], w = v & mask;
        /* Control bits are set by software and cleared by hardware. */
        uint32_t keep = was & (ADEN | ADSTART | ADCAL);
        r[CR / 4] = ((was & ~wm) | (w & wm)) | keep;
        if (r[CR / 4] & DEEPPWD) {
            if (!(was & ADVREGEN) && (r[CR / 4] & ADVREGEN))
                soc_log(s, LOG_ADC, "%s: ADVREGEN set while DEEPPWD is still 1: the regulator stays off", a->name);
            a->reg_on_at = SL_NEVER;
        } else if (!(r[CR / 4] & ADVREGEN)) {
            a->reg_on_at = SL_NEVER;
        } else if (a->reg_on_at == SL_NEVER) {
            a->reg_on_at = now;                             /* regulator starts now */
        }
        if ((w & ADCAL) && !(was & ADCAL)) {
            if (was & ADEN) {
                soc_log(s, LOG_ADC, "%s: calibration started while ADEN = 1; it needs a disabled ADC", a->name);
                r[CR / 4] &= ~ADCAL;
            } else if (!regulator_ready(s, a)) {
                soc_log(s, LOG_ADC, "%s: calibration started before the voltage regulator is ready "
                        "(DEEPPWD = 0, ADVREGEN = 1, then 20 us)", a->name);
                r[CR / 4] &= ~ADCAL;
            } else if (!adc_clock(s)) {
                no_clock_log(s, a);
                r[CR / 4] |= ADCAL;                         /* never finishes */
            } else {
                r[CR / 4] |= ADCAL;
                a->cal_until = now + soc_muldiv(SL_PS_PER_S, 116u, adc_clock(s));
            }
        }
        if ((w & ADEN) && !(was & ADEN)) {
            if (!regulator_ready(s, a)) {
                soc_log(s, LOG_ADC, "%s: ADEN set before the voltage regulator is ready "
                        "(DEEPPWD = 0, ADVREGEN = 1, then 20 us): ADRDY will not come", a->name);
            } else if (!adc_clock(s)) {
                no_clock_log(s, a);
                r[CR / 4] |= ADEN;
            } else {
                r[CR / 4] |= ADEN;
                a->rdy_at = now + soc_muldiv(SL_PS_PER_S, 8u, adc_clock(s));
            }
        }
        if ((w & ADDIS) && (r[CR / 4] & ADEN)) {
            r[CR / 4] &= ~(ADEN | ADDIS | ADSTART);
            r[ISR / 4] &= ~ADRDY;
            a->converting = 0;
        }
        if ((w & ADSTART) && !(was & ADSTART)) {
            if (!(r[ISR / 4] & ADRDY) || !(r[CR / 4] & ADEN)) {
                soc_log(s, LOG_ADC, "%s: ADSTART while the ADC is not enabled and ready (ADRDY)", a->name);
            } else {
                r[CR / 4] |= ADSTART;
                a->converting = 1;
                a->k = 0;
                a->t_next = now;
                a->ovr_logged = 0;
            }
        }
        if ((w & ADSTP) && a->converting) {
            a->converting = 0;
            r[CR / 4] &= ~(ADSTART | ADSTP);
        }
        r[CR / 4] &= ~ADSTP;
    } else {
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
    }
    update_irq(s);
    schedule(s, a);
}

void adc_reset(struct sl_soc *s, int idx)
{
    struct sl_adc *a = &s->adc[idx];
    a->converting = 0; a->k = 0; a->dr = 0; a->polled = 0;
    a->cal_until = 0; a->rdy_at = 0; a->reg_on_at = SL_NEVER;
    soc_sched(s, EV_ADC0 + idx, SL_NEVER);
}

void adc_init(struct sl_soc *s)
{
    static const char *const names[SL_NUM_ADC] = { "ADC1", "ADC2" };
    for (int i = 0; i < SL_NUM_ADC; i++) {
        struct sl_adc *a = &s->adc[i];
        memset(a, 0, sizeof *a);
        a->idx = i;
        a->name = names[i];
        a->pi = -1;
        for (int p = 0; p < s->np; p++) if (!strcmp(sl_g431_periph[p].name, names[i])) a->pi = p;
        a->reg_on_at = SL_NEVER;
    }
}
