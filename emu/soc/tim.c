/*
 * Solderless - timers TIM1-4, TIM6-8, TIM15-17 (RM0440 chapters 28-31).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * The counter is not ticked. Each timer remembers when its counter had a
 * known value; CNT, the flags and the outputs are worked out from the time
 * whenever they are read. Events are scheduled only where firmware can see
 * them: an enabled interrupt, one-pulse mode stopping the counter, or the
 * CPU polling the status register. A PWM output reaches the board as a
 * duty cycle and frequency, not as edges.
 *
 * Modelled: prescaler, auto-reload with preload, up, down and centre-
 * aligned counting, update events (UDIS, URS, UG), repetition counter,
 * one-pulse mode, output compare and PWM modes 1 and 2 with polarity and
 * complementary outputs, MOE on the advanced timers, input capture of pin
 * edges, encoder modes 1-3 (SMS 001, 010, 011: counting on TI1, TI2 or both
 * edges, up or down by the other input's level, with CC1P and CC2P, DIR set
 * by the hardware, wrapping at ARR with an update event). Not modelled: the
 * other slave modes and triggers, the G4's clock-plus-direction and x1
 * encoder modes, the index input, input filters, dead time, break inputs,
 * DMA, dithering.
 */
#include "soc.h"

#include <string.h>

#define CR1    0x00u
#define SMCR   0x08u
#define DIER   0x0Cu
#define SR     0x10u
#define EGR    0x14u
#define CCMR1  0x18u
#define CCMR2  0x1Cu
#define CCER   0x20u
#define CNT    0x24u
#define PSC    0x28u
#define ARR    0x2Cu
#define RCR    0x30u
#define CCR1   0x34u
#define BDTR   0x44u

#define CEN    (1u << 0)
#define UDIS   (1u << 1)
#define URS    (1u << 2)
#define OPM    (1u << 3)
#define DIR    (1u << 4)
#define ARPE   (1u << 7)

static const char *const tim_names[SL_NUM_TIM] = {
    "TIM1", "TIM2", "TIM3", "TIM4", "TIM6", "TIM7", "TIM8", "TIM15", "TIM16", "TIM17"
};

static uint32_t *R(struct sl_soc *s, struct sl_tim *t) { return s->regs[t->pi]; }

static uint32_t mask_bits(const struct sl_tim *t) { return t->bits32 ? 0xFFFFFFFFu : 0xFFFFu; }

int tim_index_of_periph(struct sl_soc *s, int pi)
{
    for (int i = 0; i < SL_NUM_TIM; i++)
        if (s->tim[i].pi == pi) return i;
    return -1;
}

/* Counter clock: the APB clock, doubled when the APB prescaler divides. */
static uint32_t kernel_hz(struct sl_soc *s, const struct sl_tim *t)
{
    uint32_t pclk = t->apb2 ? s->pclk2 : s->pclk1;
    return pclk == s->hclk ? pclk : 2u * pclk;
}

static uint64_t tick_ps(struct sl_soc *s, const struct sl_tim *t)
{
    uint64_t hz = kernel_hz(s, t);
    if (!hz) return SL_NEVER;
    uint64_t ps = soc_muldiv(SL_PS_PER_S, (uint64_t)t->psc + 1u, hz);
    return ps ? ps : 1;
}

static int centre(struct sl_soc *s, struct sl_tim *t) { return ((R(s, t)[CR1 / 4] >> 5) & 3u) != 0; }

/* Length of one counter cycle in counts */
static uint64_t cycle_counts(struct sl_soc *s, struct sl_tim *t)
{
    if (!t->arr) return 0;
    return centre(s, t) ? 2ull * t->arr : (uint64_t)t->arr + 1u;
}

/* Position in the cycle -> counter value */
static uint32_t pos_to_cnt(struct sl_soc *s, struct sl_tim *t, uint64_t pos)
{
    if (centre(s, t)) return (uint32_t)(pos <= t->arr ? pos : 2ull * t->arr - pos);
    if (R(s, t)[CR1 / 4] & DIR) return (uint32_t)(t->arr - pos);
    return (uint32_t)pos;
}

static uint64_t cnt_to_pos(struct sl_soc *s, struct sl_tim *t, uint32_t cnt, int down)
{
    if (cnt > t->arr) cnt = t->arr;
    if (centre(s, t)) return down ? 2ull * t->arr - cnt : cnt;
    if (R(s, t)[CR1 / 4] & DIR) return t->arr - cnt;
    return cnt;
}

