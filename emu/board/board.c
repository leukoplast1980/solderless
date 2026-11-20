/*
 * Solderless - the breadboard: nets, drivers and the simple parts.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
#include "board.h"
#include "../soc/soc.h"

#include <string.h>

#define G_CHIP     25000.0      /* uS: output stage, about 40 ohm */
#define G_PULL     25.0         /* uS: internal pull-up or pull-down, about 40 kohm */
#define G_SWITCH   1000000.0    /* uS: closed contact */
#define G_STRONG   1000.0       /* drivers at least this strong can short each other */

#define B (&s->board)

static int slot_port(int slot) { return slot >> 4; }
static int slot_bit(int slot) { return slot & 15; }

static void pin_name(int slot, char *buf)
{
    buf[0] = 'P'; buf[1] = (char)('A' + slot_port(slot));
    int b = slot_bit(slot);
    if (b >= 10) { buf[2] = '1'; buf[3] = (char)('0' + b - 10); buf[4] = 0; }
    else { buf[2] = (char)('0' + b); buf[3] = 0; }
}

static uint32_t rng_next(struct sl_board *b)
{
    uint64_t x = b->rng;
    x ^= x << 13; x ^= x >> 7; x ^= x << 17;
    b->rng = x;
    return (uint32_t)(x >> 16);
}

/* ---------------------------------------------------------------------- */
/* Averaging                                                              */
/* ---------------------------------------------------------------------- */

static void integrate(struct sl_pin_state *p, uint64_t now)
{
    if (now > p->t_last) p->high_acc += ((now - p->t_last) * (uint64_t)p->duty_q16) >> 16;
    p->t_last = now;
}

/* ---------------------------------------------------------------------- */
/* Resolution                                                             */
/* ---------------------------------------------------------------------- */

struct drivers {
    double g, gv;               /* sum of conductances, sum of g * v */
    int strong_min, strong_max, nstrong;
};

static void add(struct drivers *d, double g_us, int mv)
{
    d->g += g_us;
    d->gv += g_us * mv;
    if (g_us >= G_STRONG) {
        if (!d->nstrong || mv < d->strong_min) d->strong_min = mv;
        if (!d->nstrong || mv > d->strong_max) d->strong_max = mv;
        d->nstrong++;
    }
}

static const struct sl_af_info *af_entry(int port, int bit, int af)
{
    for (int i = 0; i < sl_g431_naf; i++) {
        const struct sl_af_info *e = &sl_g431_af[i];
        if (e->port == port && e->pin == bit && e->af == af) return e;
    }
    return 0;
}

/* What the chip's output stage puts on the pin. Fills PWM fields. */
static void chip_driver(struct sl_soc *s, int slot, struct drivers *d, struct sl_pin_state *p)
{
    int port = slot_port(slot), bit = slot_bit(slot);
    uint32_t *r = s->regs[s->pi_gpio[port]];
    uint32_t mode = (r[0] >> (2 * bit)) & 3u, od = (r[1] >> bit) & 1u;
    uint32_t pull = (r[3] >> (2 * bit)) & 3u, odr = (r[5] >> bit) & 1u;
    p->pwm = 0;
    p->chip_drives = 0;
    if (!s->clk_on[s->pi_gpio[port]]) mode = 3;          /* unclocked port: pins stay as they were at reset, analog */
    if (mode != 3 && pull == 1) add(d, G_PULL, SL_VDD_MV);
    if (mode != 3 && pull == 2) add(d, G_PULL, 0);
    if (mode == 1) {
        if (!od || !odr) { add(d, G_CHIP, odr ? SL_VDD_MV : 0); p->chip_drives = 1; }
    } else if (mode == 2) {
        int af = gpio_pin_af(s, port, bit);
        const struct sl_af_info *e = af >= 0 ? af_entry(port, bit, af) : 0;
        if (!e || e->periph < 0 || !s->clk_on[e->periph]) return;
        const char *kind = sl_g431_periph[e->periph].kind;
        if (!strcmp(kind, "timer") && e->signal[0] == 'C' && e->signal[1] == 'H') {
            struct sl_tim_out o;
            int ch = e->signal[2] - '0', neg = e->signal[3] == 'N';
            int k = tim_output(s, e->periph, ch, neg, &o);
            if (k == 0) return;
            if (k == 2) {
                p->pwm = 1;
                p->pwm_duty_q16 = o.duty_q16;
                p->pwm_freq_mhz = o.freq_mhz;
                add(d, G_CHIP, (int)(((uint64_t)SL_VDD_MV * o.duty_q16) >> 16));
            } else if (!od || !o.level) {
                add(d, G_CHIP, o.level ? SL_VDD_MV : 0);
            }
            p->chip_drives = 1;
        } else if (!strcmp(kind, "usart") && !strcmp(e->signal, "TX")) {
            add(d, G_CHIP, SL_VDD_MV);                    /* idle line; bytes go to the terminal directly */
            p->chip_drives = 1;
        }
    } else if (mode == 3) {
        int mv, g;
        if (dac_pin_out(s, port, bit, &mv, &g)) { add(d, g, mv); p->chip_drives = 1; }
    }
}

