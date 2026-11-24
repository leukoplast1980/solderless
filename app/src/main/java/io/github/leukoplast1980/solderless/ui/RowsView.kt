/*
 * Solderless - a list drawn as rows on one canvas, for the Chip tab's
 * sections (hundreds of rows, redrawn ten times a second): the layout of
 * the rows, drawing only those in view, a tapped row lit up while pressed.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import io.github.leukoplast1980.solderless.R
import kotlin.math.abs

abstract class RowsView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    protected abstract val rowCount: Int
    /** The row's height at this width */
    protected abstract fun rowHeight(i: Int, width: Int): Float
    protected abstract fun drawRow(c: Canvas, i: Int, top: Float, bottom: Float)
    protected open fun tappable(i: Int) = true

    /** A tap on a row: its index */
    var onTap: ((Int) -> Unit)? = null
    /** A tap on a row: its index and where in it (x, y from the row's top); used instead of [onTap] if set */
    var onTapAt: ((Int, Float, Float) -> Unit)? = null

    private var tops = FloatArray(1)
    private var laidOutWidth = -1

    protected fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    protected fun dp(v: Float) = v * resources.displayMetrics.density
    protected fun color(id: Int) = ContextCompat.getColor(context, id)
    protected fun paint(size: Float, tf: android.graphics.Typeface) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf }

    private val pressedBg = color(R.color.chip_background)
    protected val fill = Paint()

    /** The rows changed: [heights] true if any row's height may have. */
    protected fun changed(heights: Boolean) {
        if (heights || tops.size != rowCount + 1) {
            if (layoutRows(width)) requestLayout()
        }
        invalidate()
    }

    /** Lays the rows out for [w]; true if the total height changed */
    private fun layoutRows(w: Int): Boolean {
        val old = tops.last()
        val n = rowCount
        val t = FloatArray(n + 1)
        for (i in 0 until n) t[i + 1] = t[i] + rowHeight(i, w)
        tops = t
        laidOutWidth = w
        return t.last() != old || n == 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w != laidOutWidth || tops.size != rowCount + 1) layoutRows(w)
        setMeasuredDimension(w, (tops.last() + dp(16f)).toInt())
    }

    private val clip = Rect()

    override fun onDraw(c: Canvas) {
        c.getClipBounds(clip)
        val n = minOf(rowCount, tops.size - 1)
        for (i in 0 until n) {
            val top = tops[i]
            val bottom = tops[i + 1]
            if (bottom < clip.top || top > clip.bottom) continue
            if (i == pressed) {
                fill.color = pressedBg
                c.drawRect(0f, top, width.toFloat(), bottom, fill)
            }
            drawRow(c, i, top, bottom)
        }
    }

    /** The baseline that centres one line of [p] on [mid] */
    protected fun baseline(p: Paint, mid: Float) = mid - (p.descent() + p.ascent()) / 2

    /** One line from [x], cut with … before [right] */
    protected fun drawFitted(c: Canvas, s: String, x: Float, right: Float, mid: Float, p: TextPaint) {
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
        val n = minOf(rowCount, tops.size - 1)
        if (n == 0 || y < 0 || y >= tops[n]) return -1
        var lo = 0
        var hi = n - 1
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
                pressed = rowAt(e.y).takeIf { it >= 0 && tappable(it) } ?: -1
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
                if (i in 0 until rowCount) {
                    playSoundEffect(SoundEffectConstants.CLICK)
                    val at = onTapAt
                    if (at != null) at(i, e.x, e.y - tops[i]) else onTap?.invoke(i)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