static void update_irqs(struct sl_soc *s, struct sl_tim *t)
{
    uint32_t act = R(s, t)[SR / 4] & R(s, t)[DIER / 4];
    if (t->irq_up >= 0) soc_irq(s, t->irq_up, t->pi, (act & 1u) != 0);
    if (t->irq_cc >= 0 && t->irq_cc != t->irq_up) soc_irq(s, t->irq_cc, t->pi, (act & 0x1Eu) != 0);
    else if (t->irq_cc >= 0) soc_irq(s, t->irq_up, t->pi, (act & 0x1Fu) != 0);
}

/* Loads the preloaded values, as an update event does. */
static void load_shadows(struct sl_soc *s, struct sl_tim *t)
{
    uint32_t *r = R(s, t);
    t->psc = r[PSC / 4] & 0xFFFFu;
    t->arr = r[ARR / 4] & mask_bits(t);
    t->rcr = r[RCR / 4] & 0xFFFFu;
    for (int ch = 0; ch < 4; ch++) t->ccr[ch] = r[(CCR1 + 4u * (uint32_t)ch) / 4] & mask_bits(t);
}

static uint32_t ccmr_field(struct sl_soc *s, struct sl_tim *t, int ch, int shift)
{
    uint32_t v = R(s, t)[(ch < 2 ? CCMR1 : CCMR2) / 4];
    return v >> ((ch & 1) * 8 + shift);
}
static int cc_is_output(struct sl_soc *s, struct sl_tim *t, int ch) { return (ccmr_field(s, t, ch, 0) & 3u) == 0; }
static uint32_t oc_mode(struct sl_soc *s, struct sl_tim *t, int ch)
{
    return (ccmr_field(s, t, ch, 4) & 7u) | ((ccmr_field(s, t, ch, 16) & 1u) << 3);
}
static int oc_preload(struct sl_soc *s, struct sl_tim *t, int ch) { return (ccmr_field(s, t, ch, 3) & 1u) != 0; }

/* Counter position now and how many whole cycles passed since t->t0. */
static void position(struct sl_soc *s, struct sl_tim *t, uint64_t now, uint64_t *pos, uint64_t *cycles)
{
    uint64_t len = cycle_counts(s, t);
    if (!t->running || !len || t->tick == SL_NEVER || now <= t->t0) { *pos = t->pos0; *cycles = 0; return; }
    uint64_t ticks = (now - t->t0) / t->tick;
    uint64_t p = t->pos0 + ticks;
    *cycles = p / len;
    *pos = p % len;
}

/* Time at which the counter reaches position target (next time, strictly after now). */
static uint64_t time_of_pos(struct sl_soc *s, struct sl_tim *t, uint64_t now, uint64_t target)
{
    uint64_t len = cycle_counts(s, t);
    if (!t->running || !len || t->tick == SL_NEVER) return SL_NEVER;
    uint64_t ticks = now > t->t0 ? (now - t->t0) / t->tick : 0;
    uint64_t p = t->pos0 + ticks, base = p - p % len, at = base + target;
    if (at <= p) at += len;
    return t->t0 + (at - t->pos0) * t->tick;
}

/* Brings flags and the counter up to now: update events and compare
 * matches since the last call set their flags; the first update loads
 * preloaded registers; one-pulse mode stops the counter. */
