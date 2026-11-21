/*
 * Solderless - self-checking test firmware for the exception model.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 *
 * Runs on the bare core of tests/cpu/exc_test.c: code at 0, RAM at
 * 0x2000_0000, every other address gives a bus error. Results:
 *   RES+0  0xD0D0D0D0 when finished
 *   RES+4  bit n set: test n failed
 *   RES+8  number of tests run
 * Expected behaviour is taken from the ARMv7-M ARM, chapter B1.5.
 */
    .syntax unified
    .cpu cortex-m4
    .fpu fpv4-sp-d16
    .thumb

    .equ RES,       0x20000000
    .equ FAILS,     RES + 4
    .equ COUNT,     RES + 8
    .equ C_SVC,     RES + 0x10      /* handler counters */
    .equ C_PENDSV,  RES + 0x14
    .equ C_SYSTICK, RES + 0x18
    .equ C_IRQ2,    RES + 0x1C
    .equ C_IRQ3,    RES + 0x20
    .equ C_HARD,    RES + 0x24
    .equ C_USAGE,   RES + 0x28
    .equ C_BUS,     RES + 0x2C
    .equ C_MEM,     RES + 0x30
    .equ R_LR,      RES + 0x34      /* recorded values */
    .equ R_IMM,     RES + 0x38
    .equ R_CFSR,    RES + 0x3C
    .equ R_HFSR,    RES + 0x40
    .equ R_BFAR,    RES + 0x44
    .equ R_IPSR,    RES + 0x48
    .equ R_XPSR,    RES + 0x4C
    .equ R_ICSR,    RES + 0x50
    .equ F_PEND0,   RES + 0x54      /* flags for the IRQ handlers */
    .equ F_PEND1,   RES + 0x58
    .equ LOG_N,     RES + 0x5C
    .equ LOG,       RES + 0x60      /* 16 words */
    .equ EXCL,      RES + 0xA0
    .equ R_FPCCR,   RES + 0xB0      /* FP context: FPCCR and FPCAR at the handler's start */
    .equ R_FPCAR,   RES + 0xB4
    .equ R_FRAME,   RES + 0xB8      /* the handler's frame */
    .equ R_FPCCR2,  RES + 0xBC      /* FPCCR after the handler's first FP instruction */
    .equ R_SAVED,   RES + 0xC0      /* what that instruction saved for S0 */
    .equ BBWORD,    RES + 0x100
    .equ BBALIAS,   0x22000000 + 0x100 * 32

    .equ ICSR,      0xE000ED04
    .equ SHCSR,     0xE000ED24
    .equ CFSR,      0xE000ED28
    .equ HFSR,      0xE000ED2C
    .equ BFAR,      0xE000ED38
    .equ SHPR2,     0xE000ED1C
    .equ SHPR3,     0xE000ED20
    .equ ISER0,     0xE000E100
    .equ ISPR0,     0xE000E200
    .equ IPR0,      0xE000E400
    .equ SYST_CSR,  0xE000E010
    .equ SYST_RVR,  0xE000E014
    .equ SYST_CVR,  0xE000E018
    .equ CPACR,     0xE000ED88
    .equ FPCCR,     0xE000EF34
    .equ FPCAR,     0xE000EF38
    .equ MVFR0,     0xE000EF40

/* r11 = number of the current test */
.macro TEST n
    mov r11, #\n
    ldr r10, =COUNT
    ldr r9, [r10]
    adds r9, #1
    str r9, [r10]
.endm

/* Fails the current test unless the word at addr equals val */
.macro EXPECT_MEM addr, val
    ldr r10, =\addr
    ldr r10, [r10]
    ldr r9, =\val
    cmp r10, r9
    it ne
    blne fail
.endm

.macro EXPECT_REG reg, val
    ldr r9, =\val
    cmp \reg, r9
    it ne
    blne fail
.endm

.macro STORE addr, val
    ldr r10, =\addr
    ldr r9, =\val
    str r9, [r10]
