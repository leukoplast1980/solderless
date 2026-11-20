package io.github.leukoplast1980.solderless

import android.graphics.Bitmap
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Types a few lines into the console of the emulated G431, checks what
 * Mecrisp-Stellaris and the chip model answer, opens the Registers view,
 * and takes screenshots (saved in the app's external files folder; the UI
 * probe workflow pulls them).
 */
@RunWith(AndroidJUnit4::class)
class ReplProbeTest {

    private fun screenshot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name saved in $dir")
    }

    @Test
    fun consoleAndRegisters() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
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
                if (t.text == a.getString(R.string.tab_registers)) t.select()
            }
        }
        Thread.sleep(1500)
        var regs = ""
        scenario.onActivity { a -> regs = a.findViewById<TextView>(R.id.registers).text.toString() }
        for (l in regs.split("\n")) Log.i(TAG, "regs|$l|")
        for (expected in listOf("Program counter", "TOS   r6", "Core registers", "xPSR", "SYSCLK", "16.000 MHz")) {
            assertTrue("missing \"$expected\" in the registers:\n$regs", regs.contains(expected))
        }
        screenshot("registers.png")
    }

    companion object {
        const val TAG = "UiProbe"
    }
}
