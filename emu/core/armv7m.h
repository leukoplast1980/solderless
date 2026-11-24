/*
 * Solderless - ARMv7-M CPU core (Cortex-M4 integer core, NVIC, SCB, SysTick).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * The core knows nothing about the chip around it. Memory comes from
 * regions and MMIO callbacks supplied through struct cm_bus. The System
 * Control Space (NVIC, SCB, SysTick, MPU registers, DWT, ITM) lives here,
 * because it is part of the core.
 */
#ifndef SL_ARMV7M_H
#define SL_ARMV7M_H

#include <stdint.h>
#include <stddef.h>

#define CM_MAX_EXC 128          /* 16 system exceptions + up to 112 interrupts */

/* Exception numbers */
enum {
    CM_EXC_RESET = 1, CM_EXC_NMI = 2, CM_EXC_HARDFAULT = 3, CM_EXC_MEMMANAGE = 4,
    CM_EXC_BUSFAULT = 5, CM_EXC_USAGEFAULT = 6, CM_EXC_SVCALL = 11,
    CM_EXC_DEBUGMON = 12, CM_EXC_PENDSV = 14, CM_EXC_SYSTICK = 15, CM_EXC_IRQ0 = 16,
};

/* CFSR bits */
#define CM_MMFSR_IACCVIOL    (1u << 0)
#define CM_MMFSR_DACCVIOL    (1u << 1)
#define CM_MMFSR_MUNSTKERR   (1u << 3)
#define CM_MMFSR_MSTKERR     (1u << 4)
#define CM_MMFSR_MMARVALID   (1u << 7)
#define CM_BFSR_IBUSERR      (1u << 8)
#define CM_BFSR_PRECISERR    (1u << 9)
#define CM_BFSR_UNSTKERR     (1u << 11)
#define CM_BFSR_STKERR       (1u << 12)
#define CM_BFSR_BFARVALID    (1u << 15)
#define CM_UFSR_UNDEFINSTR   (1u << 16)
#define CM_UFSR_INVSTATE     (1u << 17)
#define CM_UFSR_INVPC        (1u << 18)
#define CM_UFSR_NOCP         (1u << 19)
#define CM_UFSR_UNALIGNED    (1u << 24)
#define CM_UFSR_DIVBYZERO    (1u << 25)

#define CM_HFSR_VECTTBL      (1u << 1)
#define CM_HFSR_FORCED       (1u << 30)
#define CM_HFSR_DEBUGEVT     (1u << 31)

/* A block of memory the core reads (and maybe writes) directly. */
struct cm_region {
    uint32_t base, size;
    uint8_t *mem;
    uint8_t writable;   /* plain stores go to mem; otherwise to bus->rom_write */
    uint8_t exec;       /* instruction fetch allowed */
};

struct cm_bus {
    /* Regions by address bits 31..28, up to two per slot. */
    struct cm_region *map[16][2];
    void *ctx;
    /* Everything outside regions and the core's own System Control Space.
     * size is 1, 2 or 4; addr is naturally aligned. Return 0 on success,
     * nonzero for a bus error. */
    int (*mmio_read)(void *ctx, uint32_t addr, int size, uint32_t *value);
    int (*mmio_write)(void *ctx, uint32_t addr, int size, uint32_t value);
    /* Side-effect-free read for debug views (may be NULL: reads as 0). */
    int (*mmio_peek)(void *ctx, uint32_t addr, uint32_t *value);
    /* Stores to a region that is not writable (flash programming).
     * Return 0 on success, nonzero for a bus error. */
    int (*rom_write)(void *ctx, uint32_t addr, int size, uint32_t value);
    /* Told of every System Control Space write (SysTick, NVIC, SCB), with
     * the register's value before and after (may be NULL). */
    void (*scs_written)(void *ctx, uint32_t addr, uint32_t value, uint32_t mask, uint32_t before, uint32_t after);
};

struct cm_cpu {
    uint32_t r[16];             /* r[13] is the active stack pointer, r[15] the PC */
    uint32_t n, z, c, v, q;     /* flags, each 0 or 1 */
    uint32_t ge;                /* GE[3:0] */
    uint32_t tbit;              /* EPSR.T */
    uint32_t ipsr;              /* active exception number, 0 in Thread mode */
    uint32_t sp_main, sp_proc;  /* the stack pointer that is not active */
    uint32_t primask, faultmask, basepri, control;

    /* Exceptions */
    int num_irq;                /* external interrupt lines */
    int prio_bits;              /* implemented priority bits (4 on STM32) */
    uint8_t prio[CM_MAX_EXC];
    uint8_t pending[CM_MAX_EXC];
    uint8_t active[CM_MAX_EXC];
    uint8_t enabled[CM_MAX_EXC];   /* interrupts only */
    uint8_t line[CM_MAX_EXC];      /* interrupt input level */

    /* System Control Block */
    uint32_t vtor, prigroup, scr, ccr, shcsr_ena, cfsr, hfsr, dfsr, mmfar, bfar, afsr;
    uint32_t cpacr, actlr;