static void advance(struct sl_soc *s, struct sl_tim *t)
{
    uint64_t now = soc_now_ps(s);
    if (!t->running) { t->t_seen = now; return; }
    uint32_t *r = R(s, t);
    for (int guard = 0; guard < 4; guard++) {
        uint64_t len = cycle_counts(s, t);
        if (!len || t->tick == SL_NEVER) break;
        uint64_t seen_ticks = t->t_seen > t->t0 ? (t->t_seen - t->t0) / t->tick : 0;
        uint64_t now_ticks = now > t->t0 ? (now - t->t0) / t->tick : 0;
        uint64_t p0 = t->pos0 + seen_ticks, p1 = t->pos0 + now_ticks;
        if (p1 == p0) break;
        /* Compare matches in (p0, p1] */
        for (int ch = 0; ch < t->nch; ch++) {
            uint32_t ccr = t->ccr[ch];
            if (ccr > t->arr) continue;
            uint64_t tp[2] = { cnt_to_pos(s, t, ccr, 0), cnt_to_pos(s, t, ccr, 1) };
            int n = centre(s, t) && ccr != 0 && ccr != t->arr ? 2 : 1;
            for (int k = 0; k < n; k++) {
                uint64_t first = p0 - p0 % len + tp[k];
                if (first <= p0) first += len;
                if (first <= p1) {
                    if (r[SR / 4] & (2u << ch)) r[SR / 4] |= 0x200u << ch;     /* overcapture/overcompare */
                    r[SR / 4] |= 2u << ch;
                }
            }
        }
        /* Update events: wrap points in (p0, p1] */
        uint64_t first_wrap = p0 - p0 % len + len;          /* position len == next cycle start */
        uint64_t half = centre(s, t) ? t->arr : 0;          /* centre-aligned: also at the top */
        int upd = first_wrap <= p1 || (half && (p0 - p0 % len + half > p0) && (p0 - p0 % len + half <= p1));
        if (!upd) break;
        uint64_t wraps = p1 >= first_wrap ? (p1 - first_wrap) / len + 1 : 0;
        uint64_t uev_count = centre(s, t) ? wraps * 2 + 1 : wraps;
        int uev = 0;
        if (!(r[CR1 / 4] & UDIS)) {
            uint64_t need = (uint64_t)t->rep + 1u;
            if (!t->adv || uev_count >= need) { uev = 1; t->rep = t->adv ? t->rcr : 0; }
            else t->rep -= (uint32_t)uev_count;
        }
        if (uev) {
            r[SR / 4] |= 1u;
            uint32_t old_arr = t->arr, old_psc = t->psc;
            uint64_t at = time_of_pos(s, t, t->t_seen, centre(s, t) && half ? half : len);
            if (r[CR1 / 4] & OPM) {
                /* One pulse: the counter stops at the update event. */
                t->running = 0;
                r[CR1 / 4] &= ~CEN;
                t->pos0 = 0;
                t->t0 = at;
                load_shadows(s, t);
                board_af_outputs_changed(s);
                break;
            }
            uint32_t pend_arr = r[ARR / 4] & mask_bits(t), pend_psc = r[PSC / 4] & 0xFFFFu;
            int changes = pend_arr != old_arr || pend_psc != old_psc;
            for (int ch = 0; ch < t->nch; ch++)
                if ((r[(CCR1 + 4u * (uint32_t)ch) / 4] & mask_bits(t)) != t->ccr[ch]) changes = 1;
            if (changes) {
                /* Restart the arithmetic at the first update with the new values. */
                t->t0 = at;
                t->pos0 = 0;
                t->t_seen = at;
                load_shadows(s, t);
                t->tick = tick_ps(s, t);
                board_af_outputs_changed(s);
                continue;
            }
        }
        break;
    }
    t->t_seen = now;
    update_irqs(s, t);
}

/* Next time something firmware waits for happens. */
static void schedule(struct sl_soc *s, struct sl_tim *t)
{
    int ev = EV_TIM0 + (int)(t - s->tim);
    uint64_t now = soc_now_ps(s), at = SL_NEVER;
    if (t->running) {
        uint32_t *r = R(s, t), dier = r[DIER / 4];
        /* A preloaded value waits for the next update: the outputs change then. */
        int pending = (r[ARR / 4] & mask_bits(t)) != t->arr || (r[PSC / 4] & 0xFFFFu) != t->psc;
        for (int ch = 0; ch < t->nch; ch++)
            if ((r[(CCR1 + 4u * (uint32_t)ch) / 4] & mask_bits(t)) != t->ccr[ch]) pending = 1;
        int want_upd = (dier & 1u) || (r[CR1 / 4] & OPM) || t->polled || pending;
        if (want_upd) {
            uint64_t len = cycle_counts(s, t);
            if (len) {
                uint64_t x = time_of_pos(s, t, now, len % len);
                if (x < at) at = x;
                if (centre(s, t)) { x = time_of_pos(s, t, now, t->arr); if (x < at) at = x; }
            }
        }
        for (int ch = 0; ch < t->nch; ch++) {
            if (!((dier >> (ch + 1)) & 1u) && !t->polled) continue;
            if (!cc_is_output(s, t, ch) || t->ccr[ch] > t->arr) continue;
            uint64_t x = time_of_pos(s, t, now, cnt_to_pos(s, t, t->ccr[ch], 0));
            if (x < at) at = x;
        }
    }
    soc_sched(s, ev, at);
}