.endm

    .section .text
    .global _start
vectors:
    .word 0x20008000            /* initial MSP */
    .word reset + 1
    .word hardfault + 1         /* NMI: not used */
    .word hardfault + 1
    .word memmanage + 1
    .word busfault + 1
    .word usagefault + 1
    .word 0, 0, 0, 0
    .word svc + 1
    .word 0, 0
    .word pendsv + 1
    .word systick + 1
    .word irq0 + 1, irq1 + 1, irq2 + 1, irq3 + 1

reset:
_start:
    /* clear the result area */
    ldr r0, =RES
    movs r1, #0
    movs r2, #64
1:  str r1, [r0], #4
    subs r2, #1
    bne 1b

    /* T0: SVC preserves the caller-saved registers and sees its immediate */
    TEST 0
    mov r0, #0x11
    mov r1, #0x22
    mov r2, #0x33
    mov r3, #0x44
    mov r12, #0x55
    svc #7
    EXPECT_REG r0, 0x11
    EXPECT_REG r1, 0x22
    EXPECT_REG r2, 0x33
    EXPECT_REG r3, 0x44
    EXPECT_REG r12, 0x55
    EXPECT_MEM C_SVC, 1
    EXPECT_MEM R_IMM, 7
    EXPECT_MEM R_LR, 0xFFFFFFF9

    /* T1: PendSV runs as soon as it is pended; IPSR and VECTACTIVE are 14 */
    TEST 1
    STORE ICSR, (1 << 28)
    isb
    EXPECT_MEM C_PENDSV, 1
    EXPECT_MEM R_IPSR, 14
    EXPECT_MEM R_ICSR, 14

    /* T2: a higher-priority interrupt preempts a running one */
    TEST 2
    STORE IPR0, 0x00004080      /* IRQ0 0x80, IRQ1 0x40 */
    STORE ISER0, 0xF
    STORE F_PEND1, 1
    STORE LOG_N, 0
    STORE ISPR0, 1
    isb
    STORE F_PEND1, 0
    EXPECT_MEM LOG_N, 4
    EXPECT_MEM LOG + 0, 0
    EXPECT_MEM LOG + 4, 1
    EXPECT_MEM LOG + 8, 0x11
    EXPECT_MEM LOG + 12, 0x10

    /* T3: a lower-priority interrupt pended by a handler runs after it */
    TEST 3
    STORE F_PEND0, 1
    STORE LOG_N, 0
    STORE ISPR0, 2
    isb
    STORE F_PEND0, 0
    EXPECT_MEM LOG_N, 4
    EXPECT_MEM LOG + 0, 1
    EXPECT_MEM LOG + 4, 0x11
    EXPECT_MEM LOG + 8, 0
    EXPECT_MEM LOG + 12, 0x10

    /* T4: PRIMASK holds interrupts back until it is cleared */
    TEST 4
    cpsid i
    STORE ISPR0, 4
    isb
    EXPECT_MEM C_IRQ2, 0
    cpsie i
    isb
    EXPECT_MEM C_IRQ2, 1

    /* T5: BASEPRI masks priorities at or below it */
    TEST 5
    ldr r0, =IPR0 + 3
    mov r1, #0x80
    strb r1, [r0]               /* IRQ3 priority 0x80 */
    mov r0, #0x80
    msr basepri, r0
    STORE ISPR0, 8
    isb
    EXPECT_MEM C_IRQ3, 0
    ldr r0, =IPR0 + 3
    mov r1, #0x40
    strb r1, [r0]               /* now above BASEPRI */
    isb
    EXPECT_MEM C_IRQ3, 1
    mov r0, #0
    msr basepri, r0

    /* T6: UsageFault when enabled: UNDEFINSTR, no HardFault */
    TEST 6
    STORE SHCSR, (1 << 18)
    udf #0
    EXPECT_MEM C_USAGE, 1
    EXPECT_MEM C_HARD, 0
    EXPECT_MEM R_CFSR, (1 << 16)

    /* T7: with UsageFault disabled the same fault escalates to HardFault */
    TEST 7
    STORE SHCSR, 0
    udf #0
    EXPECT_MEM C_HARD, 1
    EXPECT_MEM R_HFSR, (1 << 30)
    EXPECT_MEM R_CFSR, (1 << 16)

    /* T8: precise BusFault with the faulting address in BFAR */
    TEST 8
    STORE SHCSR, (1 << 17)
    ldr r0, =0x60000000
    ldr r1, [r0]
    EXPECT_MEM C_BUS, 1
    EXPECT_MEM R_BFAR, 0x60000000
    EXPECT_MEM R_CFSR, (1 << 9) | (1 << 15)

    /* T9: Thread mode on the process stack: EXC_RETURN 0xFFFFFFFD */
    TEST 9
    ldr r0, =0x20006000
    msr psp, r0
    mov r0, #2
    msr control, r0
    isb
    svc #2
    EXPECT_MEM R_LR, 0xFFFFFFFD
    mrs r0, control
    EXPECT_REG r0, 2
    mov r0, sp
    EXPECT_REG r0, 0x20006000
    mov r0, #0
    msr control, r0
    isb

    /* T10: unprivileged code cannot touch PRIMASK or the SCS; SVC regains privilege */
    TEST 10
    mov r0, #1
    msr control, r0
    isb
    msr primask, r0             /* ignored when unprivileged */
    mrs r1, primask
    EXPECT_REG r1, 0
    ldr r0, =ICSR
    ldr r1, [r0]                /* BusFault */
    EXPECT_MEM C_BUS, 2
    svc #1                      /* handler clears nPRIV */
    mrs r0, control
    EXPECT_REG r0, 0

    /* T11: an SP that is not 8-byte aligned is aligned for the frame, and
     * xPSR bit 9 records it */
    TEST 11
    sub sp, #4
    mov r4, sp
    svc #3
    mov r0, sp
    cmp r0, r4
    it ne
    blne fail
    add sp, #4
    ldr r0, =R_XPSR
    ldr r0, [r0]
    ands r0, #(1 << 9)
    EXPECT_REG r0, (1 << 9)

    /* T12: SysTick interrupts */
    TEST 12
    STORE SYST_RVR, 50
    STORE SYST_CVR, 0
    STORE SYST_CSR, 7
    mov r0, #200
