/*
 * Solderless - bus parts on the board: which part is on which pins, the
 * I2C transfers the on-chip peripherals send them, and a pin-level decoder
 * for drivers that bit-bang the bus (common in Forth).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * The on-chip I2C peripheral talks to the parts in whole bytes (start with
 * the address, bytes with their acknowledge, stop) without simulating every
 * bit. The decoder watches SCL and SDA as the nets resolve: a START is SDA
 * falling while SCL is high, a bit is SDA when SCL rises, and a part answers
 * by holding SDA low on the net (its acknowledge, the 0s it sends), so the
 * firmware reads it back through IDR as on a real bus.
 */
#include "../soc/soc.h"

#include <stdio.h>
#include <string.h>

#define B (&s->board)

static const char *dev_name(int kind)
{
    switch (kind) {
    case SL_DEV_SSD1306: return "SSD1306 OLED";
    default: return "part";
    }
}

/* Fastest SCL the part's datasheet allows */
static uint32_t dev_max_hz(int kind)
{
    switch (kind) {
    case SL_DEV_SSD1306: return 400000u;    /* fast mode; the datasheet's tcycle is 2.5 us */
    default: return 100000u;
    }
}

static void pin_label(int slot, char *b)
{
    snprintf(b, 6, "P%c%d", 'A' + (slot >> 4), slot & 15);
}

void bus_set(struct sl_soc *s)
{
    /* Parts that stay keep their state: a display keeps its picture when
     * another part is added */
    static struct sl_dev old[SL_MAX_DEV];
    int nold = B->ndev;
    memcpy(old, B->dev, sizeof old);
    B->ndev = 0;
    for (int i = 0; i < B->natt; i++) {
        const sl_attach *a = &B->att[i].a;
        if (a->kind != SL_ATT_DEVICE) continue;
        struct sl_dev *d = 0;
        for (int k = 0; k < B->ndev; k++) if (B->dev[k].part == a->part) d = &B->dev[k];
        if (!d) {
            if (B->ndev >= SL_MAX_DEV) continue;
            d = &B->dev[B->ndev++];
            memset(d, 0, sizeof *d);
            for (int k = 0; k < SL_SIG_COUNT; k++) d->pin[k] = -1;
            d->part = a->part; d->kind = a->dev; d->addr = a->addr;
            d->dec_scl = d->dec_sda = 1;
            int kept = 0;
            for (int k = 0; k < nold; k++)
                if (old[k].part == a->part && old[k].kind == a->dev) { d->m = old[k].m; kept = 1; }
            if (!kept && d->kind == SL_DEV_SSD1306) ssd1306_power_on(&d->m.oled, 0x9E3779B9u * (uint32_t)(a->part + 1));
        }
        if (a->role >= 0 && a->role < SL_SIG_COUNT) d->pin[a->role] = a->pin;
    }
}

/* ---------------------------------------------------------------------- */
/* Byte level, shared by the on-chip peripherals and the decoder           */
/* ---------------------------------------------------------------------- */

static int on_bus(const struct sl_dev *d, int scl, int sda)
{
    return d->pin[SL_SIG_SCL] == scl && d->pin[SL_SIG_SDA] == sda;
}

/* Address phase for one part: does it answer? */
static int dev_start(struct sl_soc *s, struct sl_dev *d, int addr, int read)
{
    d->selected = 0;
    if (d->addr != addr) return 0;
    d->selected = 1;
    d->dec_read = read;
    if (d->kind == SL_DEV_SSD1306) ssd1306_start(&d->m.oled);
    return 1;
}

static int dev_write(struct sl_soc *s, struct sl_dev *d, uint8_t b)
{
    if (d->kind == SL_DEV_SSD1306) ssd1306_byte(&d->m.oled, b, soc_now_ps(s));
    return 1;
}

static uint8_t dev_read(struct sl_soc *s, struct sl_dev *d)
{
    /* SSD1306 over I2C: only the status byte; D6 is set while the display is off */
    if (d->kind == SL_DEV_SSD1306) return d->m.oled.on ? 0x00 : 0x40;
    return 0xFF;
}

static void dev_stop(struct sl_soc *s, struct sl_dev *d)
{
    if (d->selected && d->kind == SL_DEV_SSD1306) ssd1306_stop(&d->m.oled);
    d->selected = 0;
}

/* Nobody answered at addr. A bus scan meets this all the time, so only the
 * likely mistake is pointed out: the address not shifted into bits 7:1. */
