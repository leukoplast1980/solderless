/*
 * Solderless - the Chip tab's Clocks section: the clock tree as RCC has it
 * set up, live; a tap on a box lists the register fields that set it.
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
import io.github.leukoplast1980.solderless.chip.ClockTree
import io.github.leukoplast1980.solderless.chip.FieldText
import io.github.leukoplast1980.solderless.vm.VmHost
import java.util.Locale

class ClocksSection(
    private val activity: Activity, private val vm: VmHost,
    /** Shows registers in the Registers section: "RCC_PLLCFGR" */
    private val openRegister: (String) -> Unit,
) {

    private val view: ClockView = activity.findViewById(R.id.clockView)
    private var tree: ClockTree? = null

    init {
        view.onBox = { key -> tree?.let { showBox(it, key) } }
    }

    /** The clock tree now, or null until the register descriptions are read */
    fun read(): ClockTree? {
        val map = vm.registerMap ?: return null
        val a = ClockTree.ADDRESSES
        val w = vm.peekWords(IntArray(a.size) { a[it].toInt() })
        val words = HashMap<Long, Long>(a.size * 2)
        for (i in a.indices) words[a[i]] = w[i].toLong() and 0xFFFFFFFFL
        return ClockTree({ words[it] ?: 0L }, vm.project.board.hseHz.toLong(), vm.project.board.lse, map)
    }

    fun refresh() {
        val t = read() ?: return
        tree = t
        view.show(t)
    }

    // ------------------------------------------------------------------
    // A box close up: what it is and the fields that set it

    private class Item(val periph: String, val register: String, val field: String)

    private fun showBox(t: ClockTree, key: String) {
        fun rcc(r: String, vararg f: String) = f.map { Item("RCC", r, it) }
        val (title, about, items) = when (key) {
            "HSI16" -> Triple("HSI16", "The internal 16 MHz RC oscillator. It runs after reset and Stellaris starts on it.", rcc("CR", "HSION", "HSIRDY"))
            "HSE" -> Triple("HSE", (if (t.hseHz == 0L) "No crystal on this board (Board settings). " else "A ${ClockTree.freq(t.hseHz)} crystal on PF0 and PF1. ") +
                "HSE is a crystal or a clock signal on PF0 (OSC_IN) and PF1 (OSC_OUT), 4 to 48 MHz.", rcc("CR", "HSEON", "HSERDY", "HSEBYP"))
            "HSI48" -> Triple("HSI48", "An internal 48 MHz RC oscillator, for USB and the random number generator.", rcc("CRRCR", "HSI48ON", "HSI48RDY"))
            "LSI" -> Triple("LSI", "An internal 32 kHz RC oscillator, for the watchdog and the RTC.", rcc("CSR", "LSION", "LSIRDY"))
            "LSE" -> Triple("LSE", (if (t.lseFitted) "A 32.768 kHz crystal on PC14 and PC15. " else "No 32.768 kHz crystal on this board (Board settings). ") +
                "LSE clocks the RTC.", rcc("BDCR", "LSEON", "LSERDY"))
            "PLL" -> Triple("PLL", "Multiplies a clock: the input ÷ M must be 2.66 to 16 MHz, × N gives the VCO (96 to 344 MHz), and R, Q and P divide the VCO again. " +
                "R can become SYSCLK (at most 170 MHz). Its settings only change while it is off.",
                rcc("CR", "PLLON", "PLLRDY") + rcc("PLLCFGR", "PLLSRC", "PLLM", "PLLN", "PLLR", "PLLREN", "PLLQ", "PLLQEN", "PLLP", "PLLPDIV", "PLLPEN"))
            "SW" -> Triple("SYSCLK", "SW picks SYSCLK: HSI16, HSE or the PLL's R output; SWS shows the switch once it has happened. " +
                "Now ${ClockTree.freq(t.sysclkHz)} from ${t.sysclkSourceName}.", rcc("CFGR", "SW", "SWS"))
            "FLASH" -> Triple("Flash wait states", "Flash is slower than the core: the faster HCLK runs, the more wait states a read needs. " +
                "${ClockTree.freq(t.hclkHz)} in ${t.rangeText} needs ${t.latencyNeeded}; FLASH_ACR has ${t.latency}. Above 150 MHz the core voltage needs boost mode (PWR_CR5).",
                listOf(Item("FLASH", "ACR", "LATENCY"), Item("FLASH", "ACR", "PRFTEN"), Item("FLASH", "ACR", "ICEN"), Item("FLASH", "ACR", "DCEN"),
                    Item("PWR", "CR1", "VOS"), Item("PWR", "CR5", "R1MODE")))
            "AHB" -> Triple("AHB", "HCLK = SYSCLK ÷ HPRE: the core, the memories, DMA and the GPIO ports.", rcc("CFGR", "HPRE"))
            "APB1" -> Triple("APB1", "PCLK1 = HCLK ÷ PPRE1. Its timers count at PCLK1, or twice that when PPRE1 divides.", rcc("CFGR", "PPRE1"))
            "APB2" -> Triple("APB2", "PCLK2 = HCLK ÷ PPRE2. Its timers count at PCLK2, or twice that when PPRE2 divides.", rcc("CFGR", "PPRE2"))
            "SysTick" -> Triple("SysTick", "The core's 24-bit timer: it counts HCLK or HCLK ÷ 8 down from LOAD.",
                listOf(Item("SysTick", "CTRL", "ENABLE"), Item("SysTick", "CTRL", "CLKSOURCE"), Item("SysTick", "CTRL", "TICKINT"), Item("SysTick", "LOAD", "RELOAD")))
            else -> peripheralBox(t, key) ?: return
        }
        val dlg = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        col.addView(text(title, 20f, R.color.bar_text))
        col.addView(text(about, 14f, R.color.console_text).apply { setPadding(0, dp(6), 0, dp(6)) })
        val map = vm.registerMap ?: return
        val found = items.mapNotNull { item ->
            val p = map.peripheral(item.periph) ?: return@mapNotNull null
            val r = p.registers.firstOrNull { it.name == item.register } ?: return@mapNotNull null
            val f = r.fields.firstOrNull { it.name == item.field } ?: return@mapNotNull null
            Triple(p, r, f)
        }
        val words = vm.peekWords(IntArray(found.size) { found[it].first.address(found[it].second).toInt() })
        var lastReg = ""
        for ((k, prf) in found.withIndex()) {
            val (p, r, f) = prf
            val reg = "${p.name}_${r.name}"
            if (reg != lastReg) {
                col.addView(text(reg, 12f, R.color.label).apply { letterSpacing = 0.04f; setPadding(0, dp(12), 0, dp(2)) })
                lastReg = reg
            }
            val v = f.of(words[k].toLong() and 0xFFFFFFFFL)
            col.addView(text("%-9s %-5s %s".format(Locale.ROOT, f.name, FieldText.value(f, v), FieldText.meaning(p, f, v, vm.chip)),
                13f, R.color.console_text, mono = true))
        }
        found.firstOrNull()?.let { (p, r, _) ->
            col.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = activity.getString(R.string.pins_in_registers, "${p.name}_${r.name}")
                setOnClickListener { dlg.dismiss(); openRegister("${p.name}_${r.name}") }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        dlg.setContentView(ScrollView(activity).apply { addView(col) })
        dlg.show()
    }

    /** A peripheral's clock: its enable bit, and its kernel clock choice if it has one */
    private fun peripheralBox(t: ClockTree, name: String): Triple<String, String, List<Item>>? {
        val map = vm.registerMap ?: return null
        val p = map.peripheral(name) ?: return null
        val rcc = map.peripheral("RCC") ?: return null
        val enReg = rcc.registers.firstOrNull { it.offset == p.enOffset } ?: return null
        val enField = enReg.fields.firstOrNull { it.bit == p.enBit } ?: return null
        val items = mutableListOf(Item("RCC", enReg.name, enField.name))
        val ccipr = rcc.registers.firstOrNull { it.name == "CCIPR" }
        val sel = when (name) { "ADC1", "ADC2" -> "ADC12SEL"; else -> "${name}SEL" }
        if (ccipr?.fields?.any { it.name == sel } == true) items.add(Item("RCC", "CCIPR", sel))
        val pk = (t.ahb.peripherals + t.apb1.peripherals + t.apb2.peripherals).firstOrNull { it.name == name }
        val about = buildString {
            append(if (pk?.on == true) "Its clock is on" else "Its clock is off: its registers read 0 and writes to them are lost")
            if (pk != null && pk.on && pk.kernel.isNotEmpty()) append("; it runs from ${pk.kernel} (${ClockTree.freq(pk.kernelHz)})")
            append(".")
        }
        return Triple(name, about, items)
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