    /* SysTick */
    uint32_t syst_csr, syst_rvr, syst_cvr, syst_calib;
    uint32_t syst_acc;          /* HCLK cycles towards the next external-clock tick */
    uint32_t syst_ext_div;      /* external reference clock divider (8 on STM32) */

    /* Debug and trace */
    uint32_t demcr, dhcsr, dwt_ctrl, dwt_cyccnt, dwt_other[8];
    uint32_t itm_ter, itm_tpr, itm_tcr;

    /* MPU (registers only for now) */
    uint32_t mpu_ctrl, mpu_rnr, mpu_rbar[8], mpu_rasr[8];

    /* FPU (FPv4-SP): S0-S31, FPSCR, and its control registers in the SCB */
    uint32_t s[32];
    uint32_t fpscr;
    uint32_t fpccr, fpcar, fpdscr;

    /* Exclusive monitor */
    int excl_valid;
    uint32_t excl_addr;

    /* Run state. The run loop looks at these before every instruction, as
     * one word (attn_pre): any of them set takes it off its fast path. */
    union {
        struct {
            uint8_t check_irq;  /* look for a pending exception before the next instruction */
            uint8_t lockup;     /* fault while a fault could not be taken */
            uint8_t sleeping;   /* 1: in WFI or WFE, waiting for a wake-up event */
            uint8_t stop_batch; /* set by callbacks to end cm_run early */
        };
        uint32_t attn_pre;
    };
    int event_reg;              /* WFE event register */
    uint64_t cycles;            /* cycles run (one per instruction) */
    uint64_t synced;            /* cycles already applied to SysTick and DWT */

    /* Set while an instruction executes. The run loop looks at these after
     * every instruction, as one word (attn_post). */
    uint32_t next_pc;
    union {
        struct {
            uint8_t sync_exc;   /* synchronous exception raised by the instruction */
            uint8_t it;         /* ITSTATE */
            uint8_t exc_ret_pending;    /* it loaded EXC_RETURN into the PC */
            uint8_t unused_;
        };
        uint32_t attn_post;
    };
    uint32_t sync_ret;          /* return address for it */
    int it_set;                 /* the instruction was IT */
    uint32_t exc_ret_value;

    /* For idle detection: counts stores that changed memory (writes to
     * peripherals and system registers always count). */
    uint64_t mem_changes;
    /* Busy loops: a backward branch whose registers and memory are the same
     * as 64 iterations ago can only be left through an interrupt. */
    struct cm_loop {
        uint32_t pc, hits, xpsr;
        uint64_t changes;
        uint32_t regs[15];
    } loops[16];
    int idle_loop;

    /* Last fault, for the fault explainer: the instruction, the registers as
     * they were when it faulted (r15 = fault_pc), the exception it raised
     * before any escalation to HardFault, and how many faults so far */
    uint32_t fault_pc;
    uint32_t fault_r[16], fault_xpsr;
    int fault_exc;
    uint32_t fault_count;

    struct cm_bus *bus;
    void *hook_ctx;
    void (*on_sysresetreq)(void *ctx);
    void (*on_itm)(void *ctx, int port, uint32_t value, int size);
};

void cm_init(struct cm_cpu *c, struct cm_bus *bus, int num_irq, int prio_bits);

/* Core reset: loads SP and PC from the vector table at address 0. */
void cm_reset(struct cm_cpu *c);

/* Runs up to n instructions. Returns the number of cycles run. Stops early
 * when the core sleeps, locks up, or stop_batch is set. */
uint32_t cm_run(struct cm_cpu *c, uint32_t n);

/* Interrupt input from a peripheral (level). */
void cm_set_irq(struct cm_cpu *c, int irq, int level);

/* Makes an exception pending (NMI, PendSV, ...). */
void cm_pend(struct cm_cpu *c, int exc);

/* True if a pending exception would wake the core from WFI. */
int cm_wake_pending(const struct cm_cpu *c);

/* Wakes the core from sleep (WFE event or explicit wake). */
void cm_wake(struct cm_cpu *c);

/* Cycles until SysTick next counts to zero, or UINT64_MAX. */
uint64_t cm_cycles_to_systick(struct cm_cpu *c);

/* Advances the counters by cycles without running instructions (sleep,
 * idle loops). */
void cm_idle_cycles(struct cm_cpu *c, uint64_t cycles);

/* Applies cycles run so far to SysTick and the DWT cycle counter. */
void cm_sync_counters(struct cm_cpu *c);

uint32_t cm_xpsr(const struct cm_cpu *c);
uint32_t cm_msp(const struct cm_cpu *c);
uint32_t cm_psp(const struct cm_cpu *c);

/* Debug read without side effects. Returns bytes copied (unmapped bytes
 * read as zero and still count). */
size_t cm_peek(const struct cm_cpu *c, uint32_t addr, uint8_t *buf, size_t len);

/* Debug read of a System Control Space register (0xE000_0000..). */
int cm_scs_peek(struct cm_cpu *c, uint32_t addr, uint32_t *value);

/* A word write as privileged code would make it, side effects included (a
 * debugger's write). Returns nonzero on a bus error. */
int cm_debug_write32(struct cm_cpu *c, uint32_t addr, uint32_t value);

#endif
