/*
 * Solderless - USART1-3, UART4 and LPUART1 (RM0440 chapters 37 and 38).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Bytes take one frame time on the wire. They reach the serial terminal
 * only if the transmitting USART's TX signal is routed to the pin the
 * terminal is wired to (PA2), and arrive from the terminal on PA3. If
 * the two sides use different baud rates, the receiver samples the other
 * side's waveform at its own rate, so it sees what a real UART would see.
 *
 * Not yet emulated: FIFO mode, IDLE detection, LIN, smartcard, IrDA,
 * hardware flow control, auto baud rate, wakeup from Stop mode.
 */
#include "soc.h"

#include <stdlib.h>
#include <string.h>

#define CR1   0x00u
#define CR2   0x04u
#define CR3   0x08u
#define BRR   0x0Cu
#define GTPR  0x10u
#define RTOR  0x14u
#define RQR   0x18u
#define ISR   0x1Cu
#define ICR   0x20u
#define RDR   0x24u
#define TDR   0x28u
#define PRESC 0x2Cu

#define CR1_UE     (1u << 0)
#define CR1_RE     (1u << 2)
#define CR1_TE     (1u << 3)
#define CR1_IDLEIE (1u << 4)
#define CR1_RXNEIE (1u << 5)
#define CR1_TCIE   (1u << 6)
#define CR1_TXEIE  (1u << 7)
#define CR1_PEIE   (1u << 8)
#define CR1_PS     (1u << 9)
#define CR1_PCE    (1u << 10)
#define CR1_M0     (1u << 12)
#define CR1_OVER8  (1u << 15)
#define CR1_M1     (1u << 28)
#define CR3_EIE    (1u << 0)
#define CR3_OVRDIS (1u << 12)

#define ISR_PE     (1u << 0)
#define ISR_FE     (1u << 1)
#define ISR_NE     (1u << 2)
#define ISR_ORE    (1u << 3)
#define ISR_IDLE   (1u << 4)
#define ISR_RXNE   (1u << 5)
#define ISR_TC     (1u << 6)
#define ISR_TXE    (1u << 7)
#define ISR_BUSY   (1u << 16)
#define ISR_TEACK  (1u << 21)
#define ISR_REACK  (1u << 22)
#define ISR_TXFE   (1u << 23)

static const char *const names[SL_NUM_USART] = { "USART1", "USART2", "USART3", "UART4", "LPUART1" };

/* The serial terminal's wires: it listens on PA2 and talks into PA3 */
#define TERM_LISTEN_PORT 0
#define TERM_LISTEN_PIN  2
#define TERM_TALK_PORT   0
#define TERM_TALK_PIN    3

struct line_cfg {
    uint32_t baud;
    int bits;           /* word length including the parity bit */
    int parity;         /* 0 none, 1 even, 2 odd */
    int stop_half;      /* stop bits in half bits */
};

int usart_index_of_periph(struct sl_soc *s, int pi)
{
    for (int i = 0; i < SL_NUM_USART; i++)
        if (s->usart[i].periph == pi) return i;
    return -1;
}

void usart_init(struct sl_soc *s)
{
    for (int i = 0; i < SL_NUM_USART; i++) {
        struct sl_usart *u = &s->usart[i];
        u->periph = -1;
        for (int p = 0; p < sl_g431_nperiph; p++)
            if (!strcmp(sl_g431_periph[p].name, names[i])) u->periph = p;
        u->lp = i == 4;
        u->irq = u->periph >= 0 ? sl_g431_periph[u->periph].irq[0] : -1;
    }
    /* The terminal holds PA3 high while idle */
    s->pin_ext_drive[TERM_TALK_PORT] |= 1u << TERM_TALK_PIN;
    s->pin_ext_level[TERM_TALK_PORT] |= 1u << TERM_TALK_PIN;
}

void usart_reset(struct sl_soc *s, int idx)
{
    struct sl_usart *u = &s->usart[idx];
    int periph = u->periph, lp = u->lp, irq = u->irq;
    memset(u, 0, sizeof *u);
    u->periph = periph; u->lp = lp; u->irq = irq;
    u->isr = ISR_TC;
    s->ev[EV_USART_TX0 + idx] = SL_NEVER;
    if (irq >= 0) cm_set_irq(&s->cpu, irq, 0);
}

