/*
 * Solderless - the Log section's list: register writes (when, which
 * instruction, which register, the fields that changed) and the chip
 * model's checks, newest first.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.util.AttributeSet
import androidx.core.graphics.withTranslation
import io.github.leukoplast1980.solderless.R
import kotlin.math.max

class LogListView(context: Context, attrs: AttributeSet? = null) : RowsView(context, attrs) {

    sealed class Row {
        /** A write: [lines] are (change, meaning) */
        class Write(val time: String, val where: String, val mine: Boolean, val count: String, val register: String,
                    val lines: List<Pair<String, String>>) : Row()
        class Check(val time: String, val text: String) : Row()
        class Note(val text: String, val tappable: Boolean) : Row()
    }

    private val text = color(R.color.console_text)
    private val label = color(R.color.label)
    private val dimText = color(R.color.console_hint)
    private val changedColor = color(R.color.changed)
    private val wordColor = color(R.color.syntax_name)
    private val mineColor = color(R.color.accent)
    private val warn = color(R.color.chip_message)
    private val rule = color(R.color.divider)
    private val checkBg = color(R.color.check_background)

    private val timePaint = paint(sp(11f), Typeface.MONOSPACE)
    private val wherePaint = paint(sp(12.5f), Typeface.MONOSPACE)
    private val regPaint = paint(sp(12.5f), Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    private val changePaint = paint(sp(12.5f), Typeface.MONOSPACE)
    private val sans = paint(sp(12f), Typeface.DEFAULT)
    private val checkPaint = paint(sp(12.5f), Typeface.DEFAULT)

    var rows: List<Row> = emptyList()
        private set
    override val rowCount get() = rows.size

    private val line get() = sp(12.5f) * 1.45f
    private val layouts = HashMap<Row.Check, StaticLayout>()

    fun show(newRows: List<Row>) {
        rows = newRows
        layouts.keys.retainAll(newRows.filterIsInstance<Row.Check>().toSet())
        changed(true)
    }

    private fun textX() = dp(72f)

    private fun checkLayout(r: Row.Check, width: Int): StaticLayout {
        val w = max(1, (width - textX() - dp(30f)).toInt())
        layouts[r]?.let { if (it.width == w) return it }
        return StaticLayout.Builder.obtain(r.text, 0, r.text.length, checkPaint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build().also { layouts[r] = it }
    }

    override fun rowHeight(i: Int, width: Int): Float = when (val r = rows[i]) {
        is Row.Write -> line * (2 + r.lines.size) + dp(12f)
        is Row.Check -> checkLayout(r, width).height + dp(22f)
        is Row.Note -> max(dp(36f), sp(12f) * 2.6f)
    }

    override fun tappable(i: Int) = rows[i].let { it is Row.Write || (it is Row.Note && it.tappable) }

    override fun drawRow(c: Canvas, i: Int, top: Float, bottom: Float) {
        val w = width.toFloat()
        val pad = dp(12f)
        val x = textX()
        when (val r = rows[i]) {
            is Row.Write -> {
                fill.color = rule
                c.drawRect(pad, top, w - pad, top + dp(1f), fill)
                var mid = top + dp(6f) + line / 2
                timePaint.color = dimText
                c.drawText(r.time, pad, baseline(timePaint, mid), timePaint)
                wherePaint.color = if (r.mine) mineColor else wordColor
                c.drawText(r.where, x, baseline(wherePaint, mid), wherePaint)
                if (r.count.isNotEmpty()) {
                    sans.color = label
                    c.drawText(r.count, x + wherePaint.measureText(r.where) + dp(8f), baseline(sans, mid), sans)
                }
                mid += line
                regPaint.color = text
                c.drawText(r.register, x, baseline(regPaint, mid), regPaint)
                for ((change, meaning) in r.lines) {
                    mid += line
                    changePaint.color = changedColor
                    c.drawText(change, x, baseline(changePaint, mid), changePaint)
                    if (meaning.isNotEmpty()) {
                        sans.color = label
                        drawFitted(c, meaning, x + changePaint.measureText(change) + dp(8f), w - pad, mid, sans)
                    }
                }
            }
            is Row.Check -> {
                val box = RectF(dp(8f), top + dp(4f), w - dp(8f), bottom - dp(4f))
                fill.color = checkBg
                c.drawRoundRect(box, dp(6f), dp(6f), fill)
                timePaint.color = dimText
                c.drawText(r.time, pad, baseline(timePaint, top + dp(11f) + line / 2), timePaint)
                checkPaint.color = warn
                c.drawText("▲", x - dp(2f), baseline(checkPaint, top + dp(11f) + line / 2), checkPaint)
                c.withTranslation(x + dp(16f), top + dp(11f)) { checkLayout(r, width).draw(this) }
            }
            is Row.Note -> {
                sans.color = if (r.tappable) mineColor else dimText
                drawFitted(c, r.text, pad, w - pad, (top + bottom) / 2, sans)
            }
        }
    }
}
