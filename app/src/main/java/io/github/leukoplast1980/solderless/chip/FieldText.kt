/*
 * Solderless - how a register field's value reads: as bits or a number,
 * what it means, and the Forth that writes it.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.chip

import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.chip.RegisterMap.Field
import io.github.leukoplast1980.solderless.chip.RegisterMap.Peripheral
import io.github.leukoplast1980.solderless.chip.RegisterMap.Register
import java.util.Locale

object FieldText {

    /** Up to 4 bits as bits ("01"), wide fields in hex, the rest as numbers */
    fun value(f: Field, v: Long): String = when {
        f.width <= 4 -> java.lang.Long.toBinaryString(v).padStart(f.width, '0')
        f.width >= 20 -> "0x%X".format(Locale.ROOT, v)
        else -> v.toString()
    }

    /** A register's value as two groups of four hex digits: "ABFF F7AF" */
    fun word(v: Long): String = "%04X %04X".format(Locale.ROOT, (v ushr 16) and 0xFFFF, v and 0xFFFF)

    /** The pin a GPIO field is about (MODER5 of GPIOA: PA5), or null */
    fun pin(p: Peripheral, f: Field): String? {
        val port = p.port ?: return null
        val n = f.number ?: return null
        return if (n in 0..15) "P$port$n" else null
    }

    /** What the value means: from the note's show: line, else from the list of values */
    fun meaning(p: Peripheral, f: Field, v: Long, chip: ChipPins?): String {
        if (f.show.isNotEmpty()) {
            var s = p.fill(f.show, f)
                .replace("{v+1}", (v + 1).toString())
                .replace("{v}", v.toString())
                .replace("{h1}", "%02X".format(Locale.ROOT, v ushr 1))
                .replace("{h}", "%X".format(Locale.ROOT, v))
            if (s.contains("{af}")) {
                val pin = pin(p, f)?.let { chip?.gpio(it) }
                val af = pin?.af?.firstOrNull { it.af.toLong() == v }?.name ?: "(nothing on this pin)"
                s = s.replace("{af}", af)
            }
            return s
        }
        return f.meaning(v)?.let { p.fill(it, f) } ?: ""
    }

    /** What the field is for: the note, else ST's description */
    fun about(p: Peripheral, f: Field): String = if (f.about.isNotEmpty()) p.fill(f.about, f) else f.description

    /**
     * Forth (Mecrisp-Stellaris) that gives the field the value [v] and
     * leaves the rest of the register as it is.
     */
    fun forth(p: Peripheral, r: Register, f: Field, v: Long): String {
        val addr = "$%08X".format(Locale.ROOT, p.address(r))
        val bits = (v shl f.bit) and 0xFFFFFFFFL
        val number = if (f.values.isEmpty() && f.width > 1) v.toString() else hex(bits)
        return when {
            // write-only registers (BSRR, ICR ...) and fields that are the whole register (BRR, ARR ...)
            !r.readable || r.writableMask == f.mask -> "$number $addr !"
            f.width == 1 -> if (v != 0L) "${hex(f.mask)} $addr bis!" else "${hex(f.mask)} $addr bic!"
            bits == 0L -> "${hex(f.mask)} $addr bic!"
            bits == f.mask -> "${hex(f.mask)} $addr bis!"
            else -> "$addr @ ${hex(f.mask)} bic ${hex(bits)} or $addr !"
        }
    }

    /** The register's new value with the field set to [v] (write-only registers: the field alone) */
    fun write(r: Register, f: Field, current: Long, v: Long): Long {
        val bits = (v shl f.bit) and f.mask
        return if (!r.readable) bits else (current and f.mask.inv() and 0xFFFFFFFFL) or bits
    }

    /** A number typed in: decimal, $hex, 0xhex or %binary; null if it is not one */
    fun parse(s: String): Long? {
        val t = s.trim().replace("_", "").replace(" ", "")
        return when {
            t.startsWith("$") -> t.substring(1).toLongOrNull(16)
            t.startsWith("0x", ignoreCase = true) -> t.substring(2).toLongOrNull(16)
            t.startsWith("%") -> t.substring(1).toLongOrNull(2)
            t.startsWith("0b", ignoreCase = true) -> t.substring(2).toLongOrNull(2)
            else -> t.toLongOrNull()
        }
    }

    private fun hex(v: Long) = "$%X".format(Locale.ROOT, v)
}
