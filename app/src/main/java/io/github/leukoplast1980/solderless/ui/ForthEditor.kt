/*
 * Solderless - the editor widget: CodeView with our own line number
 * gutter and current line highlight, and Forth highlighting, undo and
 * completion on top.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Selection
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import com.amrdeveloper.codeview.CodeView
import io.github.leukoplast1980.solderless.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Long lines wrap (a setting). The gutter numbers source lines, so a wrapped
 * line gets one number on its first row, and the current line highlight
 * covers all rows of the cursor's source line.
 *
 * CodeView's own line numbers are not used: they are drawn into a padding
 * narrower than the numbers (they overlap the text), count screen rows
 * instead of source lines, and reset the padding on every frame.
 */
class ForthEditor(context: Context, attrs: AttributeSet?) : CodeView(context, attrs) {

    /** Called when the cursor moves, for the suggestion strip. */
    var onCursorMoved: (() -> Unit)? = null

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val dividerPaint = Paint().apply {
        color = context.getColor(R.color.divider)
        strokeWidth = resources.displayMetrics.density
    }
    private val currentLinePaint = Paint().apply { color = context.getColor(R.color.current_line) }
    private val numberColor = context.getColor(R.color.label)
    private val currentNumberColor = context.getColor(R.color.console_text)

    private var digits = 0
    private var numbersRight = 0f
    private var gutterRight = 0f
    private var gutterUpdatePosted = false
    private var wrap = true

    /** Newlines in the text, kept up to date on every change. */
    private var newlines = 0

    /**
     * The source line number at a text offset is 1 + the newlines before it.
     * Remember the last answer so scrolling and typing below it only count
     * the difference instead of the whole text.
     */
    private var anchorOffset = 0
    private var anchorNewlines = 0

    /** Gliding after a fling. Null while CodeView's constructor runs (it calls back into us). */
    private var fling: EditorFling? = null

    init {
        typeface = Typeface.MONOSPACE
        // No keyboard suggestions or autocorrect: they mangle Forth words.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(false)
        setEnableLineNumber(false)
        setEnableHighlightCurrentLine(false)
        setEnableAutoIndentation(false)
        numberPaint.textSize = textSize * 0.85f

        newlines = countNewlines(text, 0, text.length)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                newlines -= countNewlines(s, start, start + count)
                // Text before the anchor changes: its count is no longer known.
                if (start < anchorOffset) { anchorOffset = 0; anchorNewlines = 0 }
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                newlines += countNewlines(s, start, start + count)
                fling?.stop()   // typing or a new file: the cursor decides where to look
            }
            override fun afterTextChanged(s: Editable) {}
        })
        updateGutter(digitsFor(newlines + 1))
        fling = EditorFling(this) { !wrap }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = fling ?: return super.onTouchEvent(event)
        if (f.onTouch(event)) return true
        val handled = super.onTouchEvent(event)
        f.afterTouch(event)
        return handled
    }

    override fun computeScroll() {
        if (fling?.computeScroll() != true) super.computeScroll()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        fling?.drawEdges(canvas)
    }

    private fun countNewlines(s: CharSequence, from: Int, to: Int): Int {
        var n = 0
        for (i in from until to) if (s[i] == '\n') n++
        return n
    }

    private fun newlinesBefore(offset: Int): Int {
        val t = text
        val o = offset.coerceIn(0, t.length)
        if (anchorOffset > t.length) { anchorOffset = 0; anchorNewlines = 0 }
        anchorNewlines += if (o >= anchorOffset) countNewlines(t, anchorOffset, o)
        else -countNewlines(t, o, anchorOffset)
        anchorOffset = o
        return anchorNewlines
    }

    /** Text size in sp; the line numbers follow it. */
    fun setTextSizeSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        numberPaint.textSize = textSize * 0.85f
        updateGutter(digitsFor(newlines + 1))
        invalidate()
    }

    /** Wrap long lines, or scroll sideways. */
    fun setLineWrap(on: Boolean) {
        if (on == wrap) return
        wrap = on
        setHorizontallyScrolling(!on)
        if (on) scrollTo(0, scrollY)
    }

    /** At least two digits, so the text does not jump between line 9 and 10. */
    private fun digitsFor(lines: Int) = max(2, lines.toString().length)

    private fun updateGutter(newDigits: Int) {
        digits = newDigits
        val charWidth = numberPaint.measureText("8")
        numbersRight = charWidth * (0.6f + digits)
        gutterRight = numbersRight + charWidth * 0.6f
        val left = (gutterRight + charWidth * 0.8f).roundToInt()
        if (left != paddingLeft) setPadding(left, paddingTop, paddingRight, paddingBottom)
    }

    private fun isFirstRow(lay: Layout, t: CharSequence, row: Int): Boolean {
        val start = lay.getLineStart(row)
        return start == 0 || t[start - 1] == '\n'
    }

    override fun onDraw(canvas: Canvas) {
        val lay = layout
        if (lay == null) { super.onDraw(canvas); return }
        val t = text

        // Changing the padding while drawing would start another layout pass
        // from inside this one; do it right after.
        if (digitsFor(newlines + 1) != digits && !gutterUpdatePosted) {
            gutterUpdatePosted = true
            post {
                gutterUpdatePosted = false
                val d = digitsFor(newlines + 1)
                if (d != digits) updateGutter(d)
            }
        }

        // Drawn in view coordinates, so the gutter stays put when the text
        // scrolls sideways (line wrap off). TextView clips the text to the
        // padding, so it never runs into the gutter.
        val left = scrollX.toFloat()

        // Current source line, all of its rows, under the text.
        val cursor = Selection.getSelectionStart(t)
        var cursorFirst = -1
        var cursorLast = -1
        if (cursor >= 0 && cursor <= t.length) {
            var start = cursor
            while (start > 0 && t[start - 1] != '\n') start--
            var end = cursor
            while (end < t.length && t[end] != '\n') end++
            cursorFirst = lay.getLineForOffset(start)
            cursorLast = lay.getLineForOffset(end)
            val top = (lay.getLineTop(cursorFirst) + extendedPaddingTop).toFloat()
            val bottom = (lay.getLineBottom(cursorLast) + extendedPaddingTop).toFloat()
            canvas.drawRect(left + gutterRight, top, left + width, bottom, currentLinePaint)
        }

        super.onDraw(canvas)

        val top = scrollY.toFloat()
        canvas.drawLine(left + gutterRight, top, left + gutterRight, top + height, dividerPaint)

        val contentTop = scrollY - extendedPaddingTop
        val first = lay.getLineForVertical(max(0, contentTop))
        val last = lay.getLineForVertical(contentTop + height)
        var number = newlinesBefore(lay.getLineStart(first)) + 1
        for (row in first..last) {
            if (!isFirstRow(lay, t, row)) continue
            if (row != first) number++
            numberPaint.color = if (row == cursorFirst) currentNumberColor else numberColor
            canvas.drawText(number.toString(), left + numbersRight, getLineBounds(row, null).toFloat(), numberPaint)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        fling?.stop()
        invalidate()  // the current line highlight and number move along
        onCursorMoved?.invoke()
    }
}