void tim_event(struct sl_soc *s, int idx)
{
    struct sl_tim *t = &s->tim[idx];
    advance(s, t);
    schedule(s, t);
}

/* The CPU is polling this timer's flags: wake it when they can change. */
void tim_poll_hint(struct sl_soc *s, int idx)
{
    struct sl_tim *t = &s->tim[idx];
    t->polled = 1;
    schedule(s, t);
}

/* Encoder mode 1, 2 or 3 from SMCR.SMS (bit 16 is SMS[3]); 0 otherwise */
static int enc_mode(struct sl_soc *s, struct sl_tim *t)
{
    uint32_t v = R(s, t)[SMCR / 4];
    uint32_t sms = (v & 7u) | ((v >> 13) & 8u);
    return sms >= 1 && sms <= 3 ? (int)sms : 0;
}

static void start(struct sl_soc *s, struct sl_tim *t)
{
    uint32_t *r = R(s, t);
    if (t->nch >= 2 && enc_mode(s, t)) { t->running = 0; return; }   /* the inputs clock it, not time */
    t->running = 1;
    t->t0 = t->t_seen = soc_now_ps(s);
    t->tick = tick_ps(s, t);
    uint32_t cnt = r[CNT / 4] & mask_bits(t);
    t->pos0 = cnt_to_pos(s, t, cnt, 0);
    if (!t->arr) soc_log(s, LOG_TIMER, "%s started with ARR = 0: the counter stays blocked", t->name);
}

static void stop(struct sl_soc *s, struct sl_tim *t)
{
    uint64_t pos, cyc;
    advance(s, t);
    position(s, t, soc_now_ps(s), &pos, &cyc);
    R(s, t)[CNT / 4] = pos_to_cnt(s, t, pos);
    t->running = 0;
    t->pos0 = pos;
}

static uint32_t cnt_now(struct sl_soc *s, struct sl_tim *t)
{
    if (!t->running) return R(s, t)[CNT / 4] & mask_bits(t);
    uint64_t pos, cyc;
    position(s, t, soc_now_ps(s), &pos, &cyc);
    return pos_to_cnt(s, t, pos);
}

/* Update generation (EGR.UG): counter restarts, shadows load. */
static void generate_update(struct sl_soc *s, struct sl_tim *t)
{
    uint32_t *r = R(s, t);
    advance(s, t);
    load_shadows(s, t);
    t->rep = t->rcr;
    uint32_t start_cnt = (r[CR1 / 4] & DIR) && !centre(s, t) ? t->arr : 0;
    r[CNT / 4] = start_cnt;
    if (t->running) {
        t->t0 = t->t_seen = soc_now_ps(s);
        t->tick = tick_ps(s, t);
        t->pos0 = cnt_to_pos(s, t, start_cnt, 0);
    }
    if (!(r[CR1 / 4] & URS)) r[SR / 4] |= 1u;
    board_af_outputs_changed(s);
}

uint32_t tim_read(struct sl_soc *s, int idx, uint32_t off, int effects)
{
    struct sl_tim *t = &s->tim[idx];
    uint32_t *r = R(s, t);
    if (effects) advance(s, t);
    switch (off) {
    case CNT: return cnt_now(s, t);
    case EGR: return 0;
    default: return r[off / 4];
    }
}

