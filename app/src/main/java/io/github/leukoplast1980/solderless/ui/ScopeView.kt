/*
 * Solderless - the Scope tab's drawing: one row per watched pin over a
 * time window. Logic levels, voltages for analog pins, PWM as a square
 * wave (or a band when its periods are too close to draw), fast toggling
 * as a dense mark; trigger line and two cursors.
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

    class Channel(val slot: Int, val pin: String, val label: String, val analog: Boolean, val events: List<Ev>)

    var channels: List<Channel> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
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
    private fun rowH(c: Channel) = if (c.analog) dp(92f) else dp(54f)

    private val cHigh = 0xFF8FD18F.toInt(); private val cLow = 0xFF5E6A70.toInt(); private val cPwm = 0xFFC49BE0.toInt()
    private val cAnalog = 0xFF80DEEA.toInt(); private val cWarn = 0xFFFFB454.toInt(); private val cLabel = 0xFF8A9690.toInt()
    private val cText = 0xFFE8EEE4.toInt(); private val cGrid = 0xFF2C353C.toInt(); private val cCursor = 0xFF7FB8FF.toInt()

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
        for ((i, ch) in channels.withIndex()) {
            val h = rowH(ch)
            if (i % 2 == 1) { fill.color = 0xFF131A1F.toInt(); c.drawRect(0f, y, width.toFloat(), y + h, fill) }
            drawChannel(c, ch, y, h)
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

    private fun drawChannel(c: Canvas, ch: Channel, top: Float, h: Float) {
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
        label(c, ch.pin, dp(10f), top + dp(18f), 12f, cText, Paint.Align.LEFT, true)
        label(c, fit(ch.label, 10.5f), dp(10f), top + dp(32f), 10.5f, cLabel, Paint.Align.LEFT)
        label(c, fit(measure, 9.5f), dp(10f), top + dp(46f), 9.5f, colour, Paint.Align.LEFT)
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
            val w = (windowNs / det.scaleFactor).toLong().coerceIn(20_000L, 6_000_000_000L)
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
        fun hz(mHz: Long): String = when {
            mHz >= 1_000_000_000 -> "%.3g kHz".format(java.util.Locale.ROOT, mHz / 1e6)
            mHz >= 10_000 -> "%.4g Hz".format(java.util.Locale.ROOT, mHz / 1e3)
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