/* ---------------------------------------------------------------------- */
/* The scope                                                              */
/* ---------------------------------------------------------------------- */

static void trace_push(struct sl_soc *s, int slot, uint64_t t_ps, int busy)
{
    struct sl_pin_state *p = &B->pin[slot];
    uint32_t next = (B->trace_head + 1) % SL_TRACE_RING;
    if (next == B->trace_tail) return;                  /* full: the reader is behind */
    sl_audio_event *e = &B->trace[B->trace_head];
    e->time_ns = t_ps / 1000u;
    e->kind = (p->trace_freq ? SL_AUDIO_PWM : SL_AUDIO_LEVEL) | (slot << 8) | (busy ? SL_TRACE_BUSY : 0);
    e->a = p->trace_freq ? p->trace_freq : (uint32_t)p->trace_mv;
    e->b = p->trace_duty;
    B->trace_head = next;
}

/* A traced pin changed (or force: record it anyway). */
static void trace_note(struct sl_soc *s, int slot, uint64_t now, int force)
{
    struct sl_pin_state *p = &B->pin[slot];
    int mv = p->floating ? -1 : p->mv;
    uint32_t f = p->pwm ? p->pwm_freq_mhz : 0, du = p->pwm ? p->pwm_duty_q16 : 0;
    if (!force && mv == p->trace_mv && f == p->trace_freq && du == p->trace_duty) return;
    p->trace_mv = mv; p->trace_freq = f; p->trace_duty = du;
    if (!force && now - p->trace_t < 1000000u) {         /* within 1 us of the last event */
        p->trace_busy = 1;
        return;
    }
    trace_push(s, slot, now, p->trace_busy);
    p->trace_busy = 0;
    p->trace_t = now;
}

void board_trace_pins(struct sl_soc *s, const int *slots, int n)
{
    uint64_t now = soc_now_ps(s);
    for (int i = 0; i < SL_PIN_SLOTS; i++) B->pin[i].traced = 0;
    for (int i = 0; i < n; i++)
        if (slots[i] >= 0 && slots[i] < SL_PIN_SLOTS) {
            B->pin[slots[i]].traced = 1;
            trace_note(s, slots[i], now, 1);
        }
}

size_t board_trace_read(struct sl_soc *s, sl_audio_event *out, size_t max)
{
    /* A burst that ended: record where the pin settled */
    uint64_t now = soc_now_ps(s);
    for (int i = 0; i < SL_PIN_SLOTS; i++) {
        struct sl_pin_state *p = &B->pin[i];
        if (p->traced && p->trace_busy && now - p->trace_t >= 1000000u) {
            trace_push(s, i, now, 1);
            p->trace_busy = 0;
            p->trace_t = now;
        }
    }
    size_t n = 0;
    while (n < max && B->trace_tail != B->trace_head) {
        out[n++] = B->trace[B->trace_tail];
        B->trace_tail = (B->trace_tail + 1) % SL_TRACE_RING;
    }
    return n;
}

