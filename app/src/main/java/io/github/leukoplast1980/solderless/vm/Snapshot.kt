/*
 * Solderless - one consistent view of the CPU state.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

class Snapshot(raw: IntArray) {
    /** r0..r15 (r13 is the active stack pointer, r15 the PC) */
    val r = raw.copyOfRange(0, 16)
    val pc get() = r[15]
    val xpsr = raw[16]
    val msp = raw[17]
    val psp = raw[18]
    val primask = raw[19]
    val basepri = raw[20]
    val faultmask = raw[21]
    val control = raw[22]
    val cfsr = raw[23]
    val hfsr = raw[24]
    val mmfar = raw[25]
    val bfar = raw[26]
    val faultPc = raw[27]
    val cycles = (raw[29].toLong() shl 32) or (raw[28].toLong() and 0xffffffffL)
    val timeNs = (raw[31].toLong() shl 32) or (raw[30].toLong() and 0xffffffffL)
    val hclk = raw[32].toLong() and 0xffffffffL
    val sysclk = raw[33].toLong() and 0xffffffffL
    val pclk1 = raw[34].toLong() and 0xffffffffL
    val pclk2 = raw[35].toLong() and 0xffffffffL
    val lockup = raw[36] != 0
    val sleeping = raw[37] != 0
    val waitingForKey = raw[38] != 0
    val paused = raw[39] != 0
    val speedPercent = raw[40]
    /** The registers when the last fault happened (r15: the faulting instruction) */
    val faultR = raw.copyOfRange(41, 57)
    val faultXpsr = raw[57]
    /** The exception the fault raised before any escalation: 3 HardFault, 4 MemManage, 5 BusFault, 6 UsageFault */
    val faultException = raw[58]
    /** Faults since power-on (0: none yet) */
    val faultCount = raw[59].toLong() and 0xffffffffL

    /** Active exception number (0: Thread mode) */
    val ipsr get() = xpsr and 0x1ff

    companion object {
        const val SIZE = 60
    }
}
