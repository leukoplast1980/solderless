/*
 * Solderless - ARMv7-M CPU core (Cortex-M4 integer core, NVIC, SCB, SysTick).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Instruction encodings and pseudocode follow the ARMv7-M Architecture
 * Reference Manual (DDI 0403E), chapter A5 (encodings), A7 (instructions)
 * and B1 (exception model). Section names are given where it helps.
 *
 * The DSP extension and the FPU are not implemented yet: their encodings
 * raise a UsageFault (UNDEFINSTR, or NOCP while CPACR denies access).
 */
#include "armv7m.h"

#include <string.h>

#define BIT(x, n)        (((x) >> (n)) & 1u)
#define FIELD(x, hi, lo) (((x) >> (lo)) & ((1u << ((hi) - (lo) + 1)) - 1u))

/* SCB register bits */
#define CCR_NONBASETHRDENA (1u << 0)
#define CCR_USERSETMPEND   (1u << 1)
#define CCR_UNALIGN_TRP    (1u << 3)
#define CCR_DIV_0_TRP      (1u << 4)
#define CCR_STKALIGN       (1u << 9)
#define CCR_MASK           0x31Bu

#define SCR_SLEEPONEXIT    (1u << 1)
#define SCR_SEVONPEND      (1u << 4)

#define SHCSR_MEMFAULTENA  (1u << 16)
#define SHCSR_BUSFAULTENA  (1u << 17)
#define SHCSR_USGFAULTENA  (1u << 18)

#define SYST_ENABLE        (1u << 0)
#define SYST_TICKINT       (1u << 1)
#define SYST_CLKSOURCE     (1u << 2)
#define SYST_COUNTFLAG     (1u << 16)

#define CONTROL_NPRIV      (1u << 0)
#define CONTROL_SPSEL      (1u << 1)

#define DEMCR_TRCENA       (1u << 24)
#define DWT_CYCCNTENA      (1u << 0)

/* Shift types */
enum { SR_LSL, SR_LSR, SR_ASR, SR_ROR, SR_RRX };

static inline uint32_t ror32(uint32_t v, unsigned n)
{
    n &= 31;
    return n ? (v >> n) | (v << (32 - n)) : v;
}

static inline uint32_t sext(uint32_t v, unsigned bits)
{
    if (bits >= 32) return v;
    uint32_t m = 1u << (bits - 1);
    v &= (1u << bits) - 1u;
    return (v ^ m) - m;
}

static inline int popcount16(uint32_t v)
{
    return __builtin_popcount(v & 0xFFFFu);
}

/* ====================================================================== */
/* State helpers                                                          */
/* ====================================================================== */

static inline int in_handler(const struct cm_cpu *c) { return c->ipsr != 0; }

static inline int privileged(const struct cm_cpu *c)
{
    return in_handler(c) || !(c->control & CONTROL_NPRIV);
}

static inline int psp_active(const struct cm_cpu *c)
{
    return !in_handler(c) && (c->control & CONTROL_SPSEL);
}

uint32_t cm_msp(const struct cm_cpu *c) { return psp_active(c) ? c->sp_main : c->r[13]; }
uint32_t cm_psp(const struct cm_cpu *c) { return psp_active(c) ? c->r[13] : c->sp_proc; }

/* Switches the active stack pointer after a change of mode or SPSEL.
 * was_psp tells which one r[13] held before the change. */
static void sp_switch(struct cm_cpu *c, int was_psp)
{
    int now_psp = psp_active(c);
    if (was_psp == now_psp) return;
    if (was_psp) { c->sp_proc = c->r[13]; c->r[13] = c->sp_main; }
    else         { c->sp_main = c->r[13]; c->r[13] = c->sp_proc; }
}

static void set_msp(struct cm_cpu *c, uint32_t v)
{
    v &= ~3u;
    if (psp_active(c)) c->sp_main = v; else c->r[13] = v;
}

static void set_psp(struct cm_cpu *c, uint32_t v)
{
    v &= ~3u;
    if (psp_active(c)) c->r[13] = v; else c->sp_proc = v;
}

uint32_t cm_xpsr(const struct cm_cpu *c)
{
    uint32_t it = c->it;
    return (c->n << 31) | (c->z << 30) | (c->c << 29) | (c->v << 28) | (c->q << 27)
         | ((it & 3u) << 25) | (c->tbit << 24) | ((c->ge & 0xFu) << 16)
         | (((it >> 2) & 0x3Fu) << 10) | (c->ipsr & 0x1FFu);
}

static inline int in_it(const struct cm_cpu *c) { return (c->it & 0xFu) != 0; }

static inline void it_advance(struct cm_cpu *c)
{
    if ((c->it & 7u) == 0) c->it = 0;
    else c->it = (c->it & 0xE0u) | ((c->it << 1) & 0x1Fu);
}

static int cond_pass(const struct cm_cpu *c, uint32_t cond)
{
    int r;
    switch (cond >> 1) {
    case 0: r = c->z; break;
    case 1: r = c->c; break;
    case 2: r = c->n; break;
    case 3: r = c->v; break;
    case 4: r = c->c && !c->z; break;
    case 5: r = c->n == c->v; break;
    case 6: r = c->n == c->v && !c->z; break;
    default: return 1;
    }
    return (cond & 1) ? !r : r;
}

/* ====================================================================== */
/* Arithmetic helpers (A2.2)                                              */
/* ====================================================================== */

static inline uint32_t add_with_carry(uint32_t x, uint32_t y, uint32_t cin,
                                      uint32_t *cout, uint32_t *vout)
{
    uint64_t us = (uint64_t)x + y + cin;
    int64_t ss = (int64_t)(int32_t)x + (int32_t)y + (int64_t)cin;
    uint32_t r = (uint32_t)us;
    *cout = (uint32_t)(us >> 32) & 1u;
    *vout = (int64_t)(int32_t)r != ss;
    return r;
}

static uint32_t shift_c(uint32_t v, int type, uint32_t n, uint32_t cin, uint32_t *cout)
{
    if (type == SR_RRX) {
        *cout = v & 1u;
        return (cin << 31) | (v >> 1);
    }
    if (n == 0) { *cout = cin; return v; }
    switch (type) {
    case SR_LSL:
        if (n < 32) { *cout = (v >> (32 - n)) & 1u; return v << n; }
        *cout = n == 32 ? (v & 1u) : 0;
        return 0;
    case SR_LSR:
        if (n < 32) { *cout = (v >> (n - 1)) & 1u; return v >> n; }
        *cout = n == 32 ? (v >> 31) : 0;
        return 0;
    case SR_ASR:
        if (n < 32) { *cout = ((uint32_t)((int32_t)v >> (n - 1))) & 1u; return (uint32_t)((int32_t)v >> n); }
        *cout = v >> 31;
        return (uint32_t)((int32_t)v >> 31);
    default: { /* ROR */
        uint32_t r = ror32(v, n);
        *cout = r >> 31;
        return r;
    }
    }
}

static void decode_imm_shift(uint32_t type, uint32_t imm5, int *t, uint32_t *n)
{
    switch (type) {
    case 0: *t = SR_LSL; *n = imm5; break;
    case 1: *t = SR_LSR; *n = imm5 ? imm5 : 32; break;
    case 2: *t = SR_ASR; *n = imm5 ? imm5 : 32; break;
    default:
        if (imm5 == 0) { *t = SR_RRX; *n = 1; }
        else { *t = SR_ROR; *n = imm5; }
    }
}

static uint32_t thumb_expand_imm_c(uint32_t imm12, uint32_t cin, uint32_t *cout)
{
    uint32_t imm8 = imm12 & 0xFFu;
    if ((imm12 >> 10) == 0) {
        *cout = cin;
        switch ((imm12 >> 8) & 3u) {
        case 0: return imm8;
        case 1: return imm8 | (imm8 << 16);
        case 2: return (imm8 << 8) | (imm8 << 24);
        default: return imm8 | (imm8 << 8) | (imm8 << 16) | (imm8 << 24);
        }
    }
    uint32_t r = ror32(0x80u | (imm12 & 0x7Fu), imm12 >> 7);
    *cout = r >> 31;
    return r;
}

/* ====================================================================== */
/* Memory                                                                 */
/* ====================================================================== */

static inline struct cm_region *region_for(const struct cm_bus *b, uint32_t a, uint32_t n)
{
    struct cm_region *r = b->map[a >> 28][0];
    if (r && a - r->base <= r->size - n) return r;
    r = b->map[a >> 28][1];
    if (r && a - r->base <= r->size - n) return r;
    return NULL;
}

static inline uint32_t get_le(const uint8_t *p, int n)
{
    switch (n) {
    case 1: return p[0];
    case 2: return (uint32_t)p[0] | ((uint32_t)p[1] << 8);
    default: { uint32_t v; memcpy(&v, p, 4); return v; }
    }
}

static inline void put_le(uint8_t *p, int n, uint32_t v)
{
    switch (n) {
    case 1: p[0] = (uint8_t)v; break;
    case 2: p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); break;
    default: memcpy(p, &v, 4); break;
    }
}

static int scs_read(struct cm_cpu *c, uint32_t a, uint32_t *v, int effects);
static int scs_write(struct cm_cpu *c, uint32_t a, uint32_t v, uint32_t mask);

static inline int is_ppb_internal(uint32_t a) { return a >= 0xE0000000u && a < 0xE0040000u; }

static inline int is_bitband(uint32_t a)
{
    return (a >= 0x22000000u && a < 0x24000000u) || (a >= 0x42000000u && a < 0x44000000u);
}

static int bus_read(struct cm_cpu *c, uint32_t a, int n, uint32_t *v, int priv);
static int bus_write(struct cm_cpu *c, uint32_t a, int n, uint32_t v, int priv);

static int bus_read(struct cm_cpu *c, uint32_t a, int n, uint32_t *v, int priv)
{
    struct cm_region *r = region_for(c->bus, a, (uint32_t)n);
    if (r) { *v = get_le(r->mem + (a - r->base), n); return 0; }

    if (a & (uint32_t)(n - 1)) {            /* unaligned and not in a region: bytes */
        uint32_t out = 0;
        for (int i = 0; i < n; i++) {
            uint32_t b;
            if (bus_read(c, a + (uint32_t)i, 1, &b, priv)) return 1;
            out |= b << (8 * i);
        }
        *v = out;
        return 0;
    }
    if (is_ppb_internal(a)) {
        if (!priv) return 1;
        uint32_t w;
        if (scs_read(c, a & ~3u, &w, 1)) return 1;
        unsigned sh = (a & 3u) * 8u;
        *v = n == 4 ? w : (w >> sh) & ((1u << (8 * n)) - 1u);
        return 0;
    }
    if (is_bitband(a)) {
        uint32_t alias = a & 0xF2000000u;              /* 0x22.. or 0x42.. */
        uint32_t off = a - alias;
        uint32_t target = (alias - 0x02000000u) + ((off >> 5) & ~3u);
        uint32_t w;
        if (bus_read(c, target, 4, &w, priv)) return 1;
        *v = (w >> ((off >> 2) & 31u)) & 1u;
        return 0;
    }
    return c->bus->mmio_read(c->bus->ctx, a, n, v) ? 1 : 0;
}

