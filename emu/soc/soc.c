/*
 * Solderless - the emulated STM32G431RB: memory map, bus, time, events.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
#include "soc.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ====================================================================== */
/* Arithmetic                                                             */
/* ====================================================================== */

/* a * b / c without overflow, rounding down. */
uint64_t soc_muldiv(uint64_t a, uint64_t b, uint64_t c)
{
#if defined(__SIZEOF_INT128__)
    return (uint64_t)(((unsigned __int128)a * b) / c);
#else
    /* 64x64 -> 128 multiply, then long division by c */
    uint64_t al = a & 0xFFFFFFFFu, ah = a >> 32, bl = b & 0xFFFFFFFFu, bh = b >> 32;
    uint64_t ll = al * bl, lh = al * bh, hl = ah * bl, hh = ah * bh;
    uint64_t mid = (ll >> 32) + (lh & 0xFFFFFFFFu) + (hl & 0xFFFFFFFFu);
    uint64_t lo = (ll & 0xFFFFFFFFu) | (mid << 32);
    uint64_t hi = hh + (lh >> 32) + (hl >> 32) + (mid >> 32);
    uint64_t q = 0, r = 0;
    for (int i = 127; i >= 0; i--) {
        uint64_t bit = i >= 64 ? (hi >> (i - 64)) & 1u : (lo >> i) & 1u;
        int carry = (int)(r >> 63);
        r = (r << 1) | bit;
        if (carry || r >= c) { r -= c; if (i < 64) q |= 1ull << i; }
    }
    return q;
#endif
}

/* ====================================================================== */
/* Log                                                                    */
/* ====================================================================== */

void soc_log(struct sl_soc *s, int key, const char *fmt, ...)
{
    if (!s->log) return;
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    s->log(s->host, key, buf);
}

/* ====================================================================== */
/* Time and events                                                        */
/* ====================================================================== */

uint64_t soc_now_ps(const struct sl_soc *s)
{
    /* d stays below the batch size (soc_run_batch commits after every
     * batch), so d * 10^12 fits in 64 bits. */
    uint64_t d = s->cpu.cycles - s->t_cyc;
    return s->t_ps + (d * SL_PS_PER_S + s->t_rem) / s->hclk;
}

void soc_time_commit(struct sl_soc *s)
{
    uint64_t d = s->cpu.cycles - s->t_cyc;
    if (!d) return;
    uint64_t num = d * SL_PS_PER_S + s->t_rem;
    s->t_ps += num / s->hclk;
    s->t_rem = num % s->hclk;
    s->t_cyc = s->cpu.cycles;
}

void soc_set_hclk(struct sl_soc *s, uint32_t hz)
{
    if (!hz || hz == s->hclk) return;
    soc_time_commit(s);
    s->t_rem = 0;
    s->hclk = hz;
}

void soc_sched(struct sl_soc *s, int ev, uint64_t at_ps)
{
    s->ev[ev] = at_ps;
    s->cpu.stop_batch = 1;         /* the running batch may already be past it */
}

uint64_t soc_next_event_ps(struct sl_soc *s)
{
    uint64_t t = SL_NEVER;
    for (int i = 0; i < EV_COUNT; i++)
        if (s->ev[i] < t) t = s->ev[i];
    uint64_t st = cm_cycles_to_systick(&s->cpu);
    if (st != UINT64_MAX) {
        uint64_t ts = soc_now_ps(s) + soc_muldiv(st, SL_PS_PER_S, s->hclk);
        if (ts < t) t = ts;
    }
    return t;
}