void tim_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask)
{
    struct sl_tim *t = &s->tim[idx];
    uint32_t *r = R(s, t);
    uint32_t wm = s->wmask[t->pi][off / 4] & mask;
    /* The SVD gives TIM2's counter, auto-reload and compare registers 16
     * bits; on the chip they are 32 bits wide. */
    if (t->bits32 && (off == CNT || off == ARR || (off >= CCR1 && off < CCR1 + 16u))) wm = mask;
    advance(s, t);
    t->polled = 0;
    switch (off) {
    case SR:
        r[SR / 4] &= v | ~wm;                       /* write 0 to clear */
        break;
    case EGR:
        if (v & wm & 1u) generate_update(s, t);
        for (int ch = 0; ch < t->nch; ch++) if ((v & wm) & (2u << ch)) r[SR / 4] |= 2u << ch;
        break;
    case CR1: {
        uint32_t was = r[CR1 / 4];
        r[CR1 / 4] = (was & ~wm) | (v & wm);
        if (!(was & CEN) && (r[CR1 / 4] & CEN)) start(s, t);
        else if ((was & CEN) && !(r[CR1 / 4] & CEN)) stop(s, t);
        board_af_outputs_changed(s);
        break;
    }
    case CNT:
        r[CNT / 4] = (r[CNT / 4] & ~wm) | (v & wm);
        if (t->running) {
            t->t0 = t->t_seen = soc_now_ps(s);
            t->pos0 = cnt_to_pos(s, t, r[CNT / 4] & mask_bits(t), 0);
        }
        break;
    case PSC:
        r[PSC / 4] = (r[PSC / 4] & ~wm) | (v & wm);   /* always preloaded: takes effect at the next update */
        break;
    case ARR:
        r[ARR / 4] = (r[ARR / 4] & ~wm) | (v & wm);
        if (!(r[CR1 / 4] & ARPE)) {
            uint32_t cnt = cnt_now(s, t);
            t->arr = r[ARR / 4] & mask_bits(t);
            if (t->running) { t->t0 = t->t_seen = soc_now_ps(s); t->pos0 = cnt_to_pos(s, t, cnt, 0); }
            board_af_outputs_changed(s);
        }
        break;
    default:
        if (off >= CCR1 && off < CCR1 + 16u) {
            int ch = (int)(off - CCR1) / 4;
            r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
            if (!oc_preload(s, t, ch) || !cc_is_output(s, t, ch)) {
                t->ccr[ch] = r[off / 4] & mask_bits(t);
                board_af_outputs_changed(s);
            }
            break;
        }
        if (off == SMCR && (r[CR1 / 4] & CEN)) {
            /* switching into or out of encoder mode while counting */
            int was = enc_mode(s, t) != 0;
            r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
            int now_enc = enc_mode(s, t) != 0;
            if (!was && now_enc && t->running) stop(s, t);
            else if (was && !now_enc) start(s, t);
            break;
        }
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
        if (off == BDTR && ((r[BDTR / 4] >> 15) & 1u)) t->moe_warned = 0;
        if (off == CCMR1 || off == CCMR2 || off == CCER || off == BDTR) board_af_outputs_changed(s);
        break;
    }
    update_irqs(s, t);
    schedule(s, t);
}

/* What channel ch (1-4), or its complementary output, puts on a pin.
 * Returns 0: not driving, 1: a static level, 2: PWM. */
