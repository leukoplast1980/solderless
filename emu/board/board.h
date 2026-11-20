/*
 * Solderless - the breadboard: what each chip pin is connected to, and the
 * resulting level and voltage on its net.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * The model is logic level with drive strengths, not a circuit simulator.
 * Every driver on a net is a voltage behind a conductance: the chip's
 * output stage (about 40 ohm), its pull-ups (about 40 kohm), a closed
 * switch, a potentiometer's wiper, an external pull-up. The net settles at
 * the conductance-weighted mean. No driver at all: the net floats.
 *
 * Runs on the VM thread, like the rest of the chip.
 */
#ifndef SL_BOARD_H
#define SL_BOARD_H

#include <stdint.h>
#include "../include/sl/solderless.h"

struct sl_soc;

#define SL_VDD_MV      3300
#define SL_PIN_HI_MV   2310     /* VIH min: 0.7 VDD */
#define SL_PIN_LO_MV   990      /* VIL max: 0.3 VDD */

/* Bounce transitions waiting for one switch */
#define SL_BOUNCE_MAX  8

struct sl_att_state {
    sl_attach a;
    int value;                  /* switch: pressed; pot: wiper in per mille */
    int closed;                 /* switch contact as it is now */
    uint64_t bounce_at[SL_BOUNCE_MAX];
    int bounce_n, bounce_i;
};

struct sl_pin_state {
    int used;                   /* something on the board is attached */
    int mv;                     /* resolved voltage, -1 when floating */
    int level;                  /* digital level as the input stage sees it */
    int floating, shorted, undefined;
    int chip_drives;            /* the chip's output stage drives the pin */
    /* PWM from a timer channel: duty and frequency instead of edges */
    int pwm;
    uint32_t pwm_duty_q16, pwm_freq_mhz;
    /* Average of the high time since the last read */
    uint32_t duty_q16;          /* fraction of time high right now (0 or 1, or the PWM duty) */
    uint64_t t_last, high_acc, win_start;
    int audio;                  /* a piezo or speaker listens */
    int last_mv; uint32_t last_freq, last_duty;
    /* The scope */
    int traced, trace_busy;
    int trace_mv; uint32_t trace_freq, trace_duty;
    uint64_t trace_t;
};

#define SL_TRACE_RING 65536

#define SL_AUDIO_RING 8192

struct sl_board {
    struct sl_att_state att[SL_MAX_ATTACH];
    int natt;
    struct sl_pin_state pin[SL_PIN_SLOTS];
    uint64_t rng;
    sl_audio_event audio[SL_AUDIO_RING];
    uint32_t audio_head, audio_tail;
    sl_audio_event trace[SL_TRACE_RING];
    uint32_t trace_head, trace_tail;
};

void board_trace_pins(struct sl_soc *s, const int *slots, int n);
size_t board_trace_read(struct sl_soc *s, sl_audio_event *out, size_t max);

size_t board_audio_read(struct sl_soc *s, sl_audio_event *out, size_t max);

void board_init(struct sl_soc *s);
void board_reset(struct sl_soc *s);              /* chip reset: pins re-resolve, parts keep their state */
void board_set(struct sl_soc *s, const sl_attach *a, int n);
void board_input(struct sl_soc *s, int part, int value);
void board_event(struct sl_soc *s);              /* EV_BOARD: bounce transitions */
void board_read(struct sl_soc *s, sl_board_state *out);

/* Re-resolves pins after the chip changed what it drives. */
void board_port_changed(struct sl_soc *s, int port, uint32_t pins);
void board_af_outputs_changed(struct sl_soc *s);   /* a peripheral output (timer, DAC) changed */

/* Level and voltage as the chip's input stage and ADC see them. */
int board_pin_level(struct sl_soc *s, int port, int pin);
int board_pin_mv(struct sl_soc *s, int port, int pin);
/* Called when the firmware reads IDR: checks floating inputs it relies on. */
void board_idr_read(struct sl_soc *s, int port);

#endif