int usart_tx_busy(struct sl_soc *s, int idx)
{
    return s->usart[idx].shifting || s->usart[idx].tdr_full;
}

static uint32_t isr_value(struct sl_soc *s, int idx)
{
    struct sl_usart *u = &s->usart[idx];
    uint32_t v = u->isr;
    if (!u->tdr_full) v |= ISR_TXE | ISR_TXFE;
    if ((u->cr1 & (CR1_UE | CR1_TE)) == (CR1_UE | CR1_TE)) v |= ISR_TEACK;
    if ((u->cr1 & (CR1_UE | CR1_RE)) == (CR1_UE | CR1_RE)) v |= ISR_REACK;
    (void)s;
    return v;
}

static void update_irq(struct sl_soc *s, int idx)
{
    struct sl_usart *u = &s->usart[idx];
    if (u->irq < 0) return;
    uint32_t isr = isr_value(s, idx), cr1 = u->cr1;
    int level = ((cr1 & CR1_RXNEIE) && (isr & (ISR_RXNE | ISR_ORE)))
             || ((cr1 & CR1_TCIE) && (isr & ISR_TC))
             || ((cr1 & CR1_TXEIE) && (isr & ISR_TXE))
             || ((cr1 & CR1_IDLEIE) && (isr & ISR_IDLE))
             || ((cr1 & CR1_PEIE) && (isr & ISR_PE))
             || ((u->cr3 & CR3_EIE) && (isr & (ISR_FE | ISR_NE | ISR_ORE)));
    cm_set_irq(&s->cpu, u->irq, level && (cr1 & CR1_UE));
}

static struct line_cfg usart_cfg(struct sl_soc *s, int idx)
{
    struct sl_usart *u = &s->usart[idx];
    struct line_cfg c;
    uint32_t m = (((u->cr1 >> 28) & 1u) << 1) | ((u->cr1 >> 12) & 1u);
    c.bits = m == 0 ? 8 : m == 1 ? 9 : 7;
    c.parity = (u->cr1 & CR1_PCE) ? ((u->cr1 & CR1_PS) ? 2 : 1) : 0;
    static const int stop[4] = { 2, 1, 4, 3 };
    c.stop_half = stop[(u->cr2 >> 12) & 3u];

    static const uint32_t presc_div[16] = { 1, 2, 4, 6, 8, 10, 12, 16, 32, 64, 128, 256, 256, 256, 256, 256 };
    uint32_t kernel = rcc_usart_kernel_hz(s, idx) / presc_div[u->presc & 15u];
    uint64_t baud = 0;
    if (u->lp) {
        uint32_t div = u->brr & 0xFFFFFu;
        if (div >= 0x300u) baud = (uint64_t)kernel * 256u / div;
    } else if (u->cr1 & CR1_OVER8) {
        uint32_t div = (u->brr & 0xFFF0u) | ((u->brr & 7u) << 1);
        if (div >= 16) baud = (uint64_t)kernel * 2u / div;
    } else {
        uint32_t div = u->brr & 0xFFFFu;
        if (div >= 16) baud = kernel / div;
    }
    c.baud = (uint32_t)baud;
    return c;
}

static uint64_t frame_ps(struct line_cfg c)
{
    if (!c.baud) return SL_PS_PER_S / 1000u;
    uint64_t half_bits = 2u * (1u + (uint32_t)c.bits) + (uint32_t)c.stop_half;
    return half_bits * SL_PS_PER_S / (2u * c.baud);
}

static int parity_of(uint32_t v, int bits)
{
    return __builtin_popcount(v & ((1u << bits) - 1u)) & 1;
}

/* Line level at time t (seconds from the start bit's falling edge) of a
 * frame sent with configuration c. */
static int line_level(uint32_t word, struct line_cfg c, double t)
{
    if (t < 0) return 1;
    long idx = (long)(t * c.baud);
    if (idx == 0) return 0;                                  /* start bit */
    if (idx <= c.bits) return (int)((word >> (idx - 1)) & 1u);
    return 1;                                                /* stop bits, then idle */
}

/* What a receiver configured as rx decodes from one frame sent as tx.
 * Returns the number of characters (0 to 2) with their error flags. */