static int bus_write(struct cm_cpu *c, uint32_t a, int n, uint32_t v, int priv)
{
    struct cm_region *r = region_for(c->bus, a, (uint32_t)n);
    if (r) {
        if (!r->writable) {
            c->mem_changes++;
            return c->bus->rom_write ? c->bus->rom_write(c->bus->ctx, a, n, v) : 1;
        }
        uint8_t *p = r->mem + (a - r->base);
        if (get_le(p, n) != (n == 4 ? v : v & ((1u << (8 * n)) - 1u))) c->mem_changes++;
        put_le(p, n, v);
        return 0;
    }
    c->mem_changes++;                          /* peripheral or system register write */
    if (a & (uint32_t)(n - 1)) {
        for (int i = 0; i < n; i++)
            if (bus_write(c, a + (uint32_t)i, 1, v >> (8 * i), priv)) return 1;
        return 0;
    }
    if (is_ppb_internal(a)) {
        unsigned sh = (a & 3u) * 8u;
        uint32_t mask = (n == 4 ? 0xFFFFFFFFu : ((1u << (8 * n)) - 1u)) << sh;
        if (!priv) {
            /* Unprivileged software may use the STIR when CCR.USERSETMPEND is set */
            if ((a & ~3u) == 0xE000EF00u && (c->ccr & CCR_USERSETMPEND))
                return scs_write(c, 0xE000EF00u, v << sh, mask);
            return 1;
        }
        return scs_write(c, a & ~3u, v << sh, mask);
    }
    if (is_bitband(a)) {
        uint32_t alias = a & 0xF2000000u;
        uint32_t off = a - alias;
        uint32_t target = (alias - 0x02000000u) + ((off >> 5) & ~3u);
        uint32_t bit = (off >> 2) & 31u, w;
        if (bus_read(c, target, 4, &w, priv)) return 1;
        w = (v & 1u) ? (w | (1u << bit)) : (w & ~(1u << bit));
        return bus_write(c, target, 4, w, priv);
    }
    return c->bus->mmio_write(c->bus->ctx, a, n, v) ? 1 : 0;
}

size_t cm_peek(const struct cm_cpu *c, uint32_t addr, uint8_t *buf, size_t len)
{
    for (size_t i = 0; i < len; i++) {
        uint32_t a = addr + (uint32_t)i;
        struct cm_region *r = region_for(c->bus, a, 1);
        if (r) { buf[i] = r->mem[a - r->base]; continue; }
        uint32_t w = 0;
        if (is_ppb_internal(a)) cm_scs_peek((struct cm_cpu *)c, a & ~3u, &w);
        else if (c->bus->mmio_peek) c->bus->mmio_peek(c->bus->ctx, a & ~3u, &w);
        buf[i] = (uint8_t)(w >> (8 * (a & 3u)));
    }
    return len;
}

/* ====================================================================== */
/* Exceptions (B1.5)                                                      */
/* ====================================================================== */

static inline int num_exc(const struct cm_cpu *c) { return 16 + c->num_irq; }

static int exc_prio(const struct cm_cpu *c, int e)
{
    switch (e) {
    case CM_EXC_RESET: return -3;
    case CM_EXC_NMI: return -2;
    case CM_EXC_HARDFAULT: return -1;
    default: return c->prio[e];
    }
}

static int group_prio(const struct cm_cpu *c, int p)
{
    if (p < 0) return p;
    uint32_t sub = c->prigroup + 1u;          /* low bits that are subpriority */
    return (int)((uint32_t)p & ~((1u << sub) - 1u) & 0xFFu);
}

static int exec_priority_ex(const struct cm_cpu *c, int with_primask)
{
    int highest = 256;
    for (int e = 1; e < num_exc(c); e++) {
        if (!c->active[e]) continue;
        int g = group_prio(c, exc_prio(c, e));
        if (g < highest) highest = g;
    }
    int boost = 256;
    if (with_primask && c->primask) boost = 0;
    if (c->basepri) {
        int b = group_prio(c, (int)c->basepri);
        if (b < boost) boost = b;
    }
    if (c->faultmask) boost = -1;
    return highest < boost ? highest : boost;
}

static int exec_priority(const struct cm_cpu *c) { return exec_priority_ex(c, 1); }

/* Highest-priority pending exception that is enabled; 0 if none. */
static int highest_pending(const struct cm_cpu *c, int *group_out)
{
    int best = 0, best_p = 0x7FFF;
    for (int e = 2; e < num_exc(c); e++) {
        if (!c->pending[e]) continue;
        if (e >= 16 && !c->enabled[e]) continue;
        int p = exc_prio(c, e);
        int key = group_prio(c, p) * 512 + p * 2;   /* group, then full priority; ties: lowest number */
        if (p < 0) key = p * 1024;
        if (!best || key < best_p) { best = e; best_p = key; }
    }
    if (best && group_out) *group_out = group_prio(c, exc_prio(c, best));
    return best;
}

int cm_wake_pending(const struct cm_cpu *c)
{
    int g;
    if (!highest_pending(c, &g)) return 0;
    return g < exec_priority_ex(c, 0);
}

void cm_pend(struct cm_cpu *c, int exc)
{
    if (exc <= 0 || exc >= num_exc(c)) return;
    if (!c->pending[exc]) {
        c->pending[exc] = 1;
        if ((c->scr & SCR_SEVONPEND) && exc >= 16) c->event_reg = 1;
    }
    c->check_irq = 1;
}

void cm_set_irq(struct cm_cpu *c, int irq, int level)
{
    int e = CM_EXC_IRQ0 + irq;
    if (irq < 0 || e >= num_exc(c)) return;
    level = level ? 1 : 0;
    if (c->line[e] == level) return;
    c->line[e] = (uint8_t)level;
    if (level && !c->active[e]) cm_pend(c, e);
}

void cm_wake(struct cm_cpu *c)
{
    c->event_reg = 1;
    c->sleeping = 0;
}

static void lockup(struct cm_cpu *c)
{
    c->lockup = 1;
    c->stop_batch = 1;
}

/* Pushes the basic frame and enters the handler for exception exc. */
static void take_exception(struct cm_cpu *c, int exc, uint32_t ret_addr)
{
    int was_psp = psp_active(c);
    uint32_t sp = c->r[13];
    uint32_t align = (c->ccr & CCR_STKALIGN) ? (sp >> 2) & 1u : 0u;
    uint32_t frame = (sp - 0x20u) & ~(align << 2);
    uint32_t words[8] = {
        c->r[0], c->r[1], c->r[2], c->r[3], c->r[12], c->r[14], ret_addr,
        (cm_xpsr(c) & ~(1u << 9)) | (align << 9),
    };
    int stack_err = 0;
    for (int i = 0; i < 8; i++)
        if (bus_write(c, frame + 4u * (uint32_t)i, 4, words[i], 1)) stack_err = 1;
    c->r[13] = frame;

    if (in_handler(c)) c->r[14] = 0xFFFFFFF1u;
    else c->r[14] = was_psp ? 0xFFFFFFFDu : 0xFFFFFFF9u;

    c->ipsr = (uint32_t)exc;
    c->control &= ~CONTROL_SPSEL;
    sp_switch(c, was_psp);
    c->it = 0;
    c->pending[exc] = 0;
    c->active[exc] = 1;
    c->excl_valid = 0;
    c->event_reg = 1;
    c->sleeping = 0;
    c->check_irq = 1;

    uint32_t vec;
    if (bus_read(c, (c->vtor & ~0x7Fu) + 4u * (uint32_t)exc, 4, &vec, 1)) {
        /* Vector table read failed: HardFault (VECTTBL) instead */
        c->hfsr |= CM_HFSR_VECTTBL;
        c->active[exc] = 0;
        if (exc == CM_EXC_HARDFAULT
            || bus_read(c, (c->vtor & ~0x7Fu) + 4u * CM_EXC_HARDFAULT, 4, &vec, 1)) {
            lockup(c);
            return;
        }
        exc = CM_EXC_HARDFAULT;
        c->active[exc] = 1;
        c->ipsr = (uint32_t)exc;
    }
    c->tbit = vec & 1u;
    c->r[15] = vec & ~1u;

    if (stack_err) {
        c->cfsr |= CM_BFSR_STKERR;
        c->hfsr |= CM_HFSR_FORCED;
        if (exc == CM_EXC_HARDFAULT) lockup(c);
        else cm_pend(c, CM_EXC_HARDFAULT);
    }
}

/* Raises a synchronous fault for the current instruction. */
static void fault(struct cm_cpu *c, int exc, uint32_t cfsr_bits)
{
    c->cfsr |= cfsr_bits;
    if (!c->sync_exc) {
        c->sync_exc = exc;
        c->sync_ret = c->r[15];
    }
}

static void bus_fault_data(struct cm_cpu *c, uint32_t addr)
{
    c->bfar = addr;
    fault(c, CM_EXC_BUSFAULT, CM_BFSR_PRECISERR | CM_BFSR_BFARVALID);
}

static void usage_fault(struct cm_cpu *c, uint32_t bit) { fault(c, CM_EXC_USAGEFAULT, bit); }

static void undefined(struct cm_cpu *c) { usage_fault(c, CM_UFSR_UNDEFINSTR); }

/* Takes the synchronous exception raised by the last instruction,
 * escalating to HardFault where B1.5.15 says so. */
static void take_sync(struct cm_cpu *c)
{
    int exc = c->sync_exc;
    uint32_t ret = c->sync_ret;
    c->sync_exc = 0;
    int cur = exec_priority(c);

    if (exc == CM_EXC_MEMMANAGE || exc == CM_EXC_BUSFAULT || exc == CM_EXC_USAGEFAULT) {
        uint32_t ena = exc == CM_EXC_MEMMANAGE ? SHCSR_MEMFAULTENA
                     : exc == CM_EXC_BUSFAULT ? SHCSR_BUSFAULTENA : SHCSR_USGFAULTENA;
        c->fault_pc = ret;
        if (!(c->shcsr_ena & ena) || group_prio(c, exc_prio(c, exc)) >= cur) {
            c->hfsr |= CM_HFSR_FORCED;
            exc = CM_EXC_HARDFAULT;
        }
    } else if (exc == CM_EXC_SVCALL || exc == CM_EXC_DEBUGMON) {
        if (group_prio(c, exc_prio(c, exc)) >= cur) {
            c->hfsr |= CM_HFSR_FORCED;
            exc = CM_EXC_HARDFAULT;
        }
    }
    if (exc == CM_EXC_HARDFAULT) {
        c->fault_pc = ret;
        if (cur <= -1) { lockup(c); return; }
    }
    take_exception(c, exc, ret);
}

static int nested_activation(const struct cm_cpu *c)
{
    int n = 0;
    for (int e = 1; e < num_exc(c); e++) n += c->active[e];
    return n;
}

/* Exception return (B1.5.8), after the instruction that loaded EXC_RETURN. */
static void exception_return(struct cm_cpu *c, uint32_t exc_return)
{
    int ret_exc = (int)c->ipsr;
    uint32_t kind = exc_return & 0x0FFFFFFFu;
    int to_thread, use_psp;

    if (!c->active[ret_exc]) goto invpc;
    switch (kind) {
    case 0x0FFFFFF1u:
        if (nested_activation(c) == 1) goto invpc;
        to_thread = 0; use_psp = 0; break;
    case 0x0FFFFFF9u:
        if (nested_activation(c) != 1 && !(c->ccr & CCR_NONBASETHRDENA)) goto invpc;
        to_thread = 1; use_psp = 0; break;
    case 0x0FFFFFFDu:
        if (nested_activation(c) != 1 && !(c->ccr & CCR_NONBASETHRDENA)) goto invpc;
        to_thread = 1; use_psp = 1; break;
    default:
        goto invpc;
    }

    c->active[ret_exc] = 0;
    if (ret_exc != CM_EXC_NMI) c->faultmask = 0;
    if (ret_exc >= 16 && c->line[ret_exc]) c->pending[ret_exc] = 1;

    {
        int was_psp = psp_active(c);
        uint32_t frame = use_psp ? cm_psp(c) : cm_msp(c);
        uint32_t w[8];
        int err = 0;
        for (int i = 0; i < 8; i++)
            if (bus_read(c, frame + 4u * (uint32_t)i, 4, &w[i], 1)) err = 1;

        /* Switch mode and stack */
        if (to_thread) c->ipsr = 0;
        else c->ipsr = w[7] & 0x1FFu;
        if (use_psp) c->control |= CONTROL_SPSEL; else c->control &= ~CONTROL_SPSEL;
        sp_switch(c, was_psp);

        if (err) {
            c->cfsr |= CM_BFSR_UNSTKERR;
            c->hfsr |= CM_HFSR_FORCED;
            c->check_irq = 1;
            cm_pend(c, CM_EXC_HARDFAULT);
            return;
        }
        uint32_t newsp = frame + 0x20u + (((w[7] >> 9) & 1u) << 2);
        c->r[13] = newsp & ~3u;
        c->r[0] = w[0]; c->r[1] = w[1]; c->r[2] = w[2]; c->r[3] = w[3];
        c->r[12] = w[4]; c->r[14] = w[5];
        c->r[15] = w[6] & ~1u;
        uint32_t psr = w[7];
        c->n = psr >> 31; c->z = (psr >> 30) & 1u; c->c = (psr >> 29) & 1u;
        c->v = (psr >> 28) & 1u; c->q = (psr >> 27) & 1u;
        c->ge = (psr >> 16) & 0xFu;
        c->it = ((psr >> 25) & 3u) | (((psr >> 10) & 0x3Fu) << 2);
        c->tbit = (psr >> 24) & 1u;
        uint32_t stacked_ipsr = psr & 0x1FFu;
        if ((to_thread && stacked_ipsr != 0) || (!to_thread && stacked_ipsr == 0)) {
            /* Integrity check failed: UsageFault INVPC on the return target */
            c->cfsr |= CM_UFSR_INVPC;
            c->sync_exc = CM_EXC_USAGEFAULT;
            c->sync_ret = c->r[15];
            take_sync(c);
            return;
        }
    }
    c->excl_valid = 0;
    c->event_reg = 1;
    c->check_irq = 1;
    if (to_thread && (c->scr & SCR_SLEEPONEXIT)) c->sleeping = 1;
    return;

invpc:
    /* The return is abandoned: UsageFault INVPC with the EXC_RETURN value
     * as the faulting address, taken from the current handler. */
    c->cfsr |= CM_UFSR_INVPC;
    c->sync_exc = CM_EXC_USAGEFAULT;
    c->sync_ret = exc_return;
    take_sync(c);
}

