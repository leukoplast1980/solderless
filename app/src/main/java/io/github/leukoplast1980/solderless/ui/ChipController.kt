/*
 * Solderless - the Chip tab: its sections (Core, Registers, Clocks, Pins, Log) over a run bar
 * with the chip's state, Pause and Step.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.edit
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.vm.Snapshot
import io.github.leukoplast1980.solderless.vm.VmHost

class ChipController(private val activity: Activity, private val vm: VmHost) {

    enum class Section(val label: Int) {
        CORE(R.string.chip_core), REGISTERS(R.string.chip_registers), CLOCKS(R.string.chip_clocks),
        PINS(R.string.chip_pins), LOG(R.string.chip_log)
    }

    val core = CoreSection(activity, vm)
    val registers = RegistersSection(activity, vm)
    val log = LogSection(activity, vm) { name -> show(Section.REGISTERS); registers.search(name) }
    val pins = PinsSection(activity, vm) { name -> show(Section.REGISTERS); registers.search(name) }
    val clocks = ClocksSection(activity, vm) { name -> show(Section.REGISTERS); registers.search(name) }

    private val sections: ChipGroup = activity.findViewById(R.id.chipSections)
    private val coreView: View = activity.findViewById(R.id.coreSection)
    private val registersView: View = activity.findViewById(R.id.registersSection)
    private val logView: View = activity.findViewById(R.id.logSection)
    private val pinsView: View = activity.findViewById(R.id.pinsSection)
    private val clocksView: View = activity.findViewById(R.id.clocksSection)
    private val runState: TextView = activity.findViewById(R.id.runState)
    private val pauseButton: Button = activity.findViewById(R.id.pause)
    private val stepButton: Button = activity.findViewById(R.id.step)
    private val copyButton: Button = activity.findViewById(R.id.registersCopy)
    private val prefs = activity.getSharedPreferences("chip", Context.MODE_PRIVATE)

    var section: Section = runCatching { Section.valueOf(prefs.getString("section", null) ?: "") }
        .getOrDefault(Section.CORE)
        private set

    /** Called after Pause or Step so the console can update its dictionary. */
    var onPausedChanged: ((Boolean) -> Unit)? = null

    init {
        for (s in Section.entries) {
            sections.addView(Chip(activity).apply {
                setText(s.label)
                isCheckable = true
                isChecked = s == section
                tag = s
                setOnClickListener { show(s) }
            })
        }
        pauseButton.setOnClickListener {
            val paused = !vm.isUserPaused
            vm.setUserPaused(paused)
            core.pauseChanged()
            registers.pauseChanged()
            if (paused) Thread { vm.refreshDictionary() }.start()
            updateButtons()
            onPausedChanged?.invoke(paused)
        }
        stepButton.setOnClickListener {
            core.stepping()
            registers.stepping()
            vm.step()
            runState.postDelayed({ refresh() }, 20)
        }
        copyButton.setOnClickListener { core.copy() }
        show(section)
    }

    fun show(s: Section) {
        section = s
        prefs.edit { putString("section", s.name) }
        for (i in 0 until sections.childCount) {
            val c = sections.getChildAt(i) as Chip
            c.isChecked = c.tag == s
        }
        coreView.visibility = if (s == Section.CORE) View.VISIBLE else View.GONE
        registersView.visibility = if (s == Section.REGISTERS) View.VISIBLE else View.GONE
        logView.visibility = if (s == Section.LOG) View.VISIBLE else View.GONE
        pinsView.visibility = if (s == Section.PINS) View.VISIBLE else View.GONE
        clocksView.visibility = if (s == Section.CLOCKS) View.VISIBLE else View.GONE
        copyButton.visibility = if (s == Section.CORE) View.VISIBLE else View.GONE
        refresh()
    }

    fun refresh() {
        val s = vm.snapshot()
        runState.text = runText(s)
        when (section) {
            Section.CORE -> core.refresh(s)
            Section.REGISTERS -> registers.refresh()
            Section.CLOCKS -> clocks.refresh()
            Section.PINS -> pins.refresh()
            Section.LOG -> log.refresh()
        }
        updateButtons()
    }

    private fun updateButtons() {
        val paused = vm.isUserPaused
        pauseButton.setText(if (paused) R.string.resume else R.string.pause)
        stepButton.isEnabled = paused
    }

    private fun runText(s: Snapshot): String = buildString {
        append(
            when {
                s.lockup -> "locked up"
                vm.faultPaused -> "stopped at a fault"
                s.paused -> "paused"
                s.waitingForKey -> "waiting for input"
                s.sleeping -> "sleeping"
                else -> "running"
            }
        )
        append(" · %s MHz".format(java.util.Locale.ROOT, java.math.BigDecimal(s.sysclk / 1e6).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()))
        append(" · %.3f s".format(java.util.Locale.ROOT, s.timeNs / 1e9))
        append(" · %,d cycles".format(java.util.Locale.ROOT, s.cycles))
        if (!s.paused && s.speedPercent in 1..95) append(" · ${s.speedPercent} % speed")
    }
}
