/*
 * Solderless - an emulated STM32G431RB and its board.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Threading model: one thread calls sl_run(), which executes guest code
 * until sl_shutdown(). Every other function may be called from any thread.
 */
#ifndef SL_SOLDERLESS_H
#define SL_SOLDERLESS_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SL_FLASH_BYTES   0x20000u      /* 128 KiB at 0x0800_0000 */
#define SL_FLASH_ADDR    0x08000000u

typedef struct sl_vm sl_vm;

typedef struct {
    /* Nonzero: no real-time pacing; idle time is skipped, so runs are
     * fast and reproducible (tests). Zero: emulated time follows the
     * wall clock. */
    int deterministic;
    uint32_t hse_hz;          /* crystal on PF0/PF1 in Hz, 0 for none */
    int lse_fitted;           /* 32.768 kHz crystal on PC14/PC15 */
    uint32_t term_baud;       /* serial terminal on PA2/PA3 (8N1); 0 means 115200 */
} sl_config;

sl_vm *sl_create(const sl_config *cfg);
void sl_destroy(sl_vm *vm);

/* Flash. sl_load_flash copies up to SL_FLASH_BYTES; the rest reads as
 * erased (0xFF). sl_copy_flash copies all of flash into buf and clears
 * the dirty flag; it returns nonzero if flash had changed. */
void sl_load_flash(sl_vm *vm, const uint8_t *image, size_t len);
int sl_copy_flash(sl_vm *vm, uint8_t *buf);
int sl_flash_dirty(sl_vm *vm);

/* Runs guest code on the calling thread until sl_shutdown(). Starts with
 * a power-on reset. */
void sl_run(sl_vm *vm);
void sl_shutdown(sl_vm *vm);
void sl_pause(sl_vm *vm, int paused);
void sl_step(sl_vm *vm);                      /* one instruction while paused */
void sl_reset(sl_vm *vm);                     /* the reset button (NRST) */
void sl_power_cycle(sl_vm *vm);               /* power-on reset: RAM cleared */
/* Waits until a reset or power cycle asked for has happened (they are applied on the emulator's thread) */
void sl_sync(sl_vm *vm);

/* Serial terminal on PA2 (chip TX) and PA3 (chip RX). Bytes cross the
 * wire at the terminal's baud rate. */
size_t sl_uart_write(sl_vm *vm, const uint8_t *data, size_t len);
size_t sl_uart_read(sl_vm *vm, uint8_t *buf, size_t len);
size_t sl_uart_read_wait(sl_vm *vm, uint8_t *buf, size_t len, int timeout_ms);

/* True while the firmware waits for input: the CPU sleeps or polls a
 * register that only input can change, nothing is on the serial wire
 * and no typed byte is waiting. */
int sl_waiting_for_key(sl_vm *vm);
int sl_wait_idle(sl_vm *vm, int timeout_ms);

#define SL_LINE_OK       0   /* Forth answered " ok." */
#define SL_LINE_ERROR    1   /* Forth printed something else */
#define SL_LINE_TIMEOUT  2   /* Forth did not return to the prompt in time */
#define SL_LINE_TOO_LONG 3
#define SL_MAX_LINE      200 /* Stellaris' input buffer */

/* Sends one line of source (without line end) and a line feed, waits until
 * the firmware waits for input again, and checks whether the output for
 * this line ended with " ok.". Output stays in the terminal buffer. */
int sl_send_line(sl_vm *vm, const char *line, size_t len, int timeout_ms);

typedef struct {
    uint32_t r[16];
    uint32_t xpsr, msp, psp, primask, basepri, faultmask, control;
    uint32_t cfsr, hfsr, mmfar, bfar, fault_pc;
    uint64_t cycles;
    uint64_t time_ns;           /* emulated time since power-on */
    uint32_t hclk, sysclk, pclk1, pclk2;
    int lockup;                 /* the core stopped after a fault it could not take */
    int sleeping;
    int waiting_for_key;
    int paused;
    int speed_percent;          /* emulated time / wall time, last second */
} sl_snapshot;

void sl_snapshot_get(sl_vm *vm, sl_snapshot *out);

/* Debug reads without side effects. */
size_t sl_read_memory(sl_vm *vm, uint32_t addr, uint8_t *buf, size_t len);
uint32_t sl_peek32(sl_vm *vm, uint32_t addr);

/* Messages about what the firmware did that a real chip would not tell
 * you (clock off, wrong baud rate, flash misuse, ...). Drains complete
 * lines into buf, each ending in '\n'. Returns bytes copied. */
size_t sl_log_read(sl_vm *vm, char *buf, size_t len);

/* ---- The breadboard ---------------------------------------------------
 *
 * The app works out the circuit (parts, pins, passives) and hands the
 * emulator one attachment per part terminal on a chip pin. Pins are given
 * as port * 16 + bit: PA0 = 0, PC13 = 45, PD2 = 50. */