static int try_take_pending(struct cm_cpu *c)
{
    int g;
    int e = highest_pending(c, &g);
    if (!e || g >= exec_priority(c)) return 0;
    take_exception(c, e, c->r[15]);
    return 1;
}

/* ====================================================================== */
/* SysTick and DWT counters                                               */
/* ====================================================================== */

static void systick_ticks(struct cm_cpu *c, uint64_t t)
{
    while (t) {
        if (c->syst_cvr == 0) {
            c->syst_cvr = c->syst_rvr;    /* reload on this tick */
            t--;
            if (c->syst_rvr == 0) return;
            continue;
        }
        if (t < c->syst_cvr) { c->syst_cvr -= (uint32_t)t; return; }
        t -= c->syst_cvr;
        c->syst_cvr = 0;
        c->syst_csr |= SYST_COUNTFLAG;
        if (c->syst_csr & SYST_TICKINT) cm_pend(c, CM_EXC_SYSTICK);
        if (t && c->syst_rvr) {
            uint64_t period = (uint64_t)c->syst_rvr + 1u;
            if (t >= period) {
                t %= period;
                c->syst_csr |= SYST_COUNTFLAG;
                if (c->syst_csr & SYST_TICKINT) cm_pend(c, CM_EXC_SYSTICK);
            }
        }
    }
}

static void counters_advance(struct cm_cpu *c, uint64_t cycles)
{
    if (c->syst_csr & SYST_ENABLE) {
        if (c->syst_csr & SYST_CLKSOURCE) {
            systick_ticks(c, cycles);
        } else {
            uint64_t acc = c->syst_acc + cycles;
            systick_ticks(c, acc / c->syst_ext_div);
            c->syst_acc = (uint32_t)(acc % c->syst_ext_div);
        }
    }
    if ((c->demcr & DEMCR_TRCENA) && (c->dwt_ctrl & DWT_CYCCNTENA))
        c->dwt_cyccnt += (uint32_t)cycles;
}

void cm_sync_counters(struct cm_cpu *c)
{
    uint64_t d = c->cycles - c->synced;
    c->synced = c->cycles;
    if (d) counters_advance(c, d);
}

void cm_idle_cycles(struct cm_cpu *c, uint64_t cycles)
{
    cm_sync_counters(c);
    c->cycles += cycles;
    c->synced = c->cycles;
    counters_advance(c, cycles);
}

/* Cycles until SysTick next counts down to zero. That is an event whether
 * or not it raises the interrupt: software may be polling COUNTFLAG. */
uint64_t cm_cycles_to_systick(struct cm_cpu *c)
{
    if (!(c->syst_csr & SYST_ENABLE))
        return UINT64_MAX;
    uint64_t ticks = c->syst_cvr ? c->syst_cvr : (c->syst_rvr ? (uint64_t)c->syst_rvr + 1u : 0);
    if (!ticks) return UINT64_MAX;
    uint64_t pend = c->cycles - c->synced;
    uint64_t cyc = (c->syst_csr & SYST_CLKSOURCE) ? ticks
                 : ticks * c->syst_ext_div - c->syst_acc;
    return cyc > pend ? cyc - pend : 1;
}

/* ====================================================================== */
/* System Control Space (B3)                                              */
/* ====================================================================== */

static const uint32_t id_regs[] = {   /* 0xE000ED40..0xE000ED7C, Cortex-M4 */
    0x00000030u, 0x00000200u, 0x00100000u, 0x00000000u,
    0x00100030u, 0x00000000u, 0x01000000u, 0x00000000u,
    0x01101110u, 0x02111000u, 0x21112231u, 0x01111110u, 0x01310132u,
};

static uint32_t shcsr_read(const struct cm_cpu *c)
{
    return c->shcsr_ena
        | ((uint32_t)c->active[CM_EXC_MEMMANAGE] << 0)
        | ((uint32_t)c->active[CM_EXC_BUSFAULT] << 1)
        | ((uint32_t)c->active[CM_EXC_USAGEFAULT] << 3)
        | ((uint32_t)c->active[CM_EXC_SVCALL] << 7)
        | ((uint32_t)c->active[CM_EXC_DEBUGMON] << 8)
        | ((uint32_t)c->active[CM_EXC_PENDSV] << 10)
        | ((uint32_t)c->active[CM_EXC_SYSTICK] << 11)
        | ((uint32_t)c->pending[CM_EXC_USAGEFAULT] << 12)
        | ((uint32_t)c->pending[CM_EXC_MEMMANAGE] << 13)
        | ((uint32_t)c->pending[CM_EXC_BUSFAULT] << 14)
        | ((uint32_t)c->pending[CM_EXC_SVCALL] << 15);
}

static uint32_t icsr_read(const struct cm_cpu *c)
{
    uint32_t v = c->ipsr & 0x1FFu;
    int others = 0;
    for (int e = 1; e < num_exc(c); e++)
        if (c->active[e] && (uint32_t)e != c->ipsr) others = 1;
    if (!others) v |= 1u << 11;                    /* RETTOBASE */
    int g;
    int hp = highest_pending(c, &g);
    v |= ((uint32_t)hp & 0x1FFu) << 12;            /* VECTPENDING */
    for (int e = 16; e < num_exc(c); e++)
        if (c->pending[e]) { v |= 1u << 22; break; }   /* ISRPENDING */
    if (c->pending[CM_EXC_SYSTICK]) v |= 1u << 26;
    if (c->pending[CM_EXC_PENDSV]) v |= 1u << 28;
    if (c->pending[CM_EXC_NMI]) v |= 1u << 31;
    return v;
}

static uint32_t prio_word(const struct cm_cpu *c, int first)
{
    uint32_t v = 0;
    for (int i = 0; i < 4; i++) {
        int e = first + i;
        if (e < num_exc(c)) v |= (uint32_t)c->prio[e] << (8 * i);
    }
    return v;
}

static void prio_write(struct cm_cpu *c, int first, uint32_t v, uint32_t mask, int lo, int hi)
{
    uint8_t keep = (uint8_t)(0xFFu << (8 - c->prio_bits));
    for (int i = 0; i < 4; i++) {
        int e = first + i;
        if (!((mask >> (8 * i)) & 0xFFu)) continue;
        if (e < lo || e > hi || e >= num_exc(c)) continue;
        c->prio[e] = (uint8_t)(v >> (8 * i)) & keep;
    }
    c->check_irq = 1;
}

static int scs_read(struct cm_cpu *c, uint32_t a, uint32_t *v, int effects)
{
    cm_sync_counters(c);
    *v = 0;

    /* ITM */
    if (a < 0xE0001000u) {
        if (a < 0xE0000080u) *v = 1;               /* stimulus port ready */
        else if (a == 0xE0000E00u) *v = c->itm_ter;
        else if (a == 0xE0000E40u) *v = c->itm_tpr;
        else if (a == 0xE0000E80u) *v = c->itm_tcr;
        return 0;
    }
    /* DWT */
    if (a < 0xE0002000u) {
        uint32_t off = a - 0xE0001000u;
        if (off == 0) *v = c->dwt_ctrl;
        else if (off == 4) *v = c->dwt_cyccnt;
        else if (off == 0x1C) *v = c->r[15];       /* PCSR */
        else if (off < 0x20) *v = c->dwt_other[off / 4];
        return 0;
    }
    if (a < 0xE000E000u) return 0;                 /* FPB and reserved: RAZ */
    if (a >= 0xE000F000u) return 0;

    uint32_t off = a - 0xE000E000u;
    if (off >= 0x100 && off < 0x500) {             /* NVIC */
        uint32_t idx = (off & 0x7Fu) / 4u;
        if (off < 0x300 && off >= 0x100) {
            uint32_t base = 16 + idx * 32, bits = 0;
            for (uint32_t b = 0; b < 32; b++) {
                uint32_t e = base + b;
                if (e >= (uint32_t)num_exc(c)) break;
                int s = off < 0x200 ? c->enabled[e] : c->pending[e];
                if (s) bits |= 1u << b;
            }
            *v = bits;
            return 0;
        }
        if (off >= 0x300 && off < 0x320) {
            uint32_t base = 16 + idx * 32, bits = 0;
            for (uint32_t b = 0; b < 32; b++) {
                uint32_t e = base + b;
                if (e < (uint32_t)num_exc(c) && c->active[e]) bits |= 1u << b;
            }
            *v = bits;
            return 0;
        }
        if (off >= 0x400) *v = prio_word(c, 16 + (int)(off - 0x400));
        return 0;
    }
    if (off >= 0xD40 && off < 0xD80) {
        uint32_t i = (off - 0xD40) / 4u;
        *v = i < sizeof id_regs / sizeof id_regs[0] ? id_regs[i] : 0;
        return 0;
    }
    if (off >= 0xD9C && off < 0xDC0) {             /* MPU RBAR/RASR and aliases */
        uint32_t r = c->mpu_rnr & 7u;
        *v = (off & 4u) ? c->mpu_rasr[r] : (c->mpu_rbar[r] | r);
        return 0;
    }
    if (off >= 0xFD0) return 0;                    /* CoreSight IDs */

    switch (off) {
    case 0x004: *v = (uint32_t)((c->num_irq + 31) / 32 - 1); break;   /* ICTR */
    case 0x008: *v = c->actlr; break;
    case 0x010: *v = c->syst_csr; if (effects) c->syst_csr &= ~SYST_COUNTFLAG; break;
    case 0x014: *v = c->syst_rvr; break;
    case 0x018: *v = c->syst_cvr; break;
    case 0x01C: *v = c->syst_calib; break;
    case 0xD00: *v = 0x410FC241u; break;           /* CPUID: Cortex-M4 r0p1 */
    case 0xD04: *v = icsr_read(c); break;
    case 0xD08: *v = c->vtor; break;
    case 0xD0C: *v = 0xFA050000u | (c->prigroup << 8); break;
    case 0xD10: *v = c->scr; break;
    case 0xD14: *v = c->ccr; break;
    case 0xD18: *v = prio_word(c, 4); break;
    case 0xD1C: *v = prio_word(c, 8); break;
    case 0xD20: *v = prio_word(c, 12); break;
    case 0xD24: *v = shcsr_read(c); break;
    case 0xD28: *v = c->cfsr; break;
    case 0xD2C: *v = c->hfsr; break;
    case 0xD30: *v = c->dfsr; break;
    case 0xD34: *v = c->mmfar; break;
    case 0xD38: *v = c->bfar; break;
    case 0xD3C: *v = c->afsr; break;
    case 0xD88: *v = c->cpacr; break;
    case 0xD90: *v = 0x00000800u; break;           /* MPU_TYPE: 8 regions */
    case 0xD94: *v = c->mpu_ctrl; break;
    case 0xD98: *v = c->mpu_rnr; break;
    case 0xDF0: *v = c->dhcsr; break;
    case 0xDFC: *v = c->demcr; break;
    case 0xF34: *v = c->fpccr; break;
    case 0xF38: *v = c->fpcar; break;
    case 0xF3C: *v = c->fpdscr; break;
    case 0xF40: *v = 0x10110021u; break;           /* MVFR0 */
    case 0xF44: *v = 0x11000011u; break;           /* MVFR1 */
    default: break;
    }
    return 0;
}