static void dispatch_event(struct sl_soc *s, int ev)
{
    switch (ev) {
    case EV_HSI: case EV_HSE: case EV_PLL: case EV_LSI: case EV_LSE: case EV_HSI48:
        rcc_event(s, ev);
        break;
    case EV_FLASH:
        flash_event(s);
        break;
    case EV_TERM_RX:
        usart_term_rx_event(s);
        break;
    case EV_BOARD:
        board_event(s);
        break;
    default:
        if (ev >= EV_USART_TX0 && ev <= EV_USART_TX_LAST) usart_tx_event(s, ev - EV_USART_TX0);
        else if (ev >= EV_TIM0 && ev <= EV_TIM_LAST) tim_event(s, ev - EV_TIM0);
        else if (ev >= EV_ADC0 && ev <= EV_ADC_LAST) adc_event(s, ev - EV_ADC0);
        else if (ev >= EV_I2C0 && ev <= EV_I2C_LAST) i2c_event(s, ev - EV_I2C0);
        else if (ev >= EV_SPI0 && ev <= EV_SPI_LAST) spi_event(s, ev - EV_SPI0);
        break;
    }
}

static void idle_break(struct sl_soc *s);

void soc_process_events(struct sl_soc *s)
{
    for (int guard = 0; guard < 1000; guard++) {
        uint64_t now = soc_now_ps(s);
        int first = -1;
        uint64_t t = SL_NEVER;
        for (int i = 0; i < EV_COUNT; i++)
            if (s->ev[i] <= now && s->ev[i] < t) { t = s->ev[i]; first = i; }
        if (first < 0) return;
        s->ev[first] = SL_NEVER;
        dispatch_event(s, first);
        /* Something outside the CPU changed, so a loop that looked idle may
         * end now: a character arrived, a flag rose. Forget what both idle
         * checks (register reads, and the CPU's own check of backward
         * branches) have seen. Without this a loop could be found idle on
         * state from before the event, and the main loop slept on to the next
         * event: a character that had just arrived was overwritten by the
         * next one before the firmware read it. */
        s->idle_poll = 0;
        s->cpu.idle_loop = 0;
        s->cpu.mem_changes++;      /* the CPU's loop check compares this too */
        idle_break(s);
    }
}

/* Moves virtual time forward to t_ps without running instructions. */
void soc_skip_to(struct sl_soc *s, uint64_t t_ps)
{
    soc_time_commit(s);
    uint64_t now = soc_now_ps(s);
    if (t_ps <= now) return;
    uint64_t dt = t_ps - now;
    uint64_t cyc = soc_muldiv(dt, s->hclk, SL_PS_PER_S);
    if (soc_muldiv(cyc, SL_PS_PER_S, s->hclk) < dt) cyc++;   /* round up: reach t_ps */
    cm_idle_cycles(&s->cpu, cyc);
    s->t_ps = t_ps;
    s->t_cyc = s->cpu.cycles;
    s->t_rem = 0;
}

uint32_t soc_run_batch(struct sl_soc *s, uint32_t max)
{
    uint64_t limit = max;
    uint64_t next = SL_NEVER;
    for (int i = 0; i < EV_COUNT; i++)
        if (s->ev[i] < next) next = s->ev[i];
    if (next != SL_NEVER) {
        uint64_t now = soc_now_ps(s);
        if (next <= now) { soc_process_events(s); return 0; }
        uint64_t span = soc_muldiv(limit, SL_PS_PER_S, s->hclk);
        if (next - now < span) {
            uint64_t c = soc_muldiv(next - now, s->hclk, SL_PS_PER_S) + 1u;
            if (c < limit) limit = c;
        }
    }
    uint64_t st = cm_cycles_to_systick(&s->cpu);
    if (st < limit) limit = st;
    if (limit == 0) limit = 1;

    uint32_t n = cm_run(&s->cpu, (uint32_t)limit);
    soc_time_commit(s);
    soc_process_events(s);
    return n;
}

/* ====================================================================== */
/* Generic registers                                                      */
/* ====================================================================== */

static void periph_regs_reset(struct sl_soc *s, int pi)
{
    const struct sl_periph_info *p = &sl_g431_periph[pi];
    memset(s->regs[pi], 0, p->size / 4u * 4u + 4u);
    for (int i = 0; i < p->nregs; i++)
        if (p->regs[i].off < p->size) s->regs[pi][p->regs[i].off / 4u] = p->regs[i].reset;
}