static void resolve(struct sl_soc *s, int slot, int edges)
{
    struct sl_pin_state *p = &B->pin[slot];
    int port = slot_port(slot), bit = slot_bit(slot);
    struct drivers d = { 0 };
    chip_driver(s, slot, &d, p);
    if ((s->pin_ext_drive[port] >> bit) & 1u)
        add(&d, G_CHIP, ((s->pin_ext_level[port] >> bit) & 1u) ? SL_VDD_MV : 0);
    for (int i = 0; i < B->natt; i++) {
        struct sl_att_state *a = &B->att[i];
        if (a->a.pin != slot) continue;
        int top = a->a.to_vcc ? SL_VDD_MV : 0;
        switch (a->a.kind) {
        case SL_ATT_SWITCH: if (a->closed) add(&d, G_SWITCH, top); break;
        case SL_ATT_PULL: if (a->a.r_ohm > 0) add(&d, 1e6 / a->a.r_ohm, top); break;
        case SL_ATT_POT: {
            double x = a->value / 1000.0, rth = a->a.r_ohm * x * (1.0 - x) + 50.0;
            add(&d, 1e6 / rth, (int)(SL_VDD_MV * x + 0.5));
            break;
        }
        default: break;
        }
    }

    int old_level = p->level, old_float = p->floating;
    p->shorted = d.nstrong >= 2 && d.strong_max - d.strong_min > 1000;
    if (d.g <= 0.0) {
        p->floating = 1;
        p->mv = -1;
        p->undefined = 0;
    } else {
        p->floating = 0;
        p->mv = (int)(d.gv / d.g + 0.5);
        uint32_t mode = (s->regs[s->pi_gpio[port]][0] >> (2 * bit)) & 3u;
        if (p->pwm || mode == 3u) {
            p->undefined = 0;                       /* analog pins have no digital input stage */
            if (mode == 3u) p->level = p->mv >= 1650;
        } else if (p->mv >= SL_PIN_HI_MV) { p->level = 1; p->undefined = 0; }
        else if (p->mv <= SL_PIN_LO_MV) { p->level = 0; p->undefined = 0; }
        else p->undefined = 1;
    }
    uint64_t now = soc_now_ps(s);
    integrate(p, now);
    p->duty_q16 = p->pwm ? p->pwm_duty_q16 : (!p->floating && p->level ? 0x10000u : 0);
    if (p->traced) trace_note(s, slot, now, 0);
    if (p->audio) {
        uint32_t f = p->pwm ? p->pwm_freq_mhz : 0, du = p->pwm ? p->pwm_duty_q16 : 0;
        int mv = p->floating ? 0 : p->mv;
        if (mv != p->last_mv || f != p->last_freq || du != p->last_duty) {
            p->last_mv = mv; p->last_freq = f; p->last_duty = du;
            uint32_t next = (B->audio_head + 1) % SL_AUDIO_RING;
            if (next != B->audio_tail) {
                sl_audio_event *e = &B->audio[B->audio_head];
                e->time_ns = now / 1000u;
                e->kind = (p->pwm ? SL_AUDIO_PWM : SL_AUDIO_LEVEL) | (slot << 8);
                e->a = p->pwm ? f : (uint32_t)mv;
                e->b = du;
                B->audio_head = next;
            }
        }
    }

    if (p->shorted && p->used) {
        char n[6]; pin_name(slot, n);
        soc_log(s, LOG_SHORT, "%s: the chip and a part drive it to opposite levels: a short. "
                "The pin is damaged on a real board (PC 0x%08X)", n, s->cpu.r[15]);
    }
    if (edges && !p->floating && !p->pwm && !p->undefined && (p->level != old_level || old_float)) {
        exti_gpio(s, port, bit, p->level);
        /* Input capture: a pin in alternate-function mode routed to a timer channel */
        int af = gpio_pin_af(s, port, bit);
        const struct sl_af_info *e = af >= 0 ? af_entry(port, bit, af) : 0;
        if (e && e->periph >= 0 && !strcmp(sl_g431_periph[e->periph].kind, "timer")
            && e->signal[0] == 'C' && e->signal[1] == 'H' && e->signal[3] != 'N')
            tim_input_edge(s, e->periph, e->signal[2] - '0', p->level);
    }
}

