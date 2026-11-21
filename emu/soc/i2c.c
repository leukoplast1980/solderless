/*
 * Solderless - I2C1, I2C2, I2C3 as masters (RM0440 chapter 41).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Modelled: the master transfer as the reference manual describes it, in
 * whole bytes on the virtual clock: START with the address from CR2, the
 * part's acknowledge or NACKF and the automatic STOP, TXIS and TXE while
 * writing, RXNE while reading (the last byte not acknowledged), NBYTES with
 * TC, TCR for RELOAD and AUTOEND, a repeated START after TC, STOP, the clock
 * stretched while TXDR is empty or RXDR full, the interrupts, PE as the
 * software reset. SCL runs at the rate TIMINGR gives with the kernel clock
 * from CCIPR, and each byte takes nine SCL periods. As on the chip, the pins
 * must be in the I2C alternate function and open-drain, and the lines need
 * pull-ups: without them SCL stays low and no START goes out.
 * Not yet: slave mode, 10-bit addresses, SMBus and PEC, timeouts, DMA,
 * arbitration (one master on the bus).
 */
#include "soc.h"

#include <string.h>

#define CR1      0x00u
#define CR2      0x04u
#define TIMINGR  0x10u
#define ISR      0x18u
#define ICR      0x1Cu
#define RXDR     0x24u
#define TXDR     0x28u

#define CR1_PE      (1u << 0)
#define CR1_TXIE    (1u << 1)
#define CR1_RXIE    (1u << 2)
#define CR1_ADDRIE  (1u << 3)
#define CR1_NACKIE  (1u << 4)
#define CR1_STOPIE  (1u << 5)
#define CR1_TCIE    (1u << 6)
#define CR1_ERRIE   (1u << 7)

#define CR2_RD_WRN  (1u << 10)
#define CR2_ADD10   (1u << 11)
#define CR2_START   (1u << 13)
#define CR2_STOP    (1u << 14)
#define CR2_RELOAD  (1u << 24)
#define CR2_AUTOEND (1u << 25)

#define ISR_TXE    (1u << 0)
#define ISR_TXIS   (1u << 1)
#define ISR_RXNE   (1u << 2)
#define ISR_ADDR   (1u << 3)
#define ISR_NACKF  (1u << 4)
#define ISR_STOPF  (1u << 5)
#define ISR_TC     (1u << 6)
#define ISR_TCR    (1u << 7)
#define ISR_BERR   (1u << 8)
#define ISR_ARLO   (1u << 9)
#define ISR_OVR    (1u << 10)
#define ISR_BUSY   (1u << 15)
#define ISR_DIR    (1u << 16)

enum {
    I2C_IDLE,       /* no transfer */
    I2C_STALLED,    /* START asked for, but SCL or SDA is held low */
    I2C_ADDR,       /* START and the address byte on the wire */
    I2C_TX,         /* a byte going out */
    I2C_TX_WAIT,    /* stretching SCL: TXDR is empty */
    I2C_RX,         /* a byte coming in */
    I2C_RX_WAIT,    /* stretching SCL: RXDR still full */
    I2C_HOLD,       /* NBYTES done: TC or TCR, SCL stretched */
    I2C_STOPPING,   /* STOP on the wire */
};

static uint32_t *regs(struct sl_soc *s, int i) { return s->regs[s->i2c[i].pi]; }
static uint64_t now(struct sl_soc *s) { return soc_now_ps(s); }

void i2c_init(struct sl_soc *s)
{
    static const char *const names[SL_NUM_I2C] = { "I2C1", "I2C2", "I2C3" };
    for (int i = 0; i < SL_NUM_I2C; i++) {
        struct sl_i2c *c = &s->i2c[i];
        memset(c, 0, sizeof *c);
        c->name = names[i];
        c->pi = -1;
        for (int k = 0; k < sl_g431_nperiph; k++)
            if (!strcmp(sl_g431_periph[k].name, names[i])) c->pi = k;
        if (c->pi >= 0) {
            const int *irq = sl_g431_periph[c->pi].irq;
            c->irq_ev = irq[0] < irq[1] ? irq[0] : irq[1];   /* EV comes before ER in the vector table */
            c->irq_er = irq[0] < irq[1] ? irq[1] : irq[0];
        }
        c->scl = c->sda = -1;
    }
}

int i2c_index_of_periph(struct sl_soc *s, int pi)
{
    for (int i = 0; i < SL_NUM_I2C; i++) if (s->i2c[i].pi == pi && pi >= 0) return i;
    return -1;
}