int cm_scs_peek(struct cm_cpu *c, uint32_t addr, uint32_t *value)
{
    return scs_read(c, addr & ~3u, value, 0);
}

static inline uint32_t merge(uint32_t old, uint32_t v, uint32_t mask)
{
    return (old & ~mask) | (v & mask);
}

static void nvic_bits_write(struct cm_cpu *c, uint32_t off, uint32_t v)
{
    uint32_t idx = (off & 0x7Fu) / 4u;
    uint32_t base = 16 + idx * 32;
    for (uint32_t b = 0; b < 32; b++) {
        if (!((v >> b) & 1u)) continue;
        uint32_t e = base + b;
        if (e >= (uint32_t)num_exc(c)) break;
        if (off < 0x180) c->enabled[e] = 1;
        else if (off < 0x200) c->enabled[e] = 0;
        else if (off < 0x280) c->pending[e] = 1;
        else c->pending[e] = c->line[e] && !c->active[e];   /* level input re-pends */
    }
    c->check_irq = 1;
}

static int scs_write(struct cm_cpu *c, uint32_t a, uint32_t v, uint32_t mask)
{
    cm_sync_counters(c);

    if (a < 0xE0001000u) {                         /* ITM */
        if (a < 0xE0000080u) {
            int port = (int)((a - 0xE0000000u) / 4u);
            if ((c->itm_tcr & 1u) && (c->itm_ter >> port) & 1u && c->on_itm) {
                int size = mask == 0xFFFFFFFFu ? 4 : (__builtin_popcount(mask) / 8);
                unsigned sh = (unsigned)__builtin_ctz(mask);
                c->on_itm(c->hook_ctx, port, v >> sh, size);
            }
        } else if (a == 0xE0000E00u) c->itm_ter = merge(c->itm_ter, v, mask);
        else if (a == 0xE0000E40u) c->itm_tpr = merge(c->itm_tpr, v, mask) & 0xFu;
        else if (a == 0xE0000E80u) c->itm_tcr = merge(c->itm_tcr, v, mask);
        return 0;
    }
    if (a < 0xE0002000u) {                         /* DWT */
        uint32_t off = a - 0xE0001000u;
        if (off == 0) c->dwt_ctrl = (c->dwt_ctrl & 0xF0000000u) | (merge(c->dwt_ctrl, v, mask) & 0x0FFFFFFFu);
        else if (off == 4) c->dwt_cyccnt = merge(c->dwt_cyccnt, v, mask);
        else if (off < 0x20 && off != 0x1C) c->dwt_other[off / 4] = merge(c->dwt_other[off / 4], v, mask);
        return 0;
    }
    if (a < 0xE000E000u || a >= 0xE000F000u) return 0;

    uint32_t off = a - 0xE000E000u;
    if (off >= 0x100 && off < 0x300) { nvic_bits_write(c, off, v & mask); return 0; }
    if (off >= 0x300 && off < 0x400) return 0;     /* IABR: read-only */
    if (off >= 0x400 && off < 0x500) {
        prio_write(c, 16 + (int)(off - 0x400), v, mask, 16, num_exc(c) - 1);
        return 0;
    }
    if (off >= 0xD9C && off < 0xDC0) {
        uint32_t r = c->mpu_rnr & 7u;
        if (off & 4u) c->mpu_rasr[r] = merge(c->mpu_rasr[r], v, mask);
        else {
            uint32_t nv = merge(c->mpu_rbar[r], v, mask);
            if (nv & 0x10u) { r = nv & 7u; c->mpu_rnr = r; }   /* VALID: select region */
            c->mpu_rbar[r] = nv & ~0x1Fu;
        }
        return 0;
    }

    switch (off) {
    case 0x008: c->actlr = merge(c->actlr, v, mask) & 0x307u; break;
    case 0x010: {
        uint32_t nv = merge(c->syst_csr, v, mask) & 7u;
        c->syst_csr = (c->syst_csr & SYST_COUNTFLAG) | nv;
        break;
    }
    case 0x014: c->syst_rvr = merge(c->syst_rvr, v, mask) & 0xFFFFFFu; break;
    case 0x018:                                     /* any write clears */
        c->syst_cvr = 0;
        c->syst_acc = 0;
        c->syst_csr &= ~SYST_COUNTFLAG;
        break;
    case 0xD04: {
        uint32_t w = v & mask;
        if (w & (1u << 31)) cm_pend(c, CM_EXC_NMI);
        if (w & (1u << 28)) cm_pend(c, CM_EXC_PENDSV);
        if (w & (1u << 27)) c->pending[CM_EXC_PENDSV] = 0;
        if (w & (1u << 26)) cm_pend(c, CM_EXC_SYSTICK);
        if (w & (1u << 25)) c->pending[CM_EXC_SYSTICK] = 0;
        break;
    }
    case 0xD08: c->vtor = merge(c->vtor, v, mask) & 0xFFFFFF80u; break;
    case 0xD0C:
        if (mask == 0xFFFFFFFFu && (v >> 16) == 0x05FAu) {
            c->prigroup = (v >> 8) & 7u;
            c->check_irq = 1;
            if ((v & 4u) && c->on_sysresetreq) {
                c->stop_batch = 1;
                c->on_sysresetreq(c->hook_ctx);
            }
        }
        break;
    case 0xD10: c->scr = merge(c->scr, v, mask) & 0x16u; break;
    case 0xD14: c->ccr = (merge(c->ccr, v, mask) & CCR_MASK) | CCR_STKALIGN; break;
    case 0xD18: prio_write(c, 4, v, mask, 4, 6); break;
    case 0xD1C: prio_write(c, 8, v, mask, 11, 11); break;
    case 0xD20: prio_write(c, 12, v, mask, 12, 15); break;
    case 0xD24: {
        uint32_t w = merge(shcsr_read(c), v, mask);
        c->shcsr_ena = w & (SHCSR_MEMFAULTENA | SHCSR_BUSFAULTENA | SHCSR_USGFAULTENA);
        c->active[CM_EXC_MEMMANAGE] = (uint8_t)BIT(w, 0);
        c->active[CM_EXC_BUSFAULT] = (uint8_t)BIT(w, 1);
        c->active[CM_EXC_USAGEFAULT] = (uint8_t)BIT(w, 3);
        c->active[CM_EXC_SVCALL] = (uint8_t)BIT(w, 7);
        c->active[CM_EXC_DEBUGMON] = (uint8_t)BIT(w, 8);
        c->active[CM_EXC_PENDSV] = (uint8_t)BIT(w, 10);
        c->active[CM_EXC_SYSTICK] = (uint8_t)BIT(w, 11);
        c->pending[CM_EXC_USAGEFAULT] = (uint8_t)BIT(w, 12);
        c->pending[CM_EXC_MEMMANAGE] = (uint8_t)BIT(w, 13);
        c->pending[CM_EXC_BUSFAULT] = (uint8_t)BIT(w, 14);
        c->pending[CM_EXC_SVCALL] = (uint8_t)BIT(w, 15);
        c->check_irq = 1;
        break;
    }
    case 0xD28: c->cfsr &= ~(v & mask); break;     /* write 1 to clear */
    case 0xD2C: c->hfsr &= ~(v & mask); break;
    case 0xD30: c->dfsr &= ~(v & mask); break;
    case 0xD34: c->mmfar = merge(c->mmfar, v, mask); break;
    case 0xD38: c->bfar = merge(c->bfar, v, mask); break;
    case 0xD3C: c->afsr &= ~(v & mask); break;
    case 0xD88: c->cpacr = merge(c->cpacr, v, mask) & 0x00F00000u; break;
    case 0xD94: c->mpu_ctrl = merge(c->mpu_ctrl, v, mask) & 7u; break;
    case 0xD98: c->mpu_rnr = merge(c->mpu_rnr, v, mask) & 0xFFu; break;
    case 0xDF0:
        if (mask == 0xFFFFFFFFu && (v >> 16) == 0xA05Fu) c->dhcsr = v & 0x2Fu;
        break;
    case 0xDFC: c->demcr = merge(c->demcr, v, mask) & 0x010F07F1u; break;
    case 0xF00: {                                   /* STIR */
        uint32_t irq = (v & mask) & 0x1FFu;
        if ((int)irq < c->num_irq) cm_pend(c, CM_EXC_IRQ0 + (int)irq);
        break;
    }
    case 0xF34: c->fpccr = merge(c->fpccr, v, mask) & 0xC0000001u; break;  /* ASPEN, LSPEN, LSPACT */
    case 0xF38: c->fpcar = merge(c->fpcar, v, mask) & ~7u; break;
    case 0xF3C: c->fpdscr = merge(c->fpdscr, v, mask) & 0x07C00000u; break;
    default: break;
    }
    return 0;
}

/* ====================================================================== */
/* Init and reset                                                         */
/* ====================================================================== */

void cm_init(struct cm_cpu *c, struct cm_bus *bus, int num_irq, int prio_bits)
{
    memset(c, 0, sizeof *c);
    c->bus = bus;
    c->num_irq = num_irq > CM_MAX_EXC - 16 ? CM_MAX_EXC - 16 : num_irq;
    c->prio_bits = prio_bits;
    c->syst_ext_div = 8;
    c->syst_calib = 0x40000000u;
}

void cm_reset(struct cm_cpu *c)
{
    struct cm_bus *bus = c->bus;
    int num_irq = c->num_irq, prio_bits = c->prio_bits;
    uint32_t ext_div = c->syst_ext_div, calib = c->syst_calib;
    void *hook_ctx = c->hook_ctx;
    void (*rst)(void *) = c->on_sysresetreq;
    void (*itm)(void *, int, uint32_t, int) = c->on_itm;
    uint64_t cycles = c->cycles;

    memset(c, 0, sizeof *c);
    c->bus = bus; c->num_irq = num_irq; c->prio_bits = prio_bits;
    c->syst_ext_div = ext_div; c->syst_calib = calib;
    c->hook_ctx = hook_ctx; c->on_sysresetreq = rst; c->on_itm = itm;
    c->cycles = c->synced = cycles;

    c->ccr = CCR_STKALIGN;
    c->dwt_ctrl = 0x40000000u;                     /* four comparators */
    c->fpccr = 0xC0000000u;

    uint32_t sp, pc;
    if (bus_read(c, 0, 4, &sp, 1) || bus_read(c, 4, 4, &pc, 1)) { lockup(c); return; }
    c->r[13] = sp & ~3u;
    c->r[14] = 0xFFFFFFFFu;
    c->r[15] = pc & ~1u;
    c->tbit = pc & 1u;
    c->check_irq = 1;
}

/* ====================================================================== */
/* Instruction helpers                                                    */
/* ====================================================================== */

/* Register read as an operand: PC reads as the instruction address + 4. */
static inline uint32_t R(const struct cm_cpu *c, uint32_t n)
{
    return n == 15 ? c->r[15] + 4u : c->r[n];
}

static inline void W(struct cm_cpu *c, uint32_t n, uint32_t v)
{
    if (n == 13) v &= ~3u;
    c->r[n] = v;
}

