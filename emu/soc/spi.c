/*
 * Solderless - SPI1, SPI2, SPI3 as masters (RM0440 chapter 39).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Modelled: master mode with the 32-bit transmit and receive FIFOs, data
 * sizes of 4 to 16 bits with the packing rules of DR (an 8-bit access moves
 * one byte, a 16- or 32-bit access two: a word written with ! in 8-bit mode
 * sends two frames), FRXTH (with its reset value RXNE waits for two bytes),
 * TXE, RXNE, BSY, FTLVL, FRLVL, overrun, the mode fault when NSS reads low
 * (SSM and SSI, or the NSS pin), NSS driven by the SPI with SSOE, CPOL and
 * CPHA, LSBFIRST, the baud rate from PCLK and BR, the interrupts. Frames go
 * to the parts whole, at the end of their time on the wire.
 * Not yet: slave mode, bidirectional and receive-only modes, CRC, TI mode,
 * NSS pulses (NSSP), I2S, DMA.
 */
#include "soc.h"

#include <string.h>

#define CR1   0x00u
#define CR2   0x04u
#define SR    0x08u
#define DR    0x0Cu

#define CR1_CPHA     (1u << 0)
#define CR1_CPOL     (1u << 1)
#define CR1_MSTR     (1u << 2)
#define CR1_SPE      (1u << 6)
#define CR1_LSBFIRST (1u << 7)
#define CR1_SSI      (1u << 8)
#define CR1_SSM      (1u << 9)

#define CR2_SSOE   (1u << 2)
#define CR2_ERRIE  (1u << 5)
#define CR2_RXNEIE (1u << 6)
#define CR2_TXEIE  (1u << 7)
#define CR2_FRXTH  (1u << 12)

#define SR_RXNE (1u << 0)
#define SR_TXE  (1u << 1)
#define SR_MODF (1u << 5)
#define SR_OVR  (1u << 6)
#define SR_BSY  (1u << 7)

static uint32_t *regs(struct sl_soc *s, int i) { return s->regs[s->spi[i].pi]; }

void spi_init(struct sl_soc *s)
{
    static const char *const names[SL_NUM_SPI] = { "SPI1", "SPI2", "SPI3" };
    for (int i = 0; i < SL_NUM_SPI; i++) {
        struct sl_spi *c = &s->spi[i];
        memset(c, 0, sizeof *c);
        c->name = names[i];
        c->pi = -1;
        c->irq = -1;
        for (int k = 0; k < sl_g431_nperiph; k++)
            if (!strcmp(sl_g431_periph[k].name, names[i])) { c->pi = k; c->irq = sl_g431_periph[k].irq[0]; }
    }
}

int spi_index_of_periph(struct sl_soc *s, int pi)
{
    for (int i = 0; i < SL_NUM_SPI; i++) if (s->spi[i].pi == pi && pi >= 0) return i;
    return -1;
}

static int frame_bits(struct sl_soc *s, int i)
{
    int ds = (int)((regs(s, i)[CR2 / 4] >> 8) & 15u);
    return ds < 3 ? 8 : ds + 1;             /* 0000-0010 are not allowed and act as 8 bits */
}

static int rx_threshold(struct sl_soc *s, int i)
{
    return (regs(s, i)[CR2 / 4] & CR2_FRXTH) ? 1 : 2;
}

static int fifo_level(int n) { return n >= 3 ? 3 : n; }

static uint32_t status(struct sl_soc *s, int i)
{
    struct sl_spi *c = &s->spi[i];
    uint32_t v = c->flags & (SR_MODF | SR_OVR);
    if (c->txn <= 2) v |= SR_TXE;
    if (c->rxn >= rx_threshold(s, i)) v |= SR_RXNE;
    if (c->busy || c->txn) v |= SR_BSY;
    v |= (uint32_t)fifo_level(c->rxn) << 9;
    v |= (uint32_t)fifo_level(c->txn) << 11;
    return v;
}

static void update_irq(struct sl_soc *s, int i)
{
    struct sl_spi *c = &s->spi[i];
    uint32_t cr2 = regs(s, i)[CR2 / 4], sr = status(s, i);
    int on = ((sr & SR_TXE) && (cr2 & CR2_TXEIE)) || ((sr & SR_RXNE) && (cr2 & CR2_RXNEIE))
          || ((sr & (SR_OVR | SR_MODF)) && (cr2 & CR2_ERRIE));
    soc_irq(s, c->irq, 50 + i, on);
}

void spi_reset(struct sl_soc *s, int i)
{
    struct sl_spi *c = &s->spi[i];
    c->txn = c->rxn = 0;
    c->busy = 0;
    c->flags = 0;
    c->modf_seen = c->ovr_dr_read = 0;
    c->mosi_level = 0;
    s->ev[EV_SPI0 + i] = SL_NEVER;
    update_irq(s, i);
}