static void update_irq(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    uint32_t cr1 = regs(s, i)[CR1 / 4], f = c->isr;
    int ev = ((f & ISR_TXIS) && (cr1 & CR1_TXIE)) || ((f & ISR_RXNE) && (cr1 & CR1_RXIE))
          || ((f & ISR_ADDR) && (cr1 & CR1_ADDRIE)) || ((f & ISR_NACKF) && (cr1 & CR1_NACKIE))
          || ((f & ISR_STOPF) && (cr1 & CR1_STOPIE)) || ((f & (ISR_TC | ISR_TCR)) && (cr1 & CR1_TCIE));
    int er = (f & (ISR_BERR | ISR_ARLO | ISR_OVR)) && (cr1 & CR1_ERRIE);
    soc_irq(s, c->irq_ev, 40 + i, ev);
    soc_irq(s, c->irq_er, 44 + i, er);
}

void i2c_reset(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    c->isr = ISR_TXE;
    c->state = I2C_IDLE;
    c->count = c->nbytes = 0;
    c->stop_req = 0;
    c->scl = c->sda = -1;
    s->ev[EV_I2C0 + i] = SL_NEVER;
    update_irq(s, i);
}

/* Software reset by clearing PE: the state machine and the flags go back,
 * the configuration stays (RM0440 41.4.6) */
static void soft_reset(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    if (c->state != I2C_IDLE && c->scl >= 0) bus_i2c_stop(s, c->scl, c->sda, 0);
    regs(s, i)[CR2 / 4] &= ~(CR2_START | CR2_STOP);
    i2c_reset(s, i);
}

/* SCL period from TIMINGR and the kernel clock (RM0440 41.4.10): the low and
 * high periods plus the synchronisation and filter delays */
static void timing(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    uint32_t t = regs(s, i)[TIMINGR / 4];
    uint32_t presc = (t >> 28) & 15u, sclh = (t >> 8) & 255u, scll = t & 255u;
    uint32_t hz = rcc_i2c_kernel_hz(s, i);
    if (!hz) { c->bit_ps = 0; c->scl_hz = 0; return; }
    uint64_t kclk = SL_PS_PER_S / hz;
    uint64_t period = (uint64_t)(presc + 1) * (sclh + 1 + scll + 1) * kclk + 4 * kclk + 100000u;   /* + about 100 ns of filter */
    c->bit_ps = period;
    c->scl_hz = (uint32_t)(SL_PS_PER_S / period);
}

/* The pin a signal of this I2C is on: a pin in its alternate function */
static int find_pin(struct sl_soc *s, int i, const char *sig, int *open_drain)
{
    struct sl_i2c *c = &s->i2c[i];
    for (int k = 0; k < sl_g431_naf; k++) {
        const struct sl_af_info *e = &sl_g431_af[k];
        if (e->periph != c->pi || strcmp(e->signal, sig)) continue;
        if (gpio_pin_af(s, e->port, e->pin) != e->af) continue;
        *open_drain = (s->regs[s->pi_gpio[e->port]][1] >> e->pin) & 1u;
        return e->port * 16 + e->pin;
    }
    return -1;
}

static void sched(struct sl_soc *s, int i, uint64_t bits_x2)
{
    /* in half periods, so a START or STOP can take less than a bit */
    soc_sched(s, EV_I2C0 + i, now(s) + s->i2c[i].bit_ps * bits_x2 / 2);
}

static void pin_name(int slot, char *b)
{
    b[0] = 'P'; b[1] = (char)('A' + (slot >> 4));
    int n = slot & 15;
    if (n >= 10) { b[2] = '1'; b[3] = (char)('0' + n - 10); b[4] = 0; }
    else { b[2] = (char)('0' + n); b[3] = 0; }
}

