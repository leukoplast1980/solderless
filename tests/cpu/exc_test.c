/*
 * Solderless - runs tests/cpu/exc_test.S on the bare core and reports.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 *   exc_test exc_test.bin
 */
#include "armv7m.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint8_t code[0x10000], ram[0x8000];

static int no_read(void *ctx, uint32_t a, int n, uint32_t *v) { (void)ctx; (void)a; (void)n; *v = 0; return 1; }
static int no_write(void *ctx, uint32_t a, int n, uint32_t v) { (void)ctx; (void)a; (void)n; (void)v; return 1; }

static const char *names[] = {
    "SVC", "PendSV", "nested preemption", "tail chaining", "PRIMASK", "BASEPRI",
    "UsageFault", "HardFault escalation", "BusFault and BFAR", "process stack",
    "unprivileged mode", "stack alignment", "SysTick", "exclusive monitor",
    "bit-band", "execute-never MemManage", "SVC escalation",
};

int main(int argc, char **argv)
{
    if (argc < 2) { fprintf(stderr, "usage: exc_test exc_test.bin\n"); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 2; }
    size_t n = fread(code, 1, sizeof code, f);
    fclose(f);
    (void)n;

    struct cm_region rc = { 0x00000000u, sizeof code, code, 1, 1 };
    struct cm_region rr = { 0x20000000u, sizeof ram, ram, 1, 1 };
    struct cm_bus bus;
    memset(&bus, 0, sizeof bus);
    bus.map[0][0] = &rc;
    bus.map[2][0] = &rr;
    bus.mmio_read = no_read;
    bus.mmio_write = no_write;

    static struct cm_cpu cpu;
    cm_init(&cpu, &bus, 32, 4);
    cm_reset(&cpu);
    for (int i = 0; i < 100000 && !cpu.lockup; i++) {
        uint64_t st = cm_cycles_to_systick(&cpu);
        cm_run(&cpu, st < 1000 ? (uint32_t)st : 1000u);
        uint32_t done;
        memcpy(&done, ram, 4);
        if (done == 0xD0D0D0D0u) break;
    }
    uint32_t done, fails, count;
    memcpy(&done, ram, 4);
    memcpy(&fails, ram + 4, 4);
    memcpy(&count, ram + 8, 4);
    if (done != 0xD0D0D0D0u) {
        printf("exception tests did not finish: %u tests started, lockup %d, PC %08x, CFSR %08x\n",
               count, cpu.lockup, cpu.r[15], cpu.cfsr);
        return 1;
    }
    int bad = 0;
    for (unsigned i = 0; i < count; i++) {
        int ok = !((fails >> i) & 1u);
        printf("%-3u %-26s %s\n", i, i < sizeof names / sizeof names[0] ? names[i] : "?", ok ? "ok" : "FAILED");
        bad |= !ok;
    }
    return bad;
}
