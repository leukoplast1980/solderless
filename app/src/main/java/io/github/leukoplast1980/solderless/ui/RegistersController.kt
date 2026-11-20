/*
 * Solderless - the Registers view: the Cortex-M4 core, Forth's stacks,
 * the clocks and the fault status, refreshed while visible, with Pause
 * and Step.
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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.vm.Snapshot
import io.github.leukoplast1980.solderless.vm.VmHost
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegistersController(private val activity: Activity, private val vm: VmHost) {

    private val text: TextView = activity.findViewById(R.id.registers)
    private val runState: TextView = activity.findViewById(R.id.runState)
    private val pauseButton: Button = activity.findViewById(R.id.pause)
    private val stepButton: Button = activity.findViewById(R.id.step)

    private val changedColor = activity.getColor(R.color.changed)
    private val labelColor = activity.getColor(R.color.label)
    private val accentColor = activity.getColor(R.color.accent)
    private val dangerColor = activity.getColor(R.color.danger)

    /** Values shown last time. */
    private var previous = HashMap<String, Long>()

    /** When each value last changed (uptime ms), for highlighting while running. */
    private val changedAt = HashMap<String, Long>()

    /** Values before the last Step: while paused, changes against these stay highlighted. */
    private var stepBaseline: Map<String, Long>? = null

    private var now = 0L

    /** Called after Pause or Step so the console can update its dictionary. */
    var onPausedChanged: ((Boolean) -> Unit)? = null

    init {
        pauseButton.setOnClickListener {
            val paused = !vm.isUserPaused
            vm.setUserPaused(paused)
            stepBaseline = null
            if (paused) Thread { vm.refreshDictionary() }.start()
            updateButtons()
            onPausedChanged?.invoke(paused)
        }
        stepButton.setOnClickListener {
            stepBaseline = HashMap(previous)
            vm.step()
            text.postDelayed({ refresh() }, 20)
        }
        // Not selectable text: a focused selectable TextView scrolls its
        // cursor back into view on every refresh. A button copies instead (a
        // long press fired while scrolling slowly).
        activity.findViewById<Button>(R.id.registersCopy).setOnClickListener {
            activity.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Solderless registers", text.text.toString()))
            Toast.makeText(activity, R.string.registers_copied, Toast.LENGTH_SHORT).show()
        }
        updateButtons()
    }

    private fun updateButtons() {
        val paused = vm.isUserPaused
        pauseButton.setText(if (paused) R.string.resume else R.string.pause)
        stepButton.isEnabled = paused
    }

    fun refresh() {
        now = SystemClock.uptimeMillis()
        val s = vm.snapshot()
        val current = HashMap<String, Long>()
        val b = SpannableStringBuilder()
        val stellaris = vm.project.firmware.isStellaris

        runState.text = buildString {
            append(
                when {
                    s.lockup -> "locked up"
                    s.paused -> "paused"
                    s.waitingForKey -> "waiting for input"
                    s.sleeping -> "sleeping"
                    else -> "running"
                }
            )
            append("  ·  %s MHz".format(java.util.Locale.ROOT, java.math.BigDecimal(s.sysclk / 1e6).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()))
            append("  ·  %,d cycles  ·  %.3f s".format(java.util.Locale.ROOT, s.cycles, s.timeNs / 1e9))
            if (!s.paused && s.speedPercent in 1..95) append("  ·  ${s.speedPercent} % speed")
        }

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
        updateButtons()
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
    private fun faultSection(b: SpannableStringBuilder, s: Snapshot) {
        heading(b, if (s.lockup) "Locked up" else "Fault status")
        val start = b.length
        if (s.lockup) b.append("  The core stopped: a fault happened where no fault handler could run.\n  Press Reset.\n")
        for (line in faultExplanation(s)) b.append("  ").append(line).append('\n')
        b.setSpan(ForegroundColorSpan(dangerColor), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append("  CFSR %08X  HFSR %08X  at pc %08X\n".format(java.util.Locale.ROOT, s.cfsr, s.hfsr, s.faultPc))
    }

    private fun faultExplanation(s: Snapshot): List<String> = buildList {
        val c = s.cfsr
        val h = s.hfsr
        if (h and (1 shl 30) != 0) add("A fault was escalated to HardFault (its own handler is off or could not run).")
        if (h and (1 shl 1) != 0) add("A vector table read failed.")
        if (h and (1 shl 31) != 0) add("BKPT or a debug event with no debugger attached.")
        if (c and (1 shl 0) != 0) add("Instruction fetch from a place that cannot hold code (peripheral or system area).")
        if (c and (1 shl 1) != 0) add("Data access not allowed by the MPU.")
        if (c and (1 shl 8) != 0) add("Instruction fetch from an address where nothing is mapped.")
        if (c and (1 shl 9) != 0) add("Load or store at 0x%08X, where nothing is mapped (BFAR).".format(java.util.Locale.ROOT, s.bfar))
        if (c and (1 shl 12) != 0) add("Stacking the exception frame failed: the stack pointer is bad.")
        if (c and (1 shl 11) != 0) add("Unstacking on exception return failed: the stack pointer is bad.")
        if (c and (1 shl 16) != 0) add("Undefined instruction (or an FPU/DSP instruction this core does not run yet).")
        if (c and (1 shl 17) != 0) add("Branch to an even address: Thumb state lost (BX/BLX/POP to a value with bit 0 clear).")
        if (c and (1 shl 18) != 0) add("Bad exception return value (EXC_RETURN) in the PC.")
        if (c and (1 shl 19) != 0) add("Coprocessor instruction while CPACR denies access (FPU off).")
        if (c and (1 shl 24) != 0) add("Unaligned access where alignment is required (LDM, STM, LDRD, STRD, exclusives).")
        if (c and (1 shl 25) != 0) add("Division by zero with CCR.DIV_0_TRP set.")
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

    private fun heading(b: SpannableStringBuilder, title: String) {
        if (b.isNotEmpty()) b.append('\n')
        val start = b.length
        b.append(title).append('\n')
        b.setSpan(ForegroundColorSpan(accentColor), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