/* Every 64th time round the same backward branch (or the first time) */
static __attribute__((noinline)) void loop_check(struct cm_cpu *c, struct cm_loop *l)
{
    if (l->pc != c->r[15]) {
        l->pc = c->r[15];
        l->hits = 0;
        return;
    }
    uint32_t x = cm_xpsr(c);
    if (l->hits > 64u && l->changes == c->mem_changes && x == l->xpsr
        && !memcmp(l->regs, c->r, sizeof l->regs)) {
        c->idle_loop = 1;
        c->stop_batch = 1;
    }
    memcpy(l->regs, c->r, sizeof l->regs);
    l->xpsr = x;
    l->changes = c->mem_changes;
}

static inline void branch_to(struct cm_cpu *c, uint32_t addr)
{
    c->next_pc = addr & ~1u;
    if (c->next_pc <= c->r[15]) {
        struct cm_loop *l = &c->loops[(c->r[15] >> 1) & 15u];
        if (l->pc == c->r[15] && (++l->hits & 63u) != 0) return;
        loop_check(c, l);
    }
}

/* BXWritePC / LoadWritePC: interworking branch, or exception return. */
static void bx_write_pc(struct cm_cpu *c, uint32_t addr)
{
    if (in_handler(c) && (addr >> 28) == 0xFu) {
        c->exc_ret_pending = 1;
        c->exc_ret_value = addr;
        return;
    }
    c->tbit = addr & 1u;
    c->next_pc = addr & ~1u;
}

static inline void set_nz(struct cm_cpu *c, uint32_t r)
{
    c->n = r >> 31;
    c->z = r == 0;
}

/* Data loads and stores. They return 1 on success; on failure they raise
 * the fault and return 0. "strict" accesses must be naturally aligned
 * (LDM, STM, LDRD, STRD, exclusives); others fault only when unaligned
 * accesses trap (CCR.UNALIGN_TRP). */
static int ld(struct cm_cpu *c, uint32_t a, int n, uint32_t *v, int strict, int unpriv)
{
    if ((a & (uint32_t)(n - 1)) && (strict || (c->ccr & CCR_UNALIGN_TRP))) {
        usage_fault(c, CM_UFSR_UNALIGNED);
        return 0;
    }
    if (bus_read(c, a, n, v, privileged(c) && !unpriv)) { bus_fault_data(c, a); return 0; }
    return 1;
}

static int st(struct cm_cpu *c, uint32_t a, int n, uint32_t v, int strict, int unpriv)
{
    if ((a & (uint32_t)(n - 1)) && (strict || (c->ccr & CCR_UNALIGN_TRP))) {
        usage_fault(c, CM_UFSR_UNALIGNED);
        return 0;
    }
    if (bus_write(c, a, n, v, privileged(c) && !unpriv)) { bus_fault_data(c, a); return 0; }
    return 1;
}

/* Instruction fetch. Returns 1 on success, raising the fault otherwise. */
static int fetch16(struct cm_cpu *c, uint32_t a, uint32_t *hw)
{
    struct cm_region *r = region_for(c->bus, a, 2);
    if (r && r->exec) { *hw = get_le(r->mem + (a - r->base), 2); return 1; }
    uint32_t top = a >> 28;
    if (r || top == 0x4 || top == 0x5 || top >= 0xA) {
        c->mmfar = a;
        fault(c, CM_EXC_MEMMANAGE, CM_MMFSR_IACCVIOL);
        return 0;
    }
    fault(c, CM_EXC_BUSFAULT, CM_BFSR_IBUSERR);
    return 0;
}

static uint32_t signed_sat(int64_t v, uint32_t bits, int *sat)
{
    int64_t hi = ((int64_t)1 << (bits - 1)) - 1, lo = -((int64_t)1 << (bits - 1));
    if (v > hi) { *sat = 1; return (uint32_t)hi; }
    if (v < lo) { *sat = 1; return (uint32_t)lo; }
    *sat = 0;
    return (uint32_t)v;
}

static uint32_t unsigned_sat(int64_t v, uint32_t bits, int *sat)
{
    int64_t hi = bits >= 32 ? 0xFFFFFFFFll : ((int64_t)1 << bits) - 1;
    if (v > hi) { *sat = 1; return (uint32_t)hi; }
    if (v < 0) { *sat = 1; return 0; }
    *sat = 0;
    return (uint32_t)v;
}

/* Data-processing core shared by the 32-bit shifted-register and
 * modified-immediate forms (A5.3.1, A5.3.11). op is the 4-bit opcode.
 * Returns 0 if the encoding is undefined. */
static int dp_op(struct cm_cpu *c, uint32_t op, int s, uint32_t rn, uint32_t rd,
                 uint32_t opnd, uint32_t carry)
{
    uint32_t res, cy = c->c, ov = c->v, a = R(c, rn);
    int logical = 1, write = 1;
    switch (op) {
    case 0x0: res = a & opnd; if (rd == 15 && s) write = 0; break;             /* AND / TST */
    case 0x1: res = a & ~opnd; break;                                          /* BIC */
    case 0x2: res = rn == 15 ? opnd : (a | opnd); break;                       /* ORR / MOV */
    case 0x3: res = rn == 15 ? ~opnd : (a | ~opnd); break;                     /* ORN / MVN */
    case 0x4: res = a ^ opnd; if (rd == 15 && s) write = 0; break;             /* EOR / TEQ */
    case 0x8: res = add_with_carry(a, opnd, 0, &cy, &ov); logical = 0;         /* ADD / CMN */
              if (rd == 15 && s) write = 0;
              break;
    case 0xA: res = add_with_carry(a, opnd, c->c, &cy, &ov); logical = 0; break;      /* ADC */
    case 0xB: res = add_with_carry(a, ~opnd, c->c, &cy, &ov); logical = 0; break;     /* SBC */
    case 0xD: res = add_with_carry(a, ~opnd, 1, &cy, &ov); logical = 0;        /* SUB / CMP */
              if (rd == 15 && s) write = 0;
              break;
    case 0xE: res = add_with_carry(~a, opnd, 1, &cy, &ov); logical = 0; break;        /* RSB */
    default: return 0;
    }
    if (write) W(c, rd, res);
    if (s) {
        set_nz(c, res);
        c->c = logical ? carry : cy;
        if (!logical) c->v = ov;
    }
    return 1;
}

/* ====================================================================== */
/* 16-bit instructions (A5.2)                                             */
/* ====================================================================== */

static void ldst_single(struct cm_cpu *c, int load, int size, int sign, uint32_t addr,
                        uint32_t rt, int unpriv)
{
    if (load) {
        uint32_t v;
        if (!ld(c, addr, size, &v, 0, unpriv)) return;
        if (sign) v = sext(v, (unsigned)size * 8u);
        if (rt == 15) bx_write_pc(c, v);
        else W(c, rt, v);
    } else {
        st(c, addr, size, R(c, rt), 0, unpriv);
    }
}

static void exec_hint(struct cm_cpu *c, uint32_t op)
{
    switch (op) {
    case 2:                                        /* WFE */
        if (c->event_reg) c->event_reg = 0;
        else c->sleeping = 1;
        break;
    case 3: c->sleeping = 1; break;                /* WFI */
    case 4: c->event_reg = 1; break;               /* SEV */
    default: break;                                /* NOP, YIELD, DBG, ... */
    }
}

