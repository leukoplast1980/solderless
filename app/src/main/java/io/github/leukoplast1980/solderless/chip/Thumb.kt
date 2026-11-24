/*
 * Solderless - a Thumb-2 disassembler for the Cortex-M4 (ARMv7-M), for the
 * fault explainer: the instructions a fault comes from (loads, stores,
 * branches, the stack, divides, the FPU's loads) are decoded; the rest are
 * named by class or shown as numbers. The syntax is GNU as' unified syntax.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.chip

import java.util.Locale

object Thumb {

    class Insn(val size: Int, val text: String) {
        /** A load or store: its base register and offset, if it has a simple one */
        var base: Int = -1
        var offset: Long = 0
        var load = false
        var store = false
    }

    /** True if [hw1] starts a 32-bit instruction */
    fun is32(hw1: Int) = (hw1 ushr 11) in 0b11101..0b11111

    private val COND = arrayOf("eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc", "hi", "ls", "ge", "lt", "gt", "le", "", "")

    private fun r(n: Int) = when (n) { 13 -> "sp"; 14 -> "lr"; 15 -> "pc"; else -> "r$n" }
    private fun hex(v: Long) = "0x%X".format(Locale.ROOT, v)
    private fun imm(v: Long) = "#$v"
    private fun list(mask: Int) = (0..15).filter { mask shr it and 1 != 0 }.joinToString(", ", "{", "}") { r(it) }
    private fun addr(v: Long) = "0x%08X".format(Locale.ROOT, v and 0xFFFFFFFFL)

    /** Disassembles the instruction at [pc] from its first halfword and, if 32-bit, its second */
    fun decode(pc: Long, hw1: Int, hw2: Int): Insn = if (is32(hw1)) decode32(pc, hw1, hw2) else decode16(pc, hw1)

    // ------------------------------------------------------------------
    // 16-bit

    private fun ls(text: String, base: Int, off: Long, load: Boolean) = Insn(2, text).also {
        it.base = base; it.offset = off; it.load = load; it.store = !load
    }

    private fun decode16(pc: Long, h: Int): Insn {
        val lo3 = h and 7
        val mid3 = (h shr 3) and 7
        return when {
            h shr 11 == 0b00011 -> {
                val op = (h shr 9) and 3
                val rm = (h shr 6) and 7
                when (op) {
                    0 -> Insn(2, "adds ${r(lo3)}, ${r(mid3)}, ${r(rm)}")
                    1 -> Insn(2, "subs ${r(lo3)}, ${r(mid3)}, ${r(rm)}")
                    2 -> Insn(2, "adds ${r(lo3)}, ${r(mid3)}, #$rm")
                    else -> Insn(2, "subs ${r(lo3)}, ${r(mid3)}, #$rm")
                }
            }
            h shr 13 == 0b000 -> {
                val op = (h shr 11) and 3
                val sh = (h shr 6) and 31
                val n = arrayOf("lsls", "lsrs", "asrs")[op]
                if (op == 0 && sh == 0) Insn(2, "movs ${r(lo3)}, ${r(mid3)}")
                else Insn(2, "$n ${r(lo3)}, ${r(mid3)}, #${if (op != 0 && sh == 0) 32 else sh}")
            }
            h shr 13 == 0b001 -> {
                val rd = (h shr 8) and 7
                val n = arrayOf("movs", "cmp", "adds", "subs")[(h shr 11) and 3]
                Insn(2, "$n ${r(rd)}, #${h and 0xFF}")
            }
            h shr 10 == 0b010000 -> {
                val op = (h shr 6) and 15
                val n = arrayOf("ands", "eors", "lsls", "lsrs", "asrs", "adcs", "sbcs", "rors", "tst", "negs", "cmp", "cmn", "orrs", "muls", "bics", "mvns")[op]
                Insn(2, "$n ${r(lo3)}, ${r(mid3)}")
            }
            h shr 10 == 0b010001 -> {
                val op = (h shr 8) and 3
                val rd = (h and 7) or ((h shr 4) and 8)
                val rm = (h shr 3) and 15
                when (op) {
                    0 -> Insn(2, "add ${r(rd)}, ${r(rm)}")
                    1 -> Insn(2, "cmp ${r(rd)}, ${r(rm)}")
                    2 -> Insn(2, "mov ${r(rd)}, ${r(rm)}")
                    else -> Insn(2, if (h and 0x80 != 0) "blx ${r(rm)}" else "bx ${r(rm)}")
                }
            }
            h shr 11 == 0b01001 -> {
                val off = (h and 0xFF) * 4L
                ls("ldr ${r((h shr 8) and 7)}, [pc, #$off]", 15, ((pc + 4) and 3L.inv()) + off, true)
            }
            h shr 12 == 0b0101 -> {
                val op = (h shr 9) and 7
                val n = arrayOf("str", "strh", "strb", "ldrsb", "ldr", "ldrh", "ldrb", "ldrsh")[op]
                ls("$n ${r(lo3)}, [${r(mid3)}, ${r((h shr 6) and 7)}]", mid3, 0, op >= 3).also { it.base = -1 }
            }
            h shr 13 == 0b011 -> {
                val b = (h shr 12) and 1
                val load = (h shr 11) and 1 == 1
                val off = ((h shr 6) and 31).toLong() * (if (b == 1) 1 else 4)
                ls("${if (load) "ldr" else "str"}${if (b == 1) "b" else ""} ${r(lo3)}, [${r(mid3)}, #$off]", mid3, off, load)
            }
            h shr 12 == 0b1000 -> {
                val load = (h shr 11) and 1 == 1
                val off = ((h shr 6) and 31) * 2L
                ls("${if (load) "ldrh" else "strh"} ${r(lo3)}, [${r(mid3)}, #$off]", mid3, off, load)
            }
            h shr 12 == 0b1001 -> {
                val load = (h shr 11) and 1 == 1
                val off = (h and 0xFF) * 4L
                ls("${if (load) "ldr" else "str"} ${r((h shr 8) and 7)}, [sp, #$off]", 13, off, load)
            }
            h shr 12 == 0b1010 ->
                if ((h shr 11) and 1 == 0) Insn(2, "adr ${r((h shr 8) and 7)}, ${addr(((pc + 4) and 3L.inv()) + (h and 0xFF) * 4L)}")
                else Insn(2, "add ${r((h shr 8) and 7)}, sp, #${(h and 0xFF) * 4}")
            h shr 12 == 0b1011 -> misc16(pc, h)
            h shr 12 == 0b1100 -> {
                val load = (h shr 11) and 1 == 1
                val rn = (h shr 8) and 7
                val wb = !load || (h shr rn) and 1 == 0
                Insn(2, "${if (load) "ldmia" else "stmia"} ${r(rn)}${if (wb) "!" else ""}, ${list(h and 0xFF)}")
            }
            h shr 12 == 0b1101 -> {
                val c = (h shr 8) and 15
                when (c) {
                    14 -> Insn(2, "udf #${h and 0xFF}")
                    15 -> Insn(2, "svc ${h and 0xFF}")
                    else -> Insn(2, "b${COND[c]} ${addr(pc + 4 + (h.toByte().toLong() shl 1))}")
                }
            }
            h shr 11 == 0b11100 -> Insn(2, "b ${addr(pc + 4 + (((h and 0x7FF) shl 21) shr 20).toLong())}")
            else -> Insn(2, ".short 0x%04x".format(Locale.ROOT, h))
        }
    }

    private fun misc16(pc: Long, h: Int): Insn {
        val lo3 = h and 7
        val mid3 = (h shr 3) and 7
        return when {
            h shr 8 == 0xB0 -> Insn(2, "${if (h and 0x80 != 0) "sub" else "add"} sp, #${(h and 0x7F) * 4}")
            (h shr 8) and 0xF5 == 0xB1 -> {
                val off = (((h shr 9) and 1) shl 6) or (((h shr 3) and 31) shl 1)
                Insn(2, "${if (h and 0x800 != 0) "cbnz" else "cbz"} ${r(lo3)}, ${addr(pc + 4 + off)}")
            }
            h shr 8 == 0xB2 -> Insn(2, "${arrayOf("sxth", "sxtb", "uxth", "uxtb")[(h shr 6) and 3]} ${r(lo3)}, ${r(mid3)}")
            h shr 9 == 0b1011010 -> Insn(2, "push ${list((h and 0xFF) or (if (h and 0x100 != 0) 1 shl 14 else 0))}")
            h shr 9 == 0b1011110 -> Insn(2, "pop ${list((h and 0xFF) or (if (h and 0x100 != 0) 1 shl 15 else 0))}")
            h == 0xB662 -> Insn(2, "cpsie i")
            h == 0xB672 -> Insn(2, "cpsid i")
            h == 0xB661 -> Insn(2, "cpsie f")
            h == 0xB671 -> Insn(2, "cpsid f")
            h shr 8 == 0xBA -> Insn(2, "${arrayOf("rev", "rev16", "", "revsh")[(h shr 6) and 3]} ${r(lo3)}, ${r(mid3)}")
            h shr 8 == 0xBE -> Insn(2, "bkpt 0x%04x".format(Locale.ROOT, h and 0xFF))
            h shr 8 == 0xBF -> if (h and 15 != 0) Insn(2, "it… ${COND[(h shr 4) and 15]}")
                else Insn(2, arrayOf("nop", "yield", "wfe", "wfi", "sev").getOrElse((h shr 4) and 15) { "hint" })
            else -> Insn(2, ".short 0x%04x".format(Locale.ROOT, h))
        }
    }

    // ------------------------------------------------------------------
    // 32-bit

    /** The modified immediate of data-processing instructions (A5.3.2) */
    private fun thumbExpandImm(i: Int, imm3: Int, imm8: Int): Long {
        val imm12 = (i shl 11) or (imm3 shl 8) or imm8
        if (imm12 shr 10 == 0) {
            val b = imm8.toLong()
            return when ((imm12 shr 8) and 3) {
                0 -> b
                1 -> (b shl 16) or b
                2 -> (b shl 24) or (b shl 8)
                else -> (b shl 24) or (b shl 16) or (b shl 8) or b
            }
        }
        val v = (0x80 or (imm12 and 0x7F)).toLong()
        val rot = imm12 shr 7
        return ((v shr rot) or (v shl (32 - rot))) and 0xFFFFFFFFL
    }

    private fun shift(type: Int, amount: Int): String = when {
        type == 0 && amount == 0 -> ""
        type == 3 && amount == 0 -> ", rrx"
        else -> ", ${arrayOf("lsl", "lsr", "asr", "ror")[type]} #${if (amount == 0) 32 else amount}"
    }

    private fun decode32(pc: Long, h1: Int, h2: Int): Insn {
        val op1 = (h1 shr 11) and 3
        val op2 = (h1 shr 4) and 0x7F
        val rn = h1 and 15
        val rt = (h2 shr 12) and 15
        val rd = (h2 shr 8) and 15
        val rm = h2 and 15
        val unknown = Insn(4, ".inst.w 0x%04x%04x".format(Locale.ROOT, h1, h2))
        when (op1) {
            1 -> {
                if (op2 and 0x64 == 0x00) {         // load/store multiple
                    val load = h1 and 0x10 != 0
                    val wb = h1 and 0x20 != 0
                    val db = (h1 shr 7) and 3 == 2
                    return when {
                        rn == 13 && wb && db && !load -> Insn(4, "push.w ${list(h2)}")
                        rn == 13 && wb && !db && load -> Insn(4, "pop.w ${list(h2)}")
                        else -> Insn(4, "${if (load) "ldm" else "stm"}${if (db) "db" else "ia"}.w ${r(rn)}${if (wb) "!" else ""}, ${list(h2)}")
                    }
                }
                if (op2 and 0x64 == 0x04) {         // load/store dual, exclusive, table branch
                    val load = h1 and 0x10 != 0
                    if ((h1 shr 7) and 3 == 0 && (h1 shr 5) and 1 == 0) {
                        return if (h1 and 0x10 == 0) Insn(4, "strex ${r(rd)}, ${r(rt)}, [${r(rn)}, #${(h2 and 0xFF) * 4}]")
                        else Insn(4, "ldrex ${r(rt)}, [${r(rn)}${if (h2 and 0xFF != 0) ", #${(h2 and 0xFF) * 4}" else ""}]")
                    }
                    if (h1 and 0xFFF0 == 0xE8D0 && (h2 shr 4) and 0xF in 0..1)
                        return Insn(4, "${if ((h2 shr 4) and 1 == 0) "tbb" else "tbh"} [${r(rn)}, ${r(rm)}${if ((h2 shr 4) and 1 == 1) ", lsl #1" else ""}]")
                    if (h1 and 0xFFF0 == 0xE8C0 || h1 and 0xFFF0 == 0xE8D0) return unknown
                    val p = (h1 shr 8) and 1 == 1
                    val u = (h1 shr 7) and 1 == 1
                    val w = (h1 shr 5) and 1 == 1
                    val off = (h2 and 0xFF) * 4L * (if (u) 1 else -1)
                    val a = when {
                        p && w -> "[${r(rn)}, #$off]!"
                        p -> if (off == 0L) "[${r(rn)}]" else "[${r(rn)}, #$off]"
                        else -> "[${r(rn)}], #$off"
                    }
                    return Insn(4, "${if (load) "ldrd" else "strd"} ${r(rt)}, ${r(rd)}, $a").also {
                        it.base = rn; it.offset = if (p) off else 0; it.load = load; it.store = !load
                    }
                }
                if (op2 and 0x60 == 0x20) {         // data processing, shifted register
                    val op = (h1 shr 5) and 15
                    val s = if (h1 and 0x10 != 0) "s" else ""
                    val sh = shift((h2 shr 4) and 3, ((h2 shr 12) and 7 shl 2) or ((h2 shr 6) and 3))
                    val n = when (op) {
                        0 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "tst.w ${r(rn)}, ${r(rm)}$sh") else "and"
                        1 -> "bic"
                        2 -> if (rn == 15) return Insn(4, "mov$s.w ${r(rd)}, ${r(rm)}$sh") else "orr"
                        3 -> if (rn == 15) return Insn(4, "mvn$s.w ${r(rd)}, ${r(rm)}$sh") else "orn"
                        4 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "teq.w ${r(rn)}, ${r(rm)}$sh") else "eor"
                        8 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "cmn.w ${r(rn)}, ${r(rm)}$sh") else "add"
                        10 -> "adc"
                        11 -> "sbc"
                        13 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "cmp.w ${r(rn)}, ${r(rm)}$sh") else "sub"
                        14 -> "rsb"
                        else -> return unknown
                    }
                    return Insn(4, "$n$s.w ${r(rd)}, ${r(rn)}, ${r(rm)}$sh")
                }
                if (op2 and 0x40 == 0x40) return fpu(h1, h2) ?: unknown
                return unknown
            }
            2 -> {
                if (h2 and 0x8000 == 0) {
                    val i = (h1 shr 10) and 1
                    val imm3 = (h2 shr 12) and 7
                    val imm8 = h2 and 0xFF
                    if (h1 and 0x0200 == 0) {       // data processing, modified immediate
                        val op = (h1 shr 5) and 15
                        val s = if (h1 and 0x10 != 0) "s" else ""
                        val v = thumbExpandImm(i, imm3, imm8)
                        val n = when (op) {
                            0 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "tst.w ${r(rn)}, #$v") else "and"
                            1 -> "bic"
                            2 -> if (rn == 15) return Insn(4, "mov$s.w ${r(rd)}, #$v") else "orr"
                            3 -> if (rn == 15) return Insn(4, "mvn$s.w ${r(rd)}, #$v") else "orn"
                            4 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "teq.w ${r(rn)}, #$v") else "eor"
                            8 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "cmn.w ${r(rn)}, #$v") else "add"
                            10 -> "adc"
                            11 -> "sbc"
                            13 -> if (rd == 15 && s.isNotEmpty()) return Insn(4, "cmp.w ${r(rn)}, #$v") else "sub"
                            14 -> "rsb"
                            else -> return unknown
                        }
                        return Insn(4, "$n$s.w ${r(rd)}, ${r(rn)}, #$v")
                    }
                    // plain binary immediate
                    val op = (h1 shr 4) and 31
                    val imm12 = (i shl 11) or (imm3 shl 8) or imm8
                    val imm16 = ((h1 and 15) shl 12) or imm12
                    val lsb = (imm3 shl 2) or ((h2 shr 6) and 3)
                    val wid = (h2 and 31) + 1
                    return when (op) {
                        0 -> Insn(4, "addw ${r(rd)}, ${r(rn)}, #$imm12")
                        4 -> Insn(4, "movw ${r(rd)}, #$imm16")
                        10 -> Insn(4, "subw ${r(rd)}, ${r(rn)}, #$imm12")
                        12 -> Insn(4, "movt ${r(rd)}, #$imm16")
                        20 -> Insn(4, "sbfx ${r(rd)}, ${r(rn)}, #$lsb, #$wid")
                        22 -> if (rn == 15) Insn(4, "bfc ${r(rd)}, #$lsb, #${(h2 and 31) - lsb + 1}")
                            else Insn(4, "bfi ${r(rd)}, ${r(rn)}, #$lsb, #${(h2 and 31) - lsb + 1}")
                        28 -> Insn(4, "ubfx ${r(rd)}, ${r(rn)}, #$lsb, #$wid")
                        else -> unknown
                    }
                }
                return branch(pc, h1, h2) ?: unknown
            }
            else -> {
                if (op2 and 0x71 == 0x00 || op2 and 0x67 == 0x01 || op2 and 0x67 == 0x03 || op2 and 0x67 == 0x05)
                    return loadStore(h1, h2) ?: unknown
                if (op2 and 0x70 == 0x20) return dataReg(h1, h2) ?: unknown
                if (op2 and 0x78 == 0x30) {         // multiply
                    val ra = rt
                    return when ((h1 shr 4) and 7) {
                        0 -> if ((h2 shr 4) and 3 == 0) {
                            if (ra == 15) Insn(4, "mul.w ${r(rd)}, ${r(rn)}, ${r(rm)}") else Insn(4, "mla ${r(rd)}, ${r(rn)}, ${r(rm)}, ${r(ra)}")
                        } else Insn(4, "mls ${r(rd)}, ${r(rn)}, ${r(rm)}, ${r(ra)}")
                        else -> Insn(4, "smul… ${r(rd)}, ${r(rn)}, ${r(rm)}")
                    }
                }
                if (op2 and 0x78 == 0x38) {         // long multiply, divide
                    return when ((h1 shr 4) and 7) {
                        0 -> Insn(4, "smull ${r(rt)}, ${r(rd)}, ${r(rn)}, ${r(rm)}")
                        1 -> Insn(4, "sdiv ${r(rd)}, ${r(rn)}, ${r(rm)}")
                        2 -> Insn(4, "umull ${r(rt)}, ${r(rd)}, ${r(rn)}, ${r(rm)}")
                        3 -> Insn(4, "udiv ${r(rd)}, ${r(rn)}, ${r(rm)}")
                        4 -> Insn(4, "smlal ${r(rt)}, ${r(rd)}, ${r(rn)}, ${r(rm)}")
                        6 -> Insn(4, "umlal ${r(rt)}, ${r(rd)}, ${r(rn)}, ${r(rm)}")
                        else -> unknown
                    }
                }
                if (op2 and 0x40 == 0x40) return fpu(h1, h2) ?: unknown
                return unknown
            }
        }
    }

    private fun branch(pc: Long, h1: Int, h2: Int): Insn? {
        val op = (h2 shr 12) and 7
        val s = (h1 shr 10) and 1
        val j1 = (h2 shr 13) and 1
        val j2 = (h2 shr 11) and 1
        if (op and 5 == 0 && (h1 shr 7) and 7 != 7) {        // B<cond>.W
            val off = (if (s == 1) -1L shl 20 else 0L) or (j2.toLong() shl 19) or (j1.toLong() shl 18) or
                ((h1 and 0x3F).toLong() shl 12) or ((h2 and 0x7FF).toLong() shl 1)
            return Insn(4, "b${COND[(h1 shr 6) and 15]}.w ${addr(pc + 4 + off)}")
        }
        if (op and 1 == 1) {                                  // B.W, BL
            val i1 = 1 - (j1 xor s)
            val i2 = 1 - (j2 xor s)
            val off = (if (s == 1) -1L shl 24 else 0L) or (i1.toLong() shl 23) or (i2.toLong() shl 22) or
                ((h1 and 0x3FF).toLong() shl 12) or ((h2 and 0x7FF).toLong() shl 1)
            return Insn(4, "${if (op and 4 != 0) "bl" else "b.w"} ${addr(pc + 4 + off)}")
        }
        if (op and 5 == 0) {
            val sysm = h2 and 0xFF
            val sys = mapOf(0 to "APSR", 1 to "IAPSR", 2 to "EAPSR", 3 to "PSR", 5 to "IPSR", 6 to "EPSR", 7 to "IEPSR",
                8 to "MSP", 9 to "PSP", 16 to "PRIMASK", 17 to "BASEPRI", 18 to "BASEPRI_MAX", 19 to "FAULTMASK", 20 to "CONTROL")[sysm] ?: "$sysm"
            return when ((h1 shr 4) and 0x7F) {
                0x38, 0x39 -> Insn(4, "msr $sys, ${r(h1 and 15)}")
                0x3E, 0x3F -> Insn(4, "mrs ${r((h2 shr 8) and 15)}, $sys")
                0x3B -> Insn(4, when ((h2 shr 4) and 15) { 4 -> "dsb sy"; 5 -> "dmb sy"; 6 -> "isb sy"; else -> "barrier" })
                0x3A -> Insn(4, arrayOf("nop.w", "yield.w", "wfe.w", "wfi.w", "sev.w").getOrElse(h2 and 0xFF) { "hint.w" })
                else -> null
            }
        }
        if (op == 2 && (h1 shr 4) and 0x7F == 0x7F) return Insn(4, "udf.w #${((h1 and 15) shl 12) or (h2 and 0xFFF)}")
        return null
    }

    /** Loads and stores of one register (A5.3.7 to A5.3.10) */
    private fun loadStore(h1: Int, h2: Int): Insn? {
        val rn = h1 and 15
        val rt = (h2 shr 12) and 15
        val size = (h1 shr 5) and 3                  // 0 byte, 1 halfword, 2 word
        val signed = (h1 shr 8) and 1 == 1
        val load = (h1 shr 4) and 1 == 1
        if (size == 3) return null
        val n = (if (load) "ldr" else "str") + (if (signed) "s" else "") + arrayOf("b", "h", "")[size] + ".w"
        fun result(text: String, off: Long, simple: Boolean) = Insn(4, "$n ${r(rt)}, $text").also {
            it.base = if (simple) rn else -1; it.offset = off; it.load = load; it.store = !load
        }
        if (rn == 15 && load) {
            val off = (h2 and 0xFFF).toLong() * (if ((h1 shr 7) and 1 == 1) 1 else -1)
            return result("[pc, #$off]", off, true)
        }
        if ((h1 shr 7) and 1 == 1) {                 // imm12
            val off = (h2 and 0xFFF).toLong()
            return result(if (off == 0L) "[${r(rn)}]" else "[${r(rn)}, #$off]", off, true)
        }
        if ((h2 shr 6) and 63 == 0) {                // register
            val sh = (h2 shr 4) and 3
            return result("[${r(rn)}, ${r(h2 and 15)}${if (sh != 0) ", lsl #$sh" else ""}]", 0, false)
        }
        if ((h2 shr 11) and 1 == 1) {                // imm8, indexed
            val p = (h2 shr 10) and 1 == 1
            val u = (h2 shr 9) and 1 == 1
            val w = (h2 shr 8) and 1 == 1
            val off = (h2 and 0xFF).toLong() * (if (u) 1 else -1)
            val text = when {
                p && w -> "[${r(rn)}, #$off]!"
                p -> "[${r(rn)}, #$off]"
                else -> "[${r(rn)}], #$off"
            }
            return result(text, if (p) off else 0, true)
        }
        return null
    }

    /** Register shifts, extends, CLZ, REV (A5.3.12) */
    private fun dataReg(h1: Int, h2: Int): Insn? {
        val rn = h1 and 15
        val rd = (h2 shr 8) and 15
        val rm = h2 and 15
        val op1 = (h1 shr 4) and 15
        val op2 = (h2 shr 4) and 15
        if (op2 == 0 && op1 shr 3 == 0) {
            val s = if (op1 and 1 == 1) "s" else ""
            return Insn(4, "${arrayOf("lsl", "lsr", "asr", "ror")[(op1 shr 1) and 3]}$s.w ${r(rd)}, ${r(rn)}, ${r(rm)}")
        }
        if (op2 shr 3 == 1 && op1 shr 3 == 0) {
            val n = arrayOf("sxth", "uxth", "sxtb16", "uxtb16", "sxtb", "uxtb")[op1.coerceAtMost(5)]
            return Insn(4, "$n.w ${r(rd)}, ${r(rm)}")
        }
        if (op1 shr 2 == 2 && op2 shr 2 == 2) {
            return when (((op1 and 3) shl 2) or (op2 and 3)) {
                4 -> Insn(4, "rev.w ${r(rd)}, ${r(rm)}")
                5 -> Insn(4, "rev16.w ${r(rd)}, ${r(rm)}")
                6 -> Insn(4, "rbit ${r(rd)}, ${r(rm)}")
                7 -> Insn(4, "revsh.w ${r(rd)}, ${r(rm)}")
                12 -> Insn(4, "clz ${r(rd)}, ${r(rm)}")
                else -> null
            }
        }
        return null
    }

    /** The FPU: loads and stores in full, the rest by name */
    private fun fpu(h1: Int, h2: Int): Insn? {
        val cp = (h2 shr 8) and 15
        if (cp != 10 && cp != 11) return Insn(4, "coprocessor %d instruction".format(Locale.ROOT, cp))
        val rn = h1 and 15
        if ((h1 shr 9) and 7 == 6) {                 // extension register load/store
            val load = (h1 shr 4) and 1 == 1
            val single = cp == 10
            val d = (h1 shr 6) and 1
            val vd = (h2 shr 12) and 15
            val reg = if (single) "s${(vd shl 1) or d}" else "d${vd or (d shl 4)}"
            if ((h1 shr 4) and 0x13 == 0x10 || (h1 shr 4) and 0x13 == 0x11 || (h1 shr 8) and 0x1 == 1 && (h1 shr 5) and 1 == 0) {
                val u = (h1 shr 7) and 1 == 1
                val off = (h2 and 0xFF) * 4L * (if (u) 1 else -1)
                return Insn(4, "${if (load) "vldr" else "vstr"} $reg, [${r(rn)}${if (off != 0L) ", #$off" else ""}]").also {
                    it.base = rn; it.offset = off; it.load = load; it.store = !load
                }
            }
            val n = h2 and 0xFF
            val regs = if (single) "{s${(vd shl 1) or d}-s${((vd shl 1) or d) + n - 1}}" else "{d${vd or (d shl 4)}…}"
            if (rn == 13 && (h1 shr 7) and 3 == 2 && !load) return Insn(4, "vpush $regs")
            if (rn == 13 && (h1 shr 7) and 3 == 1 && load) return Insn(4, "vpop $regs")
            return Insn(4, "${if (load) "vldm" else "vstm"} ${r(rn)}, $regs")
        }
        if ((h1 shr 8) and 15 == 14 && (h2 shr 4) and 1 == 1) {
            val vn = ((h1 and 15) shl 1) or ((h2 shr 7) and 1)
            val rt = (h2 shr 12) and 15
            if ((h1 shr 5) and 7 == 0) return Insn(4, if ((h1 shr 4) and 1 == 1) "vmov ${r(rt)}, s$vn" else "vmov s$vn, ${r(rt)}")
            if ((h1 shr 4) and 0xF == 0xF) return Insn(4, if (rt == 15) "vmrs APSR_nzcv, fpscr" else "vmrs ${r(rt)}, fpscr")
            if ((h1 shr 4) and 0xF == 0xE) return Insn(4, "vmsr fpscr, ${r(rt)}")
        }
        return Insn(4, "FPU instruction (VADD, VMUL, VCVT, …)")
    }
}
