package io.github.leukoplast1980.solderless

import android.graphics.Bitmap
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.Function as PinFunction
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.board.PinUse
import io.github.leukoplast1980.solderless.ui.LogListView
import io.github.leukoplast1980.solderless.ui.PinListView
import io.github.leukoplast1980.solderless.ui.RegisterListView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Types a few lines into the console of the emulated G431, checks what
 * Mecrisp-Stellaris and the chip model answer, opens the Chip tab's Core
 * and Registers sections, changes a field there, and takes screenshots
 * (saved in the app's external files folder; the UI probe workflow pulls
 * them).
 */
@RunWith(AndroidJUnit4::class)
class ReplProbeTest {

    private fun screenshot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name saved in $dir")
    }

    /** Picks a section of the Chip tab */
    private fun section(a: MainActivity, label: Int) {
        val g = a.findViewById<ChipGroup>(R.id.chipSections)
        (0 until g.childCount).map { g.getChildAt(it) as Chip }.first { it.text == a.getString(label) }.performClick()
    }

    @Test
    fun consoleAndRegisters() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(1500)
        // Other probes ran before on the same chip: start it afresh, so the banner is in the console again
        io.github.leukoplast1980.solderless.vm.VmHost.get(InstrumentationRegistry.getInstrumentation().targetContext).powerCycle()
        Thread.sleep(3000)
        val lines = listOf("3", "4", "+", ".", ": test 0 do i . loop ;", "t test", "5 test", "\$40013800 @ hex.")
        for (line in lines) {
            scenario.onActivity { a ->
                val input = a.findViewById<EditText>(R.id.input)
                input.setText(line)
                input.onEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            Thread.sleep(700)
        }
        Thread.sleep(1500)
        var console = ""
        scenario.onActivity { a ->
            console = a.findViewById<TextView>(R.id.output).text.toString()
            for (l in console.takeLast(800).split("\n")) Log.i(TAG, "text|$l|")
        }
        assertFalse("empty line in the console:\n$console", console.contains("\n\n"))
        for (expected in listOf(
            "Mecrisp-Stellaris RA 3.0.2 for STM32G431KB",
            "3  ok.", ". 7  ok.", "t test t not found.", "5 test 0 1 2 3 4  ok.",
            "\$40013800 @ hex. 00000000  ok.",
            "▲ USART1 read while its clock is off in RCC",
        )) {
            assertTrue("missing \"$expected\" in:\n$console", console.contains(expected))
        }
        screenshot("console.png")

        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) {
                val t = tabs.getTabAt(i)!!
                if (t.text == a.getString(R.string.tab_chip)) t.select()
            }
            section(a, R.string.chip_core)
        }
        Thread.sleep(1500)
        var regs = ""
        scenario.onActivity { a -> regs = a.findViewById<TextView>(R.id.registers).text.toString() }
        for (l in regs.split("\n")) Log.i(TAG, "regs|$l|")
        for (expected in listOf("Program counter", "TOS   r6", "Core registers", "xPSR", "SYSCLK", "16.000 MHz")) {
            assertTrue("missing \"$expected\" in the registers:\n$regs", regs.contains(expected))
        }
        screenshot("registers.png")

        // Registers: GPIOA open (the default), PA2 and PA3 set up for USART2 by Stellaris
        scenario.onActivity { a -> section(a, R.string.chip_registers) }
        Thread.sleep(2500)
        var rows = emptyList<String>()
        fun readRows() = scenario.onActivity { a ->
            rows = a.findViewById<RegisterListView>(R.id.registerList).rows.map { r ->
                when (r) {
                    is RegisterListView.Row.Group -> "group ${r.label}"
                    is RegisterListView.Row.Periph -> "periph ${r.name} ${r.expanded}"
                    is RegisterListView.Row.Reg -> "reg ${r.name} ${r.value} ${r.note}"
                    is RegisterListView.Row.Field -> "field ${r.name} ${r.value} ${r.meaning}"
                    is RegisterListView.Row.Note -> "note ${r.text}"
                }
            }
        }
        readRows()
        for (l in rows.take(40)) Log.i(TAG, "rows|$l|")
        for (expected in listOf("periph GPIOA true", "field MODER2 10 alternate function (AFR picks which)",
                "field AFRL2 0111 AF7 USART2_TX", "periph USART2 false")) {
            assertTrue("missing \"$expected\" in the Registers section:\n${rows.joinToString("\n")}", rows.contains(expected))
        }
        screenshot("chip-registers.png")

        // Open all of MODER's fields, then MODER5 close up, and make PA5 an output
        val ui = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun tapRow(text: String) = scenario.onActivity { a ->
            val list = a.findViewById<RegisterListView>(R.id.registerList)
            list.onTap!!(rows.indexOf(text).also { assertTrue("no row \"$text\"", it >= 0) })
        }
        tapRow(rows.first { it.startsWith("reg MODER ") })
        Thread.sleep(300)
        readRows()
        tapRow(rows.first { it.startsWith("field MODER5 ") })
        Thread.sleep(800)
        assertTrue("the field's sheet", ui.hasObject(By.textStartsWith("GPIOA_MODER · MODER5")))
        ui.findObject(By.textStartsWith("01  output")).click()
        Thread.sleep(300)
        assertTrue("the Forth for it", ui.hasObject(By.text("\$48000000 @ \$C00 bic \$400 or \$48000000 !")))
        screenshot("chip-field.png")
        ui.findObject(By.text(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.registers_write))).click()
        Thread.sleep(800)
        val vm = io.github.leukoplast1980.solderless.vm.VmHost.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val moder = vm.peekWords(intArrayOf(0x48000000))[0]
        assertTrue("MODER5 is 01 after the write: %08X".format(moder), (moder shr 10) and 3 == 1)
        readRows()
        assertTrue("MODER5 shows the change:\n${rows.joinToString("\n")}", rows.contains("field MODER5 01 output"))
        screenshot("chip-registers-written.png")

        // Forth sets ODR5; the Log shows both writes
        fun selectTab(name: Int) = scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) tabs.getTabAt(i)!!.let { if (it.text == a.getString(name)) it.select() }
        }
        selectTab(R.string.tab_repl)
        scenario.onActivity { a ->
            val input = a.findViewById<EditText>(R.id.input)
            input.setText("\$20 \$48000014 bis!")
            input.onEditorAction(EditorInfo.IME_ACTION_SEND)
        }
        Thread.sleep(1500)
        selectTab(R.string.tab_chip)
        scenario.onActivity { a -> section(a, R.string.chip_log) }
        Thread.sleep(800)
        var log = emptyList<String>()
        scenario.onActivity { a ->
            log = a.findViewById<LogListView>(R.id.logList).rows.map { r ->
                when (r) {
                    is LogListView.Row.Write -> "write ${r.where}|${r.register}|${r.lines.joinToString("|") { it.first + " " + it.second }}"
                    is LogListView.Row.Check -> "check ${r.text}"
                    is LogListView.Row.Note -> "note ${r.text}"
                }
            }
        }
        for (l in log.take(30)) Log.i(TAG, "log|$l|")
        assertTrue("the write from the Registers view:\n${log.joinToString("\n")}",
            log.contains("write Registers view|GPIOA_MODER|MODER5 11→01 output"))
        assertTrue("Forth's write:\n${log.joinToString("\n")}",
            log.any { it.startsWith("write bis! +") && it.endsWith("|GPIOA_ODR|ODR5 0→1 high") })
        screenshot("chip-log.png")
        // A tap on a write shows its register
        scenario.onActivity { a ->
            a.findViewById<LogListView>(R.id.logList).onTap!!(log.indexOfFirst { it.endsWith("|GPIOA_ODR|ODR5 0→1 high") })
        }
        Thread.sleep(800)
        readRows()
        assertTrue("GPIOA_ODR in Registers:\n${rows.joinToString("\n")}", rows.contains("field ODR5 1 high"))
        screenshot("chip-log-register.png")
        scenario.onActivity { a -> a.findViewById<EditText>(R.id.registerFind).setText("") }

        // Pins: PA2 and PA3 for the console, PA5 an output (high), and a pot on a pin set up as an input
        val boardBefore = vm.board
        scenario.onActivity { vm.setBoard(Board(listOf(Part(1, PartType.POT, listOf(PinUse("PA1", PinFunction.Adc("ADC1", 2))))))) }
        val moderNow = vm.peekWords(intArrayOf(0x48000000))[0]
        vm.pokeWord(0x48000000, moderNow and (3 shl 2).inv())
        scenario.onActivity { a -> section(a, R.string.chip_pins) }
        Thread.sleep(800)
        var pins = emptyList<String>()
        scenario.onActivity { a ->
            pins = a.findViewById<PinListView>(R.id.pinList).rows.filterIsInstance<PinListView.Row.Pin>().map {
                val s = it.setup
                "${s.name}|${s.summary}|${s.level}|${s.wired.joinToString()}|${s.mismatch ?: ""}"
            }
        }
        for (l in pins) Log.i(TAG, "pins|$l|")
        assertTrue("the pot's pin first, wrongly set up:\n${pins.joinToString("\n")}", pins[0].startsWith("PA1|input|") &&
            pins[0].endsWith("|Potentiometer|Potentiometer needs analog for ADC1 IN2 (MODER1 11); it is an input (00)"))
        assertTrue("the console's TX:\n${pins.joinToString("\n")}", pins.contains("PA2|AF7 USART2_TX|high|USB-serial RX|"))
        assertTrue("PA5 driven high:\n${pins.joinToString("\n")}", pins.any { it.startsWith("PA5|output · push-pull|high|") })
        screenshot("chip-pins.png")
        scenario.onActivity { a ->
            val l = a.findViewById<PinListView>(R.id.pinList)
            l.onTapAt!!(l.rows.indexOfFirst { (it as? PinListView.Row.Pin)?.setup?.name == "PA1" }, 10f, 10f)
        }
        Thread.sleep(800)
        assertTrue("the pin's sheet", ui.hasObject(By.textStartsWith("MODER1")))
        screenshot("chip-pin.png")
        ui.pressBack()
        Thread.sleep(300)
        scenario.onActivity { vm.setBoard(boardBefore) }

        // Leave it as it was
        vm.pokeWord(0x48000000, moder or (3 shl 10))
        vm.pokeWord(0x48000018, 0x20 shl 16)
    }

    companion object {
        const val TAG = "UiProbe"
    }
}
