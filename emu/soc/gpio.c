/*
 * Solderless - general-purpose I/O ports (RM0440 chapter 9).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Pin levels come from the breadboard (emu/board), which combines the
 * port's own drivers with whatever the board connects to each pin.
 */
#include "soc.h"

#define MODER   0x00u
#define OTYPER  0x04u
#define PUPDR   0x0Cu
#define IDR     0x10u
#define ODR     0x14u
#define BSRR    0x18u
#define LCKR    0x1Cu
#define AFRL    0x20u
#define AFRH    0x24u
#define BRR     0x28u

static uint32_t *port_regs(struct sl_soc *s, int port) { return s->regs[s->pi_gpio[port]]; }

#define lock_bits s->gpio_lock_bits
#define lock_step s->gpio_lock_step

void gpio_reset(struct sl_soc *s)
{
    for (int i = 0; i < SL_NUM_PORTS; i++) { lock_bits[i] = 0; lock_step[i] = 0; }
}

uint32_t gpio_idr(struct sl_soc *s, int port)
{
    uint32_t moder = port_regs(s, port)[MODER / 4], idr = 0;
    for (int p = 0; p < 16; p++)
        if (((moder >> (2 * p)) & 3u) != 3u && board_pin_level(s, port, p)) idr |= 1u << p;   /* analog: reads 0 */
    return idr;
}

uint32_t gpio_read(struct sl_soc *s, int port, uint32_t off)
{
    uint32_t *r = port_regs(s, port);
    switch (off) {
    case IDR: board_idr_read(s, port); return gpio_idr(s, port);
    case BSRR: case BRR: return 0;                                  /* write-only */
    case LCKR: return (lock_step[port] == 4 ? 1u << 16 : 0) | lock_bits[port];
    default: return r[off / 4];
    }
}

/* Bits of a 2-bit-per-pin or 4-bit-per-pin register that belong to locked pins */
static uint32_t locked_field_mask(uint32_t locked, int width, int first_pin)
{
    uint32_t m = 0;
    for (int i = 0; i < 32 / width; i++)
        if ((locked >> (first_pin + i)) & 1u) m |= ((1u << width) - 1u) << (i * width);
    return m;
}

void gpio_write(struct sl_soc *s, int port, uint32_t off, uint32_t v, uint32_t mask)
{
    uint32_t *r = port_regs(s, port);
    uint32_t locked = lock_step[port] == 4 ? lock_bits[port] : 0;
    uint32_t keep;
    switch (off) {
    case BSRR: {
        uint32_t w = v & mask, was = r[ODR / 4];
        r[ODR / 4] = (r[ODR / 4] & ~(w >> 16)) | (w & 0xFFFFu);    /* set wins over reset */
        if (r[ODR / 4] != was) board_port_changed(s, port, r[ODR / 4] ^ was);
        return;
    }
    case BRR: {
        uint32_t was = r[ODR / 4];
        r[ODR / 4] &= ~(v & mask & 0xFFFFu);
        if (r[ODR / 4] != was) board_port_changed(s, port, r[ODR / 4] ^ was);
        return;
    }
    case ODR: {
        uint32_t was = r[ODR / 4];
        r[ODR / 4] = (r[ODR / 4] & ~(mask & 0xFFFFu)) | (v & mask & 0xFFFFu);
        if (r[ODR / 4] != was) board_port_changed(s, port, r[ODR / 4] ^ was);
        return;
    }
    case IDR:
        return;
    case LCKR: {
        if (mask != 0xFFFFFFFFu || lock_step[port] == 4) return;
        uint32_t lckk = (v >> 16) & 1u, bits = v & 0xFFFFu;
        int step = lock_step[port];
        /* LCKK=1, LCKK=0, LCKK=1 with the same pin bits, then a read */
        if (step == 0 && lckk) { lock_step[port] = 1; lock_bits[port] = bits; }
        else if (step == 1 && !lckk && bits == lock_bits[port]) lock_step[port] = 2;
        else if (step == 2 && lckk && bits == lock_bits[port]) lock_step[port] = 4;
        else lock_step[port] = 0;
        return;
    }
    case MODER: case 0x08u: case PUPDR: keep = locked_field_mask(locked, 2, 0); break;
    case OTYPER: keep = locked; break;
    case AFRL: keep = locked_field_mask(locked, 4, 0); break;
    case AFRH: keep = locked_field_mask(locked, 4, 8); break;
    default: keep = 0; break;
    }
    uint32_t wm = s->wmask[s->pi_gpio[port]][off / 4] & mask & ~keep;
    uint32_t was = r[off / 4];
    r[off / 4] = (r[off / 4] & ~wm) | (v & wm);
    uint32_t diff = was ^ r[off / 4], pins = 0;
    if (!diff) return;
    /* Which pins' configuration changed: 1, 2 or 4 bits per pin */
    for (int p = 0; p < 16; p++) {
        uint32_t m = off == OTYPER ? 1u << p
                   : off == AFRL ? (p < 8 ? 15u << (4 * p) : 0)
                   : off == AFRH ? (p >= 8 ? 15u << (4 * (p - 8)) : 0)
                   : 3u << (2 * p);
        if (diff & m) pins |= 1u << p;
    }
    board_port_changed(s, port, pins);
}

int gpio_pin_af(struct sl_soc *s, int port, int pin)
{
    uint32_t *r = port_regs(s, port);
    if (((r[MODER / 4] >> (2 * pin)) & 3u) != 2u) return -1;
    uint32_t afr = pin < 8 ? r[AFRL / 4] : r[AFRH / 4];
    return (int)((afr >> (4 * (pin & 7))) & 15u);
}