static void exec16(struct cm_cpu *c, uint32_t hw)
{
    uint32_t pc = c->r[15];
    int setflags = !in_it(c);
    uint32_t cy, ov, res;

    switch (hw >> 12) {
    case 0x0: case 0x1: {
        uint32_t rd = hw & 7u, rn = (hw >> 3) & 7u;
        uint32_t op = (hw >> 11) & 3u;
        if (op < 3) {                              /* LSL, LSR, ASR (immediate) */
            int t; uint32_t n;
            decode_imm_shift(op, (hw >> 6) & 31u, &t, &n);
            res = shift_c(c->r[rn], t, n, c->c, &cy);
            W(c, rd, res);
            if (setflags) { set_nz(c, res); c->c = cy; }
            return;
        }
        uint32_t y = (hw & 0x400u) ? (hw >> 6) & 7u : c->r[(hw >> 6) & 7u];
        if (hw & 0x200u) res = add_with_carry(c->r[rn], ~y, 1, &cy, &ov);   /* SUB */
        else res = add_with_carry(c->r[rn], y, 0, &cy, &ov);              /* ADD */
        W(c, rd, res);
        if (setflags) { set_nz(c, res); c->c = cy; c->v = ov; }
        return;
    }
    case 0x2: case 0x3: {
        uint32_t rd = (hw >> 8) & 7u, imm = hw & 0xFFu;
        switch ((hw >> 11) & 3u) {
        case 0: W(c, rd, imm); if (setflags) set_nz(c, imm); return;            /* MOV */
        case 1: res = add_with_carry(c->r[rd], ~imm, 1, &cy, &ov);               /* CMP */
                set_nz(c, res); c->c = cy; c->v = ov; return;
        case 2: res = add_with_carry(c->r[rd], imm, 0, &cy, &ov); break;         /* ADD */
        default: res = add_with_carry(c->r[rd], ~imm, 1, &cy, &ov); break;       /* SUB */
        }
        W(c, rd, res);
        if (setflags) { set_nz(c, res); c->c = cy; c->v = ov; }
        return;
    }
    case 0x4:
        if ((hw & 0x0C00u) == 0x0000u) {           /* data processing (A5.2.2) */
            uint32_t rdn = hw & 7u, rm = (hw >> 3) & 7u;
            uint32_t a = c->r[rdn], b = c->r[rm];
            int logical = 1, write = 1;
            cy = c->c; ov = c->v;
            switch ((hw >> 6) & 15u) {
            case 0x0: res = a & b; break;                                   /* AND */
            case 0x1: res = a ^ b; break;                                   /* EOR */
            case 0x2: res = shift_c(a, SR_LSL, b & 0xFFu, c->c, &cy); break;
            case 0x3: res = shift_c(a, SR_LSR, b & 0xFFu, c->c, &cy); break;
            case 0x4: res = shift_c(a, SR_ASR, b & 0xFFu, c->c, &cy); break;
            case 0x5: res = add_with_carry(a, b, c->c, &cy, &ov); logical = 0; break;   /* ADC */
            case 0x6: res = add_with_carry(a, ~b, c->c, &cy, &ov); logical = 0; break;  /* SBC */
            case 0x7: res = shift_c(a, SR_ROR, b & 0xFFu, c->c, &cy); break;
            case 0x8: res = a & b; write = 0; setflags = 1; break;          /* TST */
            case 0x9: res = add_with_carry(~b, 0, 1, &cy, &ov); logical = 0; /* RSB #0 */
                      W(c, rdn, res);
                      if (setflags) { set_nz(c, res); c->c = cy; c->v = ov; }
                      return;
            case 0xA: res = add_with_carry(a, ~b, 1, &cy, &ov); logical = 0;
                      write = 0; setflags = 1; break;                       /* CMP */
            case 0xB: res = add_with_carry(a, b, 0, &cy, &ov); logical = 0;
                      write = 0; setflags = 1; break;                       /* CMN */
            case 0xC: res = a | b; break;                                   /* ORR */
            case 0xD: res = a * b;                                          /* MUL */
                      W(c, rdn, res);
                      if (setflags) set_nz(c, res);
                      return;
            case 0xE: res = a & ~b; break;                                  /* BIC */
            default: res = ~b; break;                                       /* MVN */
            }
            if (write) W(c, rdn, res);
            if (setflags) {
                set_nz(c, res);
                c->c = cy;
                if (!logical) c->v = ov;
            }
            return;
        }
        if ((hw & 0x0C00u) == 0x0400u) {           /* special data, branch and exchange */
            uint32_t rm = (hw >> 3) & 15u;
            uint32_t rdn = (hw & 7u) | ((hw >> 4) & 8u);
            switch ((hw >> 8) & 3u) {
            case 0:                                /* ADD (register) */
                res = R(c, rdn) + R(c, rm);
                if (rdn == 15) branch_to(c, res); else W(c, rdn, res);
                return;
            case 1:                                /* CMP (register) */
                res = add_with_carry(R(c, rdn), ~R(c, rm), 1, &cy, &ov);
                set_nz(c, res); c->c = cy; c->v = ov;
                return;
            case 2:                                /* MOV (register) */
                if (rdn == 15) branch_to(c, R(c, rm)); else W(c, rdn, R(c, rm));
                return;
            default: {
                uint32_t target = R(c, rm);
                if (hw & 0x80u) c->r[14] = (pc + 2u) | 1u;   /* BLX */
                bx_write_pc(c, target);
                return;
            }
            }
        }
        {                                          /* LDR (literal) */
            uint32_t rt = (hw >> 8) & 7u;
            uint32_t addr = ((pc + 4u) & ~3u) + ((hw & 0xFFu) << 2);
            ldst_single(c, 1, 4, 0, addr, rt, 0);
            return;
        }
    case 0x5: {                                    /* load/store, register offset */
        uint32_t rt = hw & 7u, rn = (hw >> 3) & 7u, rm = (hw >> 6) & 7u;
        uint32_t addr = c->r[rn] + c->r[rm];
        switch ((hw >> 9) & 7u) {
        case 0: ldst_single(c, 0, 4, 0, addr, rt, 0); return;   /* STR */
        case 1: ldst_single(c, 0, 2, 0, addr, rt, 0); return;   /* STRH */
        case 2: ldst_single(c, 0, 1, 0, addr, rt, 0); return;   /* STRB */
        case 3: ldst_single(c, 1, 1, 1, addr, rt, 0); return;   /* LDRSB */
        case 4: ldst_single(c, 1, 4, 0, addr, rt, 0); return;   /* LDR */
        case 5: ldst_single(c, 1, 2, 0, addr, rt, 0); return;   /* LDRH */
        case 6: ldst_single(c, 1, 1, 0, addr, rt, 0); return;   /* LDRB */
        default: ldst_single(c, 1, 2, 1, addr, rt, 0); return;  /* LDRSH */
        }
    }
    case 0x6: case 0x7: case 0x8: {                /* load/store, immediate offset */
        uint32_t rt = hw & 7u, rn = (hw >> 3) & 7u, imm5 = (hw >> 6) & 31u;
        int load = (hw >> 11) & 1;
        int size = (hw >> 12) == 0x6 ? 4 : (hw >> 12) == 0x7 ? 1 : 2;
        ldst_single(c, load, size, 0, c->r[rn] + imm5 * (uint32_t)size, rt, 0);
        return;
    }
    case 0x9: {                                    /* load/store, SP-relative */
        uint32_t rt = (hw >> 8) & 7u;
        ldst_single(c, (hw >> 11) & 1, 4, 0, c->r[13] + ((hw & 0xFFu) << 2), rt, 0);
        return;
    }
    case 0xA: {
        uint32_t rd = (hw >> 8) & 7u, imm = (hw & 0xFFu) << 2;
        if (hw & 0x800u) W(c, rd, c->r[13] + imm);          /* ADD Rd, SP, #imm */
        else W(c, rd, ((pc + 4u) & ~3u) + imm);             /* ADR */
        return;
    }
    case 0xB:                                      /* miscellaneous (A5.2.5) */
        switch ((hw >> 8) & 15u) {
        case 0x0: {
            uint32_t imm = (hw & 0x7Fu) << 2;
            W(c, 13, (hw & 0x80u) ? c->r[13] - imm : c->r[13] + imm);
            return;
        }
        case 0x1: case 0x3: case 0x9: case 0xB: {  /* CBZ, CBNZ */
            uint32_t rn = hw & 7u;
            uint32_t imm = (((hw >> 9) & 1u) << 6) | (((hw >> 3) & 31u) << 1);
            int nonzero = (hw >> 11) & 1;
            if ((c->r[rn] != 0) == nonzero) branch_to(c, pc + 4u + imm);
            return;
        }
        case 0x2: {
            uint32_t rd = hw & 7u, m = c->r[(hw >> 3) & 7u];
            switch ((hw >> 6) & 3u) {
            case 0: W(c, rd, sext(m, 16)); break;  /* SXTH */
            case 1: W(c, rd, sext(m, 8)); break;   /* SXTB */
            case 2: W(c, rd, m & 0xFFFFu); break;  /* UXTH */
            default: W(c, rd, m & 0xFFu); break;   /* UXTB */
            }
            return;
        }
        case 0x4: case 0x5: {                      /* PUSH */
            uint32_t list = (hw & 0xFFu) | ((hw & 0x100u) << 6);
            uint32_t addr = c->r[13] - 4u * (uint32_t)popcount16(list);
            uint32_t a = addr;
            for (uint32_t i = 0; i < 15; i++) {
                if (!((list >> i) & 1u)) continue;
                if (!st(c, a, 4, c->r[i], 1, 0)) return;
                a += 4;
            }
            W(c, 13, addr);
            return;
        }
        case 0x6:
            if ((hw & 0xE8u) == 0x60u) {           /* CPS */
                if (!privileged(c)) return;
                int dis = (hw >> 4) & 1;
                if (hw & 2u) c->primask = (uint32_t)dis;
                if ((hw & 1u) && (!dis || exec_priority(c) > -1)) c->faultmask = (uint32_t)dis;
                c->check_irq = 1;
                return;
            }
            undefined(c);
            return;
        case 0xA: {
            uint32_t rd = hw & 7u, m = c->r[(hw >> 3) & 7u];
            switch ((hw >> 6) & 3u) {
            case 0: W(c, rd, __builtin_bswap32(m)); return;                    /* REV */
            case 1: W(c, rd, ((m & 0x00FF00FFu) << 8) | ((m >> 8) & 0x00FF00FFu)); return;
            case 3: W(c, rd, sext(((m & 0xFFu) << 8) | ((m >> 8) & 0xFFu), 16)); return;
            default: undefined(c); return;
            }
        }
        case 0xC: case 0xD: {                      /* POP */
            uint32_t list = (hw & 0xFFu) | ((hw & 0x100u) << 7);
            uint32_t a = c->r[13], vals[16];
            for (uint32_t i = 0; i < 16; i++) {
                if (!((list >> i) & 1u)) continue;
                if (!ld(c, a, 4, &vals[i], 1, 0)) return;
                a += 4;
            }
            for (uint32_t i = 0; i < 8; i++)
                if ((list >> i) & 1u) c->r[i] = vals[i];
            W(c, 13, a);
            if (list & 0x8000u) bx_write_pc(c, vals[15]);
            return;
        }
        case 0xE:                                  /* BKPT: no debugger attached */
            c->dfsr |= 2u;
            c->hfsr |= CM_HFSR_DEBUGEVT;
            fault(c, CM_EXC_HARDFAULT, 0);
            return;
        case 0xF:
            if (hw & 0xFu) {                       /* IT */
                c->it = hw & 0xFFu;
                c->it_set = 1;
            } else {
                exec_hint(c, (hw >> 4) & 15u);
            }
            return;
        default:
            undefined(c);
            return;
        }
    case 0xC: {                                    /* STM, LDM */
        uint32_t rn = (hw >> 8) & 7u, list = hw & 0xFFu;
        uint32_t a = c->r[rn];
        if (!list) { undefined(c); return; }
        if (hw & 0x800u) {
            uint32_t vals[8];
            for (uint32_t i = 0; i < 8; i++) {
                if (!((list >> i) & 1u)) continue;
                if (!ld(c, a, 4, &vals[i], 1, 0)) return;
                a += 4;
            }
            for (uint32_t i = 0; i < 8; i++)
                if ((list >> i) & 1u) c->r[i] = vals[i];
            if (!((list >> rn) & 1u)) c->r[rn] = a;
        } else {
            for (uint32_t i = 0; i < 8; i++) {
                if (!((list >> i) & 1u)) continue;
                if (!st(c, a, 4, c->r[i], 1, 0)) return;
                a += 4;
            }
            c->r[rn] = a;
        }
        return;
    }
    case 0xD: {
        uint32_t cond = (hw >> 8) & 15u;
        if (cond == 14) { undefined(c); return; }  /* UDF */
        if (cond == 15) {                          /* SVC */
            fault(c, CM_EXC_SVCALL, 0);
            c->sync_ret = pc + 2u;
            return;
        }
        if (cond_pass(c, cond)) branch_to(c, pc + 4u + sext((hw & 0xFFu) << 1, 9));
        return;
    }
    case 0xE:                                      /* B (T2) */
        branch_to(c, pc + 4u + sext((hw & 0x7FFu) << 1, 12));
        return;
    default:
        undefined(c);
        return;
    }
}

/* ====================================================================== */
/* 32-bit instructions (A5.3)                                             */
/* ====================================================================== */

static void ldst_multiple(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op = (hw1 >> 7) & 3u, wback = (hw1 >> 5) & 1u, load = (hw1 >> 4) & 1u;
    uint32_t rn = hw1 & 15u, list = hw2 & (load ? 0xDFFFu : 0x5FFFu);
    if ((op != 1 && op != 2) || rn == 15 || popcount16(list) < 1) { undefined(c); return; }
    uint32_t n = (uint32_t)popcount16(list);
    uint32_t base = c->r[rn];
    uint32_t start = op == 1 ? base : base - 4u * n;
    uint32_t end = op == 1 ? base + 4u * n : base - 4u * n;
    uint32_t a = start;

    if (load) {
        uint32_t vals[16];
        for (uint32_t i = 0; i < 16; i++) {
            if (!((list >> i) & 1u)) continue;
            if (!ld(c, a, 4, &vals[i], 1, 0)) return;
            a += 4;
        }
        for (uint32_t i = 0; i < 15; i++)
            if ((list >> i) & 1u) W(c, i, vals[i]);
        if (wback && !((list >> rn) & 1u)) W(c, rn, end);
        if (list & 0x8000u) bx_write_pc(c, vals[15]);
    } else {
        for (uint32_t i = 0; i < 15; i++) {
            if (!((list >> i) & 1u)) continue;
            if (!st(c, a, 4, c->r[i], 1, 0)) return;
            a += 4;
        }
        if (wback) W(c, rn, end);
    }
}

