/*
 * Solderless - the Chip tab's Registers section: every peripheral's
 * registers from the SVD, the fields decoded, changes highlighted; a tap on
 * a field says what it does and can change it.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.chip.FieldText
import io.github.leukoplast1980.solderless.chip.RegisterMap
import io.github.leukoplast1980.solderless.chip.RegisterMap.Field
import io.github.leukoplast1980.solderless.chip.RegisterMap.Peripheral
import io.github.leukoplast1980.solderless.chip.RegisterMap.Register
import io.github.leukoplast1980.solderless.ui.RegisterListView.Row
import io.github.leukoplast1980.solderless.vm.VmHost
import java.util.Locale

class RegistersSection(private val activity: Activity, private val vm: VmHost) {

    private val list: RegisterListView = activity.findViewById(R.id.registerList)
    private val find: EditText = activity.findViewById(R.id.registerFind)
    private val prefs = activity.getSharedPreferences("chip", Context.MODE_PRIVATE)

    /** Peripherals opened by a tap (kept), and while searching, those closed by one */
    private val expanded = HashSet(prefs.getStringSet("expanded", setOf("GPIOA")) ?: emptySet())
    private val closedInSearch = HashSet<String>()
    /** Registers showing all their fields: "GPIOA.MODER" */
    private val allFields = HashSet<String>()
    private var query = ""

    /** What a tap on each row does */
    private var actions: List<(() -> Unit)?> = emptyList()

    /** Register values read last time, by address; only of the open peripherals */
    private var values = HashMap<Long, Long>()

    /** The peripherals in name order ("TIM2" before "TIM15"), sorted once for [sortedFor] */
    private var sorted = emptyList<Peripheral>()
    private var sortedFor: RegisterMap? = null
    /** When each field last changed: key address * 64 + bit */
    private val changedAt = HashMap<Long, Long>()
    /** The values before the last Step: while paused, what it changed stays highlighted */
    private var stepBaseline: Map<Long, Long>? = null
    private var now = 0L

    init {
        Thread({ vm.loadRegisterMap(); activity.runOnUiThread { if (list.isShown) refresh() } }, "registers").start()
        list.onTap = { i -> actions.getOrNull(i)?.invoke() }
        find.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString().trim().uppercase(Locale.ROOT)
                closedInSearch.clear()
                refresh()
            }
        })
    }

    /** Shows what [text] finds (a register from the Log: "GPIOA_ODR") */
    fun search(text: String) {
        find.setText(text)
        find.setSelection(text.length)
    }

    fun pauseChanged() { stepBaseline = null }
    fun stepping() { stepBaseline = HashMap(values) }

    private class Shown(val p: Peripheral, val open: Boolean, val regs: List<Register>, val fields: Map<Register, Set<Field>>)

    fun refresh() {
        val map = vm.registerMap
        if (map == null) {
            actions = listOf(null)
            list.show(listOf(Row.Note("Reading the chip's registers …", false)))
            return
        }
        now = SystemClock.uptimeMillis()

        // What to show: everything, or what the search finds, by name
        if (sortedFor !== map) {
            sorted = map.peripherals.sortedWith(compareBy(NATURAL) { it.name })
            sortedFor = map
        }
        val shown = sorted.mapNotNull { p -> match(p) }

        // One read for the clock enables and the open peripherals' registers
        val enOffsets = map.peripherals.filter { it.enOffset >= 0 }.map { it.enOffset }.distinct()
        val addrs = ArrayList<Long>()
        enOffsets.forEach { addrs.add(RegisterMap.RCC_BASE + it) }
        for (s in shown) if (s.open) for (r in s.regs) if (r.readable) addrs.add(s.p.address(r))
        val read = vm.peekWords(IntArray(addrs.size) { addrs[it].toInt() })
        val fresh = HashMap<Long, Long>(addrs.size * 2)
        for (i in addrs.indices) fresh[addrs[i]] = read[i].toLong() and 0xFFFFFFFFL
        noteChanges(map, fresh)
        values = fresh

        fun clockOn(p: Peripheral) = p.enOffset < 0 || ((fresh[RegisterMap.RCC_BASE + p.enOffset] ?: 0L) shr p.enBit) and 1L == 1L
        val (on, off) = shown.partition { clockOn(it.p) }

        val rows = ArrayList<Row>()
        val acts = ArrayList<(() -> Unit)?>()
        fun add(r: Row, a: (() -> Unit)?) { rows.add(r); acts.add(a) }
        if (shown.isEmpty()) add(Row.Note("Nothing called $query", false), null)
        for ((label, group, isOn) in listOf(Triple("CLOCK ON", on, true), Triple("CLOCK OFF", off, false))) {
            if (group.isEmpty()) continue
            add(Row.Group("$label  ${group.size}"), null)
            for (s in group) {
                val p = s.p
                add(Row.Periph(p.name, FieldText.word(p.base), s.open, isOn, if (p.stored) "not emulated" else "")) { toggle(p) }
                if (!s.open) continue
                if (!isOn) add(Row.Note("Its clock is off in RCC: reads give 0, writes are lost", true), null)
                for (r in s.regs) registerRows(p, r, s.fields[r], ::add)
            }
        }
        actions = acts
        list.show(rows)
    }

    private fun registerRows(p: Peripheral, r: Register, matched: Set<Field>?, add: (Row, (() -> Unit)?) -> Unit) {
        val addr = p.address(r)
        val key = "${p.name}.${r.name}"
        val v = if (r.readable) values[addr] ?: 0L else 0L
        val all = key in allFields
        val changedFields = r.fields.filter { changed(addr, it) }
        val note = when {
            !r.readable -> ""
            v == r.reset -> "reset"
            r.access == "r" -> "read only"
            else -> ""
        }
        add(Row.Reg(r.name, if (r.readable) FieldText.word(v) else "write only", note,
            changedFields.isNotEmpty() || (r.fields.isEmpty() && changed(addr, null)), false)) {
            if (!allFields.add(key)) allFields.remove(key)
            refresh()
        }
        val show = r.fields.filter { f ->
            all || (matched != null && f in matched) || f in changedFields ||
                (r.readable && f.readable && f.of(v) != f.of(r.reset))
        }
        for (f in show) {
            val fv = if (r.readable && f.readable) f.of(v) else 0L
            val atReset = f.of(v) == f.of(r.reset)
            add(Row.Field(f.name, if (r.readable && f.readable) FieldText.value(f, fv) else "–",
                if (r.readable && f.readable) FieldText.meaning(p, f, fv, vm.chip) else FieldText.about(p, f),
                f in changedFields, all && atReset && f !in changedFields)) { showField(p, r, f) }
        }
        val hidden = r.fields.size - show.size
        if (hidden > 0 && show.isNotEmpty())
            add(Row.Note("$hidden more ${if (r.readable) "at reset" else "fields"}", true)) { allFields.add(key); refresh() }
    }

    /** null: not shown. Searching: shown if its name, a register's or a field's has the text. */
    private fun match(p: Peripheral): Shown? {
        val userOpen = p.name in expanded
        if (query.isEmpty()) return Shown(p, userOpen, p.registers, emptyMap())
        val named = p.name.contains(query)
        val regs = ArrayList<Register>()
        val fields = HashMap<Register, Set<Field>>()
        for (r in p.registers) {
            val fs = r.fields.filter { it.name.contains(query) }.toSet()
            if ("${p.name}_${r.name}".contains(query) || fs.isNotEmpty()) {
                regs.add(r)
                if (fs.isNotEmpty()) fields[r] = fs
            }
        }
        if (regs.isEmpty()) return if (named) Shown(p, userOpen, p.registers, emptyMap()) else null
        val open = p.name !in closedInSearch
        return Shown(p, open, regs, fields)
    }

    private fun toggle(p: Peripheral) {
        if (query.isNotEmpty() && p.registers.any { r -> "${p.name}_${r.name}".contains(query) || r.fields.any { it.name.contains(query) } }) {
            if (!closedInSearch.add(p.name)) closedInSearch.remove(p.name)
        } else {
            if (!expanded.add(p.name)) expanded.remove(p.name)
            prefs.edit { putStringSet("expanded", HashSet(expanded)) }
        }
        refresh()
    }

    // ------------------------------------------------------------------
    // Changes: highlighted for a while when running, since the Step when paused

    private fun key(addr: Long, bit: Int) = addr * 64 + bit + 1

    private fun noteChanges(map: RegisterMap, fresh: Map<Long, Long>) {
        for ((addr, v) in fresh) {
            val old = values[addr] ?: continue
            if (old == v) continue
            val r = map.at(addr)?.second
            if (r == null || r.fields.isEmpty()) changedAt[key(addr, -1)] = now
            else for (f in r.fields) if ((old xor v) and f.mask != 0L) changedAt[key(addr, f.bit)] = now
        }
    }

    private fun changed(addr: Long, f: Field?): Boolean {
        val base = stepBaseline
        if (vm.isUserPaused && base != null) {
            val before = base[addr] ?: return false
            val v = values[addr] ?: return false
            return if (f == null) before != v else (before xor v) and f.mask != 0L
        }
        val t = changedAt[key(addr, f?.bit ?: -1)] ?: return false
        return now - t < HOLD_MS
    }

    // ------------------------------------------------------------------
    // A field close up: what it does, its values, the Forth to set it, Write

    private fun showField(p: Peripheral, r: Register, f: Field) {
        val addr = p.address(r)
        val current = if (r.readable) vm.peekWords(intArrayOf(addr.toInt()))[0].toLong() and 0xFFFFFFFFL else 0L
        val fv = f.of(current)
        val dlg = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        col.addView(text("${p.name}_${r.name} · ${f.name}", 20f, R.color.bar_text))
        val access = when (f.access) { "r" -> "read only"; "w" -> "write only"; else -> "read/write" }
        col.addView(text("${f.bits} · $access · reset ${FieldText.value(f, f.of(r.reset))} · at ${FieldText.word(addr)}",
            12f, R.color.label, mono = true))
        val about = FieldText.about(p, f)
        if (about.isNotEmpty()) col.addView(text(about, 15f, R.color.console_text).apply { setPadding(0, dp(10), 0, dp(4)) })
        if (f.about.isNotEmpty() && f.description.isNotEmpty() && !f.description.startsWith("Port x"))
            col.addView(text("ST: ${f.description}", 12f, R.color.label))
        if (p.stored) col.addView(text("Not emulated yet: the register keeps what is written, nothing more happens.", 13f, R.color.chip_message))
        if (r.readable && f.readable) {
            val m = FieldText.meaning(p, f, fv, vm.chip)
            col.addView(text("Now  ${FieldText.value(f, fv)}${if (m.isNotEmpty()) "  $m" else ""}", 14f, R.color.accent, mono = true)
                .apply { setPadding(0, dp(10), 0, 0) })
        }

        // The last write that changed it
        vm.accessLog.lastWrite(addr, f.mask, !r.readable)?.let { w ->
            col.addView(text("Last changed by ${LogSection.where(vm, w)} at %.3f s".format(Locale.ROOT, w.lastNs / 1e9) +
                if (w.count > 1) " (×${w.count})" else "", 13f, R.color.label).apply { setPadding(0, dp(8), 0, 0) })
        }

        // Wired to (GPIO fields)
        FieldText.pin(p, f)?.let { pin ->
            val users = vm.board.parts.filter { part -> part.pins.any { it.pin == pin } }.map { it.type.title }.toMutableList()
            if (pin == "PA2") users.add("USB-serial RX")
            if (pin == "PA3") users.add("USB-serial TX")
            col.addView(text("$pin: ${if (users.isEmpty()) "nothing wired" else users.joinToString(", ")}", 13f, R.color.label)
                .apply { setPadding(0, dp(8), 0, 0) })
        }

        if (!f.writable || !r.fields.any { it.writable }) {
            dlg.setContentView(ScrollView(activity).apply { addView(col) })
            dlg.show()
            return
        }

        // The value to write: one of the listed values, 0 or 1, or a number
        var chosen = if (r.readable) fv else 0L
        val forth = text("", 13f, R.color.syntax_name, mono = true).apply { setTextIsSelectable(true) }
        fun updateForth() { forth.text = FieldText.forth(p, r, f, chosen) }
        val options: List<Pair<Long, String>> = when {
            f.values.isNotEmpty() -> f.values.map { it.canonical to "${it.pattern}  ${p.fill(it.meaning, f)}" }
            f.width == 1 -> listOf(0L to "0", 1L to "1")
            else -> emptyList()
        }
        if (options.isNotEmpty()) {
            val group = RadioGroup(activity).apply { setPadding(0, dp(8), 0, 0) }
            for ((i, o) in options.withIndex()) group.addView(RadioButton(activity).apply {
                id = 1000 + i
                text = o.second
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.console_text))
                isChecked = r.readable && f.values.getOrNull(i)?.matches(fv) ?: (o.first == fv)
            })
            group.setOnCheckedChangeListener { _, id -> chosen = options[id - 1000].first; updateForth() }
            if (group.checkedRadioButtonId == -1) { chosen = options[0].first }
            col.addView(group)
        } else {
            val max = (1L shl f.width) - 1
            val input = EditText(activity).apply {
                setText(FieldText.value(f, chosen).let { if (f.width <= 4) chosen.toString() else it })
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Typeface.MONOSPACE
                hint = "0 to $max ($ for hex)"
                setTextColor(ContextCompat.getColor(activity, R.color.console_text))
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = FieldText.parse(s.toString())
                    input.error = if (v == null || v < 0 || v > max) "0 to $max" else null
                    if (input.error == null && v != null) { chosen = v; updateForth() }
                }
            })
            col.addView(input)
        }
        updateForth()
        col.addView(text("In Forth", 12f, R.color.label).apply { setPadding(0, dp(12), 0, dp(2)) })
        col.addView(forth)

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        val copy = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.registers_copy_forth)
            setOnClickListener {
                activity.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Forth", forth.text.toString()))
                Toast.makeText(activity, R.string.registers_forth_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val write = MaterialButton(activity).apply {
            setText(R.string.registers_write)
            setOnClickListener {
                val now = if (r.readable) vm.peekWords(intArrayOf(addr.toInt()))[0].toLong() and 0xFFFFFFFFL else 0L
                val ok = vm.pokeWord(addr.toInt(), FieldText.write(r, f, now, chosen).toInt())
                if (!ok) Toast.makeText(activity, R.string.registers_write_failed, Toast.LENGTH_SHORT).show()
                dlg.dismiss()
                refresh()
            }
        }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(write, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        col.addView(row)
        dlg.setContentView(ScrollView(activity).apply { addView(col) })
        dlg.show()
    }

    private fun text(s: String, size: Float, color: Int, mono: Boolean = false) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(ContextCompat.getColor(activity, color))
        if (mono) typeface = Typeface.MONOSPACE
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val HOLD_MS = 1500L

        /** GPIOA < GPIOB, TIM2 < TIM15 */
        private val NAME_PARTS = Regex("(\\D*)(\\d*)(.*)")
        private val NATURAL = Comparator<String> { a, b ->
            val ra = NAME_PARTS.matchEntire(a)!!.groupValues
            val rb = NAME_PARTS.matchEntire(b)!!.groupValues
            compareValuesBy(ra, rb, { it[1] }, { it[2].toIntOrNull() ?: -1 }, { it[3] })
        }
    }
}
