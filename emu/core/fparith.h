/*
 * Solderless - single-precision floating-point arithmetic as the Cortex-M4
 * FPU (FPv4-SP) does it: the ARMv7-M pseudocode's FPRound, NaN handling,
 * flush-to-zero, default NaN, the four rounding modes and the cumulative
 * exception flags, computed exactly in integers so no host FPU setting
 * can change a result.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
#ifndef SL_FPARITH_H
#define SL_FPARITH_H

#include <stdint.h>

/* FPSCR bits */
#define FPSCR_IOC   (1u << 0)
#define FPSCR_DZC   (1u << 1)
#define FPSCR_OFC   (1u << 2)
#define FPSCR_UFC   (1u << 3)
#define FPSCR_IXC   (1u << 4)
#define FPSCR_IDC   (1u << 7)
#define FPSCR_RMODE_SHIFT 22
#define FPSCR_FZ    (1u << 24)
#define FPSCR_DN    (1u << 25)
#define FPSCR_AHP   (1u << 26)
#define FPSCR_MASK  0xF7C0009Fu     /* the bits an FPv4-SP FPSCR holds */

/* Each takes the FPSCR for its controls and sets its cumulative flags */
uint32_t fp_add(uint32_t a, uint32_t b, uint32_t *fpscr);
uint32_t fp_sub(uint32_t a, uint32_t b, uint32_t *fpscr);
uint32_t fp_mul(uint32_t a, uint32_t b, uint32_t *fpscr);
uint32_t fp_div(uint32_t a, uint32_t b, uint32_t *fpscr);
uint32_t fp_sqrt(uint32_t a, uint32_t *fpscr);
/* addend + op1 * op2, rounded once */
uint32_t fp_muladd(uint32_t addend, uint32_t op1, uint32_t op2, uint32_t *fpscr);
/* NZCV in bits 3:0; quiet_nan_exc: VCMPE */
uint32_t fp_compare(uint32_t a, uint32_t b, int quiet_nan_exc, uint32_t *fpscr);
/* To a size-bit integer with frac_bits fraction bits */
uint32_t fp_to_fixed(uint32_t a, int size, int frac_bits, int unsign, int round_zero, uint32_t *fpscr);
uint32_t fp_from_fixed(uint32_t v, int size, int frac_bits, int unsign, int round_nearest, uint32_t *fpscr);
uint32_t fp_half_to_single(uint32_t h, uint32_t *fpscr);
uint32_t fp_single_to_half(uint32_t a, uint32_t *fpscr);
/* VMOV's 8-bit immediate */
uint32_t fp_expand_imm(uint32_t imm8);

static inline uint32_t fp_neg(uint32_t a) { return a ^ 0x80000000u; }
static inline uint32_t fp_abs(uint32_t a) { return a & 0x7FFFFFFFu; }

#endif