static void ldst_dual_excl(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t rn = hw1 & 15u, rt = hw2 >> 12, rt2 = (hw2 >> 8) & 15u;

    if ((hw1 & 0x0100u) || (hw1 & 0x0020u)) {     /* LDRD, STRD (immediate) */
        uint32_t p = (hw1 >> 8) & 1u, u = (hw1 >> 7) & 1u, w = (hw1 >> 5) & 1u;
        uint32_t load = (hw1 >> 4) & 1u, imm = (hw2 & 0xFFu) << 2;
        uint32_t base = rn == 15 ? (c->r[15] + 4u) & ~3u : c->r[rn];
        uint32_t off = u ? base + imm : base - imm;
        uint32_t addr = p ? off : base;
        if (load) {
            uint32_t a, b;
            if (!ld(c, addr, 4, &a, 1, 0) || !ld(c, addr + 4u, 4, &b, 1, 0)) return;
            W(c, rt, a);
            W(c, rt2, b);
        } else {
            if (!st(c, addr, 4, R(c, rt), 1, 0) || !st(c, addr + 4u, 4, R(c, rt2), 1, 0)) return;
        }
        if (w && rn != 15) W(c, rn, off);
        return;
    }

    uint32_t op1 = (hw1 >> 7) & 3u, op2 = (hw1 >> 4) & 3u, op3 = (hw2 >> 4) & 15u;
    if (op1 == 0 && op2 == 0) {                    /* STREX */
        uint32_t addr = c->r[rn] + ((hw2 & 0xFFu) << 2);
        uint32_t rd = (hw2 >> 8) & 15u;
        if (addr & 3u) { usage_fault(c, CM_UFSR_UNALIGNED); return; }
        if (c->excl_valid && ((addr ^ c->excl_addr) & ~7u) == 0) {
            if (!st(c, addr, 4, R(c, rt), 1, 0)) return;
            W(c, rd, 0);
        } else {
            W(c, rd, 1);
        }
        c->excl_valid = 0;
        return;
    }
    if (op1 == 0 && op2 == 1) {                    /* LDREX */
        uint32_t addr = c->r[rn] + ((hw2 & 0xFFu) << 2), v;
        if (!ld(c, addr, 4, &v, 1, 0)) return;
        c->excl_valid = 1;
        c->excl_addr = addr;
        W(c, rt, v);
        return;
    }
    if (op1 == 1 && op2 == 0 && (op3 == 4 || op3 == 5)) {   /* STREXB, STREXH */
        int size = op3 == 4 ? 1 : 2;
        uint32_t addr = c->r[rn], rd = hw2 & 15u;
        if (c->excl_valid && ((addr ^ c->excl_addr) & ~7u) == 0) {
            if (!st(c, addr, size, R(c, rt), 1, 0)) return;
            W(c, rd, 0);
        } else {
            W(c, rd, 1);
        }
        c->excl_valid = 0;
        return;
    }
    if (op1 == 1 && op2 == 1) {
        uint32_t rm = hw2 & 15u;
        if (op3 == 0 || op3 == 1) {                /* TBB, TBH */
            uint32_t v;
            uint32_t addr = op3 ? R(c, rn) + (c->r[rm] << 1) : R(c, rn) + c->r[rm];
            if (!ld(c, addr, op3 ? 2 : 1, &v, 0, 0)) return;
            branch_to(c, c->r[15] + 4u + 2u * v);
            return;
        }
        if (op3 == 4 || op3 == 5) {                /* LDREXB, LDREXH */
            int size = op3 == 4 ? 1 : 2;
            uint32_t addr = c->r[rn], v;
            if (!ld(c, addr, size, &v, 1, 0)) return;
            c->excl_valid = 1;
            c->excl_addr = addr;
            W(c, rt, v);
            return;
        }
    }
    undefined(c);
}

static void ldst_single32(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t sign = (hw1 >> 8) & 1u, size_code = (hw1 >> 5) & 3u, load = (hw1 >> 4) & 1u;
    uint32_t rn = hw1 & 15u, rt = hw2 >> 12;
    int size = 1 << size_code;
    uint32_t addr, wb_addr = 0;
    int wback = 0, unpriv = 0;

    if (size_code == 3 || (!load && sign) || (sign && size_code == 2)) { undefined(c); return; }

    if (load && rn == 15) {                        /* literal */
        uint32_t base = (c->r[15] + 4u) & ~3u, imm = hw2 & 0xFFFu;
        addr = (hw1 & 0x80u) ? base + imm : base - imm;
    } else if (rn == 15) {
        undefined(c);
        return;
    } else if (hw1 & 0x80u) {                      /* imm12 */
        addr = c->r[rn] + (hw2 & 0xFFFu);
    } else if (hw2 & 0x800u) {                     /* imm8 with P, U, W */
        uint32_t p = (hw2 >> 10) & 1u, u = (hw2 >> 9) & 1u, w = (hw2 >> 8) & 1u;
        uint32_t imm = hw2 & 0xFFu;
        if (!p && !w) { undefined(c); return; }
        if (p && u && !w) unpriv = 1;              /* LDRT, STRT, ... */
        uint32_t off = u ? c->r[rn] + imm : c->r[rn] - imm;
        addr = p ? off : c->r[rn];
        if (w) { wback = 1; wb_addr = off; }
    } else if ((hw2 & 0xFC0u) == 0) {              /* register, LSL #imm2 */
        uint32_t rm = hw2 & 15u;
        if (rm == 13 || rm == 15) { undefined(c); return; }
        addr = c->r[rn] + (c->r[rm] << ((hw2 >> 4) & 3u));
    } else {
        undefined(c);
        return;
    }

    if (load && rt == 15 && size < 4) return;      /* PLD, PLI: hints */
    if (!load && rt == 15) { undefined(c); return; }

    if (load) {
        uint32_t v;
        if (!ld(c, addr, size, &v, 0, unpriv)) return;
        if (sign) v = sext(v, (unsigned)size * 8u);
        if (wback) W(c, rn, wb_addr);
        if (rt == 15) bx_write_pc(c, v);
        else W(c, rt, v);
    } else {
        if (!st(c, addr, size, R(c, rt), 0, unpriv)) return;
        if (wback) W(c, rn, wb_addr);
    }
}

static int msr_mrs_sysm_ok(uint32_t sysm)
{
    return sysm <= 3 || (sysm >= 5 && sysm <= 9) || (sysm >= 16 && sysm <= 20);
}

static void exec_msr(struct cm_cpu *c, uint32_t rn, uint32_t mask, uint32_t sysm)
{
    uint32_t v = c->r[rn];
    if (!msr_mrs_sysm_ok(sysm)) { undefined(c); return; }
    if (sysm <= 3) {                               /* APSR variants */
        if (mask & 2u) {
            c->n = v >> 31; c->z = (v >> 30) & 1u; c->c = (v >> 29) & 1u;
            c->v = (v >> 28) & 1u; c->q = (v >> 27) & 1u;
        }
        return;
    }
    if (!privileged(c)) return;
    switch (sysm) {
    case 8: set_msp(c, v); break;
    case 9: set_psp(c, v); break;
    case 16: c->primask = v & 1u; break;
    case 17: c->basepri = v & 0xFFu & (0xFFu << (8 - c->prio_bits)); break;
    case 18: {
        uint32_t b = v & 0xFFu & (0xFFu << (8 - c->prio_bits));
        if (b && (b < c->basepri || !c->basepri)) c->basepri = b;
        break;
    }
    case 19:
        if (exec_priority(c) > -1) c->faultmask = v & 1u;
        break;
    case 20: {
        int was_psp = psp_active(c);
        c->control = (c->control & ~CONTROL_NPRIV) | (v & CONTROL_NPRIV);
        if (!in_handler(c)) c->control = (c->control & ~CONTROL_SPSEL) | (v & CONTROL_SPSEL);
        sp_switch(c, was_psp);
        break;
    }
    default: break;
    }
    c->check_irq = 1;
}

static void exec_mrs(struct cm_cpu *c, uint32_t rd, uint32_t sysm)
{
    uint32_t v = 0, x = cm_xpsr(c);
    if (!msr_mrs_sysm_ok(sysm)) { undefined(c); return; }
    if (sysm <= 7) {
        if (sysm & 1u) v |= x & 0x1FFu;            /* IPSR part */
        if (!(sysm & 4u)) v |= x & 0xF80F0000u;    /* APSR part (flags, Q, GE) */
    } else if (privileged(c)) {
        switch (sysm) {
        case 8: v = cm_msp(c); break;
        case 9: v = cm_psp(c); break;
        case 16: v = c->primask; break;
        case 17: case 18: v = c->basepri; break;
        case 19: v = c->faultmask; break;
        case 20: v = c->control; break;
        default: break;
        }
    } else if (sysm == 20) {
        v = c->control;
    }
    W(c, rd, v);
}

static void branch_misc(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op1 = (hw2 >> 12) & 7u, op = (hw1 >> 4) & 0x7Fu;
    uint32_t pc = c->r[15];
    uint32_t s = (hw1 >> 10) & 1u, j1 = (hw2 >> 13) & 1u, j2 = (hw2 >> 11) & 1u;

    if ((op1 & 5u) == 0) {
        if ((op & 0x38u) != 0x38u) {               /* B (T3), conditional */
            uint32_t cond = (hw1 >> 6) & 15u;
            uint32_t imm = (s << 20) | (j2 << 19) | (j1 << 18) | ((hw1 & 0x3Fu) << 12) | ((hw2 & 0x7FFu) << 1);
            if (cond_pass(c, cond)) branch_to(c, pc + 4u + sext(imm, 21));
            return;
        }
        if (op1 == 0) {
            switch (op) {
            case 0x38: case 0x39:                  /* MSR */
                exec_msr(c, hw1 & 15u, (hw2 >> 10) & 3u, hw2 & 0xFFu);
                return;
            case 0x3A:                             /* hints */
                if ((hw2 & 0x700u) == 0) {
                    uint32_t h = hw2 & 0xFFu;
                    if (h <= 4) exec_hint(c, h);
                    return;                        /* DBG and unallocated hints: NOP */
                }
                undefined(c);
                return;
            case 0x3B:                             /* CLREX, DSB, DMB, ISB */
                switch ((hw2 >> 4) & 15u) {
                case 2: c->excl_valid = 0; return;
                case 4: case 5: case 6: return;
                default: undefined(c); return;
                }
            case 0x3E: case 0x3F:                  /* MRS */
                exec_mrs(c, (hw2 >> 8) & 15u, hw2 & 0xFFu);
                return;
            default: break;
            }
        }
        undefined(c);                              /* includes UDF.W */
        return;
    }
    if ((op1 & 5u) == 1u || (op1 & 5u) == 5u) {    /* B (T4), BL */
        uint32_t i1 = !(j1 ^ s), i2 = !(j2 ^ s);
        uint32_t imm = (s << 24) | (i1 << 23) | (i2 << 22) | ((hw1 & 0x3FFu) << 12) | ((hw2 & 0x7FFu) << 1);
        if (op1 & 4u) c->r[14] = (pc + 4u) | 1u;
        branch_to(c, pc + 4u + sext(imm, 25));
        return;
    }
    undefined(c);                                  /* BLX (immediate): not on M profile */
}

static void dp_plain_imm(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op = (hw1 >> 4) & 31u, rn = hw1 & 15u, rd = (hw2 >> 8) & 15u;
    uint32_t imm12 = (((hw1 >> 10) & 1u) << 11) | (((hw2 >> 12) & 7u) << 8) | (hw2 & 0xFFu);
    uint32_t imm16 = ((hw1 & 15u) << 12) | imm12;
    uint32_t imm5 = (((hw2 >> 12) & 7u) << 2) | ((hw2 >> 6) & 3u);
    uint32_t a = c->r[rn];
    int sat;

    switch (op) {
    case 0x00:                                     /* ADDW, ADR (add) */
        W(c, rd, rn == 15 ? ((c->r[15] + 4u) & ~3u) + imm12 : R(c, rn) + imm12);
        return;
    case 0x04: W(c, rd, imm16); return;            /* MOVW */
    case 0x0A:                                     /* SUBW, ADR (sub) */
        W(c, rd, rn == 15 ? ((c->r[15] + 4u) & ~3u) - imm12 : R(c, rn) - imm12);
        return;
    case 0x0C: W(c, rd, (imm16 << 16) | (c->r[rd] & 0xFFFFu)); return;   /* MOVT */
    case 0x10: case 0x12: case 0x18: case 0x1A: {  /* SSAT, USAT */
        uint32_t sh = (hw1 >> 5) & 1u;
        if ((op & 2u) && imm5 == 0) { undefined(c); return; }   /* SSAT16/USAT16: DSP */
        int t; uint32_t n, dummy;
        decode_imm_shift(sh << 1, imm5, &t, &n);
        int64_t opnd = (int32_t)shift_c(a, t, n, c->c, &dummy);
        uint32_t r;
        if (op & 8u) r = unsigned_sat(opnd, hw2 & 31u, &sat);
        else r = signed_sat(opnd, (hw2 & 31u) + 1u, &sat);
        W(c, rd, r);
        if (sat) c->q = 1;
        return;
    }
    case 0x14: case 0x1C: {                        /* SBFX, UBFX */
        uint32_t lsb = imm5, width = (hw2 & 31u) + 1u;
        if (lsb + width > 32) { undefined(c); return; }
        uint32_t v = a >> lsb;
        if (width < 32) v &= (1u << width) - 1u;
        W(c, rd, op == 0x14 ? sext(v, width) : v);
        return;
    }
    case 0x16: {                                   /* BFI, BFC */
        uint32_t lsb = imm5, msb = hw2 & 31u;
        if (msb < lsb) { undefined(c); return; }
        uint32_t width = msb - lsb + 1u;
        uint32_t mask = (width >= 32 ? 0xFFFFFFFFu : ((1u << width) - 1u)) << lsb;
        uint32_t src = rn == 15 ? 0 : (a << lsb);
        W(c, rd, (c->r[rd] & ~mask) | (src & mask));
        return;
    }
    default:
        undefined(c);
        return;
    }
}