/* The pin a signal of this SPI is on: a pin in its alternate function */
static int find_pin(struct sl_soc *s, int i, const char *sig)
{
    struct sl_spi *c = &s->spi[i];
    for (int k = 0; k < sl_g431_naf; k++) {
        const struct sl_af_info *e = &sl_g431_af[k];
        if (e->periph != c->pi || strcmp(e->signal, sig)) continue;
        if (gpio_pin_af(s, e->port, e->pin) == e->af) return e->port * 16 + e->pin;
    }
    return -1;
}

/* What the master sees on its NSS input: SSI with SSM, else the NSS pin */
static int nss_input(struct sl_soc *s, int i)
{
    uint32_t cr1 = regs(s, i)[CR1 / 4], cr2 = regs(s, i)[CR2 / 4];
    if (cr1 & CR1_SSM) return (cr1 & CR1_SSI) != 0;
    if (cr2 & CR2_SSOE) return 1;           /* NSS is an output: no mode fault */
    int pin = find_pin(s, i, "NSS");
    if (pin < 0) return 0;                  /* nothing drives the NSS input */
    return board_pin_level(s, pin >> 4, pin & 15);
}

/* A master that sees NSS low drops out: MODF, SPE and MSTR cleared */
static int mode_fault(struct sl_soc *s, int i)
{
    struct sl_spi *c = &s->spi[i];
    uint32_t *r = regs(s, i);
    if (!(r[CR1 / 4] & CR1_MSTR) || !(r[CR1 / 4] & CR1_SPE) || nss_input(s, i)) return 0;
    c->flags |= SR_MODF;
    r[CR1 / 4] &= ~(CR1_SPE | CR1_MSTR);
    c->busy = 0;
    s->ev[EV_SPI0 + i] = SL_NEVER;
    if (r[CR1 / 4] & CR1_SSM)
        soc_log(s, LOG_SPI_MODF, "%s: mode fault (MODF): SSM is set but SSI is 0, so the master sees its NSS low and stops. "
                "Set SSI too (CR1 bit 8) when a GPIO is the chip select", c->name);
    else
        soc_log(s, LOG_SPI_MODF, "%s: mode fault (MODF): NSS reads low, so the master stops (SPE and MSTR cleared). "
                "With a GPIO as chip select, set SSM and SSI in CR1; or let the SPI drive NSS with SSOE", c->name);
    board_af_outputs_changed(s);
    return 1;
}

/* Starts the next frame if the FIFO holds one */
static void kick(struct sl_soc *s, int i)
{
    struct sl_spi *c = &s->spi[i];
    uint32_t *r = regs(s, i);
    if (c->busy || !(r[CR1 / 4] & CR1_SPE) || !(r[CR1 / 4] & CR1_MSTR)) return;
    int bits = frame_bits(s, i), need = bits > 8 ? 2 : 1;
    if (c->txn < need) return;
    if (mode_fault(s, i)) return;
    uint32_t v = c->txf[0];
    if (need == 2) v |= (uint32_t)c->txf[1] << 8;
    memmove(c->txf, c->txf + need, (size_t)(4 - need));
    c->txn -= need;
    c->cur = v & ((1u << bits) - 1u);
    c->cur_bits = bits;
    uint32_t pclk = i == 0 ? s->pclk2 : s->pclk1;
    uint32_t br = (r[CR1 / 4] >> 3) & 7u;
    c->hz = pclk >> (br + 1);
    if (!c->hz) c->hz = 1;
    c->busy = 1;
    uint64_t bit_ps = SL_PS_PER_S / c->hz;
    soc_sched(s, EV_SPI0 + i, soc_now_ps(s) + bit_ps * (uint64_t)bits + bit_ps / 2);
}

/* The last bit on MOSI stays there after the frame */
static int last_bit(uint32_t v, int bits, int lsb)
{
    return lsb ? (int)((v >> (bits - 1)) & 1u) : (int)(v & 1u);
}

void spi_event(struct sl_soc *s, int i)
{
    struct sl_spi *c = &s->spi[i];
    uint32_t *r = regs(s, i);
    if (!c->busy) return;
    c->busy = 0;
    int sck = find_pin(s, i, "SCK"), mosi = find_pin(s, i, "MOSI"), miso = find_pin(s, i, "MISO");
    uint32_t rx = 0;
    if (sck < 0 || mosi < 0) {
        soc_log(s, LOG_SPI_PINS, "%s: a frame went out, but no pin is its %s: set the pin's MODER to alternate function "
                "and AFR to the SPI function", c->name, sck < 0 ? "SCK" : "MOSI");
    } else {
        int mode = (int)(r[CR1 / 4] & 3u);
        int lsb = (r[CR1 / 4] & CR1_LSBFIRST) != 0;
        rx = bus_spi_frame(s, sck, mosi, miso, c->cur, c->cur_bits, mode, lsb, c->hz, c->name);
    }
    int last = last_bit(c->cur, c->cur_bits, (r[CR1 / 4] & CR1_LSBFIRST) != 0);
    if (last != c->mosi_level) { c->mosi_level = last; board_af_outputs_changed(s); }
    int n = c->cur_bits > 8 ? 2 : 1;
    if (c->rxn + n > 4) {
        c->flags |= SR_OVR;
        c->ovr_dr_read = 0;
    } else if (!(c->flags & SR_OVR)) {
        c->rxf[c->rxn++] = (uint8_t)rx;
        if (n == 2) c->rxf[c->rxn++] = (uint8_t)(rx >> 8);
    }
    kick(s, i);
    update_irq(s, i);
}

