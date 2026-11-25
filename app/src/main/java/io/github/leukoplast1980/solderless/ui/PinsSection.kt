/*
 * Solderless - the Chip tab's Pins section: how the chip has set up each
 * pin (mode, alternate function, pull, level), what is wired to it, and
 * where the two disagree.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.chip.FieldText
import io.github.leukoplast1980.solderless.chip.PinSetup
import io.github.leukoplast1980.solderless.ui.PinListView.Row
import io.github.leukoplast1980.solderless.vm.VmHost
import java.util.Locale

class PinsSection(
    private val activity: Activity, private val vm: VmHost,
    /** Shows registers in the Registers section: "GPIOA" */
    private val openRegister: (String) -> Unit,
) {

    private val list: PinListView = activity.findViewById(R.id.pinList)
    private val addresses = PinSetup.addresses()
    private var showAll = false
    private var setups: List<PinSetup> = emptyList()
    private var actions: List<(() -> Unit)?> = emptyList()

    init {
        list.onTapAt = { i, x, y ->
            if (list.rows.getOrNull(i) is Row.Package) {
                val n = list.pinNear(i, x, y)
                setups.firstOrNull { it.pin.number == n }?.let { showPin(it) }
            } else actions.getOrNull(i)?.invoke()
        }
    }

    /** How the chip has set up its pins now */
    fun read(): List<PinSetup> {
        val words = vm.peekWords(IntArray(addresses.size) { addresses[it].toInt() })
        return PinSetup.of(LongArray(words.size) { words[it].toLong() and 0xFFFFFFFFL }, vm.chip, vm.board)
    }

    fun refresh() {
        setups = read()
        val rows = ArrayList<Row>()
        val acts = ArrayList<(() -> Unit)?>()
        fun add(r: Row, a: (() -> Unit)?) { rows.add(r); acts.add(a) }
        add(Row.Package(vm.chip.pins.associate { it.number to it.name }, setups.associateBy { it.pin.number }), null)
        add(Row.Legend, null)
        val used = setups.filter { !it.atReset || it.wired.isNotEmpty() || it.mismatch != null }
        // Wrong set-ups first, then by name
        val first = (if (showAll) setups else used).sortedWith(compareBy({ it.mismatch == null }, { it.pin.port }, { it.pin.bit }))
        for (s in first) add(Row.Pin(s)) { showPin(s) }
        val rest = setups.size - used.size
        if (!showAll && rest > 0) add(Row.Note(activity.resources.getQuantityString(R.plurals.pins_more, rest, rest), true)) {
            showAll = true; refresh()
        } else if (showAll) add(Row.Note(activity.getString(R.string.pins_fewer), true)) { showAll = false; refresh() }
        actions = acts
        list.show(rows)
    }

    // ------------------------------------------------------------------
    // One pin close up: its fields in GPIOx, what is wired, its functions

    private fun showPin(s: PinSetup) {
        val dlg = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val port = "GPIO" + ('A' + s.pin.port)
        col.addView(text(s.name, 20f, R.color.bar_text))
        col.addView(text("pin ${s.pin.number} · $port bit ${s.pin.bit}", 12f, R.color.label, mono = true))
        if (s.mismatch != null) col.addView(text("▲ ${s.mismatch}", 14f, R.color.chip_message).apply { setPadding(0, dp(10), 0, 0) })
        col.addView(text(if (s.wired.isEmpty()) "Nothing wired" else "Wired: ${s.wired.joinToString(", ")}", 14f,
            if (s.wired.isEmpty()) R.color.label else R.color.syntax_defining).apply { setPadding(0, dp(8), 0, 0) })

        // The pin's fields in its port's registers, decoded
        section(col, "In $port")
        val map = vm.registerMap
        val p = map?.peripheral(port)
        if (s.clockOff) col.addView(text("The port's clock is off in RCC (AHB2ENR $port" + "EN): its registers cannot be read or written.", 13f, R.color.chip_message))
        else if (p != null) {
            val b = s.pin.bit
            val wanted = listOf("MODER" to "MODER$b", "OTYPER" to "OT$b", "OSPEEDR" to "OSPEEDR$b", "PUPDR" to "PUPDR$b",
                (if (b < 8) "AFRL" else "AFRH") to (if (b < 8) "AFRL$b" else "AFRH$b"), "IDR" to "IDR$b", "ODR" to "ODR$b")
            val regs = wanted.mapNotNull { (rn, fn) -> p.registers.firstOrNull { it.name == rn }?.let { r -> r.fields.firstOrNull { it.name == fn }?.let { r to it } } }
            val values = vm.peekWords(IntArray(regs.size) { p.address(regs[it].first).toInt() })
            for ((k, rf) in regs.withIndex()) {
                val (_, f) = rf
                val v = f.of(values[k].toLong() and 0xFFFFFFFFL)
                col.addView(text("%-9s %-4s %s".format(Locale.ROOT, f.name, FieldText.value(f, v), FieldText.meaning(p, f, v, vm.chip)),
                    13f, R.color.console_text, mono = true))
            }
        }
        if (s.pin.af.isNotEmpty()) {
            section(col, "Alternate functions")
            col.addView(text(s.pin.af.joinToString("\n") { (if (s.mode == PinSetup.MODE_AF && it.af == s.af) "▸ " else "  ") + "AF%-2d %s".format(Locale.ROOT, it.af, it.name) },
                13f, R.color.console_text, mono = true))
        }
        if (s.pin.analog.isNotEmpty()) {
            section(col, "Analog")
            col.addView(text(s.pin.analog.joinToString("\n"), 13f, R.color.console_text, mono = true))
        }
        col.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.pins_in_registers, port)
            setOnClickListener { dlg.dismiss(); openRegister(port) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        dlg.setContentView(ScrollView(activity).apply { addView(col) })
        dlg.show()
    }

    private fun section(col: LinearLayout, s: String) {
        col.addView(text(s.uppercase(), 12f, R.color.label).apply { letterSpacing = 0.06f; setPadding(0, dp(14), 0, dp(4)) })
    }

    private fun text(s: String, size: Float, color: Int, mono: Boolean = false) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(ContextCompat.getColor(activity, color))
        if (mono) typeface = Typeface.MONOSPACE
        setPadding(0, dp(3), 0, dp(2))
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