static void dp_register(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op1 = (hw1 >> 4) & 15u, op2 = (hw2 >> 4) & 15u;
    uint32_t rn = hw1 & 15u, rd = (hw2 >> 8) & 15u, rm = hw2 & 15u;
    uint32_t cy;

    if ((op1 & 8u) == 0 && op2 == 0) {             /* LSL, LSR, ASR, ROR (register) */
        uint32_t res = shift_c(c->r[rn], (int)((hw1 >> 5) & 3u), c->r[rm] & 0xFFu, c->c, &cy);
        W(c, rd, res);
        if (hw1 & 0x10u) { set_nz(c, res); c->c = cy; }
        return;
    }
    if ((op2 & 8u) && op1 <= 5u) {                 /* extend (and add) */
        uint32_t rot = ((hw2 >> 4) & 3u) * 8u;
        uint32_t m = ror32(c->r[rm], rot), v;
        switch (op1) {
        case 0: v = sext(m, 16); break;            /* SXTAH, SXTH */
        case 1: v = m & 0xFFFFu; break;            /* UXTAH, UXTH */
        case 4: v = sext(m, 8); break;             /* SXTAB, SXTB */
        case 5: v = m & 0xFFu; break;              /* UXTAB, UXTB */
        default: undefined(c); return;             /* SXTB16, UXTB16 (DSP) */
        }
        W(c, rd, rn == 15 ? v : c->r[rn] + v);
        return;
    }
    if ((op1 & 0xCu) == 0x8u && (op2 & 0xCu) == 0x8u) {   /* miscellaneous (A5.3.15) */
        uint32_t m = c->r[rm];
        switch (((hw1 >> 4) & 3u) * 4u + ((hw2 >> 4) & 3u)) {
        case 4: W(c, rd, __builtin_bswap32(m)); return;                               /* REV */
        case 5: W(c, rd, ((m & 0x00FF00FFu) << 8) | ((m >> 8) & 0x00FF00FFu)); return; /* REV16 */
        case 6: {                                                                       /* RBIT */
            uint32_t r = 0;
            for (int i = 0; i < 32; i++) if ((m >> i) & 1u) r |= 1u << (31 - i);
            W(c, rd, r);
            return;
        }
        case 7: W(c, rd, sext(((m & 0xFFu) << 8) | ((m >> 8) & 0xFFu), 16)); return;  /* REVSH */
        case 12: W(c, rd, m ? (uint32_t)__builtin_clz(m) : 32u); return;              /* CLZ */
        default: undefined(c); return;             /* QADD, SEL, ... (DSP) */
        }
    }
    undefined(c);                                  /* parallel add/subtract (DSP) */
}

static void multiply(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op1 = (hw1 >> 4) & 7u, op2 = (hw2 >> 4) & 3u;
    uint32_t rn = hw1 & 15u, ra = hw2 >> 12, rd = (hw2 >> 8) & 15u, rm = hw2 & 15u;
    if (op1 == 0 && (hw2 & 0xC0u) == 0) {
        uint32_t p = c->r[rn] * c->r[rm];
        if (op2 == 0) W(c, rd, ra == 15 ? p : p + c->r[ra]);   /* MUL, MLA */
        else if (op2 == 1) W(c, rd, c->r[ra] - p);              /* MLS */
        else undefined(c);
        return;
    }
    undefined(c);                                  /* SMLA<x><y> and other DSP forms */
}

static void long_multiply(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op1 = (hw1 >> 4) & 7u, op2 = (hw2 >> 4) & 15u;
    uint32_t rn = hw1 & 15u, lo = hw2 >> 12, hi = (hw2 >> 8) & 15u, rm = hw2 & 15u;
    uint64_t r;
    switch ((op1 << 4) | op2) {
    case 0x00:                                     /* SMULL */
        r = (uint64_t)((int64_t)(int32_t)c->r[rn] * (int32_t)c->r[rm]);
        break;
    case 0x20:                                     /* UMULL */
        r = (uint64_t)c->r[rn] * c->r[rm];
        break;
    case 0x40:                                     /* SMLAL */
        r = (uint64_t)((int64_t)(int32_t)c->r[rn] * (int32_t)c->r[rm])
          + (((uint64_t)c->r[hi] << 32) | c->r[lo]);
        break;
    case 0x60:                                     /* UMLAL */
        r = (uint64_t)c->r[rn] * c->r[rm] + (((uint64_t)c->r[hi] << 32) | c->r[lo]);
        break;
    case 0x1F: case 0x3F: {                        /* SDIV, UDIV */
        uint32_t n = c->r[rn], d = c->r[rm], q;
        if (d == 0) {
            if (c->ccr & CCR_DIV_0_TRP) { usage_fault(c, CM_UFSR_DIVBYZERO); return; }
            q = 0;
        } else if (op1 == 1) {
            if (n == 0x80000000u && d == 0xFFFFFFFFu) q = n;
            else q = (uint32_t)((int32_t)n / (int32_t)d);
        } else {
            q = n / d;
        }
        W(c, hi, q);                               /* Rd is in bits 11:8 */
        return;
    }
    default:
        undefined(c);                              /* SMLALxy, UMAAL, ... (DSP) */
        return;
    }
    W(c, lo, (uint32_t)r);
    W(c, hi, (uint32_t)(r >> 32));
}

static void coprocessor(struct cm_cpu *c, uint32_t hw2)
{
    uint32_t cp = (hw2 >> 8) & 15u;
    if (cp == 10 || cp == 11) {
        uint32_t access = (c->cpacr >> (cp == 10 ? 20 : 22)) & 3u;
        if (access == 0 || access == 2 || (access == 1 && !privileged(c))) {
            usage_fault(c, CM_UFSR_NOCP);
            return;
        }
        undefined(c);                              /* FPU instructions: not yet */
        return;
    }
    usage_fault(c, CM_UFSR_NOCP);
}

static void exec32(struct cm_cpu *c, uint32_t hw1, uint32_t hw2)
{
    uint32_t op1 = (hw1 >> 11) & 3u, op2 = (hw1 >> 4) & 0x7Fu;

    if (op1 == 1) {
        if ((op2 & 0x64u) == 0x00u) { ldst_multiple(c, hw1, hw2); return; }
        if ((op2 & 0x64u) == 0x04u) { ldst_dual_excl(c, hw1, hw2); return; }
        if ((op2 & 0x60u) == 0x20u) {              /* data processing (shifted register) */
            uint32_t op = (hw1 >> 5) & 15u, s = (hw1 >> 4) & 1u;
            uint32_t rn = hw1 & 15u, rd = (hw2 >> 8) & 15u, rm = hw2 & 15u;
            uint32_t imm5 = (((hw2 >> 12) & 7u) << 2) | ((hw2 >> 6) & 3u);
            int t; uint32_t n, cy;
            decode_imm_shift((hw2 >> 4) & 3u, imm5, &t, &n);
            uint32_t opnd = shift_c(R(c, rm), t, n, c->c, &cy);
            if (!dp_op(c, op, (int)s, rn, rd, opnd, cy)) undefined(c);
            return;
        }
        coprocessor(c, hw2);
        return;
    }
    if (op1 == 2) {
        if (hw2 & 0x8000u) { branch_misc(c, hw1, hw2); return; }
        if (!(op2 & 0x20u)) {                      /* data processing (modified immediate) */
            uint32_t op = (hw1 >> 5) & 15u, s = (hw1 >> 4) & 1u;
            uint32_t rn = hw1 & 15u, rd = (hw2 >> 8) & 15u;
            uint32_t imm12 = (((hw1 >> 10) & 1u) << 11) | (((hw2 >> 12) & 7u) << 8) | (hw2 & 0xFFu);
            uint32_t cy;
            uint32_t imm = thumb_expand_imm_c(imm12, c->c, &cy);
            if (!dp_op(c, op, (int)s, rn, rd, imm, cy)) undefined(c);
            return;
        }
        dp_plain_imm(c, hw1, hw2);
        return;
    }
    /* op1 == 3 */
    if (op2 & 0x40u) { coprocessor(c, hw2); return; }
    if ((op2 & 0x60u) == 0x00u) {                  /* loads and stores */
        if ((op2 & 0x67u) == 0x07u) { undefined(c); return; }
        if (!(op2 & 1u) && (op2 & 0x10u)) { undefined(c); return; }
        ldst_single32(c, hw1, hw2);
        return;
    }
    if ((op2 & 0x70u) == 0x20u) { dp_register(c, hw1, hw2); return; }
    if ((op2 & 0x78u) == 0x30u) { multiply(c, hw1, hw2); return; }
    if ((op2 & 0x78u) == 0x38u) { long_multiply(c, hw1, hw2); return; }
    undefined(c);
}

/* ====================================================================== */
/* Run loop                                                               */
/* ====================================================================== */

#define likely(x)   __builtin_expect(!!(x), 1)
#define unlikely(x) __builtin_expect(!!(x), 0)

uint32_t cm_run(struct cm_cpu *c, uint32_t max)
{
    uint64_t start = c->cycles, end = c->cycles + max;
    c->stop_batch = 0;
    /* The region instructions currently come from, for fast fetches */
    const uint8_t *cmem = NULL;
    uint32_t cbase = 0, csize = 0;

    while (c->cycles < end) {
        if (unlikely(c->check_irq)) {
            c->check_irq = 0;
            if (try_take_pending(c)) { c->cycles++; continue; }
        }
        if (unlikely(c->lockup | c->sleeping | c->stop_batch)) break;

        uint32_t pc = c->r[15], hw1, hw2 = 0;
        uint32_t off = pc - cbase;
        if (likely(off + 4u <= csize && c->tbit)) {
            hw1 = (uint32_t)cmem[off] | ((uint32_t)cmem[off + 1] << 8);
            if ((hw1 >> 11) >= 0x1Du) {
                hw2 = (uint32_t)cmem[off + 2] | ((uint32_t)cmem[off + 3] << 8);
                c->next_pc = pc + 4u;
            } else {
                c->next_pc = pc + 2u;
            }
        } else {
            /* Slow path: new region, region end, T bit clear or a fault */
            struct cm_region *r = region_for(c->bus, pc, 2);
            if (r && r->exec && c->tbit) { cmem = r->mem; cbase = r->base; csize = r->size; }
            if (!c->tbit) {
                usage_fault(c, CM_UFSR_INVSTATE);
            } else if (fetch16(c, pc, &hw1)) {
                int wide = (hw1 >> 11) >= 0x1Du;
                if (!wide || fetch16(c, pc + 2u, &hw2))
                    c->next_pc = pc + (wide ? 4u : 2u);
            }
            if (c->sync_exc) {
                c->cycles++;
                take_sync(c);
                continue;
            }
        }

        if (unlikely(c->it & 0xFu) && !cond_pass(c, c->it >> 4)) {
            /* condition failed: no effect */
        } else if ((hw1 >> 11) >= 0x1Du) {
            exec32(c, hw1, hw2);
        } else {
            exec16(c, hw1);
        }
        c->cycles++;

        if (unlikely(c->sync_exc)) {
            if (c->sync_exc == CM_EXC_SVCALL && in_it(c)) it_advance(c);
            take_sync(c);
            continue;
        }
        if (unlikely(c->it)) {
            if (c->it_set) c->it_set = 0;
            else it_advance(c);
        }
        c->r[15] = c->next_pc;
        if (unlikely(c->exc_ret_pending)) {
            c->exc_ret_pending = 0;
            exception_return(c, c->exc_ret_value);
        }
    }
    cm_sync_counters(c);
    return (uint32_t)(c->cycles - start);
}