static int find_periph(const struct sl_soc *s, uint32_t a)
{
    /* Programs tend to stay on one peripheral for a while */
    const struct sl_periph_info *last = &sl_g431_periph[s->last_pi];
    if (a - last->base < last->size) return s->last_pi;
    int lo = 0, hi = s->np - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        const struct sl_periph_info *p = &sl_g431_periph[mid];
        if (a < p->base) hi = mid - 1;
        else if (a >= p->base + p->size) lo = mid + 1;
        else return mid;
    }
    return -1;
}

static int gpio_port_of(const struct sl_soc *s, int pi) { return s->port_of[pi]; }

/* Recomputes which peripherals have their bus clock enabled. */
static void update_clock_gates(struct sl_soc *s)
{
    for (int i = 0; i < s->np; i++) {
        const struct sl_periph_info *p = &sl_g431_periph[i];
        if (p->en_reg < 0) s->clk_on[i] = 1;
        else s->clk_on[i] = (s->regs[s->pi_rcc][p->en_reg / 4] >> p->en_bit) & 1u;
    }
}

static void periph_reset_one(struct sl_soc *s, int pi)
{
    periph_regs_reset(s, pi);
    int port = gpio_port_of(s, pi);
    if (port >= 0) return;               /* GPIO state lives in the register file */
    int u = usart_index_of_periph(s, pi);
    if (u >= 0) usart_reset(s, u);
    if (pi == s->pi_flash) flash_reset(s);
    int t = tim_index_of_periph(s, pi);
    if (t >= 0) tim_reset(s, t);
    int a = adc_index_of_periph(s, pi);
    if (a >= 0) adc_reset(s, a);
    if (pi == s->pi_dac1) dac_reset(s);
    if (pi == s->pi_exti) exti_reset(s);
    int c = i2c_index_of_periph(s, pi);
    if (c >= 0) i2c_reset(s, c);
    int sp = spi_index_of_periph(s, pi);
    if (sp >= 0) spi_reset(s, sp);
    board_af_outputs_changed(s);
}

void soc_irq(struct sl_soc *s, int irq, int src, int level)
{
    if (irq < 0 || irq >= SL_NUM_IRQ) return;
    uint64_t bit = 1ull << (src & 63);
    uint64_t was = s->irq_src[irq];
    s->irq_src[irq] = level ? was | bit : was & ~bit;
    if ((was != 0) != (s->irq_src[irq] != 0)) cm_set_irq(&s->cpu, irq, s->irq_src[irq] != 0);
}

/* Applies RCC reset bits: a peripheral whose reset bit is written as 1
 * goes back to its reset state. */
void soc_apply_resets(struct sl_soc *s, uint32_t rcc_off, uint32_t bits)
{
    for (int i = 0; i < s->np; i++) {
        const struct sl_periph_info *p = &sl_g431_periph[i];
        if (p->rst_reg == (int)rcc_off && ((bits >> p->rst_bit) & 1u)) periph_reset_one(s, i);
    }
    update_clock_gates(s);
}

void soc_clock_gates_changed(struct sl_soc *s) { update_clock_gates(s); }

static uint32_t periph_read(struct sl_soc *s, int pi, uint32_t off, int effects)
{
    if (pi == s->pi_rcc) return rcc_read(s, off);
    if (pi == s->pi_flash) return flash_read(s, off);
    int port = gpio_port_of(s, pi);
    if (port >= 0) return gpio_read(s, port, off);
    int u = usart_index_of_periph(s, pi);
    if (u >= 0) return usart_read(s, u, off, effects);
    int t = tim_index_of_periph(s, pi);
    if (t >= 0) return tim_read(s, t, off, effects);
    int a = adc_index_of_periph(s, pi);
    if (a >= 0) return adc_read(s, a, off, effects);
    if (pi == s->pi_dac1) return dac_read(s, off);
    if (pi == s->pi_exti) return exti_read(s, off);
    int c = i2c_index_of_periph(s, pi);
    if (c >= 0) return i2c_read(s, c, off, effects);
    int sp = spi_index_of_periph(s, pi);
    if (sp >= 0) return spi_read(s, sp, off, effects);
    if (pi == s->pi_dbgmcu && off == 0) return 0x20016468u;   /* IDCODE: G431/G441, rev Z */
    return s->regs[pi][off / 4];
}

