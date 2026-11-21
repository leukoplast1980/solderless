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
    char runs[400];             /* the "Written" lines, for gen runs_gen */
    unsigned runs_gen;
    int runs_valid;
    int msg, unknown;
    uint8_t ignored;
};

int w25q_init(struct w25q *w, int cap, uint32_t seed);
void w25q_free(struct w25q *w);
void w25q_power_cycle(struct w25q *w);           /* the array and the status bits stay, the rest resets */
void w25q_select(struct w25q *w, uint64_t now_ps);
/* A byte from the master; returns the byte to shift out next and whether DO drives it */
uint8_t w25q_byte(struct w25q *w, uint8_t b, uint64_t now_ps, int *drive);
void w25q_deselect(struct w25q *w, uint64_t now_ps);
int w25q_message(struct w25q *w, char *out, size_t len);
size_t w25q_mem(struct w25q *w, uint32_t addr, uint8_t *buf, size_t len, int write);
size_t w25q_text(struct w25q *w, uint64_t now_ps, char *out, size_t len);

/* MAX7219 on an 8 x 8 LED matrix module, chained up to 8 */
#define MAX7219_CHAIN 8
struct max7219 {
    int n;                      /* chips in the chain */
    uint8_t sh[2 * MAX7219_CHAIN];  /* the shift registers, the newest byte first */
    int bytes, odd;
    uint8_t digit[MAX7219_CHAIN][8];
    uint8_t decode[MAX7219_CHAIN], intensity[MAX7219_CHAIN], scan[MAX7219_CHAIN], on[MAX7219_CHAIN], test[MAX7219_CHAIN];
    unsigned latches;
    int last_reg, last_val, warned_5v;
};

void max7219_power_on(struct max7219 *m, int chips, uint32_t seed);
void max7219_select(struct max7219 *m);
void max7219_byte(struct max7219 *m, uint8_t b);
void max7219_deselect(struct max7219 *m);
int max7219_message(struct max7219 *m, char *out, size_t len);
/* 8n x 8 brightness values, row by row, chip 0 on the right */
void max7219_pixels(const struct max7219 *m, uint8_t *px);
size_t max7219_text(const struct max7219 *m, char *out, size_t len);

/* ST7789 colour TFT controller, on a 240 x 240 or 240 x 320 IPS module */
#define ST7789_W 240
#define ST7789_H 320
enum { ST7789_MSG_EARLY = 1, ST7789_MSG_ASLEEP, ST7789_MSG_OFF, ST7789_MSG_COLMOD, ST7789_MSG_OFFSCREEN };
struct st7789 {
    uint16_t *ram;              /* 240 x 320 frame memory, RGB565; heap */
    int panel_h;                /* rows the panel shows: 240 or 320 */
    int sleeping, on, inverted, idle, in_reset;
    uint8_t colmod, madctl;
    int colmod_set;
    int xs, xe, ys, ye, x, y;
    uint8_t cmd, args[8];
    int nargs, writing;
    uint8_t px[3];
    int pxn;
    uint64_t ready_at, last_ps;
    const char *wait_why;
    int wait_ms;
    /* For the user */
    int pixels, run, run_x, run_y, commands, offscreen;
    int msg;
    unsigned said;
    uint8_t ignored;
    double early_ms;
};

int st7789_init(struct st7789 *t, int rows, uint32_t seed);
void st7789_free(struct st7789 *t);
void st7789_power_cycle(struct st7789 *t, uint32_t seed);   /* the frame memory is lost too */
void st7789_reset_pin(struct st7789 *t, int low, uint64_t now_ps);
/* A byte from the master, with the DC line as it was at its last bit (0 command, 1 data) */
void st7789_byte(struct st7789 *t, uint8_t b, int dc, uint64_t now_ps);
void st7789_check(struct st7789 *t);
int st7789_message(struct st7789 *t, char *out, size_t len);
/* 240 x panel_h RGB565 values as the panel shows them */
void st7789_pixels(const struct st7789 *t, uint16_t *px);
size_t st7789_text(const struct st7789 *t, uint64_t now_ps, char *out, size_t len);

/* BMP280 pressure and temperature sensor, I2C */
enum { BMP280_MSG_NO_MEASUREMENT = 1, BMP280_MSG_NO_CALIBRATION };
struct bmp280 {
    double pressure_pa, temp_c;     /* what the user set */
    uint8_t ctrl_meas, config, ptr;
    int first, pair, reading;
    int32_t adc_t, adc_p, filt_t, filt_p, shadow_t, shadow_p;
    uint64_t busy_until, next_ps, last_done;
    unsigned ran;
    /* what the driver has done, for the sheet */
    int id_read, wrote_ctrl, wrote_config, did_reset, data_read;
    uint32_t cal_read;
    int msg, said;
};

void bmp280_power_on(struct bmp280 *b);
void bmp280_start(struct bmp280 *b, int read, uint64_t now_ps);
void bmp280_byte(struct bmp280 *b, uint8_t v, uint64_t now_ps);
uint8_t bmp280_read(struct bmp280 *b, uint64_t now_ps);
void bmp280_input(struct bmp280 *b, const char *what, double v, uint64_t now_ps);
int bmp280_message(struct bmp280 *b, char *out, size_t len);
size_t bmp280_text(struct bmp280 *b, uint64_t now_ps, char *out, size_t len);

/* SCD41 CO2, temperature and humidity sensor, I2C */
enum { SCD41_IDLE, SCD41_PERIODIC, SCD41_LOW_POWER, SCD41_SINGLE, SCD41_ASLEEP };
enum { SCD41_MSG_PERIODIC = 1, SCD41_MSG_NO_DATA, SCD41_MSG_BUSY, SCD41_MSG_CRC, SCD41_MSG_UNKNOWN };
struct scd41 {
    double co2, temp_c, rh;         /* what the user set */
    uint64_t breath_at;
    int mode, single_rht, ready, asc, changed, writing;
    uint64_t next_ps, busy_until, cmd_at, last_meas;
    uint16_t out_co2, out_t, out_rh, offset_raw, altitude, pressure_hpa, serial[3];
    uint16_t p_offset, p_altitude;  /* the settings in its EEPROM (persist_settings) */
    int p_asc;
    uint8_t in[8], resp[9];
    int nin, nresp, rpos;
    uint16_t cmd, ignored;
    unsigned commands, measured, reads;
    int msg;
    unsigned said;
};

void scd41_power_on(struct scd41 *c, uint32_t seed);
void scd41_power_cycle(struct scd41 *c);        /* back to the persisted settings; serial and readings stay */
int scd41_start(struct scd41 *c, int read, uint64_t now_ps);     /* acknowledged? */
int scd41_byte(struct scd41 *c, uint8_t b, uint64_t now_ps);     /* acknowledged? */
uint8_t scd41_read(struct scd41 *c);
void scd41_stop(struct scd41 *c, uint64_t now_ps);
void scd41_input(struct scd41 *c, const char *what, double v, uint64_t now_ps);
int scd41_message(struct scd41 *c, char *out, size_t len);
size_t scd41_text(struct scd41 *c, uint64_t now_ps, char *out, size_t len);

#endif
