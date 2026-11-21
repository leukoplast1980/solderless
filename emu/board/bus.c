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
    case SL_DEV_W25Q: return "W25Q flash";
    case SL_DEV_MAX7219: return "MAX7219";
    case SL_DEV_ST7789: return "ST7789 display";
    case SL_DEV_BMP280: return "BMP280";
    case SL_DEV_SCD41: return "SCD41";
    default: return "part";
    }
}

/* Fastest SCL the part's datasheet allows */
static uint32_t dev_max_hz(int kind)
{
    switch (kind) {
    case SL_DEV_SSD1306: return 400000u;    /* fast mode; the datasheet's tcycle is 2.5 us */
    case SL_DEV_W25Q: return 50000000u;     /* read (03) is the slowest command: 50 MHz */
    case SL_DEV_MAX7219: return 10000000u;
    case SL_DEV_ST7789: return 62500000u;   /* write cycle 16 ns */
    case SL_DEV_BMP280: return 3400000u;    /* high-speed mode */
    case SL_DEV_SCD41: return 400000u;
    default: return 100000u;
    }
}

static void pin_label(int slot, char *b)
{
    snprintf(b, 6, "P%c%d", 'A' + (slot >> 4), slot & 15);
}

static int is_spi(int kind) { return kind == SL_DEV_W25Q || kind == SL_DEV_MAX7219 || kind == SL_DEV_ST7789; }

static void dev_power_on(struct sl_dev *d)
{
    uint32_t seed = 0x9E3779B9u * (uint32_t)(d->part + 1);
    if (d->kind == SL_DEV_SSD1306) ssd1306_power_on(&d->m.oled, seed);
    if (d->kind == SL_DEV_W25Q) w25q_init(&d->m.flash, d->addr, seed);
    if (d->kind == SL_DEV_MAX7219) max7219_power_on(&d->m.matrix, d->addr, seed);
    if (d->kind == SL_DEV_ST7789) st7789_init(&d->m.tft, d->addr, seed);
    if (d->kind == SL_DEV_BMP280) bmp280_power_on(&d->m.baro);
    if (d->kind == SL_DEV_SCD41) scd41_power_on(&d->m.co2, seed);
}

static void dev_free(struct sl_dev *d)
{
    if (d->kind == SL_DEV_W25Q) w25q_free(&d->m.flash);
    if (d->kind == SL_DEV_ST7789) st7789_free(&d->m.tft);
}

static int line(struct sl_soc *s, int slot);

void bus_set(struct sl_soc *s)
{
    /* Parts that stay keep their state: a display keeps its picture, a
     * flash its contents, when another part is added */
    static struct sl_dev old[SL_MAX_DEV];
    int nold = B->ndev;
    memcpy(old, B->dev, sizeof old);
    int kept[SL_MAX_DEV] = { 0 };
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
            int found = 0;
            for (int k = 0; k < nold; k++)
                if (!kept[k] && old[k].part == a->part && old[k].kind == a->dev && (!is_spi(a->dev) || old[k].addr == a->addr)) {
                    d->m = old[k].m; kept[k] = 1; found = 1;
                }
            if (!found) dev_power_on(d);
        }
        if (a->role >= 0 && a->role < SL_SIG_COUNT) d->pin[a->role] = a->pin;
    }
    for (int k = 0; k < nold; k++) if (!kept[k]) dev_free(&old[k]);
    /* Chip selects as they are now; a part without one is always selected */
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (!is_spi(d->kind)) continue;
        d->cs_low = d->pin[SL_SIG_CS] < 0 || !line(s, d->pin[SL_SIG_CS]);
        d->dec_sck = d->pin[SL_SIG_SCK] >= 0 && line(s, d->pin[SL_SIG_SCK]);
        d->spi_bits = 0; d->out_drive = 0; d->have_next = 0; d->spi_started = 0;
    }
}

void bus_free(struct sl_soc *s)
{
    for (int i = 0; i < B->ndev; i++) dev_free(&B->dev[i]);
    B->ndev = 0;
}

/* ---------------------------------------------------------------------- */
/* Byte level, shared by the on-chip peripherals and the decoder           */
/* ---------------------------------------------------------------------- */

static int on_bus(const struct sl_dev *d, int scl, int sda)
{
    return d->pin[SL_SIG_SCL] == scl && d->pin[SL_SIG_SDA] == sda;
}