static void periph_write(struct sl_soc *s, int pi, uint32_t off, uint32_t v, uint32_t mask)
{
    if (pi == s->pi_rcc) { rcc_write(s, off, v, mask); return; }
    if (pi == s->pi_pwr) { pwr_write(s, off, v, mask); return; }
    if (pi == s->pi_flash) { flash_write(s, off, v, mask); return; }
    int port = gpio_port_of(s, pi);
    if (port >= 0) { gpio_write(s, port, off, v, mask); return; }
    int u = usart_index_of_periph(s, pi);
    if (u >= 0) { usart_write(s, u, off, v, mask); return; }
    int t = tim_index_of_periph(s, pi);
    if (t >= 0) { tim_write(s, t, off, v, mask); return; }
    int a = adc_index_of_periph(s, pi);
    if (a >= 0) { adc_write(s, a, off, v, mask); return; }
    if (pi == s->pi_dac1) { dac_write(s, off, v, mask); return; }
    if (pi == s->pi_exti) { exti_write(s, off, v, mask); return; }
    int c = i2c_index_of_periph(s, pi);
    if (c >= 0) { i2c_write(s, c, off, v, mask); return; }
    int sp = spi_index_of_periph(s, pi);
    if (sp >= 0) { spi_write(s, sp, off, v, mask); return; }
    uint32_t wm = s->wmask[pi][off / 4] & mask;
    s->regs[pi][off / 4] = (s->regs[pi][off / 4] & ~wm) | (v & wm);
}

/* ====================================================================== */
/* Idle detection                                                         */
/* ====================================================================== */

/* A loop that keeps reading the same register with the same result, from
 * the same instruction with the same registers and without changing memory,
 * cannot end before something outside the CPU changes. That only happens
 * at a scheduled event or on input, so the emulator may sleep until then. */
static void idle_observe(struct sl_soc *s, int pi, uint32_t a, uint32_t v)
{
    struct sl_idle *d = &s->idle;
    struct cm_cpu *c = &s->cpu;
    uint32_t x = cm_xpsr(c);
    if (d->addr == a && d->value == v && d->pc == c->r[15] && d->xpsr == x
        && d->changes == c->mem_changes && c->cycles - d->cyc < 4096
        && !memcmp(d->regs, c->r, sizeof d->regs)) {
        if (++d->count >= 8) {
            s->idle_poll = 1;
            d->periph = pi;
            c->stop_batch = 1;
            /* Make sure the peripheral wakes the CPU when the register can change. */
            int t = tim_index_of_periph(s, pi), ad = adc_index_of_periph(s, pi);
            if (t >= 0) tim_poll_hint(s, t);
            if (ad >= 0) adc_poll_hint(s, ad);
        }
    } else {
        d->addr = a;
        d->value = v;
        d->pc = c->r[15];
        d->xpsr = x;
        memcpy(d->regs, c->r, sizeof d->regs);
        d->count = 0;
    }
    d->cyc = c->cycles;
    d->changes = c->mem_changes;
}

static void idle_break(struct sl_soc *s)
{
    s->idle.count = 0;
    s->idle.addr = 0;
}

/* ====================================================================== */
/* Bus callbacks                                                          */
/* ====================================================================== */

static int mmio_read(void *ctx, uint32_t a, int n, uint32_t *v)
{
    struct sl_soc *s = ctx;
    int pi = find_periph(s, a);
    if (pi >= 0) s->last_pi = pi;
    if (pi < 0) {
        soc_log(s, LOG_BUS_ERROR, "Bus error: read of 0x%08X, where there is no memory or peripheral (PC 0x%08X)",
                a, s->cpu.r[15]);
        return 1;
    }
    const struct sl_periph_info *p = &sl_g431_periph[pi];
    uint32_t off = a - p->base, w;
    if (!s->clk_on[pi]) {
        soc_log(s, LOG_CLOCK_OFF, "%s read while its clock is off in RCC: reads give 0 (PC 0x%08X)",
                p->name, s->cpu.r[15]);
        w = 0;
    } else {
        s->rd_width = n;
        w = periph_read(s, pi, off & ~3u, 1);
        s->rd_width = 4;
    }
    unsigned sh = (off & 3u) * 8u;
    *v = n == 4 ? w : (w >> sh) & ((1u << (8 * n)) - 1u);
    idle_observe(s, pi, a, *v);
    return 0;
}

