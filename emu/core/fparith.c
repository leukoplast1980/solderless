/*
 * Solderless - single-precision floating-point arithmetic of the Cortex-M4
 * FPU, after the ARMv7-M Architecture Reference Manual's pseudocode
 * (A2.5, "Floating-point data types and arithmetic"): FPUnpack, FPRound,
 * FPProcessNaNs, FPAdd, FPMul, FPDiv, FPSqrt, FPMulAdd, FPCompare,
 * FPToFixed, FixedToFP and the half-precision conversions.
 *
 * Values are kept exactly as sign * m * 2^e with a 64-bit m and a sticky
 * bit for anything below it, so every result is rounded once, in integers.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
#include "fparith.h"

#include <math.h>

enum { T_ZERO, T_NUM, T_INF, T_QNAN, T_SNAN };

struct fpv {
    int type, sign;
    uint64_t m;         /* T_NUM: value = m * 2^e, m != 0 */
    int e;
};

#define DEFAULT_NAN 0x7FC00000u

static inline int bitlen(uint64_t v) { return v ? 64 - __builtin_clzll(v) : 0; }
static inline int rmode(uint32_t fpscr) { return (int)((fpscr >> FPSCR_RMODE_SHIFT) & 3u); }

/* FPUnpack for single precision: a denormal is zero when FZ is set (IDC) */
static struct fpv unpack(uint32_t x, uint32_t *fpscr)
{
    struct fpv v = { T_NUM, (int)(x >> 31), 0, 0 };
    uint32_t exp = (x >> 23) & 0xFFu, frac = x & 0x7FFFFFu;
    if (exp == 0) {
        if (frac == 0) v.type = T_ZERO;
        else if (*fpscr & FPSCR_FZ) { v.type = T_ZERO; *fpscr |= FPSCR_IDC; }
        else { v.m = frac; v.e = -149; }
    } else if (exp == 0xFFu) {
        v.type = frac == 0 ? T_INF : (frac & 0x400000u) ? T_QNAN : T_SNAN;
    } else {
        v.m = frac | 0x800000u;
        v.e = (int)exp - 150;
    }
    return v;
}

static inline int is_nan(const struct fpv *v) { return v->type == T_QNAN || v->type == T_SNAN; }

/* FPRound: sign * (m + sticky) * 2^e to N bits (32, or 16 for half) */
static uint32_t fp_round(int sign, uint64_t m, int e, int sticky, int n, uint32_t *fpscr)
{
    const int E = n == 32 ? 8 : 5, F = n == 32 ? 23 : 10, minexp = n == 32 ? -126 : -14;
    int exponent = e + bitlen(m) - 1;
    uint32_t sbit = (uint32_t)sign << (n - 1);
    if ((*fpscr & FPSCR_FZ) && n != 16 && exponent < minexp) {   /* flush to zero */
        *fpscr |= FPSCR_UFC;
        return sbit;
    }
    int biased = exponent - minexp + 1;
    if (biased < 0) biased = 0;
    int lsb = biased == 0 ? minexp - F : exponent - F;   /* exponent of the result's last bit */
    int shift = lsb - e;
    uint64_t im;
    int half, rest;
    if (shift <= 0) {
        im = m << -shift;
        half = 0; rest = sticky;
    } else if (shift >= 64) {
        im = 0;
        half = shift == 64 ? (int)(m >> 63) : 0;
        rest = (shift == 64 ? (m & 0x7FFFFFFFFFFFFFFFull) != 0 : m != 0) || sticky;
    } else {
        im = m >> shift;
        half = (int)((m >> (shift - 1)) & 1u);
        rest = (shift > 1 && (m & ((1ull << (shift - 1)) - 1u)) != 0) || sticky;
    }
    int err = half || rest;
    if (biased == 0 && err) *fpscr |= FPSCR_UFC;     /* tiny before rounding, and inexact */
    int rm = rmode(*fpscr), up = 0;
    switch (rm) {
    case 0: up = half && (rest || (im & 1u)); break;  /* to nearest, ties to even */
    case 1: up = err && !sign; break;                 /* towards +infinity */
    case 2: up = err && sign; break;                  /* towards -infinity */
    default: break;                                   /* towards zero */
    }
    if (up) {
        im++;
        if (im == (1ull << (F + 1))) { im >>= 1; biased++; }
        if (biased == 0 && im == (1ull << F)) biased = 1;
    }
    uint32_t res;
    if (n == 32 || !(*fpscr & FPSCR_AHP)) {
        if (biased >= (1 << E) - 1) {                 /* overflow */
            int to_inf = rm == 0 || (rm == 1 && !sign) || (rm == 2 && sign);
            uint32_t inf = ((1u << E) - 1u) << F;
            res = sbit | (to_inf ? inf : inf - 1u);
            *fpscr |= FPSCR_OFC;
            err = 1;
        } else {
            res = sbit | ((uint32_t)biased << F) | (uint32_t)(im & ((1ull << F) - 1u));
        }
    } else {                                          /* alternative half precision */
        if (biased >= (1 << E)) {
            res = sbit | 0x7FFFu;
            *fpscr |= FPSCR_IOC;
            err = 0;
        } else {
            res = sbit | ((uint32_t)biased << F) | (uint32_t)(im & ((1ull << F) - 1u));
        }
    }
    if (err) *fpscr |= FPSCR_IXC;
    return res;
}

