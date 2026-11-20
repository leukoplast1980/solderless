/*
 * Solderless - models of bus parts: what each controller does with the
 * bytes it receives, as its datasheet says.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Models see bus events only (I2C start with the address, bytes with
 * their acknowledge, stop; SPI chip select and bytes), whether they come
 * from an on-chip peripheral or from the pin-level decoder watching
 * bit-banged lines.
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

/* W25Q32/64/128 serial flash (Winbond, JV family) */
enum { W25Q_MSG_BUSY = 1, W25Q_MSG_NO_WEL, W25Q_MSG_PROTECTED, W25Q_MSG_NOT_ERASED, W25Q_MSG_ASLEEP, W25Q_MSG_UNKNOWN };
struct w25q {
    uint8_t *mem;               /* the array; heap, size bytes */
    uint8_t *used;              /* per 4 KB sector: may hold bytes that are not FF */
    uint32_t size;
    int cap;                    /* JEDEC capacity byte: 0x16, 0x17, 0x18 */
    uint8_t sr1, sr2, sr3;
    uint8_t uid[8];
    int volatile_sr, powered_down, reset_enabled;
    uint64_t busy_until, last_ps;
    const char *busy_what;
    /* The command coming in */
    uint8_t cmd;
    int n, skip;
    uint32_t addr;
    uint8_t page[256], page_seen[256];
    int page_n;
    uint8_t wr[2];
    int wr_n;
    /* For the user */
    char last[48];
    uint32_t prog_addr;
    unsigned gen, reads;
    int msg, unknown;
    uint8_t ignored;
};

int w25q_init(struct w25q *w, int cap, uint32_t seed);
void w25q_free(struct w25q *w);
void w25q_select(struct w25q *w, uint64_t now_ps);
/* A byte from the master; returns the byte to shift out next and whether DO drives it */
uint8_t w25q_byte(struct w25q *w, uint8_t b, uint64_t now_ps, int *drive);
void w25q_deselect(struct w25q *w, uint64_t now_ps);
int w25q_message(struct w25q *w, char *out, size_t len);
size_t w25q_mem(struct w25q *w, uint32_t addr, uint8_t *buf, size_t len, int write);
size_t w25q_text(struct w25q *w, uint64_t now_ps, char *out, size_t len);

#endif