static int mmio_write(void *ctx, uint32_t a, int n, uint32_t v)
{
    struct sl_soc *s = ctx;
    idle_break(s);
    int pi = find_periph(s, a);
    if (pi >= 0) s->last_pi = pi;
    if (pi < 0) {
        soc_log(s, LOG_BUS_ERROR, "Bus error: write to 0x%08X, where there is no memory or peripheral (PC 0x%08X)",
                a, s->cpu.r[15]);
        return 1;
    }
    const struct sl_periph_info *p = &sl_g431_periph[pi];
    uint32_t off = a - p->base;
    if (!s->clk_on[pi]) {
        soc_log(s, LOG_CLOCK_OFF, "%s written while its clock is off in RCC: the write is lost (PC 0x%08X)",
                p->name, s->cpu.r[15]);
        return 0;
    }
    unsigned sh = (off & 3u) * 8u;
    uint32_t mask = (n == 4 ? 0xFFFFFFFFu : ((1u << (8 * n)) - 1u)) << sh;
    uint32_t before = periph_read(s, pi, off & ~3u, 0);
    periph_write(s, pi, off & ~3u, v << sh, mask);
    soc_alog_add(s, a & ~3u, v << sh, mask, before, periph_read(s, pi, off & ~3u, 0));
    return 0;
}

static void scs_written(void *ctx, uint32_t a, uint32_t v, uint32_t mask, uint32_t before, uint32_t after)
{
    soc_alog_add(ctx, a, v, mask, before, after);
}

void soc_alog_add(struct sl_soc *s, uint32_t addr, uint32_t value, uint32_t mask, uint32_t before, uint32_t after)
{
    struct sl_alog *l = &s->alog;
    uint32_t pc = s->debug_write ? SL_ACCESS_DEBUG : s->cpu.r[15];
    uint64_t t = soc_now_ps(s) / 1000u;
    /* the same instruction writing the same register again (a loop): count it */
    for (uint32_t k = 1; k <= 8 && k <= l->n; k++) {
        struct sl_alog_entry *e = &l->e[(l->head - k) % SL_ALOG_SIZE];
        if (e->pc == pc && e->addr == addr) {
            e->t_last_ns = t;
            e->value = value;
            e->mask |= mask;
            e->changed |= before ^ after;
            e->after = after;
            e->count++;
            return;
        }
    }
    l->e[l->head % SL_ALOG_SIZE] = (struct sl_alog_entry){ t, t, pc, addr, value, mask, before, after, before ^ after, 1 };
    l->head++;
    if (l->n < SL_ALOG_SIZE) l->n++;
    else l->lost++;
}

static int mmio_peek(void *ctx, uint32_t a, uint32_t *v)
{
    *v = soc_peek32(ctx, a & ~3u);
    return 0;
}

uint32_t soc_peek32(struct sl_soc *s, uint32_t a)
{
    int pi = find_periph(s, a);
    if (pi < 0 || !s->clk_on[pi]) return 0;
    return periph_read(s, pi, (a - sl_g431_periph[pi].base) & ~3u, 0);
}

static int rom_write(void *ctx, uint32_t a, int n, uint32_t v)
{
    struct sl_soc *s = ctx;
    idle_break(s);
    if (a >= SL_SYSMEM_BASE && a < SL_SYSMEM_BASE + SL_SYSMEM_SIZE) return 1;
    return flash_mem_write(s, a, n, v);
}

static void on_sysresetreq(void *ctx)
{
    struct sl_soc *s = ctx;
    if (s->sysreset) s->sysreset(s->host);
}

/* ====================================================================== */
/* Init and reset                                                         */
/* ====================================================================== */

static int periph_index(const char *name)
{
    for (int i = 0; i < sl_g431_nperiph; i++)
        if (!strcmp(sl_g431_periph[i].name, name)) return i;
    return -1;
}

