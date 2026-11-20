/*
 * Solderless - static description of the STM32G431RB, generated from ST's
 * SVD and the stm32-data chip file by tools/gen_g431.py.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
#ifndef SL_G431_DATA_H
#define SL_G431_DATA_H

#include <stdint.h>

struct sl_reg_info {
    uint16_t off;
    uint32_t reset;
    uint32_t wmask;         /* bits software can write */
    const char *name;
};

struct sl_periph_info {
    const char *name;
    const char *kind;       /* "usart", "gpio", "timer", ... */
    uint32_t base, size;
    const struct sl_reg_info *regs;
    int nregs;
    int en_reg, en_bit;     /* RCC enable register offset and bit; -1: always clocked */
    int rst_reg, rst_bit;   /* RCC reset register offset and bit; -1: none */
    int irq[4];             /* interrupt numbers, -1 terminated */
};

struct sl_pin_info {
    const char *name;       /* "PA2" */
    const char *position;   /* package pin number */
    int port, pin;
};

struct sl_af_info {
    uint8_t port, pin, af;
    int periph;             /* index into sl_g431_periph, -1 if not listed */
    const char *periph_name;
    const char *signal;     /* "TX", "RX", "CH1", ... */
};

extern const struct sl_periph_info sl_g431_periph[];
extern const int sl_g431_nperiph;
extern const struct sl_pin_info sl_g431_pins[];
extern const int sl_g431_npins;
extern const struct sl_af_info sl_g431_af[];
extern const int sl_g431_naf;

#endif