static void part_messages(struct sl_soc *s, struct sl_dev *d);

/* Address phase for one part: does it answer? */
static int dev_start(struct sl_soc *s, struct sl_dev *d, int addr, int read)
{
    d->selected = 0;
    if (d->addr != addr) return 0;
    d->dec_read = read;
    uint64_t now = soc_now_ps(s);
    if (d->kind == SL_DEV_SSD1306) ssd1306_start(&d->m.oled);
    if (d->kind == SL_DEV_BMP280) bmp280_start(&d->m.baro, read, now);
    if (d->kind == SL_DEV_SCD41) {
        int ack = scd41_start(&d->m.co2, read, now);
        part_messages(s, d);
        if (!ack) return 0;
    }
    d->selected = 1;
    return 1;
}

static int dev_write(struct sl_soc *s, struct sl_dev *d, uint8_t b)
{
    uint64_t now = soc_now_ps(s);
    if (d->kind == SL_DEV_SSD1306) ssd1306_byte(&d->m.oled, b, now);
    if (d->kind == SL_DEV_BMP280) bmp280_byte(&d->m.baro, b, now);
    if (d->kind == SL_DEV_SCD41) {
        int ack = scd41_byte(&d->m.co2, b, now);
        part_messages(s, d);
        return ack;
    }
    return 1;
}

static uint8_t dev_read(struct sl_soc *s, struct sl_dev *d)
{
    /* SSD1306 over I2C: only the status byte; D6 is set while the display is off */
    if (d->kind == SL_DEV_SSD1306) return d->m.oled.on ? 0x00 : 0x40;
    if (d->kind == SL_DEV_BMP280) { uint8_t v = bmp280_read(&d->m.baro, soc_now_ps(s)); part_messages(s, d); return v; }
    if (d->kind == SL_DEV_SCD41) return scd41_read(&d->m.co2);
    return 0xFF;
}

