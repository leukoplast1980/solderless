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
    SL_ATT_SWITCH,      /* push button from the pin to GND, or to 3V3 if to_vcc; bounces for bounce_us */
    SL_ATT_PULL,        /* resistor r_ohm to 3V3 (to_vcc) or GND */
    SL_ATT_POT,         /* wiper of a potentiometer of r_ohm between 3V3 and GND */
    SL_ATT_AUDIO,       /* piezo or speaker stage: listens, does not load the pin */
};

typedef struct {
    int part;           /* the app's part number; inputs address parts by it */
    int role;           /* which terminal of the part (RGB: 0 red, 1 green, 2 blue) */
    int pin;            /* port * 16 + bit */
    int kind;           /* SL_ATT_* */
    int to_vcc;
    int r_ohm;
    int vf_mv;
    int bounce_us;
} sl_attach;

/* Replaces the whole board. */
void sl_board_set(sl_vm *vm, const sl_attach *att, int n);

/* User input: a switch part's pressed state (0/1), or a pot's wiper
 * position in per mille (0 = at GND, 1000 = at 3V3). */
void sl_board_input(sl_vm *vm, int part, int value);

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

#ifdef __cplusplus
}
#endif

#endif
