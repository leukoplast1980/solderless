/*
 * Solderless - MAX7219 LED display driver on an 8 x 8 matrix module, alone
 * or as a chain (the common "4 in 1" boards: four chips, DOUT of each to DIN
 * of the next). Maxim MAX7219 datasheet, rev. 4.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Modelled: the 16-bit shift register of each chip, loaded on the rising
 * edges of CLK and latched on the rising edge of LOAD (CS); a chain shifts
 * through all chips, so the first 16 bits sent end in the chip farthest
 * from DIN, and a chip that got no new bits latches what it still holds.
 * Registers: no-op, digits 0-7, decode mode with the Code B font, intensity
 * (1/32 to 31/32), scan limit, shutdown, display test. At power-up the chip
 * is in shutdown, scans only digit 0, and its digit registers hold noise.
 * The module: digit 0 is the top row, D7 the left column; in a chain the
 * chip at DIN is on the right.
 * Not yet: DOUT to a separate module, the segment current limit.
 */
#include "parts.h"

#include <stdio.h>
#include <string.h>

/* Code B: 0-9, -, E, H, L, P, blank; segments DP A B C D E F G as D7..D0 */
static const uint8_t CODE_B[16] = { 0x7E, 0x30, 0x6D, 0x79, 0x33, 0x5B, 0x5F, 0x70, 0x7F, 0x7B,
                                    0x01, 0x4F, 0x37, 0x0E, 0x67, 0x00 };

void max7219_power_on(struct max7219 *m, int chips, uint32_t seed)
{
    memset(m, 0, sizeof *m);
    m->n = chips < 1 ? 1 : chips > MAX7219_CHAIN ? MAX7219_CHAIN : chips;
    for (int k = 0; k < MAX7219_CHAIN; k++)
        for (int d = 0; d < 8; d++) { seed = seed * 1103515245u + 12345u; m->digit[k][d] = (uint8_t)(seed >> 16); }
}

void max7219_select(struct max7219 *m)
{
    m->bytes = 0;
}

void max7219_byte(struct max7219 *m, uint8_t b)
{
    /* The chain is one long shift register: everything moves one byte along */
    memmove(m->sh + 1, m->sh, (size_t)(2 * m->n - 1));
    m->sh[0] = b;
    m->bytes++;
}

static void reg_write(struct max7219 *m, int k, int reg, uint8_t v)
{
    switch (reg) {
    case 0x0: break;                                        /* no-op: lets a chain address one chip */
    case 0x9: m->decode[k] = v; break;
    case 0xA: m->intensity[k] = v & 0x0Fu; break;
    case 0xB: m->scan[k] = v & 0x07u; break;
    case 0xC: m->on[k] = v & 1u; break;
    case 0xF: m->test[k] = v & 1u; break;
    default:
        if (reg >= 1 && reg <= 8) m->digit[k][reg - 1] = v;
        break;
    }
}

/* LOAD rises: every chip latches the 16 bits it holds */
void max7219_deselect(struct max7219 *m)
{
    if (m->bytes == 0) return;
    if (m->bytes % 2) m->odd = 1;                           /* a half word: the chips latch a shifted mix */
    for (int k = 0; k < m->n; k++) {
        int reg = m->sh[2 * k + 1] & 0x0F;
        uint8_t v = m->sh[2 * k];
        reg_write(m, k, reg, v);
        if (reg != 0 && k == 0) { m->last_reg = reg; m->last_val = v; }
    }
    m->latches++;
}

int max7219_message(struct max7219 *m, char *out, size_t len)
{
    if (m->latches && !m->warned_5v) {
        m->warned_5v = 1;
        snprintf(out, len, "MAX7219: the module runs from 5 V and wants at least 3.5 V for a high on DIN, CLK and CS; "
                 "a 3.3 V pin is below that. It mostly works on the bench; a level shifter makes it sure");
        return 1;
    }
    if (m->odd) {
        m->odd = 0;
        snprintf(out, len, "MAX7219: CS went high after an odd number of bytes: each chip latches 16 bits, an address "
                 "byte and a data byte, so the words in the chain are now shifted by 8 bits");
        return 2;
    }
    return 0;
}

/* Brightness of each LED, 0-255; chip 0 (at DIN) on the right */
void max7219_pixels(const struct max7219 *m, uint8_t *px)
{
    int w = 8 * m->n;
    memset(px, 0, (size_t)(w * 8));
    for (int k = 0; k < m->n; k++) {
        int x0 = (m->n - 1 - k) * 8;
        int lit = m->test[k] ? 255 : m->on[k] ? 16 * (m->intensity[k] + 1) - 1 : 0;
        /* scanning fewer digits makes each one brighter */
        if (!m->test[k] && m->on[k] && m->scan[k] < 7) lit = lit * 8 / (m->scan[k] + 1) > 255 ? 255 : lit * 8 / (m->scan[k] + 1);
        if (!lit) continue;
        for (int d = 0; d <= (m->test[k] ? 7 : m->scan[k]); d++) {
            uint8_t v = m->digit[k][d];
            if (m->test[k]) v = 0xFF;
            else if ((m->decode[k] >> d) & 1) v = (uint8_t)((v & 0x80) | CODE_B[v & 0x0F]);
            for (int c = 0; c < 8; c++) if ((v >> (7 - c)) & 1) px[d * w + x0 + c] = (uint8_t)lit;
        }
    }
}

size_t max7219_text(const struct max7219 *m, char *out, size_t len)
{
    size_t n = 0;
#define ADD(...) do { if (n < len) { int k_ = snprintf(out + n, len - n, __VA_ARGS__); if (k_ > 0) n += (size_t)k_; } } while (0)
    ADD("Chain\t%d × MAX7219 · %d × 8 LEDs\n", m->n, 8 * m->n);
    if (m->test[0]) ADD("Mode\tdisplay test (F): every LED on\n");
    else if (!m->on[0]) ADD("Mode\tshutdown (C = 0): dark\n");
    else ADD("Mode\ton\n");
    ADD("Intensity\t%d · %d/32\n", m->intensity[0], 2 * m->intensity[0] + 1);
    if (m->scan[0] == 7) ADD("Scan limit\t7 · all 8 rows\n");
    else ADD("Scan limit\t%d · rows 0–%d only\n", m->scan[0], m->scan[0]);
    ADD("Decode\t%s\n", m->decode[0] ? "Code B, for 7-segment digits" : "none: each bit an LED");
    if (m->n > 1) {
        int off = 0, differ = 0;
        for (int k = 0; k < m->n; k++) {
            off += !m->on[k] && !m->test[k];
            differ |= m->on[k] != m->on[0] || m->test[k] != m->test[0] || m->intensity[k] != m->intensity[0]
                   || m->scan[k] != m->scan[0] || m->decode[k] != m->decode[0];
        }
        if (off && off < m->n) ADD("Chips\t%d of %d still in shutdown\n", off, m->n);
        else if (differ) ADD("Chips\tset differently\n");
        else ADD("Chips\tall set alike\n");
    }
    if (m->latches) ADD("Last\tregister %X = %02X on chip 0 · %u loads\n", m->last_reg, m->last_val, m->latches);
#undef ADD
    return n;
}