/* START: work out the pins and the clock, check the lines, send the address */
static void start(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    uint32_t cr2 = regs(s, i)[CR2 / 4];
    if (cr2 & CR2_ADD10)
        soc_log(s, LOG_I2C_PINS, "%s: 10-bit addresses are not emulated yet", c->name);
    c->addr = (cr2 >> 1) & 0x7Fu;
    c->read = (cr2 & CR2_RD_WRN) != 0;
    c->nbytes = (cr2 >> 16) & 255u;
    c->reload = (cr2 & CR2_RELOAD) != 0;
    c->autoend = (cr2 & CR2_AUTOEND) != 0;
    c->count = 0;
    c->isr &= ~(ISR_TC | ISR_TCR | ISR_NACKF);
    timing(s, i);
    int od_scl = 0, od_sda = 0;
    c->scl = find_pin(s, i, "SCL", &od_scl);
    c->sda = find_pin(s, i, "SDA", &od_sda);
    char a[6], b[6];
    if (c->scl < 0 || c->sda < 0 || !c->bit_ps) {
        if (!c->bit_ps)
            soc_log(s, LOG_I2C_PINS, "%s: START, but its kernel clock is off (RCC CCIPR selects a clock that is not running)", c->name);
        else
            soc_log(s, LOG_I2C_PINS, "%s: START, but no pin is its %s: set the pin's MODER to alternate function and AFR to the I2C function",
                    c->name, c->scl < 0 ? "SCL" : "SDA");
        c->state = I2C_STALLED;
        c->isr |= ISR_BUSY;
        return;
    }
    if (!od_scl || !od_sda) {
        pin_name(od_scl ? c->sda : c->scl, a);
        soc_log(s, LOG_I2C_PUSHPULL, "%s: %s is push-pull. I2C lines must be open-drain (OTYPER): a part pulls SDA low to answer, "
                "and a push-pull pin drives it high at the same time", c->name, a);
    }
    if (!bus_pin_pulled_up(s, c->scl) || !bus_pin_pulled_up(s, c->sda)) {
        pin_name(c->scl, a); pin_name(c->sda, b);
        soc_log(s, LOG_I2C_PULLUP, "%s: nothing pulls %s up, so the line stays low and no START can go out. "
                "I2C needs pull-ups on SCL (%s) and SDA (%s): a module's own, or resistors of 2.2 to 10 kohm",
                c->name, !bus_pin_pulled_up(s, c->scl) ? a : b, a, b);
        c->state = I2C_STALLED;
        c->isr |= ISR_BUSY;
        return;
    }
    c->isr |= ISR_BUSY;
    c->state = I2C_ADDR;
    sched(s, i, 2 * 9 + 1);            /* START, then address and acknowledge */
}

static void stop(struct sl_soc *s, int i)
{
    s->i2c[i].state = I2C_STOPPING;
    sched(s, i, 1);
}

/* NBYTES transferred */
static void count_done(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    if (c->reload) { c->isr |= ISR_TCR; c->state = I2C_HOLD; }
    else if (c->autoend) stop(s, i);
    else { c->isr |= ISR_TC; c->state = I2C_HOLD; }
}

/* The next byte to send, if TXDR has one; else stretch */
static void next_tx(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    if (c->stop_req) { c->stop_req = 0; stop(s, i); return; }
    if (c->isr & ISR_TXE) {
        c->isr |= ISR_TXIS;
        c->state = I2C_TX_WAIT;
        return;
    }
    c->shift = c->txdr;
    c->isr |= ISR_TXE;
    if (c->count + 1 < c->nbytes || c->reload) c->isr |= ISR_TXIS;
    c->state = I2C_TX;
    sched(s, i, 2 * 9);
}

static void next_rx(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    if (c->stop_req) { c->stop_req = 0; stop(s, i); return; }
    c->state = I2C_RX;
    sched(s, i, 2 * 9);
}

static void deliver_rx(struct sl_soc *s, int i, uint8_t b)
{
    struct sl_i2c *c = &s->i2c[i];
    c->rxdr = b;
    c->isr |= ISR_RXNE;
    c->count++;
    if (c->count >= c->nbytes) count_done(s, i);
    else next_rx(s, i);
}

void i2c_event(struct sl_soc *s, int i)
{
    struct sl_i2c *c = &s->i2c[i];
    switch (c->state) {
    case I2C_ADDR: {
        regs(s, i)[CR2 / 4] &= ~CR2_START;
        int ack = bus_i2c_start(s, c->scl, c->sda, c->addr, c->read, c->scl_hz, c->name);
        if (!ack) {
            c->isr |= ISR_NACKF;
            stop(s, i);                 /* a master sends STOP after a NACK by itself */
            break;
        }
        if (c->nbytes == 0) { count_done(s, i); break; }
        if (c->read) next_rx(s, i);
        else next_tx(s, i);
        break;
    }
    case I2C_TX: {
        int ack = bus_i2c_write(s, c->scl, c->sda, c->shift);
        if (!ack) { c->isr |= ISR_NACKF; c->isr &= ~ISR_TXIS; stop(s, i); break; }
        c->count++;
        if (c->count >= c->nbytes) { c->isr &= ~ISR_TXIS; count_done(s, i); }
        else next_tx(s, i);
        break;
    }
    case I2C_RX: {
        /* The master acknowledges every byte but the last */
        int more = c->count + 1 < c->nbytes || c->reload;
        uint8_t b = bus_i2c_read(s, c->scl, c->sda, more);
        if (c->isr & ISR_RXNE) { c->pending = b; c->state = I2C_RX_WAIT; break; }
        deliver_rx(s, i, b);
        break;
    }
    case I2C_STOPPING:
        bus_i2c_stop(s, c->scl, c->sda, 1);
        c->isr |= ISR_STOPF;
        c->isr &= ~ISR_BUSY;
        regs(s, i)[CR2 / 4] &= ~(CR2_STOP | CR2_START);
        c->state = I2C_IDLE;
        break;
    default:
        break;
    }
    update_irq(s, i);
}