static uint32_t process_nan(const struct fpv *v, uint32_t x, uint32_t *fpscr)
{
    uint32_t r = x | 0x00400000u;
    if (v->type == T_SNAN) *fpscr |= FPSCR_IOC;
    return (*fpscr & FPSCR_DN) ? DEFAULT_NAN : r;
}

/* FPProcessNaNs: signalling NaNs first, then quiet ones, in operand order */
static int process_nans(const struct fpv *v, const uint32_t *x, int k, uint32_t *res, uint32_t *fpscr)
{
    for (int i = 0; i < k; i++) if (v[i].type == T_SNAN) { *res = process_nan(&v[i], x[i], fpscr); return 1; }
    for (int i = 0; i < k; i++) if (v[i].type == T_QNAN) { *res = process_nan(&v[i], x[i], fpscr); return 1; }
    return 0;
}

static inline uint32_t inf_of(int sign) { return ((uint32_t)sign << 31) | 0x7F800000u; }
static inline uint32_t zero_of(int sign) { return (uint32_t)sign << 31; }

/* a + b for finite non-zero values, exactly, or with the sticky bit for
 * the part of a much smaller b that falls below 61 bits of a */
static int add_exact(int sa, uint64_t ma, int ea, int sb, uint64_t mb, int eb,
                     int *sign, uint64_t *m, int *e, int *sticky)
{
    if (ea < eb) {
        int ts = sa; sa = sb; sb = ts;
        uint64_t tm = ma; ma = mb; mb = tm;
        int te = ea; ea = eb; eb = te;
    }
    int d = ea - eb, bl = bitlen(ma);
    if (bl + d <= 62) {
        uint64_t x = ma << d, y = mb;
        *e = eb; *sticky = 0;
        if (sa == sb) { *m = x + y; *sign = sa; }
        else if (x >= y) { *m = x - y; *sign = sa; }
        else { *m = y - x; *sign = sb; }
        return *m != 0;
    }
    int sh = 62 - bl;
    uint64_t x = ma << sh;
    *e = ea - sh;
    int k = *e - eb;
    uint64_t y;
    int rem;
    if (k >= 64) { y = 0; rem = mb != 0; }
    else { y = mb >> k; rem = (mb & ((1ull << k) - 1u)) != 0; }
    *sign = sa;
    *sticky = rem;
    *m = sa == sb ? x + y : x - y - (uint64_t)rem;
    return 1;
}

static uint32_t add_values(const struct fpv *a, const struct fpv *b, int negb, uint32_t *fpscr)
{
    int sign, e, sticky;
    uint64_t m;
    if (!add_exact(a->sign, a->m, a->e, b->sign ^ negb, b->m, b->e, &sign, &m, &e, &sticky))
        return zero_of(rmode(*fpscr) == 2);           /* exactly zero: -0 only rounding towards -inf */
    return fp_round(sign, m, e, sticky, 32, fpscr);
}