static void no_answer(struct sl_soc *s, int scl, int sda, int addr, const char *who)
{
    for (int i = 0; i < B->ndev; i++) {
        const struct sl_dev *d = &B->dev[i];
        if (on_bus(d, scl, sda) && d->addr == addr << 1) {
            soc_log(s, LOG_I2C_NACK, "%s: no part answers at 0x%02X. The %s is at 0x%02X: if you meant it, the address goes "
                    "in bits 7:1 of CR2 (SADD), so it is written as 0x%02X", who, addr, dev_name(d->kind), d->addr, d->addr << 1);
            return;
        }
    }
}

int bus_i2c_start(struct sl_soc *s, int scl, int sda, int addr, int read, uint32_t scl_hz, const char *who)
{
    int acked = 0, n = 0;
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (!on_bus(d, scl, sda)) continue;
        d->selected = 0;
        if (d->addr != addr) continue;
        n++;
        uint32_t max = dev_max_hz(d->kind);
        if (scl_hz > max + max / 4) {
            soc_log(s, LOG_I2C_SPEED, "%s runs SCL at %u kHz; the %s at 0x%02X handles up to %u kHz and does not answer. "
                    "Set TIMINGR for %u kHz or less", who, scl_hz / 1000u, dev_name(d->kind), addr, max / 1000u, max / 1000u);
            continue;
        }
        acked |= dev_start(s, d, addr, read);
    }
    if (n > 1) soc_log(s, LOG_I2C_CLASH, "Two parts on the bus have address 0x%02X: both answer and their replies collide", addr);
    if (n == 0) no_answer(s, scl, sda, addr, who);
    return acked;
}

int bus_i2c_write(struct sl_soc *s, int scl, int sda, uint8_t b)
{
    int ack = 0;
    for (int i = 0; i < B->ndev; i++)
        if (B->dev[i].selected && on_bus(&B->dev[i], scl, sda)) ack |= dev_write(s, &B->dev[i], b);
    return ack;
}

uint8_t bus_i2c_read(struct sl_soc *s, int scl, int sda, int ack)
{
    uint8_t v = 0xFF;                       /* nobody drives SDA: the pull-up reads as 1s */
    for (int i = 0; i < B->ndev; i++)
        if (B->dev[i].selected && on_bus(&B->dev[i], scl, sda)) v &= dev_read(s, &B->dev[i]);
    (void)ack;
    return v;
}

void bus_i2c_stop(struct sl_soc *s, int scl, int sda)
{
    for (int i = 0; i < B->ndev; i++)
        if (on_bus(&B->dev[i], scl, sda)) dev_stop(s, &B->dev[i]);
}

int bus_pin_pulled_up(struct sl_soc *s, int slot)
{
    for (int i = 0; i < B->natt; i++) {
        const sl_attach *a = &B->att[i].a;
        if (a->pin != slot) continue;
        if (a->kind == SL_ATT_PULL && a->to_vcc && a->r_ohm > 0) return 1;
        if (a->kind == SL_ATT_DEVICE && a->r_ohm > 0) return 1;
    }
    uint32_t pupdr = s->regs[s->pi_gpio[slot >> 4]][3];
    return ((pupdr >> (2 * (slot & 15))) & 3u) == 1u;
}

/* ---------------------------------------------------------------------- */
/* Pin level: the decoder for bit-banged I2C                               */
/* ---------------------------------------------------------------------- */

enum { DEC_IDLE, DEC_IN, DEC_ACK, DEC_OUT, DEC_MACK, DEC_SKIP };

static int line(struct sl_soc *s, int slot)
{
    const struct sl_pin_state *p = &B->pin[slot];
    return !p->floating && p->level;
}

static void hold_sda(struct sl_soc *s, struct sl_dev *d, int low)
{
    if (d->sda_low == low) return;
    d->sda_low = low;
    int slot = d->pin[SL_SIG_SDA];
    board_port_changed(s, slot >> 4, 1u << (slot & 15));
}