void board_port_changed(struct sl_soc *s, int port, uint32_t pins)
{
    for (int b = 0; b < 16; b++)
        if ((pins >> b) & 1u) resolve(s, port * 16 + b, 1);
}

void board_af_outputs_changed(struct sl_soc *s)
{
    for (int port = 0; port < SL_NUM_PORTS; port++) {
        uint32_t moder = s->regs[s->pi_gpio[port]][0];
        uint32_t pins = 0;
        for (int b = 0; b < 16; b++)
            if (((moder >> (2 * b)) & 3u) >= 2u) pins |= 1u << b;
        board_port_changed(s, port, pins);
    }
}

static void resolve_all(struct sl_soc *s, int edges)
{
    for (int slot = 0; slot < SL_PIN_SLOTS; slot++) resolve(s, slot, edges);
}

int board_pin_level(struct sl_soc *s, int port, int pin)
{
    struct sl_pin_state *p = &B->pin[port * 16 + pin];
    if (p->pwm) {
        int sl = port * 16 + pin;
        (void)sl;
        /* Instantaneous level of a PWM pin: ask the timer. */
        int af = gpio_pin_af(s, port, pin);
        const struct sl_af_info *e = af >= 0 ? af_entry(port, pin, af) : 0;
        if (e) {
            struct sl_tim_out o;
            if (tim_output(s, e->periph, e->signal[2] - '0', e->signal[3] == 'N', &o)) return o.level;
        }
        return 0;
    }
    if (p->floating || p->undefined) return p->used ? (int)(rng_next(B) & 1u) : 0;
    return p->level;
}

int board_pin_mv(struct sl_soc *s, int port, int pin)
{
    struct sl_pin_state *p = &B->pin[port * 16 + pin];
    if (p->floating) return (int)(rng_next(B) % 3300u);
    return p->mv;
}

void board_idr_read(struct sl_soc *s, int port)
{
    uint32_t moder = s->regs[s->pi_gpio[port]][0];
    for (int b = 0; b < 16; b++) {
        struct sl_pin_state *p = &B->pin[port * 16 + b];
        if (!p->used || ((moder >> (2 * b)) & 3u) != 0u) continue;
        char n[6]; pin_name(port * 16 + b, n);
        if (p->floating)
            soc_log(s, LOG_FLOATING, "%s floats: nothing drives it and its pull-up is off (GPIO%c_PUPDR), "
                    "so it reads at random (PC 0x%08X)", n, 'A' + port, s->cpu.r[15]);
        else if (p->undefined)
            soc_log(s, LOG_UNDEFINED, "%s is at %d.%02d V, between the input thresholds (0.99 V and 2.31 V): "
                    "it reads at random (PC 0x%08X)", n, p->mv / 1000, (p->mv % 1000) / 10, s->cpu.r[15]);
    }
}

/* ---------------------------------------------------------------------- */
/* Parts                                                                  */
/* ---------------------------------------------------------------------- */

static void schedule_next(struct sl_soc *s)
{
    uint64_t t = SL_NEVER;
    for (int i = 0; i < B->natt; i++) {
        struct sl_att_state *a = &B->att[i];
        if (a->bounce_i < a->bounce_n && a->bounce_at[a->bounce_i] < t) t = a->bounce_at[a->bounce_i];
    }
    soc_sched(s, EV_BOARD, t);
}

void board_event(struct sl_soc *s)
{
    uint64_t now = soc_now_ps(s);
    for (int i = 0; i < B->natt; i++) {
        struct sl_att_state *a = &B->att[i];
        while (a->bounce_i < a->bounce_n && a->bounce_at[a->bounce_i] <= now) {
            a->bounce_i++;
            a->closed = a->bounce_i == a->bounce_n ? a->value : !a->closed;
            resolve(s, a->a.pin, 1);
        }
    }
    schedule_next(s);
}

