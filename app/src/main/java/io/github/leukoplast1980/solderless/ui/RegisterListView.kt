/*
 * Solderless - the Registers section's list: peripherals, their registers
 * and fields, drawn as rows (a few hundred, redrawn ten times a second).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import io.github.leukoplast1980.solderless.R
import kotlin.math.abs
import kotlin.math.max

class RegisterListView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    sealed class Row {
        /** CLOCK ON, CLOCK OFF */
        class Group(val label: String) : Row()
        class Periph(val name: String, val address: String, val expanded: Boolean, val on: Boolean, val tag: String) : Row()
        class Reg(val name: String, val value: String, val note: String, val changed: Boolean, val dim: Boolean) : Row()
        class Field(val name: String, val value: String, val meaning: String, val changed: Boolean, val dim: Boolean) : Row()
        /** "13 more at reset", "Loading …" */
        class Note(val text: String, val indent: Boolean) : Row()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val text = color(R.color.console_text)
    private val label = color(R.color.label)
    private val dimText = color(R.color.console_hint)
    private val changed = color(R.color.changed)
    private val rule = color(R.color.divider)
    private val pressedBg = color(R.color.chip_background)

    private val sansBold = paint(sp(14f), Typeface.DEFAULT_BOLD)
    private val sans = paint(sp(12f), Typeface.DEFAULT)
    private val small = paint(sp(10.5f), Typeface.create(Typeface.DEFAULT, Typeface.BOLD)).apply { letterSpacing = 0.08f }
    private val mono = paint(sp(13f), Typeface.MONOSPACE)
    private val monoBold = paint(sp(13f), Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    private val monoSmall = paint(sp(12f), Typeface.MONOSPACE)
    private val monoAddr = paint(sp(11f), Typeface.MONOSPACE)
    private val fill = Paint()

    private fun paint(size: Float, tf: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf }

    private fun heightOf(r: Row): Float = when (r) {
        is Row.Group -> sp(12f) * 2.6f
        is Row.Periph -> max(dp(36f), sp(14f) * 2.4f)
        is Row.Reg -> sp(13f) * 2.1f
        is Row.Field -> max(dp(32f), sp(12f) * 2.0f)
        is Row.Note -> sp(12f) * 2.2f
    }

    var rows: List<Row> = emptyList()
        private set
    private var tops = FloatArray(1)

    /** A tap on a row: its index in [rows] */
    var onTap: ((Int) -> Unit)? = null

    fun show(newRows: List<Row>) {
        val relayout = newRows.size != rows.size || newRows.indices.any { heightOf(newRows[it]) != heightOf(rows[it]) }
        rows = newRows
        if (relayout) {
            tops = FloatArray(rows.size + 1)
            for (i in rows.indices) tops[i + 1] = tops[i] + heightOf(rows[i])
            requestLayout()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (tops.last() + dp(16f)).toInt())
    }

    private val clip = Rect()

    override fun onDraw(c: Canvas) {
        c.getClipBounds(clip)
        val w = width.toFloat()
        val pad = dp(12f)
        for (i in rows.indices) {
            val top = tops[i]
            val bottom = tops[i + 1]
            if (bottom < clip.top || top > clip.bottom) continue
            if (i == pressed) {
                fill.color = pressedBg
                c.drawRect(0f, top, w, bottom, fill)
            }
            val mid = (top + bottom) / 2
            when (val r = rows[i]) {
                is Row.Group -> {
                    small.color = label
                    c.drawText(r.label, pad, baseline(small, mid + dp(3f)), small)
                }
                is Row.Periph -> {
                    sansBold.color = if (r.on) text else dimText
                    val name = (if (r.expanded) "▾ " else "▸ ") + r.name
                    c.drawText(name, pad, baseline(sansBold, mid), sansBold)
                    var x = pad + sansBold.measureText(name) + dp(10f)
                    if (r.tag.isNotEmpty()) {
                        sans.color = dimText
                        c.drawText(r.tag, x, baseline(sans, mid), sans)
                        x += sans.measureText(r.tag)
                    }
                    monoAddr.color = dimText
                    val aw = monoAddr.measureText(r.address)
                    if (w - pad - aw > x + dp(8f)) c.drawText(r.address, w - pad - aw, baseline(monoAddr, mid), monoAddr)
                }
                is Row.Reg -> {
                    fill.color = rule
                    c.drawRect(dp(24f), top, w - pad, top + dp(1f), fill)
                    monoBold.color = if (r.dim) dimText else text
                    val x0 = dp(24f)
                    c.drawText(r.name, x0, baseline(monoBold, mid), monoBold)
                    val vx = max(x0 + mono.measureText("M") * 10.5f, x0 + monoBold.measureText(r.name) + dp(8f))
                    mono.color = if (r.changed) changed else if (r.dim) dimText else text
                    c.drawText(r.value, vx, baseline(mono, mid), mono)
                    if (r.note.isNotEmpty()) {
                        val nx = vx + mono.measureText(r.value) + dp(12f)
                        sans.color = label
                        drawFitted(c, r.note, nx, w - pad, mid, sans)
                    }
                }
                is Row.Field -> {
                    val x0 = dp(40f)
                    if (r.changed) {
                        fill.color = changed
                        c.drawRect(dp(30f), mid - sp(6f), dp(32.5f), mid + sp(6f), fill)
                    }
                    monoSmall.color = if (r.changed) changed else if (r.dim) dimText else label
                    c.drawText(r.name, x0, baseline(monoSmall, mid), monoSmall)
                    val vx = max(x0 + monoSmall.measureText("M") * 10f, x0 + monoSmall.measureText(r.name) + dp(8f))
                    monoSmall.color = if (r.changed) changed else if (r.dim) dimText else text
                    c.drawText(r.value, vx, baseline(monoSmall, mid), monoSmall)
                    if (r.meaning.isNotEmpty()) {
                        val mx = vx + monoSmall.measureText(r.value) + dp(10f)
                        sans.color = if (r.changed) changed else if (r.dim) dimText else label
                        drawFitted(c, r.meaning, mx, w - pad, mid, sans)
                    }
                }
                is Row.Note -> {
                    sans.color = dimText
                    drawFitted(c, r.text, if (r.indent) dp(40f) else pad, w - pad, mid, sans)
                }
            }
        }
    }

    private fun baseline(p: Paint, mid: Float) = mid - (p.descent() + p.ascent()) / 2

    private fun drawFitted(c: Canvas, s: String, x: Float, right: Float, mid: Float, p: TextPaint) {
        if (right - x < dp(24f)) return
        val t = TextUtils.ellipsize(s, p, right - x, TextUtils.TruncateAt.END)
        c.drawText(t, 0, t.length, x, baseline(p, mid), p)
    }

    // ------------------------------------------------------------------
    // Taps: a row lights up while pressed; a scroll does not tap

    private var pressed = -1
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun rowAt(y: Float): Int {
        if (y < 0 || y >= tops.last()) return -1
        var lo = 0
        var hi = rows.size - 1
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (tops[m] <= y) lo = m else hi = m - 1
        }
        return lo
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                pressed = rowAt(e.y).takeIf { it >= 0 && tappable(rows[it]) } ?: -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val i = pressed
                pressed = -1
                invalidate()
                if (i >= 0 && i < rows.size) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    onTap?.invoke(i)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun tappable(r: Row) = r !is Row.Group && !(r is Row.Note && !r.indent)
}