uint32_t i2c_read(struct sl_soc *s, int i, uint32_t off, int effects)
{
    struct sl_i2c *c = &s->i2c[i];
    switch (off) {
    case ISR:
        return (c->isr & ~(ISR_BUSY | ISR_DIR)) | ((c->state != I2C_IDLE) ? ISR_BUSY : 0)
             | ((c->state != I2C_IDLE && c->read) ? ISR_DIR : 0);
    case RXDR:
        if (effects && (c->isr & ISR_RXNE)) {
            uint8_t v = c->rxdr;
            c->isr &= ~ISR_RXNE;
            if (c->state == I2C_RX_WAIT) deliver_rx(s, i, c->pending);
            update_irq(s, i);
            return v;
        }
        return c->rxdr;
    case TXDR:
        return c->txdr;
    case ICR:
        return 0;
    default:
        return regs(s, i)[off / 4];
    }
}

void i2c_write(struct sl_soc *s, int i, uint32_t off, uint32_t v, uint32_t mask)
{
    struct sl_i2c *c = &s->i2c[i];
    uint32_t *r = regs(s, i);
    uint32_t wm = s->wmask[c->pi][off / 4] & mask;
    switch (off) {
    case CR1: {
        uint32_t was = r[CR1 / 4];
        r[CR1 / 4] = (was & ~wm) | (v & wm);
        if ((was & CR1_PE) && !(r[CR1 / 4] & CR1_PE)) soft_reset(s, i);
        break;
    }
    case CR2: {
        uint32_t was = r[CR2 / 4];
        uint32_t w = (was & ~wm) | (v & wm);
        r[CR2 / 4] = w;
        if (!(r[CR1 / 4] & CR1_PE)) { r[CR2 / 4] &= ~(CR2_START | CR2_STOP); break; }
        if (c->state == I2C_HOLD && (c->isr & ISR_TCR) && ((w >> 16) & 255u) != ((was >> 16) & 255u)) {
            /* RELOAD: a new NBYTES carries on */
            c->isr &= ~ISR_TCR;
            c->nbytes = (w >> 16) & 255u;
            c->reload = (w & CR2_RELOAD) != 0;
            c->autoend = (w & CR2_AUTOEND) != 0;
            c->count = 0;
            if (c->nbytes == 0) count_done(s, i);
            else if (c->read) next_rx(s, i);
            else next_tx(s, i);
        }
        if ((w & CR2_START) && !(was & CR2_START) && (c->state == I2C_IDLE || c->state == I2C_HOLD || c->state == I2C_STALLED)) {
            if (c->state == I2C_HOLD && c->scl >= 0) bus_i2c_stop(s, c->scl, c->sda, 0);  /* repeated START ends the part's run */
            start(s, i);
        } else if ((w & CR2_STOP) && !(was & CR2_STOP)) {
            if (c->state == I2C_HOLD) { c->isr &= ~(ISR_TC | ISR_TCR); stop(s, i); }
            else if (c->state == I2C_TX_WAIT || c->state == I2C_RX_WAIT) { c->isr &= ~ISR_TXIS; stop(s, i); }
            else if (c->state == I2C_TX || c->state == I2C_RX || c->state == I2C_ADDR) c->stop_req = 1;
            else if (c->state == I2C_STALLED) { c->state = I2C_IDLE; c->isr &= ~ISR_BUSY; r[CR2 / 4] &= ~(CR2_START | CR2_STOP); }
        }
        break;
    }
    case ISR:
        /* TXE can be set by software to flush TXDR */
        if ((v & mask & ISR_TXE) && c->state != I2C_TX_WAIT) c->isr |= ISR_TXE;
        break;
    case ICR: {
        uint32_t clr = v & mask & (ISR_ADDR | ISR_NACKF | ISR_STOPF | ISR_BERR | ISR_ARLO | ISR_OVR | 0x3800u);
        c->isr &= ~clr;
        break;
    }
    case TXDR:
        if (!(c->isr & ISR_TXE)) break;             /* written while full: the RM says it is ignored */
        c->txdr = (uint8_t)v;
        c->isr &= ~(ISR_TXE | ISR_TXIS);
        if (c->state == I2C_TX_WAIT) next_tx(s, i);
        break;
    case TIMINGR:
        if (r[CR1 / 4] & CR1_PE) break;             /* only while PE = 0 */
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
        break;
    default:
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
        break;
    }
    update_irq(s, i);
}
