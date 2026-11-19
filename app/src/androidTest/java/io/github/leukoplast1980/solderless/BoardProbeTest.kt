package io.github.leukoplast1980.solderless

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.vm.BoardState
import io.github.leukoplast1980.solderless.vm.VmHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Places parts through the recommender, lights the LED from Forth, and
 * looks at the Board tab and its sheets (screenshots for the UI probe).
 */
@RunWith(AndroidJUnit4::class)
class BoardProbeTest {

    private fun screenshot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name")
    }

    @Test
    fun boardTab() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.LED, PartType.BUTTON, PartType.POT, PartType.RGB_LED, PartType.PIEZO)) {
            val pr = rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!
            b = b.apply(pr)
        }
        for (p in b.parts) Log.i(TAG, "part ${p.type} on ${p.pins.joinToString { "${it.pin} ${it.function}" }}")
        scenario.onActivity { vm.setBoard(b) }
        assertEquals("PD2", b.parts.first { it.type == PartType.LED }.pins[0].pin)

        // Light the LED on PD2 from Forth
        val lines = listOf("\$48000C00 constant GPIOD", "%1000 \$4002104C bis!", "%11 4 lshift GPIOD bic!  %01 4 lshift GPIOD bis!", "%100 GPIOD \$18 + !")
        for (line in lines) {
            scenario.onActivity { a ->
                val input = a.findViewById<EditText>(R.id.input)
                input.setText(line)
                input.onEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            Thread.sleep(600)
        }
        Thread.sleep(500)

        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) if (tabs.getTabAt(i)!!.text == a.getString(R.string.tab_board)) tabs.getTabAt(i)!!.select()
        }
        Thread.sleep(1500)
        var visible = false
        scenario.onActivity { a -> visible = a.findViewById<View>(R.id.boardPane).visibility == View.VISIBLE }
        assertTrue("Board pane shows", visible)
        screenshot("board.png")

        val st = BoardState()
        vm.readBoard(st)
        Thread.sleep(300)
        vm.readBoard(st)
        val pd2 = vm.chip.gpio("PD2")!!.slot
        Log.i(TAG, "PD2 ${st.mv(pd2)} mV flags ${st.flags(pd2)} LED current ${st.currentUa(0)} uA")
        assertTrue("PD2 high", st.high(pd2))
        assertTrue("LED current about 4.5 mA, got ${st.currentUa(0)}", st.currentUa(0) in 4000..5000)

        scenario.onActivity { a -> a.findViewById<View>(R.id.boardAdd).performClick() }
        Thread.sleep(1200)
        screenshot("board-add.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
        scenario.onActivity { a -> a.findViewById<View>(R.id.boardSupport).performClick() }
        Thread.sleep(1200)
        screenshot("board-support.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
        scenario.onActivity { vm.setBoard(Board()) }
    }

    companion object {
        const val TAG = "UiProbe"
    }
}