/* A switch that changes state chatters a few times over its bounce time,
 * as real contacts do, then settles. */
static void switch_change(struct sl_soc *s, struct sl_att_state *a, int pressed)
{
    a->value = pressed;
    uint64_t now = soc_now_ps(s), span = (uint64_t)a->a.bounce_us * 1000000u;
    if (!span) {
        a->bounce_n = a->bounce_i = 0;
        a->closed = pressed;
        resolve(s, a->a.pin, 1);
        return;
    }
    int n = 3 + (int)(rng_next(B) % 4u) * 2;          /* odd number of toggles, ends at the new state */
    if (n > SL_BOUNCE_MAX) n = SL_BOUNCE_MAX - 1;
    uint64_t t = now;
    for (int i = 0; i < n; i++) {
        uint64_t step = span / (uint64_t)(n + 1);
        t += step / 2 + (step * (rng_next(B) % 1000u)) / 1000u;
        a->bounce_at[i] = t;
    }
    a->bounce_n = n;
    a->bounce_i = 0;
    a->closed = !a->closed;                          /* first contact at once */
    resolve(s, a->a.pin, 1);
    schedule_next(s);
}

void board_input(struct sl_soc *s, int part, int value)
{
    for (int i = 0; i < B->natt; i++) {
        struct sl_att_state *a = &B->att[i];
        if (a->a.part != part) continue;
        if (a->a.kind == SL_ATT_SWITCH) {
            if ((value != 0) != (a->value != 0)) switch_change(s, a, value != 0);
        } else if (a->a.kind == SL_ATT_POT) {
            a->value = value < 0 ? 0 : value > 1000 ? 1000 : value;
            resolve(s, a->a.pin, 1);
        }
    }
}

void board_set(struct sl_soc *s, const sl_attach *att, int n)
{
    if (n > SL_MAX_ATTACH) n = SL_MAX_ATTACH;
    /* Keep the state of parts that stay (a pressed button stays pressed). */
    struct sl_att_state old[SL_MAX_ATTACH];
    int nold = B->natt;
    memcpy(old, B->att, sizeof old);
    memset(B->att, 0, sizeof B->att);
    for (int i = 0; i < n; i++) {
        struct sl_att_state *a = &B->att[i];
        a->a = att[i];
        a->value = att[i].kind == SL_ATT_POT ? 500 : 0;
        for (int j = 0; j < nold; j++)
            if (old[j].a.part == att[i].part && old[j].a.role == att[i].role && old[j].a.kind == att[i].kind) {
                a->value = old[j].value;
                a->closed = old[j].value && old[j].a.kind == SL_ATT_SWITCH;
            }
    }
    B->natt = n;
    for (int slot = 0; slot < SL_PIN_SLOTS; slot++) { B->pin[slot].used = 0; B->pin[slot].audio = 0; B->pin[slot].last_mv = -2; }
    for (int i = 0; i < n; i++)
        if (att[i].pin >= 0 && att[i].pin < SL_PIN_SLOTS) {
            B->pin[att[i].pin].used = 1;
            if (att[i].kind == SL_ATT_AUDIO) B->pin[att[i].pin].audio = 1;
        }
    resolve_all(s, 1);
    schedule_next(s);
}

/* Current through an LED: the net is a voltage behind a resistance; the
 * LED and its resistor load it. */
