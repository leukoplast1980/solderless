/*
 * Solderless - the fault explainer: what the last fault was, in words, where
 * it happened (the Forth word and its callers), the instruction, why it
 * likely happened, and the registers and data stack at that moment.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.chip

import java.util.Locale

class FaultReport(
    val cfsr: Int, val hfsr: Int, val bfar: Int, val mmfar: Int, val lockup: Boolean,
    /** The exception the fault raised before any escalation (3 to 6) */
    val exception: Int,
    /** r0..r15 when it happened (r15: the instruction) */
    val regs: IntArray,
    val xpsr: Int,
    /** Memory as a debugger reads it: [n] bytes at an address, or null */
    read: (Long, Int) -> ByteArray?,
    /** The Forth word and offset an address is in: "show-temp +12", or null */
    place: (Long) -> String?,
    /** Stellaris' data stack base (r7 counts down from it), or 0 for other firmware */
    dataStackBase: Long,
) {
    private fun u(v: Int) = v.toLong() and 0xFFFFFFFFL
    private fun hex(v: Long) = "%04X %04X".format(Locale.ROOT, (v ushr 16) and 0xFFFF, v and 0xFFFF)

    val pc: Long = u(regs[15]) and 1L.inv()

    /** The instruction's halfwords: "6800", "F8D6 6800" */
    var halfwords: String = ""
        private set

    /** The instruction that faulted, decoded; null if its memory cannot be read */
    val insn: Thumb.Insn? = read(pc, 4)?.takeIf { it.size >= 2 }?.let { b ->
        val h1 = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val h2 = if (b.size >= 4) (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8) else 0
        halfwords = if (Thumb.is32(h1)) "%04X %04X".format(Locale.ROOT, h1, h2) else "%04X".format(Locale.ROOT, h1)
        Thumb.decode(pc, h1, h2)
    }

    val title: String = run {
        val name = NAMES[exception] ?: "fault"
        when {
            lockup -> "Locked up after a $name"
            exception in 4..6 && hfsr and HFSR_FORCED != 0 -> "HardFault, escalated from a $name"
            else -> name
        }
    }

    /** What the fault status registers say, in words */
    val causes: List<String> = buildList {
        val c = cfsr
        if (lockup) add("The core stopped: a fault happened where no fault handler could run. Press Reset.")
        if (hfsr and (1 shl 1) != 0) add("A vector table read failed.")
        if (hfsr and (1 shl 31) != 0) add("BKPT or a debug event with no debugger attached.")
        if (c and (1 shl 0) != 0) add("Instruction fetch from a place that cannot hold code (a peripheral or system area).")
        if (c and (1 shl 1) != 0) add("Data access not allowed by the MPU (at ${hex(u(mmfar))}).")
        if (c and (1 shl 8) != 0) add("Instruction fetch from an address where nothing is mapped.")
        if (c and (1 shl 9) != 0) add("${if (insn?.store == true) "Store to" else "Load from"} ${hex(u(bfar))}: nothing is mapped there.")
        if (c and (1 shl 10) != 0) add("A store went to an address where nothing is mapped (imprecise: the PC is past it).")
        if (c and (1 shl 12) != 0) add("Stacking the exception frame failed: the stack pointer is bad.")
        if (c and (1 shl 11) != 0) add("Unstacking on exception return failed: the stack pointer is bad.")
        if (c and (1 shl 16) != 0) add("Undefined instruction.")
        if (c and (1 shl 17) != 0) add("Branch to an even address: the Thumb bit was lost.")
        if (c and (1 shl 18) != 0) add("Bad exception return value (EXC_RETURN) in the PC.")
        if (c and (1 shl 19) != 0) add("A coprocessor (FPU) instruction while CPACR does not allow it.")
        if (c and (1 shl 24) != 0) add("Unaligned access where alignment is required (LDM, STM, LDRD, STRD, exclusives).")
        if (c and (1 shl 25) != 0) add("Division by zero (CCR.DIV_0_TRP is set).")
        if (isEmpty() && hfsr and HFSR_FORCED != 0) add("A fault was escalated to HardFault.")
    }


    /** "in show-temp +4" */
    val where: String? = place(pc)

    /** The words that called it: from LR and the return stack (Stellaris keeps return addresses on sp) */
    val calledFrom: List<String> = buildList {
        val seen = HashSet<String>()
        fun tryAdd(v: Long) {
            if (v and 1L == 0L || size >= 4) return
            val p = place(v - 1) ?: return     // the return address: just after the call
            if (seen.add(p)) add(p)
        }
        tryAdd(u(regs[14]))
        read(u(regs[13]), 64)?.let { b ->
            for (i in 0 until b.size / 4) {
                val v = (b[4 * i].toLong() and 0xFF) or ((b[4 * i + 1].toLong() and 0xFF) shl 8) or
                    ((b[4 * i + 2].toLong() and 0xFF) shl 16) or ((b[4 * i + 3].toLong() and 0xFF) shl 24)
                tryAdd(v)
            }
        }
    }

    /** Why it likely happened, as far as the instruction and registers tell */
    val hints: List<String> = buildList {
        val i = insn
        val addr = when {
            cfsr and (1 shl 15) != 0 -> u(bfar)
            cfsr and (1 shl 7) != 0 -> u(mmfar)
            else -> -1L
        }
        if (i != null && addr >= 0 && i.base in 0..12) {
            val base = u(regs[i.base])
            if (((base + i.offset) and 0xFFFFFFFFL) == addr) {
                add("The address came from r${i.base} (${hex(base)})" + (if (i.offset != 0L) " ${if (i.offset > 0) "+" else "−"} ${kotlin.math.abs(i.offset)}" else "") + ".")
                if (i.base == 6 && dataStackBase != 0L)
                    add("r6 is TOS, the top of Forth's data stack: @, ! or another memory word got a wrong address.")
            }
        }
        if (addr >= 0) add(region(addr))
        if (cfsr and (1 shl 16) != 0) add("That is not an instruction this core runs: execution went into data, or into the middle of an instruction.")
        if (cfsr and (1 shl 17) != 0) add("BX, BLX or POP {pc} got an even address. Addresses of code end in 1 on this core; in Forth, execute was given something that is not an xt.")
        if (cfsr and (1 shl 25) != 0) add("A divide by 0: / or mod with 0 on the stack.")
        if (cfsr and (1 shl 24) != 0) add("The address is not a multiple of 4.")
        if (cfsr and (1 shl 19) != 0) add("Give CP10 and CP11 full access in SCB_CPACR first (Stellaris for the G431 does this at start).")
        if (cfsr and ((1 shl 12) or (1 shl 11) or (1 shl 4) or (1 shl 3)) != 0)
            add("The stack pointer was ${hex(u(regs[13]))}: RAM is 2000 0000 to 2000 7FFF. The return stack may have overflowed.")
    }

    /** Forth's data stack when it happened: TOS (r6) first, then what r7 points to; empty for other firmware */
    val dataStack: List<Long> = if (dataStackBase == 0L) emptyList() else buildList {
        val psp = u(regs[7])
        val depth = ((dataStackBase - psp) / 4).toInt()
        if (depth < 0 || depth > 64) return@buildList
        if (depth >= 1) add(u(regs[6]))
        val more = minOf(depth - 1, 4)
        if (more > 0) read(psp, more * 4)?.let { b ->
            for (k in 0 until more) add((b[4 * k].toLong() and 0xFF) or ((b[4 * k + 1].toLong() and 0xFF) shl 8) or
                ((b[4 * k + 2].toLong() and 0xFF) shl 16) or ((b[4 * k + 3].toLong() and 0xFF) shl 24))
        }
    }

    /** What is at an address on the G431 */
    private fun region(a: Long): String = when (a) {
        in 0x00000000L..0x0001FFFFL -> "${hex(a)} is flash, seen at address 0."
        in 0x08000000L..0x0801FFFFL -> "${hex(a)} is in flash."
        in 0x10000000L..0x100027FFL -> "${hex(a)} is in CCM SRAM."
        in 0x20000000L..0x20007FFFL -> "${hex(a)} is in RAM."
        in 0x40000000L..0x5FFFFFFFL -> "There is no peripheral at ${hex(a)}: the peripherals start at 4000 0000, each at its own address (the Registers section lists them)."
        in 0xE0000000L..0xE00FFFFFL -> "${hex(a)} is in the core's own registers."
        else -> "Nothing is at ${hex(a)}. Flash is 0800 0000 to 0801 FFFF, RAM 2000 0000 to 2000 7FFF, the peripherals from 4000 0000."
    }

    companion object {
        const val HFSR_FORCED = 1 shl 30
        val NAMES = mapOf(3 to "HardFault", 4 to "MemManage fault", 5 to "BusFault", 6 to "UsageFault")
    }
}