#define SL_PIN_SLOTS    112     /* GPIOA..GPIOG x 16 */
#define SL_MAX_ATTACH   64

enum {
    SL_ATT_LED = 1,     /* r_ohm in series, then an LED (vf_mv) to GND, or from 3V3 if to_vcc */
    SL_ATT_SWITCH,      /* push button from the pin to GND, or to 3V3 if to_vcc; bounces for bounce_us.
                           A rotary encoder's two contacts are switches too, with role SL_ROLE_ENC_A and _B:
                           sl_board_turn moves them, sl_board_input does not */
    SL_ATT_PULL,        /* resistor r_ohm to 3V3 (to_vcc) or GND */
    SL_ATT_POT,         /* wiper of a potentiometer of r_ohm between 3V3 and GND */
    SL_ATT_AUDIO,       /* piezo or speaker stage: listens, does not load the pin */
    SL_ATT_DEVICE,      /* a signal of a bus part (dev): role is its SL_SIG_*, r_ohm the module's own pull-up (0: none):
                           I2C modules on SCL and SDA, SPI modules on CS (and a display's RES) */
};

/* Bus parts */
enum {
    SL_DEV_SSD1306 = 1, /* 128 x 64 OLED controller, I2C; addr 0x3C or 0x3D */
    SL_DEV_W25Q = 2,    /* serial flash, SPI; addr: JEDEC capacity 0x16 (32 Mbit), 0x17 (64), 0x18 (128) */
    SL_DEV_MAX7219 = 3, /* 8 x 8 LED matrix module(s), SPI-style; addr: chips in the chain (1-8) */
    SL_DEV_ST7789 = 4,  /* colour TFT, SPI with DC and RES; addr: rows of the panel, 240 or 320 */
    SL_DEV_BMP280 = 5,  /* pressure and temperature, I2C; addr 0x76 or 0x77 */
    SL_DEV_SCD41 = 6,   /* CO2, temperature and humidity, I2C; addr 0x62 */
};
/* Signals of bus parts, as sl_attach.role */
enum { SL_SIG_SCL = 0, SL_SIG_SDA = 1, SL_SIG_SCK = 2, SL_SIG_MOSI = 3, SL_SIG_MISO = 4, SL_SIG_CS = 5,
       SL_SIG_DC = 6, SL_SIG_RES = 7, SL_SIG_BLK = 8, SL_SIG_COUNT = 10 };

typedef struct {
    int part;           /* the app's part number; inputs address parts by it */
    int role;           /* which terminal of the part (RGB: 0 red, 1 green, 2 blue) */
    int pin;            /* port * 16 + bit */
    int kind;           /* SL_ATT_* */
    int to_vcc;
    int r_ohm;
    int vf_mv;
    int bounce_us;
    int dev;            /* SL_ATT_DEVICE: SL_DEV_* */
    int addr;           /* SL_ATT_DEVICE: the I2C address its strap pins select */
} sl_attach;

/* Replaces the whole board. */
void sl_board_set(sl_vm *vm, const sl_attach *att, int n);

/* User input: a switch part's pressed state (0/1), or a pot's wiper
 * position in per mille (0 = at GND, 1000 = at 3V3). */
void sl_board_input(sl_vm *vm, int part, int value);

/* A rotary encoder (its contacts: switches with these roles) turned by steps
 * detents, clockwise when positive: each detent is one full quadrature
 * cycle, A closing first clockwise, B first the other way, a few
 * milliseconds per change. */
enum { SL_ROLE_ENC_A = 100, SL_ROLE_ENC_B = 101 };
void sl_board_turn(sl_vm *vm, int part, int steps);

enum {
    SL_PIN_HIGH = 1, SL_PIN_FLOATING = 2, SL_PIN_SHORT = 4, SL_PIN_PWM = 8,
    SL_PIN_UNDEFINED = 16, SL_PIN_CHIP_DRIVES = 32, SL_PIN_USED = 64,
};
enum { SL_MODE_INPUT, SL_MODE_OUTPUT, SL_MODE_AF, SL_MODE_ANALOG };

typedef struct {
    int16_t mv[SL_PIN_SLOTS];           /* net voltage, -1 when floating */
    uint8_t flags[SL_PIN_SLOTS];        /* SL_PIN_* */
    uint8_t mode[SL_PIN_SLOTS];         /* SL_MODE_*, from MODER */
    uint16_t duty[SL_PIN_SLOTS];        /* time high since the last read, per mille */
    uint32_t freq_mhz[SL_PIN_SLOTS];    /* PWM frequency in mHz */
    uint16_t pwm_duty[SL_PIN_SLOTS];    /* PWM duty as set now, per mille */
    int32_t current_ua[SL_MAX_ATTACH];  /* average current through each attachment */
    uint64_t time_ns;
} sl_board_state;