static void dev_stop(struct sl_soc *s, struct sl_dev *d)
{
    if (d->selected && d->kind == SL_DEV_SSD1306) ssd1306_stop(&d->m.oled);
    if (d->selected && d->kind == SL_DEV_SCD41) { scd41_stop(&d->m.co2, soc_now_ps(s)); part_messages(s, d); }
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
/* SPI: chip select, bits, bytes                                          */
/* ---------------------------------------------------------------------- */

static void part_messages(struct sl_soc *s, struct sl_dev *d)
{
    char m[240];
    if (d->kind == SL_DEV_W25Q)
        while (d->m.flash.msg) {
            int code = d->m.flash.msg;
            if (w25q_message(&d->m.flash, m, sizeof m)) soc_log(s, LOG_PART + d->kind * 8 + code, "%s", m);
        }
    if (d->kind == SL_DEV_MAX7219) {
        int code;
        while ((code = max7219_message(&d->m.matrix, m, sizeof m))) soc_log(s, LOG_PART + d->kind * 8 + code, "%s", m);
    }
    if (d->kind == SL_DEV_ST7789) {
        int code;
        while ((code = st7789_message(&d->m.tft, m, sizeof m))) soc_log(s, LOG_PART + d->kind * 8 + code, "%s", m);
    }
    if (d->kind == SL_DEV_BMP280) {
        int code;
        while ((code = bmp280_message(&d->m.baro, m, sizeof m))) soc_log(s, LOG_PART + d->kind * 8 + code, "%s", m);
    }
    if (d->kind == SL_DEV_SCD41) {
        int code;
        while ((code = scd41_message(&d->m.co2, m, sizeof m))) soc_log(s, LOG_PART + d->kind * 8 + code, "%s", m);
    }
}

static void spi_select(struct sl_soc *s, struct sl_dev *d, int low)
{
    if (d->cs_low == low) return;
    d->cs_low = low;
    uint64_t now = soc_now_ps(s);
    d->spi_bits = 0; d->spi_started = 0; d->have_next = 0; d->out_pos = 0;
    d->spi_out = 0xFF;
    int was_driving = d->out_drive;
    d->out_drive = 0;
    if (d->kind == SL_DEV_W25Q) {
        if (low) w25q_select(&d->m.flash, now);
        else w25q_deselect(&d->m.flash, now);
    }
    if (d->kind == SL_DEV_MAX7219) {
        if (low) max7219_select(&d->m.matrix);
        else max7219_deselect(&d->m.matrix);
    }
    if (d->kind == SL_DEV_ST7789 && !low) st7789_check(&d->m.tft);
    part_messages(s, d);
    if (was_driving && d->pin[SL_SIG_MISO] >= 0)
        board_port_changed(s, d->pin[SL_SIG_MISO] >> 4, 1u << (d->pin[SL_SIG_MISO] & 15));
}

static uint8_t spi_model_byte(struct sl_soc *s, struct sl_dev *d, uint8_t b, int *drive)
{
    uint64_t now = soc_now_ps(s);
    uint8_t out = 0xFF;
    *drive = 0;
    if (d->kind == SL_DEV_W25Q) out = w25q_byte(&d->m.flash, b, now, drive);
    if (d->kind == SL_DEV_MAX7219) max7219_byte(&d->m.matrix, b);
    if (d->kind == SL_DEV_ST7789)   /* DC is read with the byte's last bit */
        st7789_byte(&d->m.tft, b, d->pin[SL_SIG_DC] < 0 || line(s, d->pin[SL_SIG_DC]), now);
    part_messages(s, d);
    return out;
}

/* SCK edge the part samples on (all parts here: rising, modes 0 and 3) */
static void spi_rise(struct sl_soc *s, struct sl_dev *d, int mosi)
{
    d->spi_started = 1;
    d->spi_in = (uint8_t)(d->spi_in << 1 | (mosi & 1));
    if (++d->spi_bits < 8) return;
    d->spi_bits = 0;
    d->next_out = spi_model_byte(s, d, d->spi_in, &d->next_drive);
    d->have_next = 1;
}

/* The other edge: DO moves on to the next bit */
static void spi_fall(struct sl_dev *d)
{
    if (!d->spi_started) return;            /* mode 3: the first falling edge comes before any bit */
    if (d->have_next) {
        d->spi_out = d->next_out; d->out_drive = d->next_drive; d->out_pos = 0; d->have_next = 0;
    } else if (d->out_pos < 7) d->out_pos++;
    d->miso_level = (d->spi_out >> (7 - d->out_pos)) & 1;
}

static int spi_on(const struct sl_dev *d, int sck, int mosi)
{
    return is_spi(d->kind) && d->pin[SL_SIG_SCK] == sck && d->pin[SL_SIG_MOSI] == mosi;
}

uint32_t bus_spi_frame(struct sl_soc *s, int sck, int mosi, int miso, uint32_t v, int bits, int mode, int lsb,
                       uint32_t hz, const char *who)
{
    struct sl_dev *sel[SL_MAX_DEV];
    int nsel = 0;
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (!spi_on(d, sck, mosi) || !d->cs_low) continue;
        sel[nsel++] = d;
        uint32_t max = dev_max_hz(d->kind);
        if (hz > max + max / 8)
            soc_log(s, LOG_SPI_SPEED, "%s clocks SCK at %.3g MHz; the %s handles up to %u MHz, so it misreads bits. "
                    "Raise BR in CR1 (SCK is PCLK / 2, 4, 8 ... 256)", who, hz / 1e6, dev_name(d->kind), max / 1000000u);
        if (mode == 1 || mode == 2)
            soc_log(s, LOG_SPI_MODE, "%s runs SPI mode %d; the %s takes mode 0 or 3 (it reads MOSI on the rising edge of SCK), "
                    "so every bit lands one place off. Set CPOL = CPHA in CR1", who, mode, dev_name(d->kind));
        if (lsb)
            soc_log(s, LOG_SPI_ORDER, "%s sends LSB first (LSBFIRST in CR1); the %s expects the most significant bit first, "
                    "so it reads every byte mirrored", who, dev_name(d->kind));
    }
    int miso_was[SL_MAX_DEV];
    for (int k = 0; k < nsel; k++) miso_was[k] = sel[k]->out_drive;

    int skew = mode == 1 || mode == 2;
    uint32_t rx = 0;
    int clash = 0;
    for (int b = 0; b < bits; b++) {
        int pos = lsb ? b : bits - 1 - b;
        int bit = (int)((v >> pos) & 1u);
        if (skew) {                         /* the part samples while the master changes MOSI: the next bit */
            int npos = lsb ? b + 1 : bits - 2 - b;
            bit = npos >= 0 && npos < bits ? (int)((v >> npos) & 1u) : bit;
        }
        int in = 1, drivers = 0, lv[2] = { 0, 0 };
        if (!skew)
            for (int k = 0; k < nsel; k++) if (sel[k]->out_drive) { in &= sel[k]->miso_level; drivers++; lv[sel[k]->miso_level] = 1; }
        for (int k = 0; k < nsel; k++) {
            struct sl_dev *d = sel[k];
            int x = bit;
            if (hz > dev_max_hz(d->kind) + dev_max_hz(d->kind) / 8) {
                B->rng = B->rng * 6364136223846793005ull + 1442695040888963407ull;
                if ((B->rng >> 61) == 0) x ^= 1;   /* one bit in eight misread */
            }
            spi_rise(s, d, x);
            spi_fall(d);
        }
        if (skew)
            for (int k = 0; k < nsel; k++) if (sel[k]->out_drive) { in &= sel[k]->miso_level; drivers++; lv[sel[k]->miso_level] = 1; }
        if (drivers > 1 && lv[0] && lv[1]) clash = 1;
        int r = drivers ? in : miso >= 0 ? board_pin_level(s, miso >> 4, miso & 15) : 0;   /* floating: at random */
        rx |= (uint32_t)r << pos;
    }
    if (clash)
        soc_log(s, LOG_SPI_MISO, "%s: two parts drive MISO at once with different bits: their chip selects are both low", who);
    for (int k = 0; k < nsel; k++)
        if (sel[k]->out_drive != miso_was[k] && miso >= 0) board_port_changed(s, miso >> 4, 1u << (miso & 15));
    return rx;
}

