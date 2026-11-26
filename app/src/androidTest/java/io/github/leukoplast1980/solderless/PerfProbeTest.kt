package io.github.leukoplast1980.solderless

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Printer
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.vm.VmHost
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures, tab by tab, how fast the emulated chip runs and what the UI
 * thread spends its time on, while Forth runs loops at 170 MHz: a busy
 * loop, one toggling a pin, one printing. Each phase settles for 2 s, then
 * records for 8 s:
 * - speed: emulated time against wall time, once a second
 * - main: how much of the time the UI thread was busy, and on what (each
 *   message the main Looper dispatched, by its target)
 * - frames: how many, their durations, layout and draw time
 *
 * Not a pass/fail test, and not part of the UI probe: the Perf probe
 * workflow runs it and keeps perf.txt. It uses only APIs that have been
 * there since 0.4.1, so the file can be copied onto an older version to
 * compare.
 */
@RunWith(AndroidJUnit4::class)
class PerfProbeTest {

    /** What the main thread did: time per message target, frames and their layout cost */
    private class Recorder {
        val byTarget = HashMap<String, LongArray>()     // ns, count, longest ns
        var busyNs = 0L
        var t0 = 0L
        var target = ""
        val frameTotal = ArrayList<Long>()
        var layoutNs = 0L
        var drawNs = 0L
        var startNs = 0L
        var stopNs = 0L
        @Volatile var on = false

        val printer = Printer { line ->
            if (!on) return@Printer
            if (line.startsWith(">>>>>")) {
                t0 = System.nanoTime()
                target = parse(line)
            } else if (line.startsWith("<<<<<") && t0 != 0L) {
                val d = System.nanoTime() - t0
                busyNs += d
                byTarget.getOrPut(target) { LongArray(3) }.let { it[0] += d; it[1]++; if (d > it[2]) it[2] = d }
                t0 = 0
            }
        }

        val frames = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (!on) return@OnFrameMetricsAvailableListener
            synchronized(frameTotal) {
                frameTotal += m.getMetric(FrameMetrics.TOTAL_DURATION)
                layoutNs += m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                drawNs += m.getMetric(FrameMetrics.DRAW_DURATION)
            }
        }

        fun parse(line: String): String {
            // ">>>>> Dispatching to Handler (android.view.Choreographer$FrameHandler) {1234} io.x.MainActivity$poll$1@abcd: 0"
            val afterBrace = line.substringAfter("} ", "")
            val cb = afterBrace.substringBefore("@").substringBefore(": ").trim()
            return if (cb.isEmpty() || cb == "null") line.substringAfter("(").substringBefore(")") else cb.substringAfterLast('.')
        }

        fun start() { busyNs = 0; byTarget.clear(); synchronized(frameTotal) { frameTotal.clear(); layoutNs = 0; drawNs = 0 }; startNs = System.nanoTime(); on = true }
        fun stop() { on = false; stopNs = System.nanoTime() }