void soc_init(struct sl_soc *s)
{
    s->np = sl_g431_nperiph;
    for (int i = 0; i < s->np; i++) {
        const struct sl_periph_info *p = &sl_g431_periph[i];
        uint32_t words = p->size / 4u + 1u;
        s->regs[i] = calloc(words, 4);
        s->wmask[i] = calloc(words, 4);
        for (int r = 0; r < p->nregs; r++)
            if (p->regs[r].off < p->size) s->wmask[i][p->regs[r].off / 4u] = p->regs[r].wmask;
    }
    s->pi_rcc = periph_index("RCC");
    s->pi_pwr = periph_index("PWR");
    s->pi_flash = periph_index("FLASH");
    s->pi_syscfg = periph_index("SYSCFG");
    s->pi_dbgmcu = periph_index("DBGMCU");
    s->pi_exti = periph_index("EXTI");
    s->pi_dac1 = periph_index("DAC1");
    s->pi_adc_common = periph_index("ADC12_COMMON");
    static const char *ports[SL_NUM_PORTS] = { "GPIOA", "GPIOB", "GPIOC", "GPIOD", "GPIOE", "GPIOF", "GPIOG" };
    for (int i = 0; i < SL_NUM_PORTS; i++) s->pi_gpio[i] = periph_index(ports[i]);
    memset(s->port_of, -1, sizeof s->port_of);
    for (int i = 0; i < SL_NUM_PORTS; i++) if (s->pi_gpio[i] >= 0) s->port_of[s->pi_gpio[i]] = (int8_t)i;
    s->last_pi = 0;
    memset(s->af_index, -1, sizeof s->af_index);
    for (int i = 0; i < sl_g431_naf; i++) {
        const struct sl_af_info *e = &sl_g431_af[i];
        if (e->port < SL_NUM_PORTS && e->pin < 16 && e->af < 16
            && s->af_index[e->port][e->pin][e->af] < 0)
            s->af_index[e->port][e->pin][e->af] = (int16_t)i;
    }

    memset(s->flash, 0xFF, sizeof s->flash);
    memset(s->sysmem, 0xFF, sizeof s->sysmem);
    /* Factory data in system memory */
    static const uint8_t uid[12] = { 0x2C, 0x00, 0x41, 0x00, 0x0B, 0x51, 0x53, 0x4C, 0x44, 0x52, 0x31, 0x20 };
    memcpy(s->sysmem + (0x1FFF7590u - SL_SYSMEM_BASE), uid, sizeof uid);
    s->sysmem[0x1FFF75E0u - SL_SYSMEM_BASE] = 0x80;     /* flash size: 128 KiB */
    s->sysmem[0x1FFF75E1u - SL_SYSMEM_BASE] = 0x00;
    s->sysmem[0x1FFF7500u - SL_SYSMEM_BASE] = 0x02;     /* package code; see design doc */
    /* Temperature sensor and VREFINT calibration values (typical) */
    s->sysmem[0x1FFF75A8u - SL_SYSMEM_BASE] = 0x10; s->sysmem[0x1FFF75A9u - SL_SYSMEM_BASE] = 0x04;
    s->sysmem[0x1FFF75AAu - SL_SYSMEM_BASE] = 0xF8; s->sysmem[0x1FFF75ABu - SL_SYSMEM_BASE] = 0x05;
    s->sysmem[0x1FFF75CAu - SL_SYSMEM_BASE] = 0x6C; s->sysmem[0x1FFF75CBu - SL_SYSMEM_BASE] = 0x05;

    s->r_flash = (struct cm_region){ SL_FLASH_BASE, SL_FLASH_SIZE, s->flash, 0, 1 };
    s->r_alias = (struct cm_region){ 0x00000000u, SL_FLASH_SIZE, s->flash, 0, 1 };
    s->r_sram = (struct cm_region){ SL_SRAM_BASE, SL_SRAM_SIZE, s->sram, 1, 1 };
    s->r_ccm = (struct cm_region){ SL_CCM_BASE, SL_CCM_SIZE, s->sram + SL_CCM_OFFSET, 1, 1 };
    s->r_sysmem = (struct cm_region){ SL_SYSMEM_BASE, SL_SYSMEM_SIZE, s->sysmem, 0, 1 };
    s->bus.map[0x0][0] = &s->r_alias;
    s->bus.map[0x0][1] = &s->r_flash;            /* 0x08.. shares the 0x0 slot */
    s->bus.map[0x1][0] = &s->r_ccm;
    s->bus.map[0x1][1] = &s->r_sysmem;
    s->bus.map[0x2][0] = &s->r_sram;
    s->bus.ctx = s;
    s->bus.mmio_read = mmio_read;
    s->bus.mmio_write = mmio_write;
    s->bus.mmio_peek = mmio_peek;
    s->bus.scs_written = scs_written;
    s->bus.rom_write = rom_write;

    cm_init(&s->cpu, &s->bus, SL_NUM_IRQ, 4);
    s->cpu.hook_ctx = s;
    s->cpu.on_sysresetreq = on_sysresetreq;

    if (!s->term_baud) s->term_baud = 115200;
    s->hclk = 16000000u;
    usart_init(s);
    tim_init(s);
    adc_init(s);
    i2c_init(s);
    spi_init(s);
    board_init(s);
    soc_reset(s, 1);
}