1:  subs r0, #1
    bne 1b
    STORE SYST_CSR, 0
    ldr r0, =C_SYSTICK
    ldr r0, [r0]
    cmp r0, #2
    it lo
    bllo fail

    /* T13: exception entry clears the exclusive monitor */
    TEST 13
    ldr r0, =EXCL
    ldrex r1, [r0]
    mov r3, #5
    strex r2, r3, [r0]
    EXPECT_REG r2, 0
    ldrex r1, [r0]
    svc #0
    strex r2, r3, [r0]
    EXPECT_REG r2, 1

    /* T14: bit-band alias */
    TEST 14
    STORE BBWORD, 0
    STORE BBALIAS + 5 * 4, 1
    EXPECT_MEM BBWORD, 0x20
    EXPECT_MEM BBALIAS + 5 * 4, 1
    EXPECT_MEM BBALIAS + 4 * 4, 0

    /* T15: executing from a peripheral address is a MemManage fault */
    TEST 15
    STORE SHCSR, (1 << 16)
    ldr r0, =0x40000001
    blx r0
    EXPECT_MEM C_MEM, 1
    EXPECT_MEM R_CFSR, 1        /* IACCVIOL */

    /* T16: SVC from a handler with higher priority than SVCall escalates */
    TEST 16
    STORE SHPR2, 0xF0000000     /* SVCall priority 0xF0 */
    STORE SHPR3, 0x00E00000     /* PendSV priority 0xE0 */
    STORE SHCSR, 0
    ldr r0, =C_HARD
    ldr r5, [r0]
    STORE R_IMM, 0x99           /* tells pendsv to issue an SVC */
    STORE ICSR, (1 << 28)
    isb
    ldr r0, =C_HARD
    ldr r0, [r0]
    sub r0, r5
    EXPECT_REG r0, 1
    EXPECT_MEM R_HFSR, (1 << 30)

    /* T17: an FP context stacks lazily: room in an extended frame, saved by
     * the handler's first FP instruction, restored on the return */
    TEST 17
    EXPECT_MEM MVFR0, 0x10110021
    STORE CPACR, (0xF << 20)
    dsb
    isb
    ldr r0, =0x3F800000
    vmov s0, r0                 /* the first FP instruction: CONTROL.FPCA */
    ldr r0, =0x40000000
    vmov s15, r0
    mrs r0, control
    EXPECT_REG r0, 4
    svc #2
    vmov r0, s0
    EXPECT_REG r0, 0x3F800000   /* the handler's 2.0 undone */
    vmov r0, s15
    EXPECT_REG r0, 0x40000000
    EXPECT_MEM R_LR, 0xFFFFFFE9 /* extended frame, Thread mode, MSP */
    ldr r0, =R_FPCCR
    ldr r0, [r0]
    and r0, #9                  /* LSPACT, THREAD */
    EXPECT_REG r0, 9
    ldr r0, =R_FPCAR
    ldr r0, [r0]
    ldr r1, =R_FRAME
    ldr r1, [r1]
    sub r0, r1
    EXPECT_REG r0, 0x20
    ldr r0, =R_FPCCR2
    ldr r0, [r0]
    and r0, #1
    EXPECT_REG r0, 0
    EXPECT_MEM R_SAVED, 0x3F800000
    EXPECT_MEM FPCCR, 0xC0000018 /* LSPACT clear again; THREAD, HFRDY */

    /* T18: a handler without FP instructions leaves the registers alone,
     * whatever its frame holds */
    TEST 18
    svc #3                      /* writes 0x12345678 where S0 would be saved */
    vmov r0, s0
    EXPECT_REG r0, 0x3F800000
    ldr r0, =FPCCR
    ldr r0, [r0]
    and r0, #1
    EXPECT_REG r0, 0

    /* T19: without lazy stacking S0-S15 are saved at once and restored */
    TEST 19
    STORE FPCCR, 0x80000000     /* ASPEN only */
    vmov s0, r0                 /* r0 = 0 */
    svc #3
    vmov r0, s0
    EXPECT_REG r0, 0x12345678
    STORE FPCCR, 0xC0000000

    /* T20: no FP context, a basic frame */
    TEST 20
    mrs r0, control
    bic r0, #4
    msr control, r0
    isb
    svc #0
    EXPECT_MEM R_LR, 0xFFFFFFF9
    vmov.f32 s1, #1.0           /* a new context: CONTROL.FPCA again */
    mrs r0, control
    EXPECT_REG r0, 4

    STORE RES, 0xD0D0D0D0