        fun report(): String {
            val wall = (stopNs - startNs).coerceAtLeast(1)
            val f = synchronized(frameTotal) { frameTotal.sorted() }
            fun pct(p: Int) = if (f.isEmpty()) 0.0 else f[(f.size - 1) * p / 100] / 1e6
            val jank = f.count { it > 16_700_000L }
            val top = byTarget.entries.sortedByDescending { it.value[0] }.take(8)
                .joinToString(", ") { "${it.key} ${"%.0f".format(it.value[0] / 1e6)}ms/${it.value[1]} max ${"%.0f".format(it.value[2] / 1e6)}ms" }
            return "main=${"%.1f".format(busyNs * 100.0 / wall)}% frames=${f.size} jank=$jank " +
                "p50=${"%.1f".format(pct(50))} p90=${"%.1f".format(pct(90))} p99=${"%.1f".format(pct(99))}ms " +
                "layout=${"%.0f".format(layoutNs / 1e6)}ms draw=${"%.0f".format(drawNs / 1e6)}ms | $top"
        }
    }

    private val out = StringBuilder()

    private fun note(s: String) {
        Log.i(TAG, s)
        out.append(s).append('\n')
    }

    private fun ok(vm: VmHost, line: String) {
        val r = vm.sendLineChecked(line, 10000)
        if (r.toString() != "OK") note("line failed ($r): $line")
    }

    @Test
    fun perf() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(2000)
        val vm = VmHost.get(ctx)
        vm.powerCycle()
        Thread.sleep(3000)

        // A board with displays and an LED, so the Board tab has something to draw
        val rec = vm.recommender()
        var b = Board()
        for (t in listOf(PartType.OLED, PartType.TFT, PartType.MATRIX, PartType.LED, PartType.POT))
            b = b.apply(rec.propose(b.parts, Part(b.nextId(), t, emptyList()))!!)
        scenario.onActivity { vm.setBoard(b) }
        Thread.sleep(1000)

        for (l in listOf(
            ": pll-on 4 \$40022000 bis! \$10000000 \$40021058 bis! \$100 \$40007080 bic! \$01005532 \$4002100C ! \$1000000 \$40021000 bis! begin \$2000000 \$40021000 bit@ until ;",
            ": sys170 begin \$40 \$4000441C bit@ until 1 \$40004400 bic! \$80 \$40021008 bis! 3 \$40021008 bis! begin \$40021008 @ \$C and \$C = until \$80 \$40021008 bic! 1476 \$4000440C ! 1 \$40004400 bis! ;",
            ": busy begin 0 100000 0 do i + loop drop key? until key drop ;",
            ": tog begin 50000 0 do \$20 \$48000018 ! \$200000 \$48000018 ! loop key? until key drop ;",
            ": spam begin 20 0 do i . loop cr key? until key drop ;",
            "\$C00 \$48000000 bic! \$400 \$48000000 bis!",
            "pll-on sys170",
        )) ok(vm, l)
        note("clock: ${vm.snapshot().let { "speed ${it.speedPercent}%" }}")

        val rec2 = Recorder()
        val ht = HandlerThread("frames").apply { start() }
        scenario.onActivity { a ->
            Looper.getMainLooper().setMessageLogging(rec2.printer)
            a.window.addOnFrameMetricsAvailableListener(rec2.frames, Handler(ht.looper))
        }

        fun tab(name: String) = scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) {
                val t = tabs.getTabAt(i)!!
                val txt = t.text.toString()
                if (txt == name || (name == "Chip" && txt == "Registers")) t.select()
            }
        }

        fun measure(name: String) {
            Thread.sleep(2000)
            Log.i(TAG, "START $name")
            rec2.start()
            val speeds = ArrayList<Int>()
            repeat(8) { Thread.sleep(1000); speeds += vm.snapshot().speedPercent }
            rec2.stop()
            note("PERF $name speed=${speeds.average().toInt()}% (${speeds.joinToString(",")}) ${rec2.report()}")
        }

        fun stopLoop() { vm.send(" "); Thread.sleep(1500) }

        tab("Console"); measure("idle-console")

        vm.send("busy\n")
        tab("Console"); measure("busy-console")
        tab("Editor"); measure("busy-editor")
        tab("Board"); measure("busy-board")
        tab("Scope"); measure("busy-scope")
        tab("Chip"); measure("busy-chip")
        // The Chip tab's sections, where there are any (not in 0.4.1)
        var sections = 0
        scenario.onActivity { a ->
            val id = a.resources.getIdentifier("chipSections", "id", a.packageName)
            if (id != 0) sections = a.findViewById<ChipGroup>(id).childCount
        }
        for (k in 0 until sections) {
            var label = ""
            scenario.onActivity { a ->
                val id = a.resources.getIdentifier("chipSections", "id", a.packageName)
                val c = a.findViewById<ChipGroup>(id).getChildAt(k) as Chip
                label = c.text.toString()
                c.performClick()
            }
            measure("busy-chip-$label")
        }
        stopLoop()

        vm.send("tog\n")
        tab("Console"); measure("gpio-console")
        tab("Board"); measure("gpio-board")
        stopLoop()

        vm.send("spam\n")
        tab("Console"); measure("print-console")
        stopLoop()

        scenario.onActivity { Looper.getMainLooper().setMessageLogging(null) }
        scenario.onActivity { vm.setBoard(Board()) }
        vm.powerCycle()
        File(ctx.getExternalFilesDir(null), "perf.txt").writeText(out.toString())
        ht.quitSafely()
        scenario.close()
    }

    /**
     * How quickly the Chip tab follows a pot: Forth copies the pot's ADC
     * reading into TIM2_CCR1 in a loop; the pot is turned the way its
     * slider does it (vm.boardInput on the UI thread) and the probe times
     * how long the UI thread waits for that, and how long until the
     * Registers section, then the Log, shows the new value.
     */
    @Test
    fun potLatency() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(2000)
        val vm = VmHost.get(ctx)
        vm.powerCycle()
        Thread.sleep(3000)
        val rec = vm.recommender()
        var b = Board()
        b = b.apply(rec.propose(b.parts, Part(b.nextId(), PartType.POT, emptyList()))!!)
        val pot = b.parts.first { it.type == PartType.POT }
        scenario.onActivity { vm.setBoard(b) }
        Thread.sleep(500)
        val sample = io.github.leukoplast1980.solderless.board.Samples.forPart(b, vm.chip, pot)
        for (line in sample.lines().filter { it.isNotBlank() }) ok(vm, line)
        ok(vm, ": pot-pwm pot-init 1 \$40021058 bis! begin pot@ \$40000034 ! key? until key drop ;")
        vm.send("pot-pwm\n")
        Thread.sleep(500)

        fun tabChip() = scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.tabs)
            for (i in 0 until tabs.tabCount) if (tabs.getTabAt(i)!!.text.toString() == "Chip") tabs.getTabAt(i)!!.select()
        }
        fun section(label: String) = scenario.onActivity { a ->
            val g = a.findViewById<ChipGroup>(R.id.chipSections)
            for (k in 0 until g.childCount) (g.getChildAt(k) as Chip).let { if (it.text.toString() == label) it.performClick() }
        }
        tabChip()
        section("Registers")
        scenario.onActivity { a -> a.findViewById<android.widget.EditText>(R.id.registerFind).setText("TIM2_CCR1") }
        Thread.sleep(1500)

        // What each view shows for TIM2_CCR1 now
        fun regValue(): String {
            var v = ""
            scenario.onActivity { a ->
                val l = a.findViewById<io.github.leukoplast1980.solderless.ui.RegisterListView>(R.id.registerList)
                v = l.rows.filterIsInstance<io.github.leukoplast1980.solderless.ui.RegisterListView.Row.Reg>()
                    .firstOrNull { it.name == "CCR1" }?.value ?: "?"
            }
            return v
        }
        fun logValue(): String {
            var v = ""
            scenario.onActivity { a ->
                val l = a.findViewById<io.github.leukoplast1980.solderless.ui.LogListView>(R.id.logList)
                v = l.rows.filterIsInstance<io.github.leukoplast1980.solderless.ui.LogListView.Row.Write>()
                    .firstOrNull { it.register == "TIM2_CCR1" }?.lines?.joinToString { it.first } ?: "?"
            }
            return v
        }

        fun measure(name: String, read: () -> String) {
            val lat = ArrayList<Double>()
            val block = ArrayList<Double>()
            var missed = 0
            for (step in 0 until 16) {
                val v = if (step % 2 == 0) 250 + step * 10 else 750 - step * 10
                val before = read()
                val t0 = System.nanoTime()
                scenario.onActivity {
                    val t = System.nanoTime()
                    vm.boardInput(pot.id, v)
                    block += (System.nanoTime() - t) / 1e6
                }
                var seen = false
                while (System.nanoTime() - t0 < 3_000_000_000L) {
                    if (read() != before) { seen = true; break }
                    Thread.sleep(3)
                }
                if (seen) lat += (System.nanoTime() - t0) / 1e6 else missed++
                Thread.sleep(400)
            }
            fun stats(l: List<Double>) = if (l.isEmpty()) "-" else l.sorted().let {
                "median ${"%.0f".format(it[it.size / 2])} max ${"%.0f".format(it.last())} ms" }
            note("POT $name: shown after ${stats(lat)}${if (missed > 0) " ($missed not seen in 3 s)" else ""}; " +
                "UI thread waited ${stats(block)} per pot change")
        }

        measure("registers", ::regValue)

        // Dragging: 60 changes a second on the UI thread, as the slider sends them
        val rec2 = Recorder()
        val ht = HandlerThread("frames").apply { start() }
        scenario.onActivity { a ->
            Looper.getMainLooper().setMessageLogging(rec2.printer)
            a.window.addOnFrameMetricsAvailableListener(rec2.frames, Handler(ht.looper))
        }
        rec2.start()
        val main = Handler(Looper.getMainLooper())
        var k = 0
        val drag = object : Runnable {
            override fun run() {
                vm.boardInput(pot.id, 500 + (400 * Math.sin(k++ / 20.0)).toInt())
                if (k < 300) main.postDelayed(this, 16)
            }
        }
        main.post(drag)
        Thread.sleep(5500)
        rec2.stop()
        note("POT drag-registers ${rec2.report()}")

        section("Log")
        Thread.sleep(1500)
        measure("log", ::logValue)
        k = 0
        rec2.start()
        main.post(drag)
        Thread.sleep(5500)
        rec2.stop()
        note("POT drag-log ${rec2.report()}")

        scenario.onActivity { Looper.getMainLooper().setMessageLogging(null) }
        vm.send(" ")
        Thread.sleep(500)
        scenario.onActivity { a -> a.findViewById<android.widget.EditText>(R.id.registerFind).setText("") }
        scenario.onActivity { vm.setBoard(Board()) }
        vm.powerCycle()
        File(ctx.getExternalFilesDir(null), "perf-pot.txt").writeText(out.toString())
        ht.quitSafely()
        scenario.close()
    }

    companion object {
        private const val TAG = "PerfProbe"
    }
}