static int resample(uint32_t data, struct line_cfg tx, struct line_cfg rx,
                    uint32_t out[2], uint32_t err[2])
{
    /* Build the transmitted word, adding the parity bit */
    int dbits = tx.parity ? tx.bits - 1 : tx.bits;
    uint32_t word = data & ((1u << dbits) - 1u);
    if (tx.parity) word |= (uint32_t)(parity_of(word, dbits) ^ (tx.parity == 2)) << dbits;

    uint32_t lo = rx.baud * 97u / 100u, hi = rx.baud * 103u / 100u;
    if (tx.baud >= lo && tx.baud <= hi && tx.bits == rx.bits && tx.parity == rx.parity) {
        out[0] = data & ((1u << dbits) - 1u);
        err[0] = 0;
        return 1;
    }
    if (!tx.baud || !rx.baud) return 0;

    int n = 0;
    double start = 0.0;
    double tx_end = (1.0 + tx.bits + tx.stop_half / 2.0) / tx.baud;
    while (n < 2 && start < tx_end) {
        uint32_t w = 0;
        for (int i = 0; i < rx.bits; i++)
            w |= (uint32_t)line_level(word, tx, start + (1.5 + i) / rx.baud) << i;
        double stop_t = start + (1.5 + rx.bits) / rx.baud;
        uint32_t e = line_level(word, tx, stop_t) ? 0 : ISR_FE;
        int rdbits = rx.parity ? rx.bits - 1 : rx.bits;
        uint32_t d = w & ((1u << rdbits) - 1u);
        if (rx.parity && (((w >> rdbits) & 1u) != (uint32_t)(parity_of(d, rdbits) ^ (rx.parity == 2))))
            e |= ISR_PE;
        out[n] = d;
        err[n] = e;
        n++;
        /* Next falling edge after the stop sample, within the frame */
        double step = 1.0 / (tx.baud * 4.0), t = stop_t;
        int prev = line_level(word, tx, t);
        start = -1;
        for (t += step; t < tx_end; t += step) {
            int l = line_level(word, tx, t);
            if (prev && !l) { start = t; break; }
            prev = l;
        }
        if (start < 0) break;
    }
    return n;
}

static struct line_cfg term_cfg(struct sl_soc *s)
{
    struct line_cfg c = { s->term_baud, 8, 0, 2 };
    return c;
}

/* The USART whose signal (TX or RX) is routed to a pin, or -1. */
static int usart_on_pin(struct sl_soc *s, int port, int pin, const char *signal)
{
    int af = gpio_pin_af(s, port, pin);
    if (af < 0) return -1;
    for (int i = 0; i < sl_g431_naf; i++) {
        const struct sl_af_info *a = &sl_g431_af[i];
        if (a->port == port && a->pin == pin && a->af == af && !strcmp(a->signal, signal)) {
            int u = usart_index_of_periph(s, a->periph);
            if (u >= 0) return u;
        }
    }
    return -1;
}

static void start_shift(struct sl_soc *s, int idx, uint32_t data)
{
    struct sl_usart *u = &s->usart[idx];
    struct line_cfg c = usart_cfg(s, idx);
    u->shift = data;
    u->shift_baud = c.baud;
    u->shifting = 1;
    soc_sched(s, EV_USART_TX0 + idx, soc_now_ps(s) + frame_ps(c));
}

void usart_tx_event(struct sl_soc *s, int idx)
{
    struct sl_usart *u = &s->usart[idx];
    if (!u->shifting) return;
    u->shifting = 0;

    if (usart_on_pin(s, TERM_LISTEN_PORT, TERM_LISTEN_PIN, "TX") == idx && s->term_tx_push) {
        struct line_cfg tx = usart_cfg(s, idx), rx = term_cfg(s);
        uint32_t out[2], err[2];
        int n = resample(u->shift, tx, rx, out, err);
        if (n != 1 || out[0] != (u->shift & 0xFFu) || err[0])
            soc_log(s, LOG_BAUD, "%s sends at %u baud, %d bits%s; the terminal expects %u baud 8N1. "
                    "It receives garbage.", names[idx], tx.baud, tx.bits, tx.parity ? " with parity" : "",
                    rx.baud);
        for (int i = 0; i < n; i++) s->term_tx_push(s->host, (uint8_t)out[i]);
    }

    if (u->tdr_full) {
        u->tdr_full = 0;
        start_shift(s, idx, u->tdr);
    } else {
        u->isr |= ISR_TC;
    }
    update_irq(s, idx);
}