/* Pin level: bit-banged SPI. The part samples MOSI on SCK rising and moves
 * DO on SCK falling, while its CS is low. */
static void spi_decode(struct sl_soc *s, struct sl_dev *d, int slot)
{
    if (slot == d->pin[SL_SIG_CS]) {
        spi_select(s, d, !line(s, slot));
        return;
    }
    if (slot != d->pin[SL_SIG_SCK]) return;
    int sck = line(s, slot), was = d->dec_sck;
    d->dec_sck = sck;
    if (!d->cs_low || sck == was) return;
    int drove = d->out_drive, lvl = d->miso_level;
    if (sck) spi_rise(s, d, d->pin[SL_SIG_MOSI] >= 0 ? line(s, d->pin[SL_SIG_MOSI]) : 0);
    else spi_fall(d);
    int m = d->pin[SL_SIG_MISO];
    if (m >= 0 && (drove != d->out_drive || (d->out_drive && lvl != d->miso_level)))
        board_port_changed(s, m >> 4, 1u << (m & 15));
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
            } else if (!dev_write(s, d, d->dec_byte)) { d->dec_phase = DEC_SKIP; break; }   /* not acknowledged */
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
        if (is_spi(d->kind)) {
            if (d->pin[SL_SIG_CS] == slot || d->pin[SL_SIG_SCK] == slot) spi_decode(s, d, slot);
            if (d->kind == SL_DEV_ST7789 && d->pin[SL_SIG_RES] == slot) st7789_reset_pin(&d->m.tft, !line(s, slot), soc_now_ps(s));
            continue;
        }
        if (d->pin[SL_SIG_SCL] < 0 || d->pin[SL_SIG_SDA] < 0) continue;
        if (d->pin[SL_SIG_SCL] == slot || d->pin[SL_SIG_SDA] == slot) decode(s, d);
    }
}

int bus_pin_drive(struct sl_soc *s, int slot, int *level)
{
    for (int i = 0; i < B->ndev; i++) {
        const struct sl_dev *d = &B->dev[i];
        if (d->sda_low && d->pin[SL_SIG_SDA] == slot) { *level = 0; return 1; }
        if (d->out_drive && d->cs_low && d->pin[SL_SIG_MISO] == slot) { *level = d->miso_level; return 1; }
    }
    return 0;
}

void bus_device_input(struct sl_soc *s, int part, const char *what, double v)
{
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part != part) continue;
        if (d->kind == SL_DEV_BMP280) bmp280_input(&d->m.baro, what, v, soc_now_ps(s));
        if (d->kind == SL_DEV_SCD41) scd41_input(&d->m.co2, what, v, soc_now_ps(s));
    }
}

int bus_pin_is_miso(struct sl_soc *s, int slot)
{
    for (int i = 0; i < B->ndev; i++) if (is_spi(B->dev[i].kind) && B->dev[i].pin[SL_SIG_MISO] == slot) return 1;
    return 0;
}

