/*
 * Solderless - SSD1306, the 128 x 64 OLED controller, over I2C.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * From the SSD1306 datasheet, rev 1.1: the command table (section 9), the
 * three addressing modes (10.1.3), the I2C control byte (8.1.5.2) and the
 * charge pump application note. As on the chip: the RAM holds noise at
 * power-on, nothing lights until the charge pump is enabled (8D 14) and the
 * display switched on (AF), and the module is wired for the alternative COM
 * pin configuration, so sequential (DA 02) shows every other row in the
 * wrong place. Not modelled: scrolling (accepted, logged in the text),
 * reading the RAM (not possible over I2C anyway), fade and zoom.
 */
#include "parts.h"

#include <stdio.h>
#include <string.h>

void ssd1306_power_on(struct ssd1306 *d, uint32_t seed)
{
    memset(d, 0, sizeof *d);
    /* GDDRAM is not cleared at power-on: noise until the driver clears it */
    uint32_t x = seed | 1u;
    for (int p = 0; p < 8; p++)
        for (int c = 0; c < 128; c++) {
            x ^= x << 13; x ^= x >> 17; x ^= x << 5;
            d->ram[p][c] = (uint8_t)x;
        }
    d->contrast = 0x7F;
    d->mux = 63;
    d->com_alt = 1;                 /* DA reset value 0x12 */
    d->charge_pump = 0x10;
    d->clk_div = 0x80; d->precharge = 0x22; d->vcomh = 0x20;
    d->mode = 2;
    d->col_end = 127; d->page_end = 7;
    d->last_page = -1;
}

void ssd1306_start(struct ssd1306 *d)
{
    d->expect_ctrl = 1;
    d->run_bytes = 0;
}

void ssd1306_stop(struct ssd1306 *d)
{
    /* A command and its arguments may come in separate transfers, one byte
     * each, as many drivers send them: the parser keeps its place */
    d->expect_ctrl = 1;
    if (d->run_bytes) d->last_bytes = d->run_bytes;
}

static int arg_count(uint8_t c)
{
    switch (c) {
    case 0x81: case 0x20: case 0xA8: case 0xD3: case 0xD5: case 0xD9: case 0xDA: case 0xDB: case 0x8D:
        return 1;
    case 0x21: case 0x22: case 0xA3:
        return 2;
    case 0x29: case 0x2A:
        return 5;
    case 0x26: case 0x27:
        return 6;
    default:
        return 0;
    }
}

static void command(struct ssd1306 *d, const uint8_t *c)
{
    uint8_t op = c[0];
    d->last_cmd = op;
    if (op <= 0x0F) { d->col = (d->col & 0xF0) | op; return; }          /* page mode: lower column */
    if (op >= 0x10 && op <= 0x1F) { d->col = (d->col & 0x0F) | ((op & 7) << 4); return; }
    if (op >= 0x40 && op <= 0x7F) { d->start_line = op & 63; return; }
    if (op >= 0xB0 && op <= 0xB7) { d->page = op & 7; return; }
    switch (op) {
    case 0x81: d->contrast = c[1]; break;
    case 0x20: d->mode = c[1] & 3; if (d->mode == 3) d->mode = 2; break;
    case 0x21: d->col_start = c[1] & 127; d->col_end = c[2] & 127; d->col = d->col_start; break;
    case 0x22: d->page_start = c[1] & 7; d->page_end = c[2] & 7; d->page = d->page_start; break;
    case 0xA0: case 0xA1: d->seg_remap = op & 1; break;
    case 0xA4: case 0xA5: d->entire_on = op & 1; break;
    case 0xA6: case 0xA7: d->inverse = op & 1; break;
    case 0xA8: if ((c[1] & 63) >= 15) d->mux = c[1] & 63; break;
    case 0xAE: case 0xAF: d->on = op & 1; break;
    case 0xD3: d->offset = c[1] & 63; break;
    case 0xD5: d->clk_div = c[1]; break;
    case 0xD9: d->precharge = c[1]; break;
    case 0xDA: d->com_alt = (c[1] >> 4) & 1; d->com_lr = (c[1] >> 5) & 1; break;
    case 0xDB: d->vcomh = c[1]; break;
    case 0x8D: d->charge_pump = c[1]; break;
    case 0x2E: d->scroll = 0; break;
    case 0x2F: d->scroll = 1; break;
    case 0x26: case 0x27: case 0x29: case 0x2A: case 0xA3: case 0xE3: break;
    default:
        if (op >= 0xC0 && op <= 0xCF) { d->com_rev = (op >> 3) & 1; break; }
        d->unknown++;
        break;
    }
}