static double led_current_ua(struct sl_soc *s, const struct sl_att_state *a, int high)
{
    const struct sl_pin_state *p = &B->pin[a->a.pin];
    double vth, rth;
    if (p->chip_drives || p->pwm) { vth = high ? SL_VDD_MV : 0; rth = 40.0; }
    else if (p->floating) return 0.0;
    else {
        struct drivers d = { 0 };
        struct sl_pin_state tmp = *p;
        chip_driver(s, a->a.pin, &d, &tmp);
        if (d.g <= 0.0) return 0.0;
        vth = d.gv / d.g; rth = 1e6 / d.g;
    }
    double r = (a->a.r_ohm > 0 ? a->a.r_ohm : 0) + rth;
    double drop = a->a.to_vcc ? SL_VDD_MV - a->a.vf_mv - vth : vth - a->a.vf_mv;
    if (drop <= 0.0) return 0.0;
    return drop * 1000.0 / r;                        /* mV / ohm = mA; x1000 = uA */
}

void board_read(struct sl_soc *s, sl_board_state *out)
{
    uint64_t now = soc_now_ps(s);
    memset(out, 0, sizeof *out);
    out->time_ns = now / 1000u;
    uint16_t duty_pm[SL_PIN_SLOTS];
    for (int slot = 0; slot < SL_PIN_SLOTS; slot++) {
        struct sl_pin_state *p = &B->pin[slot];
        int port = slot_port(slot), bit = slot_bit(slot);
        integrate(p, now);
        uint64_t win = now - p->win_start;
        uint32_t duty = win ? (uint32_t)((p->high_acc * 1000u) / win) : (p->duty_q16 * 1000u) >> 16;
        if (duty > 1000) duty = 1000;
        duty_pm[slot] = (uint16_t)duty;
        p->high_acc = 0;
        p->win_start = now;
        out->duty[slot] = (uint16_t)duty;
        out->mv[slot] = (int16_t)p->mv;
        out->mode[slot] = (uint8_t)((s->regs[s->pi_gpio[port]][0] >> (2 * bit)) & 3u);
        out->freq_mhz[slot] = p->pwm ? p->pwm_freq_mhz : 0;
        out->pwm_duty[slot] = p->pwm ? (uint16_t)((p->pwm_duty_q16 * 1000u + 0x8000u) >> 16) : 0;
        out->flags[slot] = (uint8_t)((p->level && !p->floating ? SL_PIN_HIGH : 0) | (p->floating ? SL_PIN_FLOATING : 0)
                                     | (p->shorted ? SL_PIN_SHORT : 0) | (p->pwm ? SL_PIN_PWM : 0)
                                     | (p->undefined ? SL_PIN_UNDEFINED : 0) | (p->chip_drives ? SL_PIN_CHIP_DRIVES : 0)
                                     | (p->used ? SL_PIN_USED : 0));
    }
    for (int i = 0; i < B->natt; i++) {
        const struct sl_att_state *a = &B->att[i];
        if (a->a.kind != SL_ATT_LED) continue;
        double d = duty_pm[a->a.pin] / 1000.0;
        double ua = a->a.to_vcc ? (1.0 - d) * led_current_ua(s, a, 0) : d * led_current_ua(s, a, 1);
        out->current_ua[i] = (int32_t)(ua + 0.5);
        if (ua > 20000.0) {
            char n[6]; pin_name(a->a.pin, n);
            soc_log(s, LOG_PIN_CURRENT, "%s: %d mA through the LED, above the pin's 20 mA limit. "
                    "A larger resistor is needed", n, (int)(ua / 1000.0));
        }
    }
}

size_t board_audio_read(struct sl_soc *s, sl_audio_event *out, size_t max)
{
    size_t n = 0;
    while (n < max && B->audio_tail != B->audio_head) {
        out[n++] = B->audio[B->audio_tail];
        B->audio_tail = (B->audio_tail + 1) % SL_AUDIO_RING;
    }
    return n;
}

void board_init(struct sl_soc *s)
{
    memset(B, 0, sizeof *B);
    B->rng = 0x9E3779B97F4A7C15ull;
}

void board_reset(struct sl_soc *s)
{
    uint64_t now = soc_now_ps(s);
    for (int slot = 0; slot < SL_PIN_SLOTS; slot++) {
        struct sl_pin_state *p = &B->pin[slot];
        p->t_last = p->win_start = now;
        p->high_acc = 0;
    }
    resolve_all(s, 0);
    schedule_next(s);
}