int tim_output(struct sl_soc *s, int pi, int ch, int neg, struct sl_tim_out *o)
{
    int idx = tim_index_of_periph(s, pi);
    if (idx < 0 || ch < 1 || ch > 4) return 0;
    struct sl_tim *t = &s->tim[idx];
    int c = ch - 1;
    if (c >= t->nch) return 0;
    uint32_t *r = R(s, t);
    uint32_t ccer = r[CCER / 4] >> (4 * c);
    int en = neg ? (ccer >> 2) & 1u : ccer & 1u;
    int pol = neg ? (ccer >> 3) & 1u : (ccer >> 1) & 1u;
    if (!en || !cc_is_output(s, t, c)) return 0;
    if (t->adv && !((r[BDTR / 4] >> 15) & 1u)) {
        /* Warned only once the counter runs: enabling the channel and then
         * setting MOE is the usual order, and not a mistake. */
        if (t->moe_warned || !(r[CR1 / 4] & CEN)) return 0;
        t->moe_warned = 1;
        soc_log(s, LOG_TIMER, "%s CH%d%s is enabled, but MOE in %s_BDTR is 0: an advanced timer keeps its "
                "outputs off until MOE is set", t->name, ch, neg ? "N" : "", t->name);
        return 0;
    }
    uint32_t mode = oc_mode(s, t, c);
    uint32_t cnt = cnt_now(s, t), ccr = t->ccr[c], arr = t->arr;
    int ref;
    memset(o, 0, sizeof *o);
    switch (mode) {
    case 4: ref = 0; break;                                   /* force inactive */
    case 5: ref = 1; break;                                   /* force active */
    case 6: case 7: {                                         /* PWM 1 and 2 */
        int up = !centre(s, t) ? !(r[CR1 / 4] & DIR) : 1;
        ref = up ? cnt < ccr : cnt > ccr;
        if (centre(s, t)) ref = cnt < ccr;
        if (mode == 7) ref = !ref;
        uint64_t len = cycle_counts(s, t);
        if (t->running && len && ccr > 0 && ccr <= arr) {
            uint64_t high = centre(s, t) ? 2ull * ccr : ccr;
            if (!up && !centre(s, t)) high = (uint64_t)arr + 1u - ccr;
            uint32_t duty = (uint32_t)((high << 16) / len);
            if (mode == 7) duty = 0x10000u - duty;
            int lvl = ref ^ pol ^ neg;
            o->duty_q16 = pol ^ neg ? 0x10000u - duty : duty;
            o->freq_mhz = (uint32_t)soc_muldiv(kernel_hz(s, t), 1000u, ((uint64_t)t->psc + 1u) * len);
            o->level = lvl;
            return 2;
        }
        if (ccr > arr && mode == 6) ref = 1;                  /* PWM1 with CCR > ARR: always active */
        break;
    }
    case 3: {                                                 /* toggle on match: square wave at half the update rate */
        uint64_t len = cycle_counts(s, t);
        if (t->running && len && ccr <= arr) {
            o->duty_q16 = 0x8000u;
            o->freq_mhz = (uint32_t)soc_muldiv(kernel_hz(s, t), 1000u, ((uint64_t)t->psc + 1u) * len * 2u);
            o->level = (int)((cnt >= ccr) ^ (uint32_t)pol);
            return 2;
        }
        ref = 0;
        break;
    }
    case 1: ref = t->running && cnt >= ccr; break;            /* active on match */
    case 2: ref = !(t->running && cnt >= ccr); break;         /* inactive on match */
    default: ref = 0; break;                                  /* frozen */
    }
    o->level = ref ^ pol ^ neg;
    return 1;
}

/* Encoder mode: an edge on TI1 or TI2 counts up or down by the level of
 * the other input (RM0440, counting direction versus encoder signals). */
static void encoder_edge(struct sl_soc *s, struct sl_tim *t, int c, int level)
{
    uint32_t *r = R(s, t);
    int mode = enc_mode(s, t);
    if (!(r[CR1 / 4] & CEN)) return;
    if ((ccmr_field(s, t, 0, 0) & 3u) != 1u || (ccmr_field(s, t, 1, 0) & 3u) != 1u) {
        if (!t->enc_warned) {
            t->enc_warned = 1;
            soc_log(s, LOG_TIMER, "%s is in encoder mode, but CH1 and CH2 are not both inputs: set CC1S and CC2S "
                    "to 01 in CCMR1 (TI1FP1 on TI1, TI2FP2 on TI2)", t->name);
        }
        return;
    }
    uint32_t ccer = r[CCER / 4];
    int ti1 = t->ti[0] ^ (int)((ccer >> 1) & 1u), ti2 = t->ti[1] ^ (int)((ccer >> 5) & 1u);
    int rising = (level ^ (int)((ccer >> (1 + 4 * c)) & 1u)) != 0;
    int dir;                                               /* +1 up, -1 down, 0 no count */
    if (c == 0) {
        if (mode == 2) return;                             /* mode 2 counts on TI2 only */
        dir = rising ? (ti2 ? -1 : 1) : (ti2 ? 1 : -1);
    } else {
        if (mode == 1) return;                             /* mode 1 counts on TI1 only */
        dir = rising ? (ti1 ? 1 : -1) : (ti1 ? -1 : 1);
    }
    uint32_t cnt = r[CNT / 4] & mask_bits(t), arr = t->arr;
    int wrap = 0;
    if (dir > 0) { if (cnt >= arr) { cnt = 0; wrap = 1; } else cnt++; r[CR1 / 4] &= ~DIR; }
    else { if (cnt == 0) { cnt = arr; wrap = 1; } else cnt--; r[CR1 / 4] |= DIR; }
    r[CNT / 4] = cnt;
    if (wrap && !(r[CR1 / 4] & UDIS)) r[SR / 4] |= 1u;
    update_irqs(s, t);
}

