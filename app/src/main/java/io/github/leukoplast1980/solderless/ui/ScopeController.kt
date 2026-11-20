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
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.vm.NativeVm
import io.github.leukoplast1980.solderless.vm.VmHost

class ScopeController(private val activity: AppCompatActivity, private val vm: VmHost) {

    private val view: ScopeView = activity.findViewById(R.id.scopeView)
    private val readout: TextView = activity.findViewById(R.id.scopeReadout)
    private val hold: MaterialButton = activity.findViewById(R.id.scopeHold)
    private val trigger: MaterialButton = activity.findViewById(R.id.scopeTrigger)
    private val windows = mapOf(R.id.scopeW1ms to 1_000_000L, R.id.scopeW10ms to 10_000_000L, R.id.scopeW100ms to 100_000_000L,
        R.id.scopeW1s to 1_000_000_000L, R.id.scopeW5s to 5_000_000_000L)

    private val events = HashMap<Int, ArrayList<ScopeView.Ev>>()
    private val raw = IntArray(2 + 5 * 4096)
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
            heldEnd = (heldEnd + dt).coerceIn(now - KEEP_NS + view.windowNs, now)
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

    private fun channelsOf(b: Board): List<Triple<Int, String, Pair<String, Boolean>>> {
        val out = ArrayList<Triple<Int, String, Pair<String, Boolean>>>()
        for (p in b.parts.sortedBy { it.id }) for ((r, u) in p.pins.withIndex()) {
            val pin = vm.chip.gpio(u.pin) ?: continue
            val role = p.type.roles[r]
            val analog = u.function is Function.Adc || u.function is Function.Dac || p.type == PartType.POT
            val fn = when (val f = u.function) { is Function.Timer -> ", ${f.timer}"; is Function.Adc -> ", ${f.adc}"; is Function.Dac -> ", DAC1"; else -> "" }
            out += Triple(pin.slot, u.pin, (p.type.title + (if (role.isEmpty()) "" else " $role") + fn) to analog)
        }
        for (name in extraPins(activity, vm.project.name)) {
            val pin = vm.chip.gpio(name) ?: continue
            if (out.none { it.first == pin.slot }) out += Triple(pin.slot, name, "watched" to false)
        }
        return out
    }

    /** Drains the trace (always, so the last seconds are there when the tab opens); redraws if visible. */
    fun poll(visible: Boolean) {
        val b = vm.board
        val chans = channelsOf(b)
        val slots = chans.map { it.first }.toIntArray()
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
            val cut = list.indexOfFirst { it.t >= keepFrom }
            if (cut > 1) list.subList(0, cut - 1).clear()
            if (list.size > MAX_EVENTS) list.subList(0, list.size - MAX_EVENTS).clear()
        }
        if (!visible) return
        view.endNs = end
        view.triggerNs = if (trigSlot >= 0) lastTrig ?: -1 else -1
        view.channels = chans.map { (slot, pin, lab) -> ScopeView.Channel(slot, pin, lab.first, lab.second, events[slot] ?: emptyList()) }
        view.invalidate()
        updateReadout()
    }

    /** Latest trigger edge whose window has been fully recorded (or before [until]) */
    private fun lastTrigger(until: Long = now - view.windowNs * 3 / 4): Long? {
        val ev = events[trigSlot] ?: return null
        for (k in ev.indices.reversed()) {
            if (k == 0 || ev[k].t > until) continue
            val high = ev[k].a in 1650..100000; val was = ev[k - 1].a in 1650..100000
            if (high != was && high == trigRising) return ev[k].t
        }
        return null
    }

    private fun chooseTrigger() {
        val chans = channelsOf(vm.board).filter { !it.third.second }
        val items = listOf(activity.getString(R.string.scope_trigger_off)) +
            chans.flatMap { listOf("${it.second} ↑ ${it.third.first}", "${it.second} ↓ ${it.third.first}") }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.scope_trigger)
            .setItems(items.toTypedArray()) { _, which ->
                lastTrig = null
                if (which == 0) { trigSlot = -1; trigger.text = activity.getString(R.string.scope_trigger) }
                else {
                    val c = chans[(which - 1) / 2]
                    trigSlot = c.first; trigRising = (which - 1) % 2 == 0
                    trigger.text = activity.getString(R.string.scope_trigger) + " " + c.second + if (trigRising) " ↑" else " ↓"
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
