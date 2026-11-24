/*
 * Solderless - the Chip tab's Log section: which instruction wrote which
 * register, the fields it changed from what to what, and the chip model's
 * checks, newest first; filtered by kind or peripheral.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.view.View
import android.widget.PopupMenu
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.chip.AccessLog
import io.github.leukoplast1980.solderless.chip.FieldText
import io.github.leukoplast1980.solderless.chip.RegisterMap
import io.github.leukoplast1980.solderless.ui.LogListView.Row
import io.github.leukoplast1980.solderless.vm.VmHost
import java.util.Locale

class LogSection(
    private val activity: Activity, private val vm: VmHost,
    /** Shows a register in the Registers section: "GPIOA_ODR" */
    private val openRegister: (String) -> Unit,
) {

    private enum class Kind { ALL, WRITES, CHECKS }

    private val list: LogListView = activity.findViewById(R.id.logList)
    private val filters: ChipGroup = activity.findViewById(R.id.logFilters)
    private var kind = Kind.ALL
    private var periph: String? = null
    /** Writes to USART2_TDR: the characters the console shows */
    private var showConsole = false

    private var shownGeneration = -1L
    private var actions: List<(() -> Unit)?> = emptyList()
    private lateinit var periphChip: Chip

    init {
        val kinds = listOf(Kind.ALL to R.string.log_all, Kind.WRITES to R.string.log_writes, Kind.CHECKS to R.string.log_checks)
        for ((k, label) in kinds) filters.addView(Chip(activity).apply {
            setText(label)
            isCheckable = true
            isChecked = k == kind
            tag = k
            setOnClickListener { kind = k; syncChips(); rebuild() }
        })
        periphChip = Chip(activity).apply {
            setText(R.string.log_peripheral)
            setOnClickListener { pickPeripheral(this) }
            setOnCloseIconClickListener { periph = null; syncChips(); rebuild() }
        }
        filters.addView(periphChip)
        filters.addView(Chip(activity).apply {
            setText(R.string.log_clear)
            setOnClickListener { vm.accessLog.clear(); rebuild() }
        })
        list.onTap = { i -> actions.getOrNull(i)?.invoke() }
    }

    private fun syncChips() {
        for (i in 0 until filters.childCount) {
            val c = filters.getChildAt(i) as Chip
            if (c.tag is Kind) c.isChecked = c.tag == kind
        }
        periphChip.text = periph ?: activity.getString(R.string.log_peripheral)
        periphChip.isCloseIconVisible = periph != null
    }

    private fun pickPeripheral(anchor: View) {
        val map = vm.registerMap ?: return
        val log = vm.accessLog
        val names = (0 until log.size).mapNotNull { (log[it] as? AccessLog.Write)?.let { w -> map.at(w.addr)?.first?.name } }
            .distinct().sorted()
        val menu = PopupMenu(activity, anchor)
        if (names.isEmpty()) menu.menu.add(R.string.log_no_writes).isEnabled = false
        for (n in names) menu.menu.add(n)
        menu.setOnMenuItemClickListener { item ->
            periph = item.title.toString()
            syncChips()
            rebuild()
            true
        }
        menu.show()
    }

    fun refresh() {
        if (vm.accessLog.generation == shownGeneration && list.rows.isNotEmpty()) return
        rebuild()
    }

    private fun rebuild() {
        val map = vm.registerMap
        val log = vm.accessLog
        shownGeneration = log.generation
        val rows = ArrayList<Row>()
        val acts = ArrayList<(() -> Unit)?>()
        fun add(r: Row, a: (() -> Unit)?) { rows.add(r); acts.add(a) }
        if (map == null) {
            add(Row.Note(activity.getString(R.string.log_reading), false), null)
        } else {
            if (log.lost > 0) add(Row.Note(plural(R.plurals.log_lost, log.lost), false), null)
            var hidden = 0L
            var shown = 0
            for (i in log.size - 1 downTo 0) {
                if (shown >= MAX_ROWS) break
                when (val e = log[i]) {
                    is AccessLog.Write -> {
                        if (kind == Kind.CHECKS) continue
                        val pr = map.at(e.addr)
                        if (periph != null && pr?.first?.name != periph) continue
                        if (!showConsole && periph != "USART2" && pr?.first?.name == "USART2" && pr.second.name == "TDR") {
                            hidden += e.count
                            continue
                        }
                        val name = pr?.let { "${it.first.name}_${it.second.name}" } ?: FieldText.word(e.addr)
                        add(Row.Write(time(if (e.count > 1) e.lastNs else e.timeNs), where(vm, e), e.debug,
                            if (e.count > 1) "×${e.count}" else "", name, changes(pr, e))) {
                            if (pr != null) openRegister(name)
                        }
                        shown++
                    }
                    is AccessLog.Message -> {
                        if (kind == Kind.WRITES || periph != null && !e.text.contains(periph!!)) continue
                        add(Row.Check(time(e.timeNs), e.text), null)
                        shown++
                    }
                }
            }
            if (hidden > 0) add(Row.Note(plural(R.plurals.log_console_hidden, hidden), true)) {
                showConsole = true
                rebuild()
            } else if (showConsole) add(Row.Note(activity.getString(R.string.log_console_hide), true)) {
                showConsole = false
                rebuild()
            }
            if (shown == 0 && hidden == 0L) add(Row.Note(activity.getString(R.string.log_empty), false), null)
        }
        actions = acts
        list.show(rows)
    }

    private fun time(ns: Long) = "%.3f".format(Locale.ROOT, ns / 1e9)

    private fun plural(id: Int, n: Long) =
        activity.resources.getQuantityString(id, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n)

    /** The fields a write changed: ("MODER5 11→01", "output") */
    private fun changes(pr: Pair<RegisterMap.Peripheral, RegisterMap.Register>?, e: AccessLog.Write): List<Pair<String, String>> {
        if (pr == null) return listOf("${FieldText.word(e.before)} → ${FieldText.word(e.after)}" to "")
        val (p, r) = pr
        val out = ArrayList<Pair<String, String>>()
        if (!r.readable || r.fields.isEmpty() && e.changed == 0L) {
            // write-only registers (BSRR, ICR ...): the bits written
            val fs = r.fields.filter { e.value and it.mask != 0L }
            for (f in fs) out.add("${f.name} ${FieldText.value(f, f.of(e.value))}" to FieldText.meaning(p, f, f.of(e.value), vm.chip))
            if (fs.isEmpty()) out.add("wrote ${FieldText.word(e.value)}" to "")
        } else if (r.fields.isEmpty()) {
            out.add("${FieldText.word(e.before)} → ${FieldText.word(e.after)}" to "")
        } else {
            for (f in r.fields) {
                if (e.changed and f.mask == 0L) continue
                val a = f.of(e.before)
                val b = f.of(e.after)
                val change = if (a != b) "${f.name} ${FieldText.value(f, a)}→${FieldText.value(f, b)}"
                else "${f.name} changed, now ${FieldText.value(f, b)}"
                out.add(change to FieldText.meaning(p, f, b, vm.chip))
            }
            if (out.isEmpty()) out.add("no change" to "wrote ${FieldText.word(e.value)}")
        }
        if (out.size > MAX_LINES) {
            val more = out.size - (MAX_LINES - 1)
            return out.take(MAX_LINES - 1) + ("+ $more more fields" to "")
        }
        return out
    }

    companion object {
        private const val MAX_ROWS = 400
        private const val MAX_LINES = 4

        /** Who wrote: the Forth word and offset, the Registers view, or the address */
        fun where(vm: VmHost, w: AccessLog.Write): String {
            if (w.debug) return "Registers view"
            val word = if (vm.project.firmware.isStellaris) vm.dictionary.wordAt(w.pc.toInt()) else null
            return if (word != null) "${word.name} +${w.pc.toInt() - word.code}" else "pc ${FieldText.word(w.pc)}"
        }
    }
}