/* An edge on a pin routed to channel ch in input mode: capture, and in
 * encoder mode a count. */
void tim_input_edge(struct sl_soc *s, int pi, int ch, int level)
{
    int idx = tim_index_of_periph(s, pi);
    if (idx < 0 || ch < 1 || ch > 4) return;
    struct sl_tim *t = &s->tim[idx];
    int c = ch - 1;
    if (c >= t->nch) return;
    uint32_t *r = R(s, t);
    if (c < 2) {
        t->ti[c] = level;                                  /* the other input's level, for encoder mode */
        if (t->nch >= 2 && enc_mode(s, t)) encoder_edge(s, t, c, level);
    }
    if ((ccmr_field(s, t, c, 0) & 3u) != 1u) return;          /* only TIx on its own channel */
    uint32_t ccer = r[CCER / 4] >> (4 * c);
    if (!(ccer & 1u)) return;
    int p = (ccer >> 1) & 1u, np = (ccer >> 3) & 1u;
    int want = (p && np) ? 1 : (p ? !level : level);
    if (!want) return;
    advance(s, t);
    if (r[SR / 4] & (2u << c)) r[SR / 4] |= 0x200u << c;
    r[SR / 4] |= 2u << c;
    r[(CCR1 + 4u * (uint32_t)c) / 4] = cnt_now(s, t);
    update_irqs(s, t);
}

/* Clocks changed: keep the counter value, continue at the new rate. */
void tim_clocks_changed(struct sl_soc *s)
{
    for (int i = 0; i < SL_NUM_TIM; i++) {
        struct sl_tim *t = &s->tim[i];
        if (t->pi < 0 || !t->running) continue;
        advance(s, t);
        uint64_t pos, cyc;
        position(s, t, soc_now_ps(s), &pos, &cyc);
        t->t0 = t->t_seen = soc_now_ps(s);
        t->pos0 = pos;
        t->tick = tick_ps(s, t);
        schedule(s, t);
    }
    board_af_outputs_changed(s);
}

void tim_reset(struct sl_soc *s, int idx)
{
    struct sl_tim *t = &s->tim[idx];
    t->running = 0; t->polled = 0; t->moe_warned = 0; t->enc_warned = 0;
    t->ti[0] = t->ti[1] = 1;                               /* inputs pulled up until an edge says otherwise */
    t->t0 = t->t_seen = 0; t->pos0 = 0; t->rep = 0;
    load_shadows(s, t);
    if (t->irq_up >= 0) soc_irq(s, t->irq_up, t->pi, 0);
    if (t->irq_cc >= 0) soc_irq(s, t->irq_cc, t->pi, 0);
    soc_sched(s, EV_TIM0 + idx, SL_NEVER);
}

void tim_init(struct sl_soc *s)
{
    for (int i = 0; i < SL_NUM_TIM; i++) {
        struct sl_tim *t = &s->tim[i];
        memset(t, 0, sizeof *t);
        t->name = tim_names[i];
        t->pi = -1;
        for (int p = 0; p < s->np; p++)
            if (!strcmp(sl_g431_periph[p].name, t->name)) t->pi = p;
        if (t->pi < 0) continue;
        const struct sl_periph_info *pi = &sl_g431_periph[t->pi];
        t->adv = !strcmp(t->name, "TIM1") || !strcmp(t->name, "TIM8") || !strcmp(t->name, "TIM15")
                 || !strcmp(t->name, "TIM16") || !strcmp(t->name, "TIM17");
        t->bits32 = !strcmp(t->name, "TIM2");
        t->apb2 = pi->base >= 0x40010000u;
        t->nch = !strcmp(t->name, "TIM6") || !strcmp(t->name, "TIM7") ? 0
               : !strcmp(t->name, "TIM15") ? 2 : (!strcmp(t->name, "TIM16") || !strcmp(t->name, "TIM17")) ? 1 : 4;
        /* TIM1 and TIM8: BRK, UP, TRG_COM, CC lines; the SVD lists them out of order */
        if (!strcmp(t->name, "TIM1")) { t->irq_up = 25; t->irq_cc = 27; }
        else if (!strcmp(t->name, "TIM8")) { t->irq_up = 44; t->irq_cc = 46; }
        else { t->irq_up = pi->irq[0]; t->irq_cc = pi->irq[0]; }
    }
}
