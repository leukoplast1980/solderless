/*
 * Solderless - models of bus parts: what each controller does with the
 * bytes it receives, as its datasheet says.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Models see bus events only (I2C start with the address, bytes with
 * their acknowledge, stop), whether they come from an on-chip I2C
 * peripheral or from the pin-level decoder watching bit-banged lines.
 */
#ifndef SL_PARTS_H
#define SL_PARTS_H

#include <stddef.h>
#include <stdint.h>

struct sl_soc;

/* SSD1306: 128 x 64 OLED controller (Solomon Systech, rev 1.1) */
struct ssd1306 {
    uint8_t ram[8][128];        /* GDDRAM: 8 pages of 128 columns, bit 0 the top row of a page */
    int on, contrast, entire_on, inverse;
    int mux, offset, start_line, seg_remap, com_rev, com_alt, com_lr;
    int charge_pump;            /* 8D: 0x14 on, 0x10 off */
    int clk_div, precharge, vcomh;
    int mode;                   /* 20: 0 horizontal, 1 vertical, 2 page */
    int col_start, col_end, page_start, page_end;
    int col, page;              /* where the next data byte goes */
    int scroll;
    /* I2C: each run starts with a control byte, Co and D/C# */
    int expect_ctrl, co, dc;
    uint8_t cmd[8];
    int cmd_n, cmd_need;
    /* What the user sees about the last access */
    uint64_t last_ps;
    int last_page, last_bytes, run_bytes;
    uint8_t last_cmd;
    int unknown;                /* commands it does not know */
};

void ssd1306_power_on(struct ssd1306 *d, uint32_t seed);
void ssd1306_start(struct ssd1306 *d);
void ssd1306_byte(struct ssd1306 *d, uint8_t b, uint64_t now_ps);
void ssd1306_stop(struct ssd1306 *d);
/* Pixels as lit now (128 x 64, row by row, 0/1); the panel is dark when off */
void ssd1306_pixels(const struct ssd1306 *d, uint8_t *px);
size_t ssd1306_text(const struct ssd1306 *d, uint64_t now_ps, char *out, size_t len);

#endif