void usart_term_rx_event(struct sl_soc *s)
{
    s->term_rx_busy = 0;
    int idx = usart_on_pin(s, TERM_TALK_PORT, TERM_TALK_PIN, "RX");
    if (idx >= 0) {
        struct sl_usart *u = &s->usart[idx];
        if ((u->cr1 & (CR1_UE | CR1_RE)) == (CR1_UE | CR1_RE)) {
            struct line_cfg rx = usart_cfg(s, idx), tx = term_cfg(s);
            uint32_t out[2], err[2];
            int n = resample(s->term_rx_byte, tx, rx, out, err);
            if (n != 1 || err[0] || out[0] != s->term_rx_byte)
                soc_log(s, LOG_BAUD, "%s receives at %u baud, the terminal sends at %u baud: "
                        "the characters arrive garbled", names[idx], rx.baud, tx.baud);
            for (int i = 0; i < n; i++) {
                if (u->isr & ISR_RXNE) {
                    if (u->cr3 & CR3_OVRDIS) {
                        u->rdr = out[i];          /* overrun detection off: overwritten */
                    } else {
                        u->isr |= ISR_ORE;        /* the new character is lost */
                        soc_log(s, LOG_OVERRUN, "%s overrun: a character arrived before the previous one "
                                "was read from RDR, and was lost", names[idx]);
                    }
                } else {
                    u->rdr = out[i];
                    u->isr |= ISR_RXNE;
                }
                u->isr |= err[i];
            }
            update_irq(s, idx);
        } else {
            soc_log(s, LOG_USART_LOST, "%s receiver is off: typed characters are lost", names[idx]);
        }
    }
    soc_term_kick(s);
}

uint32_t usart_read(struct sl_soc *s, int idx, uint32_t off, int effects)
{
    struct sl_usart *u = &s->usart[idx];
    switch (off) {
    case CR1: return u->cr1;
    case CR2: return u->cr2;
    case CR3: return u->cr3;
    case BRR: return u->brr;
    case GTPR: return u->gtpr;
    case RTOR: return u->rtor;
    case ISR: return isr_value(s, idx);
    case RDR:
        if (effects) {
            u->isr &= ~ISR_RXNE;
            update_irq(s, idx);
        }
        return u->rdr;
    case TDR: return u->tdr;
    case PRESC: return u->presc;
    default: return 0;
    }
}

void usart_write(struct sl_soc *s, int idx, uint32_t off, uint32_t v, uint32_t mask)
{
    struct sl_usart *u = &s->usart[idx];
    uint32_t wm = s->wmask[u->periph][off / 4] & mask;
#define MERGE(f) (u->f = (u->f & ~wm) | (v & wm))
    switch (off) {
    case CR1: {
        uint32_t old = u->cr1;
        MERGE(cr1);
        if ((old & CR1_UE) && !(u->cr1 & CR1_UE)) {          /* disabled: abort transfers */
            u->shifting = 0;
            u->tdr_full = 0;
            s->ev[EV_USART_TX0 + idx] = SL_NEVER;
            u->isr = ISR_TC;
        }
        break;
    }
    case CR2: MERGE(cr2); break;
    case CR3: MERGE(cr3); break;
    case BRR: MERGE(brr); break;
    case GTPR: MERGE(gtpr); break;
    case RTOR: MERGE(rtor); break;
    case PRESC: MERGE(presc); break;
    case RQR:
        if (v & mask & (1u << 3)) u->isr &= ~ISR_RXNE;         /* RXFRQ */
        if (v & mask & (1u << 4)) u->tdr_full = 0;            /* TXFRQ */
        break;
    case ICR:
        u->isr &= ~(v & mask & 0x00123BDFu);
        break;
    case TDR: {
        uint32_t data = v & mask & 0x1FFu;
        if ((u->cr1 & (CR1_UE | CR1_TE)) != (CR1_UE | CR1_TE)) break;   /* transmitter off */
        u->isr &= ~ISR_TC;
        if (!u->shifting) start_shift(s, idx, data);
        else { u->tdr = data; u->tdr_full = 1; }
        break;
    }
    default:
        break;
    }
#undef MERGE
    update_irq(s, idx);
}
