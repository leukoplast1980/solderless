/*
 * Solderless - DAC1, outputs 1 and 2 on PA4 and PA5 (RM0440 chapter 22).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Modelled: enable, data holding registers in all their alignments, the
 * output register loaded directly or by the software trigger, the output
 * buffer (strong) or no buffer (about 15 kohm), the ready flag. The pin
 * has to be in analog mode to carry the output, as on the chip. Not yet:
 * timer triggers, waves, DMA, sample and hold.
 */
#include "soc.h"

#define CR      0x00u
#define SWTRGR  0x04u
#define DHR12R1 0x08u
#define DHR12L1 0x0Cu
#define DHR8R1  0x10u
#define DHR12R2 0x14u
#define DHR12L2 0x18u
#define DHR8R2  0x1Cu
#define DHR12RD 0x20u
#define DHR12LD 0x24u
#define DHR8RD  0x28u
#define DOR1    0x2Cu
#define DOR2    0x30u
#define SR      0x34u
#define MCR     0x3Cu

static uint32_t *regs(struct sl_soc *s) { return s->regs[s->pi_dac1]; }

static int enabled(struct sl_soc *s, int ch) { return (regs(s)[CR / 4] >> (16 * ch)) & 1u; }
static int triggered(struct sl_soc *s, int ch) { return (regs(s)[CR / 4] >> (16 * ch + 1)) & 1u; }

static void load_dor(struct sl_soc *s, int ch)
{
    if (s->dac_dor[ch] == s->dac_dhr[ch]) return;
    s->dac_dor[ch] = s->dac_dhr[ch];
    board_port_changed(s, 0, 1u << (4 + ch));
}

static void set_dhr(struct sl_soc *s, int ch, uint32_t v12)
{
    s->dac_dhr[ch] = v12 & 0xFFFu;
    if (!triggered(s, ch)) load_dor(s, ch);
}

int dac_pin_out(struct sl_soc *s, int port, int pin, int *mv, int *g_us)
{
    if (s->pi_dac1 < 0 || port != 0 || (pin != 4 && pin != 5) || !s->clk_on[s->pi_dac1]) return 0;
    int ch = pin - 4;
    if (!enabled(s, ch)) return 0;
    uint32_t mode = (regs(s)[MCR / 4] >> (16 * ch)) & 7u;
    if (mode == 3u || mode == 7u) return 0;                   /* internal only */
    int buffered = mode == 0u || mode == 1u || mode == 4u || mode == 5u;
    *mv = (int)((s->dac_dor[ch] * 3300u + 2047u) / 4095u);
    *g_us = buffered ? 5000 : 66;
    return 1;
}

uint32_t dac_read(struct sl_soc *s, uint32_t off)
{
    uint32_t *r = regs(s);
    switch (off) {
    case DOR1: return s->dac_dor[0];
    case DOR2: return s->dac_dor[1];
    case SR: return (r[SR / 4] & ~((1u << 11) | (1u << 27))) | (enabled(s, 0) ? 1u << 11 : 0) | (enabled(s, 1) ? 1u << 27 : 0);
    case SWTRGR: return 0;
    default: return r[off / 4];
    }
}

void dac_write(struct sl_soc *s, uint32_t off, uint32_t v, uint32_t mask)
{
    uint32_t *r = regs(s);
    uint32_t wm = s->wmask[s->pi_dac1][off / 4] & mask;
    uint32_t w = (r[off / 4] & ~wm) | (v & wm);
    switch (off) {
    case DHR12R1: r[off / 4] = w; set_dhr(s, 0, w); break;
    case DHR12L1: r[off / 4] = w; set_dhr(s, 0, w >> 4); break;
    case DHR8R1:  r[off / 4] = w; set_dhr(s, 0, (w & 0xFFu) << 4); break;
    case DHR12R2: r[off / 4] = w; set_dhr(s, 1, w); break;
    case DHR12L2: r[off / 4] = w; set_dhr(s, 1, w >> 4); break;
    case DHR8R2:  r[off / 4] = w; set_dhr(s, 1, (w & 0xFFu) << 4); break;
    case DHR12RD: r[off / 4] = w; set_dhr(s, 0, w); set_dhr(s, 1, w >> 16); break;
    case DHR12LD: r[off / 4] = w; set_dhr(s, 0, w >> 4); set_dhr(s, 1, w >> 20); break;
    case DHR8RD:  r[off / 4] = w; set_dhr(s, 0, (w & 0xFFu) << 4); set_dhr(s, 1, ((w >> 8) & 0xFFu) << 4); break;
    case SWTRGR:
        for (int ch = 0; ch < 2; ch++)
            if (((v & mask) >> ch) & 1u && enabled(s, ch) && triggered(s, ch)) load_dor(s, ch);
        break;
    case CR: {
        uint32_t was = r[CR / 4];
        r[CR / 4] = w;
        if ((was ^ w) & 0x00010001u) board_port_changed(s, 0, 3u << 4);
        break;
    }
    default:
        r[off / 4] = w;
        if (off == MCR) board_port_changed(s, 0, 3u << 4);
        break;
    }
}

void dac_reset(struct sl_soc *s)
{
    s->dac_dhr[0] = s->dac_dhr[1] = 0;
    s->dac_dor[0] = s->dac_dor[1] = 0;
}
