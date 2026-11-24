/*
 * Solderless - the Chip tab's Core section: the Cortex-M4 core, Forth's
 * stacks, the clocks and the fault status, refreshed while visible.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.widget.Toast
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.chip.FaultReport
import io.github.leukoplast1980.solderless.vm.Snapshot
import io.github.leukoplast1980.solderless.vm.VmHost
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoreSection(private val activity: Activity, private val vm: VmHost) {

    private val text: TextView = activity.findViewById(R.id.registers)

    private val changedColor = activity.getColor(R.color.changed)
    private val labelColor = activity.getColor(R.color.label)
    private val accentColor = activity.getColor(R.color.accent)
    private val dangerColor = activity.getColor(R.color.danger)
    private val wordColor = activity.getColor(R.color.syntax_name)

    /** Values shown last time. */
    private var previous = HashMap<String, Long>()

    /** When each value last changed (uptime ms), for highlighting while running. */
    private val changedAt = HashMap<String, Long>()

    /** Values before the last Step: while paused, changes against these stay highlighted. */
    private var stepBaseline: Map<String, Long>? = null

    private var now = 0L

    /** Pause or Resume: highlights go back to "changed lately" */
    fun pauseChanged() { stepBaseline = null }

    /** Before a Step: what it changes stays highlighted until the next one */
    fun stepping() { stepBaseline = HashMap(previous) }

    /**
     * Not selectable text: a focused selectable TextView scrolls its cursor
     * back into view on every refresh. A button copies instead (a long press
     * fired while scrolling slowly).
     */
    fun copy() {
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Solderless registers", text.text.toString()))
        Toast.makeText(activity, R.string.registers_copied, Toast.LENGTH_SHORT).show()
    }

    fun refresh(s: Snapshot) {
        now = SystemClock.uptimeMillis()
        val current = HashMap<String, Long>()
        val b = SpannableStringBuilder()
        val stellaris = vm.project.firmware.isStellaris

        if (s.lockup || s.cfsr != 0 || s.hfsr != 0) faultSection(b, s)

        // Where the CPU is
        val word = if (stellaris) vm.dictionary.wordAt(s.pc) else null
        val where = buildString {
            if (word != null) append("${word.name} +${s.pc - word.code}")
            if (s.ipsr != 0) {
                if (isNotEmpty()) append("  ")
                append("in ").append(exceptionName(s.ipsr))
            }
        }
        heading(b, "Program counter")
        value(b, current, "pc", "pc", s.pc.toLong() and MASK, where)

        if (stellaris) forthSection(b, current, s)

        heading(b, "Core registers")
        for (r in 0 until 12 step 2) pair(b, current, "r$r", s.r[r], "r${r + 1}", s.r[r + 1])
        pair(b, current, "r12", s.r[12], "sp", s.r[13])
        pair(b, current, "lr", s.r[14], "pc", s.r[15])
        value(b, current, "xpsr", "xPSR", s.xpsr.toLong() and MASK, xpsrText(s.xpsr))
        pair(b, current, "msp", s.msp, "psp", s.psp)
        value(b, current, "primask", "PRIMASK  ", s.primask.toLong(), if (s.primask != 0) "interrupts off" else "")
        value(b, current, "basepri", "BASEPRI  ", s.basepri.toLong(), if (s.basepri != 0) "masks priority ≥ ${s.basepri}" else "")
        value(b, current, "faultmask", "FAULTMASK", s.faultmask.toLong(), "")
        value(b, current, "control", "CONTROL  ", s.control.toLong(), controlText(s.control))

        heading(b, "Clocks")
        clock(b, current, "SYSCLK", s.sysclk)
        clock(b, current, "HCLK  ", s.hclk)
        clock(b, current, "PCLK1 ", s.pclk1)
        clock(b, current, "PCLK2 ", s.pclk2)

        previous = current
        text.text = b
    }

    private fun forthSection(b: SpannableStringBuilder, current: MutableMap<String, Long>, s: Snapshot) {
        val ds = vm.stellaris.dataStackBase
        val rs = vm.stellaris.returnStackBase
        val tos = s.r[6]
        val psp = s.r[7]
        val depth = (ds - psp) / 4
        val rdepth = (rs - s.r[13]) / 4
        heading(b, "Forth")
        value(b, current, "TOS", "TOS   r6", tos.toLong() and MASK, tos.toString())
        value(b, current, "PSP", "PSP   r7", psp.toLong() and MASK, "depth $depth")
        value(b, current, "RSP", "RSP   sp", s.r[13].toLong() and MASK, "depth $rdepth")
        value(b, current, "I", "index r4", s.r[4].toLong() and MASK, "")
        value(b, current, "limit", "limit r5", s.r[5].toLong() and MASK, "")

        heading(b, "Data stack")
        when {
            depth <= 0 || depth > 64 -> b.append(if (depth == 0) "  empty\n" else "  (not a Forth stack right now)\n")
            else -> {
                val shown = minOf(depth, MAX_STACK)
                value(b, current, "ds0", "  TOS", tos.toLong() and MASK, tos.toString())
                if (shown > 1) {
                    val mem = vm.readMemory(psp, (shown - 1) * 4)
                    if (mem != null) {
                        val bb = ByteBuffer.wrap(mem).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 1 until shown) {
                            val v = bb.getInt((i - 1) * 4)
                            value(b, current, "ds$i", "  %-3d".format(java.util.Locale.ROOT, i), v.toLong() and MASK, v.toString())
                        }
                    }
                }
                if (depth > shown) b.append("  … ${depth - shown} more\n")
            }
        }
    }

    /** The fault status registers in plain words. */
    /** The fault explainer: the last fault in words, where, the instruction, why, the registers */
    private fun faultSection(b: SpannableStringBuilder, s: Snapshot) {
        val stellaris = vm.project.firmware.isStellaris
        val f = FaultReport(
            s.cfsr, s.hfsr, s.bfar, s.mmfar, s.lockup, s.faultException, s.faultR, s.faultXpsr,
            read = { a, n -> vm.readMemory(a.toInt(), n) },
            place = { pc -> LogSection.place(vm, pc) },
            dataStackBase = if (stellaris) vm.stellaris.dataStackBase.toLong() and MASK else 0L,
        )
        heading(b, f.title, dangerColor)
        for (line in f.causes) coloured(b, "  $line\n", dangerColor)
        if (f.where != null) {
            b.append("  in ")
            coloured(b, f.where, wordColor)
            if (f.calledFrom.isNotEmpty()) {
                b.append(", called from ")
                f.calledFrom.forEachIndexed { k, c -> if (k > 0) b.append(", "); coloured(b, c, wordColor) }
            }
            b.append('\n')
        }
        b.append("  %08X  %-9s  %s\n".format(java.util.Locale.ROOT, f.pc, f.halfwords, f.insn?.text ?: "(cannot be read)"))
        for (line in f.hints) coloured(b, "  $line\n", labelColor)
        b.append("  Registers when it happened:\n")
        val r = f.regs
        for (k in 0 until 16 step 2) {
            val n1 = arrayOf("r0", "r2", "r4", "r6", "r8", "r10", "r12", "lr")[k / 2]
            val n2 = arrayOf("r1", "r3", "r5", "r7", "r9", "r11", "sp", "pc")[k / 2]
            val i1 = if (n1 == "lr") 14 else k
            val i2 = if (n2 == "sp") 13 else if (n2 == "pc") 15 else k + 1
            b.append("  %-4s %08X   %-4s %08X\n".format(java.util.Locale.ROOT, n1, r[i1], n2, r[i2]))
        }
        if (f.dataStack.isNotEmpty()) {
            b.append("  Data stack: ")
            b.append(f.dataStack.joinToString("  ") { v -> "%d".format(java.util.Locale.ROOT, v.toInt()) })
            b.append("  (TOS first)\n")
        }
        b.append("  CFSR %08X  HFSR %08X\n".format(java.util.Locale.ROOT, s.cfsr, s.hfsr))
        if (!s.lockup) coloured(b, "  Stellaris' fault handler tries again and again: press Reset to go on.\n", labelColor)
    }

    private fun coloured(b: SpannableStringBuilder, text: String, color: Int) {
        val start = b.length
        b.append(text)
        b.setSpan(ForegroundColorSpan(color), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun exceptionName(n: Int): String = when (n) {
        1 -> "Reset"; 2 -> "NMI"; 3 -> "HardFault"; 4 -> "MemManage"; 5 -> "BusFault"
        6 -> "UsageFault"; 11 -> "SVCall"; 12 -> "DebugMonitor"; 14 -> "PendSV"; 15 -> "SysTick"
        else -> if (n >= 16) "IRQ ${n - 16}" else "exception $n"
    }

    private fun xpsrText(x: Int): String = buildString {
        append(if (x < 0) 'N' else 'n')
        append(if (x and (1 shl 30) != 0) 'Z' else 'z')
        append(if (x and (1 shl 29) != 0) 'C' else 'c')
        append(if (x and (1 shl 28) != 0) 'V' else 'v')
        append(if (x and (1 shl 27) != 0) 'Q' else 'q')
        if (x and (1 shl 24) == 0) append("  T=0!")
        val it = ((x shr 25) and 3) or (((x shr 10) and 0x3f) shl 2)
        if (it and 0xF != 0) append("  in IT block")
        val ipsr = x and 0x1ff
        if (ipsr != 0) append("  ").append(exceptionName(ipsr))
    }

    private fun controlText(c: Int): String = buildList {
        add(if (c and 1 != 0) "unprivileged" else "privileged")
        if (c and 2 != 0) add("PSP")
        if (c and 4 != 0) add("FP context")
    }.joinToString(", ")

    private fun clock(b: SpannableStringBuilder, current: MutableMap<String, Long>, name: String, hz: Long) {
        value(b, current, name.trim(), name, hz, "%.3f MHz".format(java.util.Locale.ROOT, hz / 1e6))
    }

    private fun heading(b: SpannableStringBuilder, title: String, color: Int = accentColor) {
        if (b.isNotEmpty()) b.append('\n')
        val start = b.length
        b.append(title).append('\n')
        b.setSpan(ForegroundColorSpan(color), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun value(
        b: SpannableStringBuilder, current: MutableMap<String, Long>,
        key: String, label: String, v: Long, note: String,
    ) {
        current[key] = v
        val start = b.length
        b.append(label)
        b.setSpan(ForegroundColorSpan(labelColor), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append("  ")
        hex(b, key, v)
        if (note.isNotEmpty()) b.append("  ").append(note)
        b.append('\n')
    }

    private fun pair(b: SpannableStringBuilder, current: MutableMap<String, Long>, k1: String, v1: Int, k2: String, v2: Int) {
        for ((k, v) in listOf(k1 to v1, k2 to v2)) {
            val lv = v.toLong() and MASK
            current[k] = lv
            val start = b.length
            b.append("%-4s".format(java.util.Locale.ROOT, k))
            b.setSpan(ForegroundColorSpan(labelColor), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            hex(b, k, lv)
            b.append("   ")
        }
        b.append('\n')
    }

    private fun hex(b: SpannableStringBuilder, key: String, v: Long) {
        val start = b.length
        b.append("%08X".format(java.util.Locale.ROOT, v))
        if (highlighted(key, v)) {
            b.setSpan(ForegroundColorSpan(changedColor), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Paused after a Step: everything the step changed stays highlighted
     * until the next Step. Running: a value stays highlighted for HOLD_MS
     * after it last changed, so it is readable.
     */
    private fun highlighted(key: String, v: Long): Boolean {
        val old = previous[key]
        if (old != null && old != v) changedAt[key] = now
        val baseline = stepBaseline
        if (vm.isUserPaused && baseline != null) {
            val before = baseline[key]
            return before != null && before != v
        }
        val t = changedAt[key] ?: return false
        return now - t < HOLD_MS
    }

    companion object {
        private const val MASK = 0xffffffffL
        private const val MAX_STACK = 16
        private const val HOLD_MS = 1500L
    }
}
