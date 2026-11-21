/*
 * Solderless - the Scope tab's drawing: one row per watched pin over a
 * time window. Logic levels, voltages for analog pins, PWM as a square
 * wave (or a band when its periods are too close to draw), fast toggling
 * as a dense mark; trigger line and two cursors. Bus lines get their bits
 * drawn from what the on-chip I2C or SPI sent, a row of decoded bytes
 * after them and a line saying what the part made of the transfer.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ScopeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** One recorded change: time (ns), PWM or level, mV or mHz, duty (0x10000 = 100 %), fast toggling before it */
    class Ev(val t: Long, val pwm: Boolean, val a: Long, val b: Long, val busy: Boolean)

    sealed class Row

    /** A pin. [group]: rows of one part or bus share a background; [bus]: what went over the bus this pin is on. */
    class Channel(val slot: Int, val pin: String, val label: String, val analog: Boolean, val events: List<Ev>,
                  val group: String = "pin:$slot", val bus: List<BusEv> = emptyList()) : Row()

    /** The bytes on a bus, and what the transfer at a time (back to a time) did */
    class Decode(val events: List<BusEv>, val describe: (from: Long, at: Long) -> String) : Row()

    /** Set on every poll; a layout pass only when the rows' heights change */
    var channels: List<Row> = emptyList()
        set(v) {
            val relayout = v.size != field.size || v.indices.any { rowH(v[it]) != rowH(field[it]) }
            field = v
            if (relayout) requestLayout()
            invalidate()
        }
    /** Shown span and its end, in emulated ns */
    var windowNs = 1_000_000_000L
    var endNs = 0L
    var triggerNs = -1L
    var cursorA = -1L
    var cursorB = -1L
    var onUserZoom: (Long) -> Unit = {}
    var onUserPan: (Long) -> Unit = {}
    var onCursors: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val labelW get() = dp(100f)
    private val right get() = width - dp(8f)
    private fun rowH(r: Row) = when (r) { is Channel -> if (r.analog) dp(92f) else dp(54f); is Decode -> dp(58f) }

    private val cHigh = 0xFF8FD18F.toInt(); private val cLow = 0xFF5E6A70.toInt(); private val cPwm = 0xFFC49BE0.toInt()
    private val cAnalog = 0xFF80DEEA.toInt(); private val cWarn = 0xFFFFB454.toInt(); private val cLabel = 0xFF8A9690.toInt()
    private val cText = 0xFFE8EEE4.toInt(); private val cGrid = 0xFF2C353C.toInt(); private val cCursor = 0xFF7FB8FF.toInt()
    private val cBus = 0xFFE6B673.toInt(); private val cNack = 0xFFE57373.toInt()
    private val cBg = 0xFF101418.toInt(); private val cStripe = 0xFF131A1F.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = channels.sumOf { rowH(it).toDouble() + dp(2f) }.toFloat() + dp(30f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), max(h, dp(120f)).toInt())
    }

    private fun x(t: Long): Float = labelW + (t - (endNs - windowNs)).toFloat() / windowNs * (right - labelW)
    private fun t(x: Float): Long = endNs - windowNs + ((x - labelW) / (right - labelW) * windowNs).toLong()

    private val dashed = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        if (channels.isEmpty()) {
            label(c, "Nothing to watch: add parts on the Board, or tap a pin there and show it here.", dp(12f), dp(40f), 12f, cLabel, Paint.Align.LEFT)
            return
        }
        var y = 0f
        var group: String? = null; var stripe = true
        for (row in channels) {
            val h = rowH(row)
            when (row) {
                is Channel -> {
                    if (row.group != group) { group = row.group; stripe = !stripe }
                    val bg = if (stripe) cStripe else cBg
                    if (stripe) { fill.color = bg; c.drawRect(0f, y, width.toFloat(), y + h, fill) }
                    drawChannel(c, row, y, h, bg)
                }
                is Decode -> drawDecode(c, row, y)
            }
            y += h + dp(2f)
        }
        // time axis
        line.color = 0xFF3A4650.toInt(); line.strokeWidth = dp(1f); line.pathEffect = null
        c.drawLine(labelW, y + dp(2f), right, y + dp(2f), line)
        val steps = if (right - labelW < dp(260f)) 2 else 4
        for (k in 0..steps) {
            val xx = labelW + (right - labelW) * k / steps
            c.drawLine(xx, y + dp(2f), xx, y + dp(7f), line)
            val tt = -windowNs + windowNs * k / steps
            label(c, if (k == steps && triggerNs < 0) "now" else span(tt + (if (triggerNs >= 0) endNs - triggerNs else 0)), xx, y + dp(19f), 9f, cLabel,
                if (k == 0) Paint.Align.LEFT else if (k == steps) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
        // trigger and cursors
        if (triggerNs in (endNs - windowNs)..endNs) {
            line.color = cWarn; line.strokeWidth = dp(1f); line.pathEffect = dashed
            c.drawLine(x(triggerNs), 0f, x(triggerNs), y, line)
            line.pathEffect = null
            label(c, "T", x(triggerNs) + dp(3f), y - dp(3f), 9f, cWarn, Paint.Align.LEFT)
        }
        for ((t, n) in listOf(cursorA to "A", cursorB to "B")) {
            if (t < 0 || t !in (endNs - windowNs)..endNs) continue
            val xx = x(t)
            line.color = cCursor; line.strokeWidth = dp(1.2f)
            c.drawLine(xx, 0f, xx, y, line)
            fill.color = cCursor
            c.drawRoundRect(xx - dp(8f), y - dp(16f), xx + dp(8f), y - dp(2f), dp(3f), dp(3f), fill)
            label(c, n, xx, y - dp(5f), 10f, 0xFF101418.toInt())
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) {
        text.textSize = dp(size); text.color = colour; text.textAlign = align; text.isFakeBoldText = bold
        c.drawText(s, x, y, text)
        text.isFakeBoldText = false
    }

    private fun drawChannel(c: Canvas, ch: Channel, top: Float, h: Float, bg: Int) {
        val hi = top + dp(8f); val lo = top + h - dp(8f)
        val ev = ch.events
        val start = endNs - windowNs
        // the event in force at the window's start
        var i = ev.binarySearchBy(start) { it.t }.let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        if (ch.analog) {
            line.color = cGrid; line.strokeWidth = dp(1f)
            c.drawLine(labelW, hi, right, hi, line); c.drawLine(labelW, (hi + lo) / 2, right, (hi + lo) / 2, line); c.drawLine(labelW, lo, right, lo, line)
            label(c, "3.3 V", right - dp(2f), hi + dp(9f), 8f, cLabel, Paint.Align.RIGHT)
        }
        var prevY = Float.NaN
        var measure = ""
        var colour = if (ch.analog) cAnalog else cHigh
        while (i < ev.size && ev[i].t < endNs) {
            val e = ev[i]
            val t0 = max(e.t, start); val t1 = min(if (i + 1 < ev.size) ev[i + 1].t else endNs, endNs)
            val x0 = x(t0); val x1 = max(x(t1), x0 + 0.5f)
            if (e.busy && i > 0) {                              // toggled fast since the previous event
                fill.color = (if (ch.analog) cAnalog else cHigh) and 0x99FFFFFF.toInt()
                val xb = max(x(ev[i - 1].t), labelW)
                c.drawRect(xb, hi - dp(1f), max(x0, xb + dp(2f)), lo + dp(1f), fill)
            }
            if (e.pwm && e.a > 0) {
                colour = cPwm
                val periodNs = 1e12 / e.a
                val px = periodNs / windowNs * (right - labelW)
                val duty = e.b / 65536.0
                if (px >= dp(4f)) {
                    line.color = cPwm; line.strokeWidth = dp(1.8f)
                    val path = Path()
                    var k = ceil((t0 - e.t) / periodNs - 1).toLong()
                    path.moveTo(x0, lo)
                    while (true) {
                        val rise = e.t + (k * periodNs).toLong(); val fall = rise + (duty * periodNs).toLong()
                        if (rise >= t1) break
                        val xr = x(max(rise, t0)); val xf = x(min(fall, t1))
                        if (fall > t0) { path.lineTo(xr, lo); path.lineTo(xr, hi); path.lineTo(xf, hi); if (fall < t1) path.lineTo(xf, lo) }
                        k++
                    }
                    path.lineTo(x1, if (path.isEmpty) lo else lo)
                    c.drawPath(path, line)
                } else {
                    fill.color = cPwm and 0x47FFFFFF
                    c.drawRect(x0, hi, x1, lo, fill)
                    line.color = cPwm; line.strokeWidth = dp(1.2f)
                    c.drawLine(x0, hi, x1, hi, line); c.drawLine(x0, lo, x1, lo, line)
                    if (x1 - x0 > dp(110f)) label(c, "PWM %s · %d %%".format(java.util.Locale.ROOT, hz(e.a), (duty * 100).toInt()), (x0 + x1) / 2, (hi + lo) / 2 + dp(4f), 10f, cText)
                }
                measure = "${hz(e.a)} · ${(duty * 100).toInt()} %"
                prevY = Float.NaN
            } else if (e.a < 0 || e.a > 100000) {               // floating (-1 as unsigned)
                line.color = cWarn; line.strokeWidth = dp(1.2f); line.pathEffect = dashed
                c.drawLine(x0, (hi + lo) / 2, x1, (hi + lo) / 2, line)
                line.pathEffect = null
                measure = "floating"
                prevY = Float.NaN
            } else {
                val mv = e.a.toFloat()
                val yy = if (ch.analog) lo - (lo - hi) * (mv / 3300f).coerceIn(0f, 1f) else if (mv >= 1650f) hi else lo
                line.color = if (ch.analog) cAnalog else if (mv >= 1650f) cHigh else cLow
                line.strokeWidth = dp(1.8f)
                if (!prevY.isNaN() && prevY != yy) c.drawLine(x0, prevY, x0, yy, line.apply { color = if (ch.analog) cAnalog else cHigh })
                line.color = if (ch.analog) cAnalog else if (mv >= 1650f) cHigh else cLow
                c.drawLine(x0, yy, x1, yy, line)
                prevY = yy
                measure = if (ch.analog) "%.2f V".format(java.util.Locale.ROOT, mv / 1000f) else ""
            }
            i++
        }
        if (!ch.analog && measure.isEmpty()) measure = digitalMeasure(ev, start)
        if (ch.bus.isNotEmpty()) {
            drawSynth(c, ch, hi, lo, top, h, bg)
            busMeasure(ch, start)?.let { measure = it; colour = cBus }
        }
        label(c, ch.pin, dp(10f), top + dp(18f), 12f, cText, Paint.Align.LEFT, true)
        label(c, fit(ch.label, 10.5f), dp(10f), top + dp(32f), 10.5f, cLabel, Paint.Align.LEFT)
        label(c, fit(measure, 9.5f), dp(10f), top + dp(46f), 9.5f, colour, Paint.Align.LEFT)
    }

    // ------------------------------------------------------------------
    // Buses

    /** The first event that may still be on screen: events last at most a few ms */
    private fun firstVisible(ev: List<BusEv>, start: Long): Int =
        ev.binarySearchBy(start - 50_000_000L) { it.t }.let { if (it >= 0) it else -it - 1 }

    /** What this pin is on [e]'s bus: SCL, SDA, SCK, MOSI, MISO, CS, or -1 */
    private fun roleOf(e: BusEv, slot: Int): Int = when {
        e.i2c && slot == e.p0 -> SCL
        e.i2c && slot == e.p1 -> SDA
        !e.i2c && slot == e.p0 -> SCK
        !e.i2c && slot == e.p1 -> MOSI
        !e.i2c && slot == e.p2 -> MISO
        !e.i2c && slot == e.cs -> CSP
        else -> -1
    }

    /** Level steps (time, high) of a pin through an event the on-chip peripheral sent */
    private fun steps(e: BusEv, role: Int): List<Pair<Long, Boolean>> {
        val out = ArrayList<Pair<Long, Boolean>>()
        val t = e.t; val d = e.dur
        when (e.kind) {
            BusEv.START -> if (role == SCL) out += t to true else { out += t to true; out += t + d / 2 to false }
            BusEv.STOP -> if (role == SCL) { out += t to false; out += t + d / 3 to true } else { out += t to false; out += t + 2 * d / 3 to true }
            BusEv.ADDR, BusEv.BYTE -> {
                val b = d / 9.0
                for (i in 0 until 9) {
                    val t0 = t + (i * b).toLong()
                    if (role == SCL) { out += t0 to false; out += t0 + (b / 2).toLong() to true }
                    else out += t0 to if (i < 8) (e.value shr (7 - i)) and 1 != 0 else !e.acked
                }
            }
            BusEv.SPI -> {
                val n = e.bits.coerceAtLeast(1); val b = d / n.toDouble()
                val first = ((e.mode shr 1) xor (e.mode and 1)) != 0
                val v = if (role == MISO) e.miso else e.value
                for (i in 0 until n) {
                    val t0 = t + (i * b).toLong()
                    if (role == SCK) { out += t0 to first; out += t0 + (b / 2).toLong() to !first }
                    else out += t0 to ((v shr (if (e.lsb) i else n - 1 - i)) and 1 != 0)
                }
            }
        }
        return out
    }

    /** The bits an on-chip I2C or SPI sent, drawn over the pin's line (which only shows where it rested) */
    private fun drawSynth(c: Canvas, ch: Channel, hi: Float, lo: Float, top: Float, h: Float, bg: Int) {
        val ev = ch.bus
        val start = endNs - windowNs
        var prev: BusEv? = null
        var bx0 = -1f; var bx1 = -1f                         // bytes too fine to draw, run together into a band
        var lastHigh: Boolean? = null; var lastEnd = Long.MIN_VALUE   // where the drawn line was left
        fun band() {
            if (bx0 < 0) return
            fill.color = bg; c.drawRect(bx0, top, bx1, top + h, fill)
            fill.color = cBus and 0x47FFFFFF; c.drawRect(bx0, hi, bx1, lo, fill)
            line.color = cBus; line.strokeWidth = dp(1.2f)
            c.drawLine(bx0, hi, bx1, hi, line); c.drawLine(bx0, lo, bx1, lo, line)
            bx0 = -1f
        }
        for (k in firstVisible(ev, start) until ev.size) {
            val e = ev[k]
            if (e.t > endNs) break
            if (!e.hw || e.kind == BusEv.CS || e.kind == BusEv.LOST) continue
            val role = roleOf(e, ch.slot)
            if (role < 0 || role == CSP || role == MISO && !e.driven) { prev = e; continue }
            // between the bytes of a transfer the on-chip I2C holds SCL low (and lets SDA go)
            val p = prev
            if (e.i2c && p != null && p.i2c && p.kind != BusEv.STOP && e.t > p.end && e.kind != BusEv.START) {
                val g0 = max(p.end, start); val g1 = min(e.t, endNs)
                if (x(g1) - x(g0) >= 1f) {
                    band()
                    fill.color = bg; c.drawRect(x(g0), top, x(g1), top + h, fill)
                    line.color = if (role == SCL) cLow else cBus; line.strokeWidth = dp(1.8f)
                    val yy = if (role == SCL) lo else hi
                    if (lastHigh != null && lastHigh != (role != SCL) && lastEnd >= p.end) {
                        line.color = cBus; line.strokeWidth = dp(1.1f); c.drawLine(x(g0), hi, x(g0), lo, line)
                        line.strokeWidth = dp(1.8f); line.color = if (role == SCL) cLow else cBus
                    }
                    c.drawLine(x(g0), yy, x(g1), yy, line)
                    lastHigh = role != SCL; lastEnd = e.t
                }
            }
            prev = e
            if (e.end < start) continue
            val x0 = x(max(e.t, start)); val x1 = x(min(e.end, endNs))
            val bits = when (e.kind) { BusEv.ADDR, BusEv.BYTE -> 9; BusEv.SPI -> e.bits.coerceAtLeast(1); else -> 1 }
            val bitPx = (x(e.end) - x(e.t)) / bits
            if (bitPx < dp(1.5f)) {                          // too fine to draw: a band
                if (bx0 >= 0 && x0 <= bx1 + dp(1f)) bx1 = max(bx1, max(x1, x0 + 1f))
                else { band(); bx0 = x0; bx1 = max(x1, x0 + 1f) }
                continue
            }
            band()
            fill.color = bg; c.drawRect(x0, top, max(x1, x0 + 1f), top + h, fill)
            val st = steps(e, role)
            for ((i, sp) in st.withIndex()) {
                val a = max(sp.first, start); val b = min(if (i + 1 < st.size) st[i + 1].first else e.end, endNs)
                if (b <= a) continue
                val yy = if (sp.second) hi else lo
                line.color = if (sp.second) cBus else cLow; line.strokeWidth = dp(1.8f)
                c.drawLine(x(a), yy, x(b), yy, line)
                val was = if (i > 0) st[i - 1].second else if (lastEnd >= e.t - e.dur / 8) lastHigh else null
                if (was != null && was != sp.second && sp.first >= start) {
                    line.color = cBus; line.strokeWidth = dp(1.1f)
                    c.drawLine(x(sp.first), hi, x(sp.first), lo, line)
                }
            }
            lastHigh = st.lastOrNull()?.second; lastEnd = e.end
        }
        band()
    }

    /** The third label line of a bus pin: its clock, or how many bytes went over it in the window */
    private fun busMeasure(ch: Channel, start: Long): String? {
        val ev = ch.bus
        var bytes = 0; var driven = 0; var csBits = 0; var role = -1
        var clock: BusEv? = null
        for (k in firstVisible(ev, start) until ev.size) {
            val e = ev[k]
            if (e.t > endNs) break
            if (e.end < start || e.kind == BusEv.CS || e.kind == BusEv.LOST) continue
            val r = roleOf(e, ch.slot)
            if (r < 0) continue
            if (r == CSP) { if (e.kind == BusEv.SPI) csBits += e.bits; role = CSP; continue }
            role = r
            if (e.kind == BusEv.ADDR || e.kind == BusEv.BYTE || e.kind == BusEv.SPI) {
                bytes++
                if (e.driven) driven++
                if (clock == null && e.hw && e.dur > 0) clock = e
            }
        }
        fun count(n: Int) = if (n == 1) "1 byte" else "$n bytes"
        return when (role) {
            -1 -> null
            CSP -> if (csBits > 0) "low $csBits bits" else null
            SCL, SCK -> clock?.let { e ->
                val bits = if (e.i2c) 9 else e.bits.coerceAtLeast(1)
                hz(bits * 1_000_000_000_000L / e.dur) + if (e.i2c) "" else " · mode ${e.mode}"
            }
            MISO -> if (driven > 0) count(driven) else if (bytes > 0) "not driven" else null
            else -> if (bytes > 0) count(bytes) else null
        }
    }

    private fun box(c: Canvas, x0: Float, x1: Float, top: Float, colour: Int) {
        fill.color = colour and 0x38FFFFFF
        c.drawRoundRect(x0, top + dp(4f), x1, top + dp(22f), dp(4f), dp(4f), fill)
        line.color = colour; line.strokeWidth = dp(1f)
        c.drawRoundRect(x0, top + dp(4f), x1, top + dp(22f), dp(4f), dp(4f), line)
    }

    /** Text in a box if it fits, the first choice that does */
    private fun boxText(c: Canvas, x0: Float, x1: Float, top: Float, choices: List<String>, back: String = "") {
        text.textSize = dp(10f)
        val room = x1 - x0 - dp(4f)
        val backW = if (back.isEmpty()) 0f else text.measureText(" $back")
        for (s in choices) {
            val w = text.measureText(s)
            val withBack = back.isNotEmpty() && w + backW <= room
            if (w > room) continue
            val tw = w + if (withBack) backW else 0f
            val left = (x0 + x1) / 2 - tw / 2
            label(c, s, left, top + dp(17f), 10f, cText, Paint.Align.LEFT)
            if (withBack) label(c, " $back", left + w, top + dp(17f), 10f, cAnalog, Paint.Align.LEFT)
            return
        }
    }

    private fun drawDecode(c: Canvas, d: Decode, top: Float) {
        val ev = d.events
        val start = endNs - windowNs
        label(c, "decoded", dp(10f), top + dp(17f), 11f, cLabel, Paint.Align.LEFT)
        c.save()
        c.clipRect(labelW, top, right, top + dp(26f))
        var runX0 = -1f; var runX1 = -1f; var runN = 0
        fun flush() {
            if (runN == 0) return
            box(c, runX0, max(runX1, runX0 + dp(2f)), top, cBus)
            boxText(c, runX0, runX1, top, listOf(if (runN == 1) "1 byte" else "$runN bytes", "$runN"))
            runN = 0
        }
        for (k in firstVisible(ev, start) until ev.size) {
            val e = ev[k]
            if (e.t > endNs) break
            if (e.end < start || e.kind == BusEv.CS) continue
            var x0 = x(e.t); var x1 = x(e.end)
            if (e.kind == BusEv.START || e.kind == BusEv.STOP) {
                // marks only where the bytes next to them are drawn one by one
                val n = ev.getOrNull(if (e.kind == BusEv.START) k + 1 else k - 1)
                if (n == null || n.kind != BusEv.ADDR && n.kind != BusEv.BYTE || x(n.end) - x(n.t) < dp(14f)) continue
                if (x1 - x0 < dp(8f)) {                          // a mark 8 dp wide, clear of the byte next to it
                    val m = (x0 + x1) / 2
                    if (e.kind == BusEv.START) { x1 = min(m + dp(4f), x(n.t)); x0 = x1 - dp(8f) }
                    else { x0 = max(m - dp(4f), x(n.end)); x1 = x0 + dp(8f) }
                }
                flush()
                box(c, x0, x1, top, cHigh)
                label(c, if (e.kind == BusEv.START) "S" else "P", (x0 + x1) / 2, top + dp(17f), 10f, cText)
                continue
            }
            if (e.kind == BusEv.LOST) {
                flush()
                x1 = max(x1, x0 + dp(3f))
                box(c, x0, x1, top, cWarn)
                boxText(c, x0, x1, top, listOf("too fast to decode", "too fast", "!"))
                continue
            }
            if (x1 - x0 < dp(3f)) {                          // too narrow: run them together
                if (runN > 0 && x0 > runX1 + dp(2f)) flush()
                if (runN == 0) runX0 = x0
                runX1 = max(x1, runX0 + dp(2f)); runN++
                continue
            }
            flush()
            x0 += dp(1f); x1 -= dp(1f)
            val v = BusEv.hex(e.value)
            when (e.kind) {
                BusEv.ADDR -> {
                    box(c, x0, x1, top, if (e.acked) cBus else cNack)
                    val a = BusEv.hex(e.value shr 1) + if (e.value and 1 != 0) " R" else " W"
                    boxText(c, x0, x1, top, listOf(a + if (e.acked) " ✓" else " ✗", a, BusEv.hex(e.value shr 1)))
                }
                BusEv.BYTE -> if (e.read) {
                    box(c, x0, x1, top, cAnalog)
                    boxText(c, x0, x1, top, listOf(v))
                } else {
                    box(c, x0, x1, top, if (e.acked) cBus else cNack)
                    boxText(c, x0, x1, top, listOf(v + if (e.acked) " ✓" else " ✗", v))
                }
                BusEv.SPI -> {
                    box(c, x0, x1, top, cBus)
                    boxText(c, x0, x1, top, listOf(v), if (e.driven) BusEv.hex(e.miso) else "")
                }
            }
        }
        flush()
        c.restore()
        val focus = if (cursorA in start..endNs) cursorA else endNs
        val what = d.describe(start, focus)
        if (what.isNotEmpty()) label(c, fitTo(what, 11.5f, right - dp(10f)), dp(10f), top + dp(46f), 11.5f, cLabel, Paint.Align.LEFT)
    }

    private fun fitTo(s: String, size: Float, w: Float): String {
        val tp = android.text.TextPaint(text).apply { textSize = dp(size) }
        return android.text.TextUtils.ellipsize(s, tp, w, android.text.TextUtils.TruncateAt.END).toString()
    }

    /** Text cut with an ellipsis to the label column */
    private fun fit(s: String, size: Float): String {
        val tp = android.text.TextPaint(text).apply { textSize = dp(size) }
        return android.text.TextUtils.ellipsize(s, tp, labelW - dp(14f), android.text.TextUtils.TruncateAt.END).toString()
    }

    /** Frequency and duty from the rising edges in the window */
    private fun digitalMeasure(ev: List<Ev>, start: Long): String {
        val rises = ArrayList<Long>(); var highNs = 0L; var lastHigh = -1L
        for (k in ev.indices) {
            val e = ev[k]
            if (e.t < start || e.t > endNs || e.pwm) continue
            val high = e.a in 1650..100000
            val wasHigh = k > 0 && ev[k - 1].a in 1650..100000
            if (high && !wasHigh) { rises += e.t; lastHigh = e.t }
            if (!high && wasHigh && lastHigh >= 0 && rises.size >= 1) highNs += e.t - lastHigh
        }
        if (rises.size < 2) {
            val last = ev.lastOrNull { it.t <= endNs } ?: return ""
            return if (last.a in 1650..100000) "high" else if (last.a in 0..1649) "low" else ""
        }
        val period = (rises.last() - rises.first()).toDouble() / (rises.size - 1)
        val duty = (highNs.toDouble() / (rises.last() - rises.first())).coerceIn(0.0, 1.0)
        return "%s · %d %%".format(java.util.Locale.ROOT, hz((1e12 / period).toLong()), (duty * 100).toInt())
    }

    // ------------------------------------------------------------------
    // Touch: pinch to zoom, drag to look back, tap for cursors

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val w = (windowNs / det.scaleFactor).toLong().coerceIn(2_000L, 6_000_000_000L)
            onUserZoom(w)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (abs(dx) < abs(dy)) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            onUserPan((dx / (right - labelW) * windowNs).toLong())
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.x < labelW) return false
            val tt = t(e.x)
            when {
                cursorA < 0 -> cursorA = tt
                cursorB < 0 -> cursorB = tt
                abs(tt - cursorA) < abs(tt - cursorB) -> cursorA = tt
                else -> cursorB = tt
            }
            onCursors(); invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) { cursorA = -1; cursorB = -1; onCursors(); invalidate() }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        if (!scale.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    companion object {
        private const val SCL = 0; private const val SDA = 1; private const val SCK = 2
        private const val MOSI = 3; private const val MISO = 4; private const val CSP = 5

        private fun g3(v: Double) = java.math.BigDecimal(v).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()

        fun hz(mHz: Long): String = when {
            mHz >= 1_000_000_000 -> g3(mHz / 1e9) + " MHz"
            mHz >= 1_000_000 -> g3(mHz / 1e6) + " kHz"
            mHz >= 10_000 -> g3(mHz / 1e3) + " Hz"
            else -> "%.2f Hz".format(java.util.Locale.ROOT, mHz / 1e3)
        }

        fun span(ns: Long): String {
            val a = abs(ns)
            val s = if (ns < 0) "−" else ""
            fun g(v: Double) = java.math.BigDecimal(v).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()
            return when {
                a == 0L -> "0"
                a >= 1_000_000_000 -> s + g(a / 1e9) + " s"
                a >= 1_000_000 -> s + g(a / 1e6) + " ms"
                else -> s + g(a / 1e3) + " µs"
            }
        }
    }
}