static void data(struct ssd1306 *d, uint8_t b, uint64_t now_ps)
{
    d->ram[d->page & 7][d->col & 127] = b;
    d->last_ps = now_ps;
    d->last_page = d->page;
    d->run_bytes++;
    switch (d->mode) {
    case 0:                         /* horizontal: along the page, then the next page */
        if (++d->col > d->col_end) {
            d->col = d->col_start;
            if (++d->page > d->page_end) d->page = d->page_start;
        }
        break;
    case 1:                         /* vertical: down the column, then the next column */
        if (++d->page > d->page_end) {
            d->page = d->page_start;
            if (++d->col > d->col_end) d->col = d->col_start;
        }
        break;
    default:                        /* page: along the page only */
        if (++d->col > 127) d->col = 0;
        break;
    }
}

void ssd1306_byte(struct ssd1306 *d, uint8_t b, uint64_t now_ps)
{
    if (d->expect_ctrl) {
        d->co = (b >> 7) & 1;
        d->dc = (b >> 6) & 1;
        d->expect_ctrl = 0;
        return;
    }
    if (d->dc) data(d, b, now_ps);
    else {
        if (d->cmd_n == 0) d->cmd_need = 1 + arg_count(b);
        d->cmd[d->cmd_n++] = b;
        if (d->cmd_n >= d->cmd_need) { command(d, d->cmd); d->cmd_n = 0; }
    }
    if (d->co) d->expect_ctrl = 1;  /* one byte, then the next control byte */
}

static int lit(const struct ssd1306 *d)
{
    return d->on && (d->charge_pump & 0x04);
}

void ssd1306_pixels(const struct ssd1306 *d, uint8_t *px)
{
    memset(px, 0, 128 * 64);
    if (!lit(d)) return;
    for (int y = 0; y < 64; y++) {
        /* The module's panel is wired for the alternative COM configuration */
        int com = d->com_alt ? y : ((y & 1) ? 32 + y / 2 : y / 2);
        if (com > d->mux) continue;
        int line = d->com_rev ? com : d->mux - com;
        int row = (line + d->start_line + d->offset) & 63;
        for (int x = 0; x < 128; x++) {
            int col = d->seg_remap ? x : 127 - x;
            int on = d->entire_on ? 1 : (d->ram[row >> 3][col] >> (row & 7)) & 1;
            px[y * 128 + x] = (uint8_t)(on ^ d->inverse);
        }
    }
}

size_t ssd1306_text(const struct ssd1306 *d, uint64_t now_ps, char *out, size_t len)
{
    static const char *const modes[3] = { "horizontal", "vertical", "page" };
    char last[96];
    if (d->last_page < 0) snprintf(last, sizeof last, "nothing written yet");
    else {
        uint64_t ago = (now_ps - d->last_ps) / 100000000u;      /* tenths of a ms */
        snprintf(last, sizeof last, "page %d · %d bytes · %llu.%llu ms ago", d->last_page, d->last_bytes ? d->last_bytes : d->run_bytes,
                 (unsigned long long)(ago / 10), (unsigned long long)(ago % 10));
    }
    int n = snprintf(out, len,
        "Display\t%s\n"
        "Charge pump\t%s\n"
        "Addressing\t%s · columns %d–%d · pages %d–%d\n"
        "Orientation\tsegment remap %s · COM scan %s · COM pins %s\n"
        "Lines\tmultiplex %d · start %d · offset %d%s%s\n"
        "Last write\t%s\n",
        d->on ? (lit(d) ? "on" : "on, but dark: the charge pump is off") : "off (AE)",
        (d->charge_pump & 0x04) ? "on (8D 14)" : "off: send 8D 14 before AF",
        modes[d->mode], d->col_start, d->col_end, d->page_start, d->page_end,
        d->seg_remap ? "A1" : "A0", d->com_rev ? "C8" : "C0", d->com_alt ? "alternative (DA 12)" : "sequential (DA 02): rows interleaved",
        d->mux + 1, d->start_line, d->offset, d->inverse ? " · inverted" : "", d->entire_on ? " · all on (A5)" : "",
        last);
    if (n > 0 && (size_t)n < len) {
        int m = snprintf(out + n, len - n, "Contrast\t0x%02X%s\n", d->contrast, d->scroll ? "\nScrolling\ton (not shown)" : "");
        if (m > 0) n += m;
    }
    return n < 0 ? 0 : (size_t)n < len ? (size_t)n : len - 1;
}