static void decode(struct sl_soc *s, struct sl_dev *d)
{
    int scl = line(s, d->pin[SL_SIG_SCL]), sda = line(s, d->pin[SL_SIG_SDA]);
    int pscl = d->dec_scl, psda = d->dec_sda;
    d->dec_scl = scl; d->dec_sda = sda;
    if (scl && pscl && sda != psda) {
        if (!sda) {                         /* START, or a repeated START */
            if (d->selected) dev_stop(s, d);
            hold_sda(s, d, 0);
            d->dec_phase = DEC_IN; d->dec_bits = 0; d->dec_byte = 0; d->dec_ack = -1;   /* -1: address next */
        } else {                            /* STOP */
            dev_stop(s, d);
            hold_sda(s, d, 0);
            d->dec_phase = DEC_IDLE;
        }
        return;
    }
    if (scl && !pscl) {                     /* SCL rises: the bit is valid */
        if (d->dec_phase == DEC_IN && d->dec_bits < 8) { d->dec_byte = (uint8_t)(d->dec_byte << 1 | sda); d->dec_bits++; }
        else if (d->dec_phase == DEC_MACK) d->dec_ack = !sda;
        return;
    }
    if (!scl && pscl) {                     /* SCL falls: time to change SDA */
        switch (d->dec_phase) {
        case DEC_IN:
            if (d->dec_bits < 8) break;
            if (d->dec_ack == -1) {         /* the address byte */
                int ack = dev_start(s, d, d->dec_byte >> 1, d->dec_byte & 1);
                if (!ack) {
                    /* the 7-bit address sent as the byte, without the R/W bit below it */
                    if ((d->dec_byte & 0x7F) == d->addr && d->dec_byte >> 1 != d->addr)
                        soc_log(s, LOG_I2C_NACK, "Address byte 0x%02X: no part answers. The %s is at 0x%02X: if you meant it, the "
                                "address goes in bits 7:1 with R/W in bit 0, so it is sent as 0x%02X to write and 0x%02X to read",
                                d->dec_byte, dev_name(d->kind), d->addr, d->addr << 1, d->addr << 1 | 1);
                    d->dec_phase = DEC_SKIP; break;
                }
            } else dev_write(s, d, d->dec_byte);
            hold_sda(s, d, 1);              /* acknowledge */
            d->dec_ack = 0;
            d->dec_phase = DEC_ACK;
            break;
        case DEC_ACK:                       /* the acknowledge clock is over */
            hold_sda(s, d, 0);
            d->dec_bits = 0; d->dec_byte = 0;
            if (d->dec_read) {
                d->dec_byte = dev_read(s, d);
                hold_sda(s, d, !((d->dec_byte >> 7) & 1));
                d->dec_phase = DEC_OUT;
            } else d->dec_phase = DEC_IN;
            break;
        case DEC_OUT:                       /* the master took a bit: the next one */
            if (++d->dec_bits < 8) hold_sda(s, d, !((d->dec_byte >> (7 - d->dec_bits)) & 1));
            else { hold_sda(s, d, 0); d->dec_phase = DEC_MACK; }
            break;
        case DEC_MACK:
            if (d->dec_ack == 1) {
                d->dec_byte = dev_read(s, d);
                d->dec_bits = 0;
                hold_sda(s, d, !((d->dec_byte >> 7) & 1));
                d->dec_phase = DEC_OUT;
            } else d->dec_phase = DEC_SKIP;   /* not acknowledged: the master ends the read */
            break;
        default:
            break;
        }
    }
}

void bus_pin_changed(struct sl_soc *s, int slot)
{
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->pin[SL_SIG_SCL] < 0 || d->pin[SL_SIG_SDA] < 0) continue;
        if (d->pin[SL_SIG_SCL] == slot || d->pin[SL_SIG_SDA] == slot) decode(s, d);
    }
}

int bus_pin_held_low(struct sl_soc *s, int slot)
{
    for (int i = 0; i < B->ndev; i++)
        if (B->dev[i].sda_low && B->dev[i].pin[SL_SIG_SDA] == slot) return 1;
    return 0;
}

size_t bus_device_read(struct sl_soc *s, int part, uint8_t *px, size_t len, char *text, size_t tlen)
{
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part != part) continue;
        if (d->kind == SL_DEV_SSD1306) {
            static uint8_t frame[128 * 64];
            ssd1306_pixels(&d->m.oled, frame);
            size_t n = len < sizeof frame ? len : sizeof frame;
            if (px) memcpy(px, frame, n);
            if (text && tlen) {
                char pins[32] = "", a[6], b[6];
                if (d->pin[SL_SIG_SCL] >= 0 && d->pin[SL_SIG_SDA] >= 0) {
                    pin_label(d->pin[SL_SIG_SCL], a); pin_label(d->pin[SL_SIG_SDA], b);
                    snprintf(pins, sizeof pins, "SCL %s · SDA %s", a, b);
                }
                int k = snprintf(text, tlen, "Bus\tI2C 0x%02X · %s\n", d->addr, pins);
                if (k > 0 && (size_t)k < tlen) ssd1306_text(&d->m.oled, soc_now_ps(s), text + k, tlen - k);
            }
            return n;
        }
    }
    if (text && tlen) text[0] = 0;
    return 0;
}