/* Reads the board and starts a new averaging window. */
void sl_board_read(sl_vm *vm, sl_board_state *out);

/* A bus part as the user sees it. A display: its pixels as they are lit
 * now, one byte per pixel, row by row (SSD1306: 128 x 64, 0 or 1; the
 * contrast is in the text. MAX7219: 8 x chips wide, 8 high, brightness
 * 0-255, the chip at DIN on the right. ST7789: 240 x 240 or 240 x 320,
 * two bytes a pixel, RGB565 little-endian). Text: lines of "name\tvalue", what the part's
 * controller holds (on/off, modes, the last access). Returns the number of
 * pixels written; 0 if the part is not on the board. */
size_t sl_device_read(sl_vm *vm, int part, uint8_t *pixels, size_t len, char *text, size_t tlen);

/* A memory part's contents (W25Q: the flash array), read or written
 * directly, as an external programmer would: a write sets bytes as given,
 * 1s included. Returns the bytes moved; 0 if the part has no memory. */
size_t sl_device_mem(sl_vm *vm, int part, uint32_t addr, uint8_t *buf, size_t len, int write);
/* A count that changes whenever a memory part's contents do (0 for other parts) */
uint32_t sl_device_gen(sl_vm *vm, int part);

/* What a sensor senses, as the user sets it: BMP280 "pressure_pa", "temp_c";
 * SCD41 "co2_ppm", "temp_c", "rh", and "breath" (any value: a breath on it now). */
void sl_device_input(sl_vm *vm, int part, const char *what, double value);

/* Sound: every change on a pin with an SL_ATT_AUDIO part, timestamped in
 * emulated time. A level (mV) for edges and DAC samples, or a PWM setting
 * the player turns into a square wave. */
enum { SL_AUDIO_LEVEL = 0, SL_AUDIO_PWM = 1 };
typedef struct {
    uint64_t time_ns;
    int32_t kind;           /* SL_AUDIO_*; bits 8-15: pin slot */
    uint32_t a;             /* level: mV; PWM: frequency in mHz */
    uint32_t b;             /* PWM: duty, 0x10000 = 100 % */
} sl_audio_event;

/* Drains up to max events; *now_ns gets the emulated time now. */
size_t sl_audio_read(sl_vm *vm, sl_audio_event *out, size_t max, uint64_t *now_ns);

/* The scope: the same events for the pins it watches (port * 16 + bit).
 * Setting the pins records their state at once. Edges closer than 1 us on
 * one pin are not recorded one by one: the next event carries
 * SL_TRACE_BUSY, meaning the pin toggled fast since the previous one. */
#define SL_TRACE_BUSY 0x10000
void sl_trace_pins(sl_vm *vm, const int *slots, int n);
size_t sl_trace_read(sl_vm *vm, sl_audio_event *out, size_t max, uint64_t *now_ns);

/* The scope's decoder: what went over a bus whose pins it watches, a byte
 * at a time, from an on-chip I2C or SPI or from the decoders that read
 * bit-banged lines. Times in emulated ns. */
enum {
    SL_BUS_I2C_START = 1,   /* (repeated) START */
    SL_BUS_I2C_ADDR,        /* the address byte, R/W in bit 0; miso: acknowledged */
    SL_BUS_I2C_BYTE,        /* a data byte; miso: acknowledged (by the part writing, by the master reading) */
    SL_BUS_I2C_STOP,
    SL_BUS_SPI_BYTE,        /* a frame: value out on MOSI, miso back */
    SL_BUS_LOST,            /* events the reader was too slow for: miso << 16 | value of them, t0 to t1;
                               bits: the kind of the first, so I2C or SPI */
    SL_BUS_SPI_CS,          /* a part's chip select: value 1 selected, 0 released */
};
#define SL_BUS_HW   0x10    /* flags: from an on-chip peripheral (the pins did not toggle bit by bit) */
#define SL_BUS_READ 0x20    /* flags: I2C, the master reading; SPI: the display's DC was high (data) */
#define SL_BUS_LSB  0x40    /* flags: SPI, least significant bit first */
#define SL_BUS_DRIVEN 0x80  /* flags: SPI, a part drove MISO */
typedef struct {
    uint64_t t0_ns, t1_ns;
    uint8_t kind, flags;    /* flags: SL_BUS_*, SPI: CPOL << 1 | CPHA in bits 0-1 */
    uint8_t bits;           /* SPI frame size */
    int8_t part;            /* the part that answered or was selected, -1: none */
    uint16_t value, miso;
    int8_t pin[2];          /* I2C: SCL, SDA; SPI: SCK, MOSI (port * 16 + bit) */
    int8_t pin2;            /* SPI: MISO, or -1 */
    int8_t cs;              /* SPI: the selected part's chip select, or -1 */
} sl_bus_event;
size_t sl_bus_read(sl_vm *vm, sl_bus_event *out, size_t max);

#ifdef __cplusplus
}
#endif

#endif