static uint32_t addsub(uint32_t x, uint32_t y, int sub, uint32_t *fpscr)
{
    struct fpv v[2] = { unpack(x, fpscr), unpack(y, fpscr) };
    uint32_t ops[2] = { x, y }, r;
    if (process_nans(v, ops, 2, &r, fpscr)) return r;
    int s1 = v[0].sign, s2 = v[1].sign ^ sub;
    int inf1 = v[0].type == T_INF, inf2 = v[1].type == T_INF;
    int zero1 = v[0].type == T_ZERO, zero2 = v[1].type == T_ZERO;
    if (inf1 && inf2 && s1 != s2) { *fpscr |= FPSCR_IOC; return DEFAULT_NAN; }
    if ((inf1 && !s1) || (inf2 && !s2)) return inf_of(0);
    if ((inf1 && s1) || (inf2 && s2)) return inf_of(1);
    if (zero1 && zero2 && s1 == s2) return zero_of(s1);
    if (zero1 && zero2) return zero_of(rmode(*fpscr) == 2);
    if (zero1) { struct fpv b = v[1]; b.sign = s2; return fp_round(b.sign, b.m, b.e, 0, 32, fpscr); }
    if (zero2) return fp_round(v[0].sign, v[0].m, v[0].e, 0, 32, fpscr);
    return add_values(&v[0], &v[1], sub, fpscr);
}

uint32_t fp_add(uint32_t a, uint32_t b, uint32_t *fpscr) { return addsub(a, b, 0, fpscr); }
uint32_t fp_sub(uint32_t a, uint32_t b, uint32_t *fpscr) { return addsub(a, b, 1, fpscr); }

uint32_t fp_mul(uint32_t x, uint32_t y, uint32_t *fpscr)
{
    struct fpv v[2] = { unpack(x, fpscr), unpack(y, fpscr) };
    uint32_t ops[2] = { x, y }, r;
    if (process_nans(v, ops, 2, &r, fpscr)) return r;
    int sign = v[0].sign ^ v[1].sign;
    int inf1 = v[0].type == T_INF, inf2 = v[1].type == T_INF;
    int zero1 = v[0].type == T_ZERO, zero2 = v[1].type == T_ZERO;
    if ((inf1 && zero2) || (zero1 && inf2)) { *fpscr |= FPSCR_IOC; return DEFAULT_NAN; }
    if (inf1 || inf2) return inf_of(sign);
    if (zero1 || zero2) return zero_of(sign);
    return fp_round(sign, v[0].m * v[1].m, v[0].e + v[1].e, 0, 32, fpscr);
}

/* m to 24 significant bits: m in [2^23, 2^24) */
static void normalise(uint64_t *m, int *e)
{
    int sh = 24 - bitlen(*m);
    *m <<= sh;
    *e -= sh;
}

uint32_t fp_div(uint32_t x, uint32_t y, uint32_t *fpscr)
{
    struct fpv v[2] = { unpack(x, fpscr), unpack(y, fpscr) };
    uint32_t ops[2] = { x, y }, r;
    if (process_nans(v, ops, 2, &r, fpscr)) return r;
    int sign = v[0].sign ^ v[1].sign;
    int inf1 = v[0].type == T_INF, inf2 = v[1].type == T_INF;
    int zero1 = v[0].type == T_ZERO, zero2 = v[1].type == T_ZERO;
    if ((inf1 && inf2) || (zero1 && zero2)) { *fpscr |= FPSCR_IOC; return DEFAULT_NAN; }
    if (inf1 || zero2) {
        if (!inf1) *fpscr |= FPSCR_DZC;
        return inf_of(sign);
    }
    if (zero1 || inf2) return zero_of(sign);
    uint64_t a = v[0].m, b = v[1].m;
    int ea = v[0].e, eb = v[1].e;
    normalise(&a, &ea);
    normalise(&b, &eb);
    uint64_t n = a << 39;
    return fp_round(sign, n / b, ea - eb - 39, n % b != 0, 32, fpscr);
}

static uint64_t isqrt(uint64_t v)
{
    uint64_t r = (uint64_t)sqrt((double)v);
    while (r * r > v) r--;
    while ((r + 1) * (r + 1) <= v) r++;
    return r;
}

uint32_t fp_sqrt(uint32_t x, uint32_t *fpscr)
{
    struct fpv v = unpack(x, fpscr);
    if (is_nan(&v)) return process_nan(&v, x, fpscr);
    if (v.type == T_ZERO) return zero_of(v.sign);
    if (v.type == T_INF && !v.sign) return inf_of(0);
    if (v.sign) { *fpscr |= FPSCR_IOC; return DEFAULT_NAN; }
    uint64_t m = v.m;
    int e = v.e;
    normalise(&m, &e);
    int s = ((e - 38) & 1) ? 39 : 38;               /* an even exponent, m << s below 2^63 */
    uint64_t big = m << s, root = isqrt(big);
    return fp_round(0, root, (e - s) / 2, root * root != big, 32, fpscr);
}