void soc_reset(struct sl_soc *s, int power_on)
{
    for (int i = 0; i < EV_COUNT; i++) s->ev[i] = SL_NEVER;
    if (power_on) memset(s->sram, 0, sizeof s->sram);
    uint32_t bdcr = s->regs[s->pi_rcc][0x90 / 4];   /* backup domain survives a system reset */
    for (int i = 0; i < s->np; i++) periph_regs_reset(s, i);
    if (!power_on) s->regs[s->pi_rcc][0x90 / 4] = bdcr;
    rcc_reset(s, power_on);
    flash_reset(s);
    gpio_reset(s);
    for (int i = 0; i < SL_NUM_USART; i++) usart_reset(s, i);
    memset(s->irq_src, 0, sizeof s->irq_src);
    for (int i = 0; i < SL_NUM_TIM; i++) if (s->tim[i].pi >= 0) tim_reset(s, i);
    for (int i = 0; i < SL_NUM_ADC; i++) if (s->adc[i].pi >= 0) adc_reset(s, i);
    dac_reset(s);
    exti_reset(s);
    for (int i = 0; i < SL_NUM_I2C; i++) if (s->i2c[i].pi >= 0) i2c_reset(s, i);
    for (int i = 0; i < SL_NUM_SPI; i++) if (s->spi[i].pi >= 0) spi_reset(s, i);
    update_clock_gates(s);
    s->term_rx_busy = 0;
    s->idle_poll = 0;
    idle_break(s);
    soc_time_commit(s);
    s->t_rem = 0;
    s->hclk = 16000000u;
    rcc_update_clocks(s);
    if (power_on) bus_power_cycle(s);
    board_reset(s);
    cm_reset(&s->cpu);
    if (s->cpu.lockup)
        soc_log(s, LOG_LOCKUP, "The core could not start: no valid vector table at 0x08000000 (is flash empty?)");
}

/* ====================================================================== */
/* Terminal                                                               */
/* ====================================================================== */

void soc_term_kick(struct sl_soc *s)
{
    if (s->term_rx_busy || !s->term_rx_ready || !s->term_rx_ready(s->host)) return;
    uint8_t b;
    if (!s->term_rx_pop(s->host, &b)) return;
    s->term_rx_byte = b;
    s->term_rx_busy = 1;
    /* 8N1 frame: start bit, 8 data bits, stop bit */
    uint64_t frame = SL_PS_PER_S * 10u / s->term_baud;
    soc_sched(s, EV_TERM_RX, soc_now_ps(s) + frame);
}

int soc_term_busy(struct sl_soc *s)
{
    if (s->term_rx_busy) return 1;
    for (int i = 0; i < SL_NUM_USART; i++)
        if (usart_tx_busy(s, i)) return 1;
    return 0;
}
