/*
 * Solderless - CPU test harness: a bare ARMv7-M core with two RAM regions,
 * built as a shared library for tests/cpu/diff_test.py.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
#include "../../emu/core/armv7m.h"

#include <stdlib.h>
#include <string.h>

#define CODE_BASE 0x00000000u
#define CODE_SIZE 0x00010000u
#define DATA_BASE 0x20000000u
#define DATA_SIZE 0x00010000u

struct t_vm {
    struct cm_cpu cpu;
    struct cm_bus bus;
    struct cm_region code, data;
    uint8_t code_mem[CODE_SIZE];
    uint8_t data_mem[DATA_SIZE];
};

static int no_read(void *ctx, uint32_t a, int n, uint32_t *v) { (void)ctx; (void)a; (void)n; *v = 0; return 1; }
static int no_write(void *ctx, uint32_t a, int n, uint32_t v) { (void)ctx; (void)a; (void)n; (void)v; return 1; }

void *t_new(void)
{
    struct t_vm *t = calloc(1, sizeof *t);
    t->code = (struct cm_region){ CODE_BASE, CODE_SIZE, t->code_mem, 1, 1 };
    t->data = (struct cm_region){ DATA_BASE, DATA_SIZE, t->data_mem, 1, 1 };
    t->bus.map[0][0] = &t->code;
    t->bus.map[2][0] = &t->data;
    t->bus.mmio_read = no_read;
    t->bus.mmio_write = no_write;
    cm_init(&t->cpu, &t->bus, 32, 4);
    return t;
}

void t_free(void *p) { free(p); }

void t_write(void *p, uint32_t addr, const uint8_t *buf, uint32_t len)
{
    struct t_vm *t = p;
    for (uint32_t i = 0; i < len; i++) {
        uint32_t a = addr + i;
        if (a - CODE_BASE < CODE_SIZE) t->code_mem[a - CODE_BASE] = buf[i];
        else if (a - DATA_BASE < DATA_SIZE) t->data_mem[a - DATA_BASE] = buf[i];
    }
}

void t_read(void *p, uint32_t addr, uint8_t *buf, uint32_t len)
{
    struct t_vm *t = p;
    cm_peek(&t->cpu, addr, buf, len);
}

/* regs: r0..r15, then xPSR. Thread mode, privileged, MSP. */
void t_set_state(void *p, const uint32_t *regs)
{
    struct t_vm *t = p;
    struct cm_cpu *c = &t->cpu;
    for (int i = 0; i < 16; i++) c->r[i] = regs[i];
    c->r[13] &= ~3u;
    uint32_t x = regs[16];
    c->n = x >> 31; c->z = (x >> 30) & 1; c->c = (x >> 29) & 1; c->v = (x >> 28) & 1;
    c->q = (x >> 27) & 1; c->ge = (x >> 16) & 15; c->tbit = 1;
    c->it = 0; c->ipsr = 0; c->control = 0; c->primask = 0;
    c->sleeping = 0; c->lockup = 0;
    c->cfsr = 0; c->hfsr = 0; c->excl_valid = 0;
    memset(c->active, 0, sizeof c->active);
    memset(c->pending, 0, sizeof c->pending);
}

void t_get_state(void *p, uint32_t *regs)
{
    struct t_vm *t = p;
    for (int i = 0; i < 16; i++) regs[i] = t->cpu.r[i];
    regs[16] = cm_xpsr(&t->cpu);
    regs[17] = t->cpu.cfsr;
    regs[18] = t->cpu.lockup;
}

uint32_t t_run(void *p, uint32_t n) { return cm_run(&((struct t_vm *)p)->cpu, n); }

/* Runs until the PC reaches end_pc, at most max instructions. */
uint32_t t_run_until(void *p, uint32_t end_pc, uint32_t max)
{
    struct t_vm *t = p;
    uint32_t done = 0;
    while (done < max && t->cpu.r[15] != end_pc && !t->cpu.lockup) done += cm_run(&t->cpu, 1);
    return done;
}

void t_set_ccr(void *p, uint32_t ccr) { ((struct t_vm *)p)->cpu.ccr = ccr; }
