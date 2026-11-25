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
import io.github.leukoplast1980.solderless.ui.ScopeView
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

    private var open: ActivityScenario<MainActivity>? = null

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { open = it }

    /** A test that failed half-way leaves no activity or board behind for the next */
    @org.junit.After
    fun closeUp() {
        val s = open ?: return
        open = null
        val vm = VmHost.get(InstrumentationRegistry.getInstrumentation().targetContext)
        if (s.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
            runCatching { s.onActivity { vm.setBoard(Board()) } }
            s.close()
        }
    }

    private fun screenshot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name")
    }

    private fun selectTab(scenario: ActivityScenario<MainActivity>, name: Int) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) if (tabs.getTabAt(i)!!.text == a.getString(name)) tabs.getTabAt(i)!!.select()
        }
        Thread.sleep(1200)
    }

    /** An OLED on I2C: its sample code draws, the board shows it, the close-up and the card too */
    @Test
    fun oled() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.OLED, PartType.LED, PartType.BUTTON)) b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        val oled = b.parts.first { it.type == PartType.OLED }
        Log.i(TAG, "oled on ${oled.pins} at ${oled.i2cAddr}")
        scenario.onActivity { vm.setBoard(b) }
        val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, oled)
        for (line in sample.lines().filter { it.isNotBlank() })
            assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked(line, 5000))
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK,
            vm.sendLineChecked("oled-init oled-frame oled-ball 30 20 oled-pixel oled-show", 15000))
        val px = ByteArray(128 * 64)
        val text = vm.readDevice(oled.id, px)
        Log.i(TAG, "oled state: $text")
        assertTrue("lit: $text", text != null && "Display\ton" in text)
        assertEquals("top-left corner of the frame", 1, px[0].toInt())
        assertEquals("the ball", 1, px[10 * 128 + 10].toInt())
        assertEquals("inside, dark", 0, px[30 * 128 + 60].toInt())
        selectTab(scenario, R.string.tab_board)
        Thread.sleep(800)
        screenshot("oled-board.png")

        var at: android.graphics.RectF? = null
        val loc = IntArray(2)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            bv.partBounds(oled.id)?.let { r -> bv.requestRectangleOnScreen(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt() + (170 * bv.resources.displayMetrics.density).toInt()), true) }   // scrolled clear of the buttons over the bottom
        }
        Thread.sleep(500)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            at = bv.partBounds(oled.id); bv.getLocationOnScreen(loc)
        }
        assertTrue("OLED drawn", at != null)
        ui().click(loc[0] + at!!.centerX().toInt(), loc[1] + at!!.centerY().toInt())
        Thread.sleep(1500)
        screenshot("oled-closeup.png")
        assertTrue("close-up open", ui().hasObject(androidx.test.uiautomator.By.text("OLED display")))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(600)

        scenario.onActivity { vm.setBoard(Board(vm.board.parts.map { if (it.id == oled.id) it.copy(pinnedOverTabs = true) else it })) }
        selectTab(scenario, R.string.tab_repl)
        Thread.sleep(800)
        screenshot("oled-pinned.png")
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    private fun ui() = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** A rotary encoder: a swipe on its knob turns it, the timer counts, a tap pushes it */
    @Test
    fun encoder() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        b = b.apply(rec.propose(b.parts, Part(b.nextId(), PartType.ENCODER, emptyList()))!!)
        val enc = b.parts[0]
        Log.i(TAG, "encoder on ${enc.pins}")
        scenario.onActivity { vm.setBoard(b) }
        val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, enc)
        for (line in sample.lines().filter { it.isNotBlank() })
            assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked(line, 5000))
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked("enc-init", 5000))
        selectTab(scenario, R.string.tab_board)
        Thread.sleep(800)
        var at: android.graphics.RectF? = null
        val loc = IntArray(2)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            bv.partBounds(enc.id)?.let { r -> bv.requestRectangleOnScreen(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt() + (170 * bv.resources.displayMetrics.density).toInt()), true) }   // scrolled clear of the buttons over the bottom
        }
        Thread.sleep(500)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            at = bv.knobBounds(enc.id); bv.getLocationOnScreen(loc)
        }
        assertTrue("encoder drawn", at != null)
        // swipe right across the knob: 80 dp, five detents
        val d = ctx.resources.displayMetrics.density
        val kx = loc[0] + at!!.centerX().toInt()
        val ky = loc[1] + at!!.centerY().toInt()
        Log.i(TAG, "knob at $at, view at ${loc.toList()}, swipe from $kx,$ky")
        ui().swipe(kx.toInt(), ky, (kx + 80 * d).toInt(), ky, 30)
        Thread.sleep(1500)
        val counter = io.github.leukoplast1980.solderless.board.ChipPins.BASE[(enc.pins[0].function as io.github.leukoplast1980.solderless.board.Function.Timer).timer]!!.toInt() + 0x24
        val steps = runCatching { vm.peekWords(intArrayOf(counter))[0] }.getOrDefault(0)
        Log.i(TAG, "encoder counted $steps")
        screenshot("encoder-board.png")
        vm.boardTurn(enc.id, 2)                     // straight to the emulator: two detents more
        Thread.sleep(800)
        val more = runCatching { vm.peekWords(intArrayOf(counter))[0] }.getOrDefault(0)
        Log.i(TAG, "encoder counted $more after two more")
        assertTrue("a swipe right turned it clockwise: $steps (then $more)", steps in 8..40)
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    /** The board without headers: parts in two columns, each lead labelled with its pin; a tap
     * on a label opens the pin, the Pins button lists all 64 */
    @Test
    fun pinLabels() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.BUTTON, PartType.RGB_LED, PartType.POT, PartType.OLED, PartType.BARO))
            b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        Log.i(TAG, "labels: " + b.parts.joinToString { "${it.type} ${it.pins.map { u -> u.pin }}" })
        scenario.onActivity { vm.setBoard(b) }
        selectTab(scenario, R.string.tab_board)
        Thread.sleep(1000)
        screenshot("board-labels.png")
        val pot = b.parts.first { it.type == PartType.POT }.pins.first().pin
        var at: android.graphics.RectF? = null
        val loc = IntArray(2)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            for (p in b.parts) for (u in p.pins) assertTrue("${p.type} ${u.pin} labelled", bv.labelBounds(u.pin) != null)
            assertTrue("USB-serial labelled", bv.labelBounds("PA2") != null && bv.labelBounds("PA3") != null)
            at = bv.labelBounds(pot); bv.getLocationOnScreen(loc)
        }
        ui().click(loc[0] + at!!.centerX().toInt(), loc[1] + at!!.centerY().toInt())
        Thread.sleep(1500)
        assertTrue("the pin's sheet opens", ui().findObject(androidx.test.uiautomator.UiSelector().text(pot)).exists())
        screenshot("board-label-pin.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
        scenario.onActivity { a -> a.findViewById<View>(R.id.boardPins).performClick() }
        Thread.sleep(1500)
        assertTrue("the pin list", ui().findObject(androidx.test.uiautomator.UiSelector().textContains("VBAT")).exists())
        screenshot("board-pins.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500)
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    /** A BMP280 and an SCD41 on one I2C bus with an OLED: their samples read what the sheets set */
    @Test
    fun sensors() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.OLED, PartType.BARO, PartType.CO2)) b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        val baro = b.parts.first { it.type == PartType.BARO }
        val co2 = b.parts.first { it.type == PartType.CO2 }
        b = b.with(baro.copy(pressureHpa = 987.6, tempC = 18.5))
        Log.i(TAG, "sensors on ${baro.pins} at ${baro.i2cAddr}, ${co2.i2cAddr}")
        scenario.onActivity { vm.setBoard(b) }
        for (p in listOf(baro, co2)) {
            val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, p)
            for (line in sample.lines().filter { it.isNotBlank() })
                assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked(line, 5000))
        }
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK,
            vm.sendLineChecked("baro-init baro. cr co2-init 5100 ms co2-read . co2. cr", 30000))
        val text = vm.readDevice(baro.id, ByteArray(16)) ?: ""
        Log.i(TAG, "baro: $text")
        assertTrue("calibration read: $text", "24 bytes read" in text)
        selectTab(scenario, R.string.tab_board)
        Thread.sleep(1000)
        screenshot("sensors-board.png")
        for ((p, name) in listOf(baro to "baro-sheet.png", co2 to "co2-sheet.png")) {
            var at: android.graphics.RectF? = null
            val loc = IntArray(2)
            scenario.onActivity { a ->
                val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
                bv.partBounds(p.id)?.let { r -> bv.requestRectangleOnScreen(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt() + (170 * bv.resources.displayMetrics.density).toInt()), true) }   // scrolled clear of the buttons over the bottom
            }
            Thread.sleep(500)
            scenario.onActivity { a ->
                val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
                at = bv.partBounds(p.id); bv.getLocationOnScreen(loc)
            }
            assertTrue("${p.type} drawn", at != null)
            ui().click(loc[0] + at!!.centerX().toInt(), loc[1] + at!!.centerY().toInt())
            Thread.sleep(1500)
            screenshot(name)
            assertTrue("sheet open", ui().hasObject(androidx.test.uiautomator.By.text(p.type.title)))
            if (p.type == PartType.CO2) {
                ui().findObject(androidx.test.uiautomator.By.text(ctx.getString(R.string.sensor_breathe))).click()
                Thread.sleep(300)
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(800)
        }
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked("5100 ms co2-read . co2. cr", 30000))
        val co2Text = vm.readDevice(co2.id, ByteArray(16)) ?: ""
        Log.i(TAG, "co2: $co2Text")
        val ppm = Regex("Reading\t(\\d+) ppm").find(co2Text)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue("a breath raises CO2: $ppm", ppm > 1500)
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    /** An LED matrix and a colour display on SPI: their samples draw, the board and the close-ups show it */
    @Test
    fun displays() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.MATRIX, PartType.TFT)) b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        val matrix = b.parts.first { it.type == PartType.MATRIX }
        val tft = b.parts.first { it.type == PartType.TFT }
        Log.i(TAG, "matrix on ${matrix.pins}, display on ${tft.pins}")
        scenario.onActivity { vm.setBoard(b) }
        for (p in listOf(matrix, tft)) {
            val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, p)
            for (line in sample.lines().filter { it.isNotBlank() })
                assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked(line, 5000))
        }
        val m = b.nameOf(matrix); val t = b.nameOf(tft)
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK,
            vm.sendLineChecked("$m-init 3 3 $m-pixel 10 4 $m-pixel 30 6 $m-pixel $m-show  $t-init $t-bars", 30000))
        val px = ByteArray(240 * 320 * 2)
        val text = vm.readDevice(tft.id, px)
        Log.i(TAG, "display state: $text")
        assertTrue("on: $text", text != null && "Display\ton" in text)
        assertEquals("top-left: white", 0xFF.toByte(), px[1])
        selectTab(scenario, R.string.tab_board)
        Thread.sleep(800)
        screenshot("displays-board.png")
        for ((p, name) in listOf(matrix to "matrix-closeup.png", tft to "tft-closeup.png")) {
            var at: android.graphics.RectF? = null
            val loc = IntArray(2)
            scenario.onActivity { a ->
                val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
                bv.partBounds(p.id)?.let { r -> bv.requestRectangleOnScreen(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt() + (170 * bv.resources.displayMetrics.density).toInt()), true) }   // scrolled clear of the buttons over the bottom
            }
            Thread.sleep(500)
            scenario.onActivity { a ->
                val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
                at = bv.partBounds(p.id); bv.getLocationOnScreen(loc)
            }
            assertTrue("${p.type} drawn", at != null)
            ui().click(loc[0] + at!!.centerX().toInt(), loc[1] + at!!.centerY().toInt())
            Thread.sleep(1500)
            screenshot(name)
            assertTrue("close-up open", ui().hasObject(androidx.test.uiautomator.By.text(p.type.title)))
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(800)
        }
        scenario.onActivity { vm.setBoard(Board(vm.board.parts.map { it.copy(pinnedOverTabs = true) })) }
        selectTab(scenario, R.string.tab_repl)
        Thread.sleep(1000)
        screenshot("displays-pinned.png")
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    /** The scope decodes the buses: an OLED command on I2C, a register in every MAX7219 on SPI */
    @Test
    fun scopeBuses() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.OLED, PartType.MATRIX)) b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        val oled = b.parts.first { it.type == PartType.OLED }
        val matrix = b.parts.first { it.type == PartType.MATRIX }
        Log.i(TAG, "oled on ${oled.pins}, matrix on ${matrix.pins}")
        scenario.onActivity { vm.setBoard(b) }
        selectTab(scenario, R.string.tab_scope)
        for (p in listOf(oled, matrix)) {
            val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, p)
            for (line in sample.lines().filter { it.isNotBlank() })
                assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked(line, 5000))
        }
        val o = b.nameOf(oled); val m = b.nameOf(matrix)
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK,
            vm.sendLineChecked("$o-init $m-init 100 ms \$AF $o-cmd 1 ms \$0C 1 $m-all", 15000))
        Thread.sleep(1500)
        // Triggered on the edges that start each transfer
        for ((pin, edge, w, name) in listOf(
            listOf(oled.pins[1].pin, "↓", 260_000L, "scope-i2c.png"),
            listOf(matrix.pins[0].pin, "↓", 180_000L, "scope-spi.png"))) {
            scenario.onActivity { a -> a.findViewById<View>(R.id.scopeTrigger).performClick() }
            Thread.sleep(800)
            val item = ui().wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textStartsWith("$pin $edge")), 5000)
            if (item == null) screenshot("scope-trigger-list.png")
            assertTrue("trigger on $pin $edge offered", item != null)
            item.click()
            Thread.sleep(500)
            scenario.onActivity { a -> a.findViewById<ScopeView>(R.id.scopeView).onUserZoom(w as Long) }
            Thread.sleep(1200)
            if (name == "scope-spi.png") scenario.onActivity { a ->
                val sv = a.findViewById<ScopeView>(R.id.scopeView)
                (sv.parent as android.widget.ScrollView).smoothScrollTo(0, sv.height)
            }
            Thread.sleep(800)
            screenshot(name as String)
        }
        var rows = 0
        scenario.onActivity { a -> rows = a.findViewById<ScopeView>(R.id.scopeView).channels.count { it is ScopeView.Decode } }
        assertEquals("a decoded row per bus", 2, rows)
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    /** A W25Q on SPI: its sample writes and reads back, the sheet shows it, the hex view edits a byte */
    @Test
    fun flash() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.FLASH, PartType.LED)) b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        val flash = b.parts.first { it.type == PartType.FLASH }
        Log.i(TAG, "flash on ${flash.pins}")
        scenario.onActivity { vm.setBoard(b) }
        val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, flash)
        for (line in sample.lines().filter { it.isNotBlank() })
            assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked(line, 5000))
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked("flash-demo", 15000))
        val got = String(vm.readMemory(flash.id, 0, 16))
        assertEquals("Hello from Forth", got)
        selectTab(scenario, R.string.tab_board)
        Thread.sleep(800)
        screenshot("flash-board.png")

        var at: android.graphics.RectF? = null
        val loc = IntArray(2)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            bv.partBounds(flash.id)?.let { r -> bv.requestRectangleOnScreen(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt() + (170 * bv.resources.displayMetrics.density).toInt()), true) }   // scrolled clear of the buttons over the bottom
        }
        Thread.sleep(500)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            at = bv.partBounds(flash.id); bv.getLocationOnScreen(loc)
        }
        assertTrue("flash drawn", at != null)
        ui().click(loc[0] + at!!.centerX().toInt(), loc[1] + at!!.centerY().toInt())
        Thread.sleep(1500)
        screenshot("flash-sheet.png")
        val open = ui().findObject(androidx.test.uiautomator.By.text(ctx.getString(R.string.flash_open_hex)))
        assertTrue("the sheet is open", open != null)
        open.click()
        Thread.sleep(1500)
        // byte 000000: 48 'H' becomes 4A 'J'
        ui().findObject(androidx.test.uiautomator.By.desc(ctx.getString(R.string.hex_byte, "000000"))).click()
        ui().findObject(androidx.test.uiautomator.By.text("4")).click()
        ui().findObject(androidx.test.uiautomator.By.text("A")).click()
        Thread.sleep(600)
        screenshot("flash-hex.png")
        ui().findObject(androidx.test.uiautomator.By.text(ctx.getString(R.string.hex_apply, 1))).click()
        Thread.sleep(800)
        assertEquals("Jello from Forth", String(vm.readMemory(flash.id, 0, 16)))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(600)
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    /** Parts pinned over the other tabs: the card on Console, Scope and Registers, and the pot's slider */
    @Test
    fun floatingControls() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
        Thread.sleep(2500)
        val vm = VmHost.get(ctx)
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.LED, PartType.BUTTON, PartType.POT)) {
            val pr = rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!
            b = b.apply(pr)
        }
        b = Board(b.parts.map { it.copy(pinnedOverTabs = true) })
        val pot = b.parts.first { it.type == PartType.POT }
        scenario.onActivity { vm.setBoard(b) }
        selectTab(scenario, R.string.tab_repl)
        screenshot("pinned-console.png")

        val ui = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.findObject(androidx.test.uiautomator.UiSelector().descriptionStartsWith("pot"))!!.click()
        Thread.sleep(800)
        var strip = false
        scenario.onActivity { a -> strip = a.findViewById<View>(R.id.potStrip).visibility == View.VISIBLE }
        assertTrue("the pot's slider opens", strip)
        val plus = ui.findObject(androidx.test.uiautomator.UiSelector().description(ctx.getString(R.string.pot_up)))!!
        repeat(5) { plus.click(); Thread.sleep(150) }
        var v = -1
        scenario.onActivity { a -> v = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView).potValue[pot.id] ?: -1 }
        assertEquals("five steps of 1 % up from the middle", 550, v)
        Thread.sleep(500)
        screenshot("pinned-pot-open.png")

        // Dragging along the pill turns the pot to where the finger is: about a quarter
        val pill = ui.findObject(androidx.test.uiautomator.UiSelector().descriptionStartsWith("pot"))!!.bounds
        ui.swipe(pill.right - pill.height(), pill.centerY(), pill.left + pill.height() / 2 + (pill.width() - pill.height()) / 4, pill.centerY(), 30)
        Thread.sleep(600)
        scenario.onActivity { a -> v = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView).potValue[pot.id] ?: -1 }
        Log.i(TAG, "pot after the drag: $v")
        assertTrue("dragged to about 25 %, got $v", v in 180..320)
        screenshot("pinned-pot-dragged.png")

        for ((tab, shot) in listOf(R.string.tab_scope to "pinned-scope.png", R.string.tab_chip to "pinned-chip.png")) {
            selectTab(scenario, tab)
            var shown = false
            scenario.onActivity { a -> shown = a.findViewById<View>(R.id.pinned).visibility == View.VISIBLE }
            assertTrue("the card shows over ${ctx.getString(tab)}", shown)
            screenshot(shot)
        }
        selectTab(scenario, R.string.tab_board)
        var gone = false
        scenario.onActivity { a -> gone = a.findViewById<View>(R.id.pinned).visibility != View.VISIBLE && a.findViewById<View>(R.id.potStrip).visibility != View.VISIBLE }
        assertTrue("no card or slider on the Board tab", gone)
        scenario.onActivity { vm.setBoard(Board()) }
        scenario.close()
    }

    @Test
    fun boardTab() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = launch()
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
            val r = vm.sendLineChecked(line, 5000)
            Log.i(TAG, "sent: $line -> $r")
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
        // Pick the LED tile: the proposal sheet with the dashed preview
        val ui = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.findObject(androidx.test.uiautomator.UiSelector().textStartsWith("LED"))?.click()
        Thread.sleep(1500)
        screenshot("board-proposal.png")
        // The other pins open behind their button
        ui.findObject(androidx.test.uiautomator.UiSelector().textStartsWith("Other pins"))?.click()
        Thread.sleep(800)
        screenshot("board-proposal-pins.png")
        ui.findObject(androidx.test.uiautomator.UiSelector().textStartsWith("Other pins"))?.click()
        Thread.sleep(500)
        // Further down the sheet: the sample code
        repeat(2) { ui.swipe(ui.displayWidth / 2, ui.displayHeight * 85 / 100, ui.displayWidth / 2, ui.displayHeight * 25 / 100, 25); Thread.sleep(600) }
        screenshot("board-proposal-code.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
        scenario.onActivity { a -> a.findViewById<View>(R.id.boardSupport).performClick() }
        Thread.sleep(1200)
        screenshot("board-support.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
        // The scope: blink the LED from Forth, press the bouncing button
        vm.sendLineChecked(": blink 6 0 do %100 GPIOD \$14 + xor! 300000 0 do loop loop ;", 5000)
        vm.sendLineChecked("blink", 10000)
        val button = b.parts.first { it.type == PartType.BUTTON }
        vm.boardInput(button.id, 1); Thread.sleep(150); vm.boardInput(button.id, 0)
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) if (tabs.getTabAt(i)!!.text == a.getString(R.string.tab_scope)) tabs.getTabAt(i)!!.select()
        }
        Thread.sleep(1500)
        var shown = false
        scenario.onActivity { a -> shown = a.findViewById<View>(R.id.scopePane).visibility == View.VISIBLE }
        assertTrue("Scope pane shows", shown)
        scenario.onActivity { a -> a.findViewById<ScopeView>(R.id.scopeView).onUserZoom(5_000_000_000L); a.findViewById<View>(R.id.scopeHold).performClick() }
        Thread.sleep(500)
        screenshot("scope.png")
        Thread.sleep(7000)
        screenshot("scope-held-7s-later.png")     // held: nothing may have gone

        // An RGB LED on PA8, PA9, PA10 (TIM1 CH1-3), each lead labelled with its pin
        val rgb = Part(1, PartType.RGB_LED, listOf(
            io.github.leukoplast1980.solderless.board.PinUse("PA8", io.github.leukoplast1980.solderless.board.Function.Timer("TIM1", 1, 6)),
            io.github.leukoplast1980.solderless.board.PinUse("PA9", io.github.leukoplast1980.solderless.board.Function.Timer("TIM1", 2, 6)),
            io.github.leukoplast1980.solderless.board.PinUse("PA10", io.github.leukoplast1980.solderless.board.Function.Timer("TIM1", 3, 6)),
        ))
        scenario.onActivity { vm.setBoard(Board(listOf(rgb))) }
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) if (tabs.getTabAt(i)!!.text == a.getString(R.string.tab_board)) tabs.getTabAt(i)!!.select()
        }
        Thread.sleep(1200)
        screenshot("board-rgb.png")

        // A tap on the RGB LED: its sample code
        var at: android.graphics.RectF? = null
        val loc = IntArray(2)
        scenario.onActivity { a ->
            val bv = a.findViewById<io.github.leukoplast1980.solderless.ui.BoardView>(R.id.boardView)
            at = bv.partBounds(rgb.id); bv.getLocationOnScreen(loc)
        }
        assertTrue("RGB LED drawn", at != null)
        ui.click(loc[0] + at!!.centerX().toInt(), loc[1] + at!!.centerY().toInt())
        Thread.sleep(1500)
        screenshot("board-sample.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)

        // The sample runs as it is: send it line by line, then set a colour
        val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(Board(listOf(rgb)), vm.chip, rgb)
        for (line in sample.lines().filter { it.isNotBlank() }) {
            val r = vm.sendLineChecked(line, 5000)
            if (r != io.github.leukoplast1980.solderless.forth.LineResult.OK) Log.i(TAG, "sample line $r: $line")
            assertEquals(line, io.github.leukoplast1980.solderless.forth.LineResult.OK, r)
        }
        assertEquals(io.github.leukoplast1980.solderless.forth.LineResult.OK, vm.sendLineChecked("rgb-init 100 40 0 rgb-colour", 5000))
        Thread.sleep(400)
        vm.readBoard(st); Thread.sleep(300); vm.readBoard(st)
        val pa8 = vm.chip.gpio("PA8")!!.slot; val pa9 = vm.chip.gpio("PA9")!!.slot; val pa10 = vm.chip.gpio("PA10")!!.slot
        Log.i(TAG, "sample: PA8 high ${st.high(pa8)}, PA9 pwm ${st.pwm(pa9)} duty ${st.pwmDuty(pa9)} time-high ${st.duty(pa9)}, PA10 high ${st.high(pa10)}")
        assertTrue("red full on", st.high(pa8) && !st.pwm(pa8))
        assertTrue("green at 40 %, high ${st.duty(pa9)}/1000", st.pwm(pa9) && st.duty(pa9) in 370..430)
        assertTrue("blue off", !st.high(pa10) && !st.pwm(pa10))
        Thread.sleep(800)
        screenshot("board-sample-running.png")
        scenario.onActivity { vm.setBoard(Board()) }
    }

    companion object {
        const val TAG = "UiProbe"
    }
}
