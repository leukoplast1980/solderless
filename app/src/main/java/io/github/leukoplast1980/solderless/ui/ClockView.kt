/*
 * Solderless - the Clocks section's diagram: the oscillators, the PLL, the
 * SYSCLK switch, the bus dividers and the peripherals on each bus, the
 * paths the clock takes in green.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.core.content.ContextCompat
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.chip.ClockTree
import io.github.leukoplast1980.solderless.chip.ClockTree.Companion.freq
import kotlin.math.max

class ClockView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Boxes a tap opens: "HSI16", "HSE", "HSI48", "LSI", "LSE", "PLL", "SW", "FLASH", "AHB", "APB1", "APB2", "SysTick", or a peripheral */
    var onBox: ((String) -> Unit)? = null

    private var tree: ClockTree? = null
    private val boxes = ArrayList<Pair<RectF, String>>()
    private var laidOut = -1f

    fun show(t: ClockTree) {
        tree = t
        val h = render(null, width.toFloat())
        if (h != laidOut) requestLayout()
        invalidate()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun paint(size: Float, tf: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf }

    private val on = color(R.color.accent)
    private val text = color(R.color.console_text)
    private val label = color(R.color.label)
    private val dimText = color(R.color.console_hint)
    private val dim = color(R.color.divider)
    private val panel = color(R.color.chip_background)
    private val bg = color(R.color.console_background)
    private val warn = color(R.color.chip_message)

    private val title = paint(sp(12.5f), Typeface.DEFAULT_BOLD)
    private val value = paint(sp(11f), Typeface.MONOSPACE)
    private val small = paint(sp(11.5f), Typeface.DEFAULT)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        laidOut = render(null, w.toFloat())
        setMeasuredDimension(w, laidOut.toInt())
    }

    override fun onDraw(c: Canvas) { render(c, width.toFloat()) }

    // ------------------------------------------------------------------
    // Drawing; with no canvas it only measures (and places the boxes for taps)

    private fun render(c: Canvas?, w: Float): Float {
        val t = tree ?: return dp(100f)
        if (c == null) boxes.clear()
        val pad = dp(10f)
        val lineH = sp(11f) * 1.45f
        val boxH = dp(10f) + sp(12.5f) * 1.3f + lineH

        fun box(r: RectF, key: String, active: Boolean, head: String, vararg lines: Pair<String, Int>) {
            if (c == null) { boxes.add(r to key); return }
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = if (active) panel else bg
            c.drawRoundRect(r, dp(5f), dp(5f), boxPaint)
            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = dp(if (active) 1.4f else 1f)
            boxPaint.color = if (active) on else dim
            c.drawRoundRect(r, dp(5f), dp(5f), boxPaint)
            title.color = if (active) text else dimText
            var y = r.top + dp(5f) + sp(12.5f)
            c.drawText(TextUtils.ellipsize(head, title, r.width() - dp(10f), TextUtils.TruncateAt.END).toString(), r.left + dp(6f), y, title)
            for ((s, col) in lines) {
                y += lineH
                value.color = col
                c.drawText(TextUtils.ellipsize(s, value, r.width() - dp(10f), TextUtils.TruncateAt.END).toString(), r.left + dp(6f), y, value)
            }
        }

        fun line(active: Boolean, vararg pts: Float) {
            if (c == null) return
            linePaint.color = if (active) on else dim
            linePaint.strokeWidth = dp(if (active) 1.8f else 1f)
            linePaint.pathEffect = if (active) null else dash
            val p = Path()
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
            c.drawPath(p, linePaint)
        }

        fun textAt(s: String, x: Float, y: Float, p: TextPaint, col: Int, right: Float = w - pad) {
            if (c == null) return
            p.color = col
            c.drawText(TextUtils.ellipsize(s, p, right - x, TextUtils.TruncateAt.END).toString(), x, y, p)
        }

        // The oscillators
        var y = dp(8f)
        val gap = dp(6f)
        val sw = (w - 2 * pad - 4 * gap) / 5
        val srcX = FloatArray(5)
        for ((i, s) in t.sources.withIndex()) {
            val x = pad + i * (sw + gap)
            srcX[i] = x + sw / 2
            val state = when {
                s.hz == 0L -> "no crystal"
                !s.on -> "off"
                !s.ready -> "starting"
                else -> freq(s.hz)
            }
            box(RectF(x, y, x + sw, y + boxH), s.name, s.on && s.ready, s.name, state to (if (s.on && s.ready) on else dimText))
        }
        val srcBottom = y + boxH

        // The PLL
        y = srcBottom + dp(22f)
        val pllActive = t.pllOn && t.pllReady
        val pllRight = w * 0.68f
        val pllLines = buildList {
            add("÷${t.pllM} → ${freq(t.pllInHz)}  ×${t.pllN} → ${freq(t.vcoHz)}" to if (pllActive) on else dimText)
            add("R ÷${t.pllR} → ${freq(t.pllRHz)}${if (t.pllREnabled) "" else " (off)"}" to if (pllActive && t.pllREnabled) on else dimText)
            if (t.pllQEnabled) add("Q ÷${t.pllQ} → ${freq(t.pllQHz)}" to if (pllActive) on else dimText)
            if (t.pllPEnabled) add("P ÷${t.pllP} → ${freq(t.pllPHz)}" to if (pllActive) on else dimText)
            if (t.pllOn && t.pllProblem != null) add(t.pllProblem to warn)
        }
        val pllBox = RectF(pad, y, pllRight, y + dp(10f) + sp(12.5f) * 1.3f + lineH * pllLines.size)
        box(pllBox, "PLL", pllActive, if (t.pllOn) "PLL · from ${t.pllSourceName}" else "PLL · off", *pllLines.toTypedArray())
        val srcIndex = when (t.pllSource) { 2 -> 0; 3 -> 1; else -> -1 }
        if (srcIndex >= 0) line(pllActive, srcX[srcIndex], srcBottom, srcX[srcIndex], pllBox.top)

        // The SYSCLK switch: HSI16, HSE or the PLL's R output
        y = pllBox.bottom + dp(18f)
        val muxW = dp(120f)
        val mx = pad + (pllRight - pad) / 2
        val mux = RectF(mx - muxW / 2, y, mx + muxW / 2, y + dp(24f))
        if (c != null) {
            val p = Path().apply {
                moveTo(mux.left, mux.top); lineTo(mux.right, mux.top)
                lineTo(mux.right - dp(12f), mux.bottom); lineTo(mux.left + dp(12f), mux.bottom); close()
            }
            boxPaint.style = Paint.Style.FILL; boxPaint.color = panel
            c.drawPath(p, boxPaint)
            boxPaint.style = Paint.Style.STROKE; boxPaint.color = on; boxPaint.strokeWidth = dp(1.4f)
            c.drawPath(p, boxPaint)
            value.color = text
            val s = "SW: ${t.sysclkSourceName}"
            c.drawText(s, mx - value.measureText(s) / 2, mux.centerY() + sp(4f), value)
        } else boxes.add(mux to "SW")
        line(t.sysclkSource == 3, mx, pllBox.bottom, mx, mux.top)
        // HSI16 and HSE straight to the switch, around the PLL on its right
        val sideX = pllRight + dp(14f)
        line(t.sysclkSource == 1, srcX[0], srcBottom, srcX[0], srcBottom + dp(10f), sideX, srcBottom + dp(10f), sideX, mux.centerY(), mux.right - dp(6f), mux.centerY())
        line(t.sysclkSource == 2, srcX[1], srcBottom, srcX[1], srcBottom + dp(14f), sideX + dp(8f), srcBottom + dp(14f), sideX + dp(8f), mux.centerY() + dp(6f), mux.right - dp(8f), mux.centerY() + dp(6f))

        // SYSCLK, and the flash wait states beside it
        y = mux.bottom + dp(14f)
        val half = (w - 2 * pad - gap) / 2
        val sys = RectF(pad, y, pad + half, y + boxH)
        box(sys, "SW", true, "SYSCLK", freq(t.sysclkHz) to on)
        line(true, mx.coerceIn(sys.left + dp(10f), sys.right - dp(10f)), mux.bottom, mx.coerceIn(sys.left + dp(10f), sys.right - dp(10f)), sys.top)
        val ok = t.latency >= t.latencyNeeded
        box(RectF(pad + half + gap, y, w - pad, y + boxH), "FLASH", true, "FLASH  ${t.latency} wait states",
            (if (ok) "enough (${t.rangeText})" else "needs ${t.latencyNeeded}!") to (if (ok) on else warn))

        // AHB: HCLK for the core and memories
        y = sys.bottom + dp(16f)
        val ahb = RectF(pad, y, pad + half, y + boxH)
        box(ahb, "AHB", true, "AHB ÷${t.ahbDivider} → HCLK", freq(t.hclkHz) to on)
        line(true, ahb.centerX(), sys.bottom, ahb.centerX(), ahb.top)
        var ry = y + sp(12f)
        val rx = pad + half + gap
        textAt("Core, memories, DMA", rx, ry, small, text)
        ry += lineH
        textAt("SysTick ${if (t.sysTickOn) freq(t.sysTickHz) else "stopped"}", rx, ry, small, if (t.sysTickOn) on else dimText)
        if (c == null) boxes.add(RectF(rx, ry - lineH, w - pad, ry + dp(4f)) to "SysTick")
        ry += lineH
        val ahbOn = t.ahb.peripherals.filter { it.on }.joinToString(" ") { it.name }
        if (ahbOn.isNotEmpty()) { textAt(ahbOn, rx, ry, small, on); ry += lineH }
        line(true, ahb.right, ahb.centerY(), rx - dp(4f), ahb.centerY())

        // The two APB buses and their peripherals
        y = max(ahb.bottom, ry) + dp(18f)
        val b1 = RectF(pad, y, pad + half, y + boxH)
        val b2 = RectF(pad + half + gap, y, w - pad, y + boxH)
        line(true, ahb.centerX(), ahb.bottom, ahb.centerX(), y - dp(8f), b2.centerX(), y - dp(8f), b2.centerX(), b2.top)
        line(true, ahb.centerX(), y - dp(8f), b1.centerX(), y - dp(8f), b1.centerX(), b1.top)
        var bottom = y + boxH
        for ((bus, r) in listOf(t.apb1 to b1, t.apb2 to b2)) {
            box(r, bus.name, true, "${bus.name} ÷${bus.divider} → ${bus.clock}", freq(bus.hz) to on)
            var py = r.bottom + dp(4f)
            for (p in bus.peripherals.filter { it.on }) {
                py += lineH
                textAt("● ${p.name}", r.left + dp(4f), py, small, on, r.right)
                val k = if (p.kernel.isEmpty()) "" else "${p.kernel} ${freq(p.kernelHz)}"
                textAt(k, r.left + dp(4f) + small.measureText("● USART2 "), py, value, label, r.right)
                if (c == null) boxes.add(RectF(r.left, py - lineH, r.right, py + dp(4f)) to p.name)
            }
            val off = bus.peripherals.filter { !it.on }.joinToString(" ") { it.name }
            if (off.isNotEmpty()) {
                // the ones without a clock, wrapped
                val words = off.split(" ")
                var lineText = ""
                for (word in words + "") {
                    val next = if (lineText.isEmpty()) "○ $word" else "$lineText $word"
                    if (word.isEmpty() || small.measureText(next) > r.width() - dp(8f)) {
                        py += lineH
                        textAt(lineText, r.left + dp(4f), py, small, dimText, r.right)
                        lineText = "○ $word"
                    } else lineText = next
                }
            }
            bottom = max(bottom, py)
        }
        y = bottom + dp(20f)
        textAt("Green: where the clock goes. Tap a box for the fields that set it.", pad, y, small, label)
        return y + dp(16f)
    }

    // ------------------------------------------------------------------
    // Taps

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                val slop = dp(8f)
                if (kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop) {
                    boxes.lastOrNull { it.first.contains(e.x, e.y) }?.let {
                        playSoundEffect(SoundEffectConstants.CLICK)
                        onBox?.invoke(it.second)
                    }
                }
            }
        }
        return true
    }
}