done:
    b done

fail:
    ldr r10, =FAILS
    ldr r9, [r10]
    mov r8, #1
    lsl r8, r8, r11
    orr r9, r8
    str r9, [r10]
    bx lr

/* ------------------------------------------------------------------ */

/* Frame pointer of the exception in r0 (MSP or PSP, from EXC_RETURN) */
.macro FRAME
    tst lr, #4
    ite eq
    mrseq r0, msp
    mrsne r0, psp
.endm

.macro INC addr
    ldr r1, =\addr
    ldr r2, [r1]
    adds r2, #1
    str r2, [r1]
.endm

svc:
    INC C_SVC
    ldr r1, =R_LR
    str lr, [r1]
    FRAME
    ldr r1, [r0, #24]           /* stacked PC */
    ldrb r1, [r1, #-2]          /* SVC immediate */
    ldr r2, =R_IMM
    str r1, [r2]
    ldr r2, [r0, #28]
    ldr r3, =R_XPSR
    str r2, [r3]
    cmp r1, #2
    bne 2f
    ldr r1, =FPCCR              /* SVC 2: record the lazy state, then use the FPU */
    ldr r2, [r1]
    ldr r3, =R_FPCCR
    str r2, [r3]
    ldr r2, =FPCAR
    ldr r2, [r2]
    ldr r3, =R_FPCAR
    str r2, [r3]
    ldr r3, =R_FRAME
    str r0, [r3]
    vmov.f32 s0, #2.0
    ldr r2, [r1]
    ldr r3, =R_FPCCR2
    str r2, [r3]
    ldr r2, =R_FPCAR
    ldr r2, [r2]
    ldr r2, [r2]
    ldr r3, =R_SAVED
    str r2, [r3]
2:  cmp r1, #3
    bne 3f
    ldr r2, =0x12345678         /* SVC 3: scribble over S0's place in the frame */
    str r2, [r0, #0x20]
3:  cmp r1, #1
    bne 1f
    mrs r1, control             /* SVC 1: back to privileged */
    bic r1, #1
    msr control, r1
1:  mov r0, #0xAA               /* clobber what the frame restores */
    mov r1, #0xAA
    mov r2, #0xAA
    mov r3, #0xAA
    mov r12, #0xAA
    bx lr

pendsv:
    INC C_PENDSV
    mrs r1, ipsr
    ldr r2, =R_IPSR
    str r1, [r2]
    ldr r1, =ICSR
    ldr r1, [r1]
    ubfx r1, r1, #0, #9
    ldr r2, =R_ICSR
    str r1, [r2]
    ldr r1, =R_IMM
    ldr r1, [r1]
    cmp r1, #0x99
    bne 1f
    svc #9                      /* escalates: SVCall cannot preempt */
1:  bx lr

systick:
    INC C_SYSTICK
    bx lr

log_append:                     /* r1: value */
    ldr r2, =LOG_N
    ldr r3, [r2]
    ldr r0, =LOG
    str r1, [r0, r3, lsl #2]
    adds r3, #1
    str r3, [r2]
    bx lr

irq0:
    push {lr}
    mov r1, #0
    bl log_append
    ldr r0, =F_PEND1
    ldr r0, [r0]
    cbz r0, 1f
    STORE ISPR0, 2
    isb
1:  mov r1, #0x10
    bl log_append
    pop {pc}

irq1:
    push {lr}
    mov r1, #1
    bl log_append
    ldr r0, =F_PEND0
    ldr r0, [r0]
    cbz r0, 1f
    STORE ISPR0, 1
    isb
1:  mov r1, #0x11
    bl log_append
    pop {pc}

irq2:
    INC C_IRQ2
    bx lr

irq3:
    INC C_IRQ3
    bx lr

/* Faults: record the status registers, clear them, skip the 16-bit
 * faulting instruction */
.macro FAULT counter
    INC \counter
    ldr r1, =CFSR
    ldr r2, [r1]
    str r2, [r1]                /* write 1 to clear */
    ldr r3, =R_CFSR
    str r2, [r3]
    ldr r1, =HFSR
    ldr r2, [r1]
    str r2, [r1]
    ldr r3, =R_HFSR
    str r2, [r3]
    ldr r1, =BFAR
    ldr r2, [r1]
    ldr r3, =R_BFAR
    str r2, [r3]
    FRAME
    ldr r1, [r0, #24]
    adds r1, #2
    str r1, [r0, #24]
    bx lr
.endm

hardfault:  FAULT C_HARD
usagefault: FAULT C_USAGE
busfault:   FAULT C_BUS

memmanage:                      /* return to the caller of the bad BLX */
    INC C_MEM
    ldr r1, =CFSR
    ldr r2, [r1]
    str r2, [r1]
    ldr r3, =R_CFSR
    str r2, [r3]
    FRAME
    ldr r1, [r0, #20]           /* stacked LR */
    bic r1, #1
    str r1, [r0, #24]
    bx lr

    .ltorg