uint32_t spi_read(struct sl_soc *s, int i, uint32_t off, int effects)
{
    struct sl_spi *c = &s->spi[i];
    switch (off) {
    case SR: {
        uint32_t v = status(s, i);
        if (effects) {
            if (v & SR_MODF) c->modf_seen = 1;
            if ((v & SR_OVR) && c->ovr_dr_read) { c->flags &= ~SR_OVR; c->ovr_dr_read = 0; }
        }
        return v;
    }
    case DR: {
        int n = s->rd_width == 1 ? 1 : 2;
        uint32_t v = 0;
        for (int k = 0; k < n && k < c->rxn; k++) v |= (uint32_t)c->rxf[k] << (8 * k);
        /* An overrun matters once the firmware reads what came in: only sending, it is harmless */
        if (effects && (c->flags & SR_OVR) && !c->ovr_dr_read)
            soc_log(s, LOG_SPI_OVR, "%s: overrun (OVR): frames came in while the receive FIFO was full and were lost, "
                    "so DR gives old bytes. Read DR after every frame, or empty it before a transfer you read", c->name);
        if (effects) {
            int take = n < c->rxn ? n : c->rxn;
            memmove(c->rxf, c->rxf + take, (size_t)(4 - take));
            c->rxn -= take;
            if (c->flags & SR_OVR) c->ovr_dr_read = 1;
            update_irq(s, i);
        }
        return v;
    }
    default:
        return regs(s, i)[off / 4];
    }
}

void spi_write(struct sl_soc *s, int i, uint32_t off, uint32_t v, uint32_t mask)
{
    struct sl_spi *c = &s->spi[i];
    uint32_t *r = regs(s, i);
    uint32_t wm = s->wmask[c->pi][off / 4] & mask;
    switch (off) {
    case CR1: {
        uint32_t was = r[CR1 / 4];
        r[CR1 / 4] = (was & ~wm) | (v & wm);
        if (c->modf_seen) { c->flags &= ~SR_MODF; c->modf_seen = 0; }
        if ((r[CR1 / 4] ^ was) & (CR1_SPE | CR1_MSTR | CR1_CPOL)) board_af_outputs_changed(s);
        if ((r[CR1 / 4] & CR1_SPE) && !(was & CR1_SPE)) mode_fault(s, i);
        kick(s, i);
        break;
    }
    case CR2: {
        uint32_t was = r[CR2 / 4];
        uint32_t w = (was & ~wm) | (v & wm);
        r[CR2 / 4] = w;
        if ((w ^ was) & CR2_SSOE) board_af_outputs_changed(s);
        break;
    }
    case DR: {
        /* Data packing: an 8-bit access moves one byte, a wider one two */
        int n = (mask & 0xFFFFu) == 0xFFu || (mask & 0xFFFFu) == 0xFF00u ? 1 : 2;
        uint32_t d = (mask & 0xFFFFu) == 0xFF00u ? v >> 8 : v;
        if (c->txn + n > 4) break;          /* the FIFO is full: the write is lost */
        c->txf[c->txn++] = (uint8_t)d;
        if (n == 2) c->txf[c->txn++] = (uint8_t)(d >> 8);
        kick(s, i);
        break;
    }
    case SR:
        break;                              /* CRCERR is the only writable bit; no CRC yet */
    default:
        r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
        break;
    }
    update_irq(s, i);
}

/* An SPI signal's output, for the pin it is on: 1 if driven, *level */
int spi_pin_out(struct sl_soc *s, int pi, const char *sig, int *level)
{
    int i = spi_index_of_periph(s, pi);
    if (i < 0) return 0;
    /* SPI2 and SPI3 list their pins under the I2S names too */
    if (!strcmp(sig, "I2S_CK")) sig = "SCK";
    else if (!strcmp(sig, "I2S_SD")) sig = "MOSI";
    else if (!strcmp(sig, "I2S_WS")) sig = "NSS";
    uint32_t cr1 = regs(s, i)[CR1 / 4], cr2 = regs(s, i)[CR2 / 4];
    if (!(cr1 & CR1_MSTR)) return 0;
    if (!strcmp(sig, "NSS")) {
        if (!(cr2 & CR2_SSOE)) return 0;
        *level = !(cr1 & CR1_SPE);          /* low while the SPI is on */
        return 1;
    }
    if (!(cr1 & CR1_SPE)) return 0;
    if (!strcmp(sig, "SCK")) { *level = (cr1 & CR1_CPOL) != 0; return 1; }
    if (!strcmp(sig, "MOSI")) { *level = s->spi[i].mosi_level; return 1; }
    return 0;
}