uint32_t fp_muladd(uint32_t a, uint32_t x, uint32_t y, uint32_t *fpscr)
{
    struct fpv v[3] = { unpack(a, fpscr), unpack(x, fpscr), unpack(y, fpscr) };
    uint32_t ops[3] = { a, x, y }, r;
    int inf1 = v[1].type == T_INF, inf2 = v[2].type == T_INF;
    int zero1 = v[1].type == T_ZERO, zero2 = v[2].type == T_ZERO;
    int done = process_nans(v, ops, 3, &r, fpscr);
    if (v[0].type == T_QNAN && ((inf1 && zero2) || (zero1 && inf2))) {
        *fpscr |= FPSCR_IOC;
        return DEFAULT_NAN;
    }
    if (done) return r;
    int infa = v[0].type == T_INF, zeroa = v[0].type == T_ZERO;
    int sp = v[1].sign ^ v[2].sign, infp = inf1 || inf2, zerop = zero1 || zero2;
    if ((inf1 && zero2) || (zero1 && inf2) || (infa && infp && v[0].sign != sp)) {
        *fpscr |= FPSCR_IOC;
        return DEFAULT_NAN;
    }
    if ((infa && !v[0].sign) || (infp && !sp)) return inf_of(0);
    if ((infa && v[0].sign) || (infp && sp)) return inf_of(1);
    if (zeroa && zerop && v[0].sign == sp) return zero_of(sp);
    if (zerop) {
        if (zeroa) return zero_of(rmode(*fpscr) == 2);
        return fp_round(v[0].sign, v[0].m, v[0].e, 0, 32, fpscr);
    }
    uint64_t pm = v[1].m * v[2].m;
    int pe = v[1].e + v[2].e;
    if (zeroa) return fp_round(sp, pm, pe, 0, 32, fpscr);
    int sign, e, sticky;
    uint64_t m;
    if (!add_exact(v[0].sign, v[0].m, v[0].e, sp, pm, pe, &sign, &m, &e, &sticky))
        return zero_of(rmode(*fpscr) == 2);
    return fp_round(sign, m, e, sticky, 32, fpscr);
}

/* An unpacked value as a double: exact for every single */
static double as_double(const struct fpv *v)
{
    if (v->type == T_ZERO) return 0.0;
    if (v->type == T_INF) return v->sign ? -INFINITY : INFINITY;
    double d = ldexp((double)v->m, v->e);
    return v->sign ? -d : d;
}

uint32_t fp_compare(uint32_t x, uint32_t y, int quiet_nan_exc, uint32_t *fpscr)
{
    struct fpv a = unpack(x, fpscr), b = unpack(y, fpscr);
    if (is_nan(&a) || is_nan(&b)) {
        if (a.type == T_SNAN || b.type == T_SNAN || quiet_nan_exc) *fpscr |= FPSCR_IOC;
        return 0x3u;
    }
    double da = as_double(&a), db = as_double(&b);
    if (da == db) return 0x6u;
    return da < db ? 0x8u : 0x2u;
}

uint32_t fp_to_fixed(uint32_t x, int size, int frac_bits, int unsign, int round_zero, uint32_t *fpscr)
{
    struct fpv v = unpack(x, fpscr);
    if (is_nan(&v)) { *fpscr |= FPSCR_IOC; return 0; }
    int64_t hi = unsign ? (int64_t)((1ull << size) - 1u) : ((int64_t)1 << (size - 1)) - 1;
    int64_t lo = unsign ? 0 : -((int64_t)1 << (size - 1));
    uint32_t mask = size == 32 ? 0xFFFFFFFFu : (1u << size) - 1u;
    if (v.type == T_ZERO) return 0;
    int64_t res;
    int err = 0;
    if (v.type == T_INF) {
        res = v.sign ? lo : hi;
        *fpscr |= FPSCR_IOC;
        return (uint32_t)res & mask;
    }
    int e = v.e + frac_bits;
    uint64_t mag;
    int half = 0, rest = 0, huge = 0;
    if (e >= 0) {
        if (e > 40) huge = 1;
        mag = huge ? 0 : v.m << e;
    } else {
        int sh = -e;
        if (sh >= 64) { mag = 0; rest = 1; }
        else {
            mag = v.m >> sh;
            half = (int)((v.m >> (sh - 1)) & 1u);
            rest = sh > 1 && (v.m & ((1ull << (sh - 1)) - 1u)) != 0;
        }
    }
    err = half || rest;
    int rm = round_zero ? 3 : rmode(*fpscr), up = 0;
    switch (rm) {
    case 0: up = half && (rest || (mag & 1u)); break;
    case 1: up = err && !v.sign; break;
    case 2: up = err && v.sign; break;
    default: break;
    }
    if (up) mag++;
    if (huge || mag > (1ull << 40)) {
        res = v.sign ? lo : hi;
        *fpscr |= FPSCR_IOC;
        return (uint32_t)res & mask;
    }
    res = v.sign ? -(int64_t)mag : (int64_t)mag;
    if (res > hi || res < lo) {
        *fpscr |= FPSCR_IOC;
        res = res > hi ? hi : lo;
    } else if (err) {
        *fpscr |= FPSCR_IXC;
    }
    return (uint32_t)res & mask;
}

