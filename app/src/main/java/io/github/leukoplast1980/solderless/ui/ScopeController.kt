/*
 * Solderless - the Scope tab: keeps the last seconds of every watched pin
 * and shows them over a window, live, held or triggered.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.Function
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.vm.NativeVm
import io.github.leukoplast1980.solderless.vm.VmHost

class ScopeController(private val activity: AppCompatActivity, private val vm: VmHost) {

    private val view: ScopeView = activity.findViewById(R.id.scopeView)
    private val readout: TextView = activity.findViewById(R.id.scopeReadout)
    private val hold: MaterialButton = activity.findViewById(R.id.scopeHold)
    private val trigger: MaterialButton = activity.findViewById(R.id.scopeTrigger)
    private val windows = mapOf(R.id.scopeW10us to 10_000L, R.id.scopeW100us to 100_000L, R.id.scopeW1ms to 1_000_000L,
        R.id.scopeW10ms to 10_000_000L, R.id.scopeW1s to 1_000_000_000L)

    private val events = HashMap<Int, ArrayList<ScopeView.Ev>>()
    private val raw = IntArray(2 + 5 * 4096)
    private val busRaw = IntArray(BusEv.INTS * 2048)
    /** By bus (BusEv.key): what went over it */
    private val busEvents = HashMap<String, ArrayList<BusEv>>()
    private var now = 0L
    private var held = false
    private var heldEnd = 0L
    private var trigSlot = -1
    private var trigRising = false
    private var lastTrig: Long? = null
    private var board: Board? = null
    private var traced = IntArray(0)

    init {
        val group = activity.findViewById<MaterialButtonToggleGroup>(R.id.scopeWindow)
        group.check(R.id.scopeW1s)
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) { view.windowNs = windows[id] ?: view.windowNs; view.invalidate() } }
        view.onUserZoom = { w -> group.clearChecked(); view.windowNs = w; view.invalidate() }
        view.onUserPan = { dt ->
            if (!held) setHeld(true)
            // back as far as there is history (held, that can be more than KEEP_NS)
            val oldest = events.values.mapNotNull { it.firstOrNull()?.t }.minOrNull() ?: now
            heldEnd = (heldEnd + dt).coerceIn(minOf(oldest + view.windowNs, now), now)
            view.endNs = heldEnd; view.invalidate()
        }
        view.onCursors = { updateReadout() }
        hold.setOnClickListener { setHeld(!held) }
        trigger.setOnClickListener { chooseTrigger() }
    }

    private fun setHeld(h: Boolean) {
        held = h
        heldEnd = if (view.endNs > 0) view.endNs else now
        hold.text = activity.getString(if (h) R.string.scope_held else R.string.scope_hold)
        hold.isChecked = h
    }

    /** A watched pin: its slot, name, label, analog, the rows it shares a background with, its bus */
    private class Chan(val slot: Int, val pin: String, val label: String, val analog: Boolean, val group: String, val bus: String?)

    /** One row per pin: a pin several parts share (a bus) is shown once */
    private fun channelsOf(b: Board): List<Chan> {
        val out = ArrayList<Chan>()
        val slotOf = { name: String -> vm.chip.gpio(name)?.slot }
        for (p in b.parts.sortedBy { it.id }) {
            val bus = BusDecode.keyOf(p, slotOf)
            for ((r, u) in p.pins.withIndex()) {
                val pin = vm.chip.gpio(u.pin) ?: continue
                val role = p.type.roles[r]
                val k = out.indexOfFirst { it.slot == pin.slot }
                if (bus != null) {
                    val label = role.uppercase() + ", " + when (val f = u.function) {
                        is Function.I2c -> f.periph
                        is Function.Spi -> f.periph
                        else -> p.type.title
                    }
                    if (k >= 0) {                           // another part on the same line
                        val c = out[k]
                        if (c.bus == bus && c.label != label && !c.label.endsWith(" parts"))
                            out[k] = Chan(c.slot, c.pin, role.uppercase() + ", 2 parts", false, c.group, bus)
                        else if (c.bus == bus && c.label.endsWith(" parts")) {
                            val n = c.label.substringAfterLast(", ").substringBefore(" ").toIntOrNull() ?: 2
                            out[k] = Chan(c.slot, c.pin, role.uppercase() + ", ${n + 1} parts", false, c.group, bus)
                        }
                        continue
                    }
                    out += Chan(pin.slot, u.pin, label, false, bus, bus)
                    continue
                }
                if (k >= 0) continue
                val analog = u.function is Function.Adc || u.function is Function.Dac || p.type == PartType.POT
                val fn = when (val f = u.function) { is Function.Timer -> ", ${f.timer}"; is Function.Adc -> ", ${f.adc}"; is Function.Dac -> ", DAC1"; else -> "" }
                out += Chan(pin.slot, u.pin, p.type.title + (if (role.isEmpty()) "" else " $role") + fn, analog, "part:${p.id}", null)
            }
        }
        for (name in extraPins(activity, vm.project.name)) {
            val pin = vm.chip.gpio(name) ?: continue
            if (out.none { it.slot == pin.slot }) out += Chan(pin.slot, name, "watched", false, "pin:${pin.slot}", null)
        }
        return out
    }

    /** The rows: the pins, and after the last pin of each bus its decoded bytes */
    private fun rowsOf(chans: List<Chan>, parts: Map<Int, Part>): List<ScopeView.Row> {
        val rows = ArrayList<ScopeView.Row>()
        for ((i, c) in chans.withIndex()) {
            val ev = c.bus?.let { busEvents[it] } ?: emptyList<BusEv>()
            rows += ScopeView.Channel(c.slot, c.pin, c.label, c.analog, events[c.slot] ?: emptyList(), c.group, ev)
            val key = c.bus ?: continue
            if (chans.drop(i + 1).any { it.bus == key }) continue
            rows += ScopeView.Decode(ev) { from, at -> BusDecode.describeAt(ev, from, at, parts) }
        }
        return rows
    }

    /** Drains the trace (always, so the last seconds are there when the tab opens); redraws if visible. */
    fun poll(visible: Boolean) {
        val b = vm.board
        val chans = channelsOf(b)
        val slots = chans.map { it.slot }.toIntArray()
        if (b !== board || !slots.contentEquals(traced)) {
            board = b
            if (!slots.contentEquals(traced)) {
                traced = slots
                events.keys.retainAll(slots.toSet())
                NativeVm.tracePins(vm.handle, slots)
            }
        }
        if (slots.isEmpty() && !visible) return
        while (true) {
            val n = NativeVm.traceRead(vm.handle, raw)
            now = (raw[1].toLong() shl 32) or (raw[0].toLong() and 0xFFFFFFFFL)
            for (i in 0 until n) {
                val o = 2 + 5 * i
                val t = (raw[o + 1].toLong() shl 32) or (raw[o].toLong() and 0xFFFFFFFFL)
                val kind = raw[o + 2]
                events.getOrPut((kind shr 8) and 255) { ArrayList() }.add(
                    ScopeView.Ev(t, kind and 255 == 1, raw[o + 3].toLong() and 0xFFFFFFFFL, raw[o + 4].toLong() and 0xFFFFFFFFL, kind and 0x10000 != 0))
            }
            if (n < 4096) break
        }
        while (true) {
            val n = NativeVm.busRead(vm.handle, busRaw)
            for (i in 0 until n) {
                val e = BusEv.unpack(busRaw, i)
                busEvents.getOrPut(e.key) { ArrayList() }.add(e)
            }
            if (n < busRaw.size / BusEv.INTS) break
        }
        // Where the display is: held, or on the last trigger
        val trig = if (trigSlot >= 0 && !held) lastTrigger() else null
        if (trig != null) lastTrig = trig
        val end = when {
            held -> heldEnd
            trigSlot >= 0 -> lastTrig?.let { it + view.windowNs * 3 / 4 } ?: now
            else -> now
        }
        // Keep the last seconds and whatever is on screen, and the event in force before them
        val keepFrom = minOf(now - KEEP_NS, end - view.windowNs)
        for (list in events.values) {
            var lo = 0; var hi = list.size              // the first event at or after keepFrom
            while (lo < hi) { val m = (lo + hi) ushr 1; if (list[m].t < keepFrom) lo = m + 1 else hi = m }
            if (lo > 1) list.subList(0, lo - 1).clear()
            if (list.size > MAX_EVENTS) list.subList(0, list.size - MAX_EVENTS).clear()
        }
        for (list in busEvents.values) {
            var lo = 0; var hi = list.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (list[m].t < keepFrom) lo = m + 1 else hi = m }
            if (lo > 0) list.subList(0, lo).clear()
            if (list.size > MAX_BUS_EVENTS) list.subList(0, list.size - MAX_BUS_EVENTS).clear()
        }
        busEvents.keys.retainAll(chans.mapNotNull { it.bus }.toSet())
        if (!visible) return
        view.endNs = end
        view.triggerNs = if (trigSlot >= 0) lastTrig ?: -1 else -1
        view.channels = rowsOf(chans, b.parts.associateBy { it.id })
        view.invalidate()
        updateReadout()
    }

    /** Latest trigger edge whose window has been fully recorded (or before [until]) */
    private fun lastTrigger(until: Long = now - view.windowNs * 3 / 4): Long? {
        var found: Long? = null
        val ev = events[trigSlot]
        if (ev != null) for (k in ev.indices.reversed()) {
            if (k == 0 || ev[k].t > until) continue
            val high = ev[k].a in 1650..100000; val was = ev[k - 1].a in 1650..100000
            if (high != was && high == trigRising) { found = ev[k].t; break }
        }
        // An on-chip I2C or SPI does not move the pin's traced level bit by bit: its edges are in what it sent
        for (list in busEvents.values) {
            val e0 = list.firstOrNull() ?: continue
            if (trigSlot != e0.p0 && trigSlot != e0.p1 && trigSlot != e0.p2) continue
            for (k in list.indices.reversed()) {
                val e = list[k]
                if (!e.hw || e.t > until) continue
                val t = when {
                    e.kind == BusEv.START && !trigRising -> e.t + e.dur / 2      // SDA falls, then SCL
                    e.kind == BusEv.STOP && trigRising -> e.t + e.dur * 2 / 3
                    e.kind == BusEv.SPI && (k == 0 || list[k - 1].kind != BusEv.SPI || e.t - list[k - 1].end > e.dur) -> e.t
                    else -> continue
                }
                if (found == null || t > found) found = t
                break
            }
        }
        return found
    }

    private fun chooseTrigger() {
        val chans = channelsOf(vm.board).filter { !it.analog }
        val items = listOf(activity.getString(R.string.scope_trigger_off)) +
            chans.flatMap { listOf("${it.pin} ↑ ${it.label}", "${it.pin} ↓ ${it.label}") }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.scope_trigger)
            .setItems(items.toTypedArray()) { _, which ->
                lastTrig = null
                if (which == 0) { trigSlot = -1; trigger.text = activity.getString(R.string.scope_trigger) }
                else {
                    val c = chans[(which - 1) / 2]
                    trigSlot = c.slot; trigRising = (which - 1) % 2 == 0
                    trigger.text = activity.getString(R.string.scope_trigger) + " " + c.pin + if (trigRising) " ↑" else " ↓"
                }
            }.show()
    }

    private fun updateReadout() {
        val a = view.cursorA; val b = view.cursorB
        readout.visibility = if (a >= 0 || b >= 0) View.VISIBLE else View.GONE
        readout.text = when {
            a >= 0 && b >= 0 -> {
                val dt = kotlin.math.abs(b - a)
                "A–B  Δt " + ScopeView.span(dt) + if (dt > 0) "  ·  " + ScopeView.hz((1e12 / dt).toLong()) else ""
            }
            else -> activity.getString(R.string.scope_cursor_hint)
        }
    }

    companion object {
        const val KEEP_NS = 6_000_000_000L
        /** Per pin: enough for 5 s of a pin toggling every 25 us */
        const val MAX_EVENTS = 200_000
        /** Per bus: a few seconds of a busy one */
        const val MAX_BUS_EVENTS = 400_000

        private fun prefs(c: Context) = c.getSharedPreferences("scope", Context.MODE_PRIVATE)

        /** Pins the user added to the scope from the Board tab (beyond those with parts) */
        fun extraPins(c: Context, project: String): Set<String> =
            prefs(c).getString("extra:$project", "")!!.split(',').filter { it.isNotEmpty() }.toSet()

        fun setWatched(c: Context, project: String, pin: String, on: Boolean) {
            val s = extraPins(c, project).toMutableSet()
            if (on) s += pin else s -= pin
            prefs(c).edit().putString("extra:$project", s.joinToString(",")).apply()
        }
    }
}