size_t bus_device_mem(struct sl_soc *s, int part, uint32_t addr, uint8_t *buf, size_t len, int write)
{
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part == part && d->kind == SL_DEV_W25Q) return w25q_mem(&d->m.flash, addr, buf, len, write);
    }
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
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part != part || (d->kind != SL_DEV_BMP280 && d->kind != SL_DEV_SCD41)) continue;
        if (text && tlen) {
            char sc[6] = "", sd[6] = "";
            if (d->pin[SL_SIG_SCL] >= 0) pin_label(d->pin[SL_SIG_SCL], sc);
            if (d->pin[SL_SIG_SDA] >= 0) pin_label(d->pin[SL_SIG_SDA], sd);
            int k = snprintf(text, tlen, "Bus\tI2C 0x%02X · SCL %s · SDA %s\n", d->addr, sc, sd);
            if (k > 0 && (size_t)k < tlen) {
                if (d->kind == SL_DEV_BMP280) bmp280_text(&d->m.baro, soc_now_ps(s), text + k, tlen - k);
                else scd41_text(&d->m.co2, soc_now_ps(s), text + k, tlen - k);
            }
        }
        return 0;
    }
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part != part || d->kind != SL_DEV_ST7789) continue;
        static uint16_t frame[ST7789_W * ST7789_H];
        st7789_pixels(&d->m.tft, frame);
        size_t nb = (size_t)(ST7789_W * d->m.tft.panel_h) * 2, k0 = len < nb ? len : nb;
        if (px) for (size_t k = 0; k < k0; k++) px[k] = (uint8_t)(frame[k / 2] >> (8 * (k & 1)));
        if (text && tlen) {
            char p[5][6] = { "", "", "", "", "" };
            static const int sig[5] = { SL_SIG_CS, SL_SIG_SCK, SL_SIG_MOSI, SL_SIG_DC, SL_SIG_RES };
            for (int k = 0; k < 5; k++) if (d->pin[sig[k]] >= 0) pin_label(d->pin[sig[k]], p[k]);
            int k = snprintf(text, tlen, "Bus\tCS %s · SCL %s · SDA %s · DC %s · RES %s\n", p[0], p[1], p[2], p[3], p[4]);
            if (k > 0 && (size_t)k < tlen) st7789_text(&d->m.tft, soc_now_ps(s), text + k, tlen - k);
        }
        return k0;
    }
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part != part || d->kind != SL_DEV_MAX7219) continue;
        static uint8_t frame[8 * MAX7219_CHAIN * 8];
        max7219_pixels(&d->m.matrix, frame);
        size_t np = (size_t)(64 * d->m.matrix.n), k0 = len < np ? len : np;
        if (px) memcpy(px, frame, k0);
        if (text && tlen) {
            char p[3][6] = { "", "", "" };
            static const int sig[3] = { SL_SIG_CS, SL_SIG_SCK, SL_SIG_MOSI };
            for (int k = 0; k < 3; k++) if (d->pin[sig[k]] >= 0) pin_label(d->pin[sig[k]], p[k]);
            int k = snprintf(text, tlen, "Bus\tCS %s · CLK %s · DIN %s\n", p[0], p[1], p[2]);
            if (k > 0 && (size_t)k < tlen) max7219_text(&d->m.matrix, text + k, tlen - k);
        }
        return k0;
    }
    for (int i = 0; i < B->ndev; i++) {
        struct sl_dev *d = &B->dev[i];
        if (d->part != part || d->kind != SL_DEV_W25Q) continue;
        if (text && tlen) {
            char p[4][6] = { "", "", "", "" };
            static const int sig[4] = { SL_SIG_CS, SL_SIG_SCK, SL_SIG_MOSI, SL_SIG_MISO };
            for (int k = 0; k < 4; k++) if (d->pin[sig[k]] >= 0) pin_label(d->pin[sig[k]], p[k]);
            int k = snprintf(text, tlen, "Bus\tSPI · CS %s · CLK %s · DI %s · DO %s\n", p[0], p[1], p[2], p[3]);
            if (k > 0 && (size_t)k < tlen) w25q_text(&d->m.flash, soc_now_ps(s), text + k, tlen - k);
        }
        return 0;
    }
    if (text && tlen) text[0] = 0;
    return 0;
}