uint32_t fp_from_fixed(uint32_t x, int size, int frac_bits, int unsign, int round_nearest, uint32_t *fpscr)
{
    uint32_t mask = size == 32 ? 0xFFFFFFFFu : (1u << size) - 1u;
    x &= mask;
    int64_t v = unsign ? (int64_t)x : (int64_t)(int32_t)(size == 32 ? x : (x ^ (1u << (size - 1))) - (1u << (size - 1)));
    if (v == 0) return 0;
    uint32_t f = *fpscr;
    if (round_nearest) f &= ~(3u << FPSCR_RMODE_SHIFT);
    int sign = v < 0;
    uint32_t r = fp_round(sign, (uint64_t)(sign ? -v : v), -frac_bits, 0, 32, &f);
    *fpscr = (*fpscr & (3u << FPSCR_RMODE_SHIFT)) | (f & ~(3u << FPSCR_RMODE_SHIFT));
    return r;
}

uint32_t fp_half_to_single(uint32_t h, uint32_t *fpscr)
{
    int sign = (int)((h >> 15) & 1u);
    uint32_t exp = (h >> 10) & 0x1Fu, frac = h & 0x3FFu;
    if (exp == 0x1Fu && !(*fpscr & FPSCR_AHP)) {
        if (frac == 0) return inf_of(sign);
        if (!(frac & 0x200u)) *fpscr |= FPSCR_IOC;   /* signalling */
        if (*fpscr & FPSCR_DN) return DEFAULT_NAN;
        return ((uint32_t)sign << 31) | 0x7FC00000u | ((frac & 0x1FFu) << 13);
    }
    if (exp == 0 && frac == 0) return zero_of(sign);
    uint64_t m = exp ? (frac | 0x400u) : frac;
    int e = exp ? (int)exp - 25 : -24;
    uint32_t f = *fpscr & ~FPSCR_FZ;                  /* a half's value is always a normal single */
    uint32_t r = fp_round(sign, m, e, 0, 32, &f);
    return r;
}

uint32_t fp_single_to_half(uint32_t x, uint32_t *fpscr)
{
    struct fpv v = unpack(x, fpscr);
    int ahp = (*fpscr & FPSCR_AHP) != 0;
    uint32_t sbit = (uint32_t)v.sign << 15;
    if (is_nan(&v)) {
        uint32_t r;
        if (ahp) r = sbit;
        else if (*fpscr & FPSCR_DN) r = 0x7E00u;
        else r = sbit | 0x7E00u | ((x >> 13) & 0x1FFu);
        if (v.type == T_SNAN || ahp) *fpscr |= FPSCR_IOC;
        return r;
    }
    if (v.type == T_INF) {
        if (ahp) { *fpscr |= FPSCR_IOC; return sbit | 0x7FFFu; }
        return sbit | 0x7C00u;
    }
    if (v.type == T_ZERO) return sbit;
    return fp_round(v.sign, v.m, v.e, 0, 16, fpscr);
}

uint32_t fp_expand_imm(uint32_t imm8)
{
    uint32_t sign = (imm8 >> 7) & 1u, b6 = (imm8 >> 6) & 1u;
    uint32_t exp = ((b6 ^ 1u) << 7) | (b6 ? 0x7Cu : 0u) | ((imm8 >> 4) & 3u);
    return (sign << 31) | (exp << 23) | ((imm8 & 15u) << 19);
}
