/*
 * Solderless - the Registers section's list: peripherals, their registers
 * and fields, drawn as rows.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.util.AttributeSet
import io.github.leukoplast1980.solderless.R
import kotlin.math.max

class RegisterListView(context: Context, attrs: AttributeSet? = null) : RowsView(context, attrs) {

    sealed class Row {
        /** CLOCK ON, CLOCK OFF */
        class Group(val label: String) : Row()
        class Periph(val name: String, val address: String, val expanded: Boolean, val on: Boolean, val tag: String) : Row()
        class Reg(val name: String, val value: String, val note: String, val changed: Boolean, val dim: Boolean) : Row()
        class Field(val name: String, val value: String, val meaning: String, val changed: Boolean, val dim: Boolean) : Row()
        /** "13 more at reset" (indented, tappable), "Reading …" */
        class Note(val text: String, val indent: Boolean) : Row()
    }

    private val text = color(R.color.console_text)
    private val label = color(R.color.label)
    private val dimText = color(R.color.console_hint)
    private val changedColor = color(R.color.changed)
    private val rule = color(R.color.divider)

    private val sansBold = paint(sp(14f), Typeface.DEFAULT_BOLD)
    private val sans = paint(sp(12f), Typeface.DEFAULT)
    private val small = paint(sp(10.5f), Typeface.DEFAULT_BOLD).apply { letterSpacing = 0.08f }
    private val mono = paint(sp(13f), Typeface.MONOSPACE)
    private val monoBold = paint(sp(13f), Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    private val monoSmall = paint(sp(12f), Typeface.MONOSPACE)
    private val monoAddr = paint(sp(11f), Typeface.MONOSPACE)

    var rows: List<Row> = emptyList()
        private set

    override val rowCount get() = rows.size

    private fun heightOf(r: Row): Float = when (r) {
        is Row.Group -> sp(12f) * 2.6f
        is Row.Periph -> max(dp(36f), sp(14f) * 2.4f)
        is Row.Reg -> sp(13f) * 2.1f
        is Row.Field -> max(dp(32f), sp(12f) * 2.0f)
        is Row.Note -> sp(12f) * 2.2f
    }

    override fun rowHeight(i: Int, width: Int) = heightOf(rows[i])

    fun show(newRows: List<Row>) {
        val heights = newRows.size != rows.size || newRows.indices.any { heightOf(newRows[it]) != heightOf(rows[it]) }
        rows = newRows
        changed(heights)
    }

    override fun tappable(i: Int): Boolean = rows[i].let { it !is Row.Group && !(it is Row.Note && !it.indent) }

    override fun drawRow(c: Canvas, i: Int, top: Float, bottom: Float) {
        val w = width.toFloat()
        val pad = dp(12f)
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
                mono.color = if (r.changed) changedColor else if (r.dim) dimText else text
                c.drawText(r.value, vx, baseline(mono, mid), mono)
                if (r.note.isNotEmpty()) {
                    sans.color = label
                    drawFitted(c, r.note, vx + mono.measureText(r.value) + dp(12f), w - pad, mid, sans)
                }
            }
            is Row.Field -> {
                val x0 = dp(40f)
                if (r.changed) {
                    fill.color = changedColor
                    c.drawRect(dp(30f), mid - sp(6f), dp(32.5f), mid + sp(6f), fill)
                }
                monoSmall.color = if (r.changed) changedColor else if (r.dim) dimText else label
                c.drawText(r.name, x0, baseline(monoSmall, mid), monoSmall)
                val vx = max(x0 + monoSmall.measureText("M") * 10f, x0 + monoSmall.measureText(r.name) + dp(8f))
                monoSmall.color = if (r.changed) changedColor else if (r.dim) dimText else text
                c.drawText(r.value, vx, baseline(monoSmall, mid), monoSmall)
                if (r.meaning.isNotEmpty()) {
                    sans.color = if (r.changed) changedColor else if (r.dim) dimText else label
                    drawFitted(c, r.meaning, vx + monoSmall.measureText(r.value) + dp(10f), w - pad, mid, sans)
                }
            }
            is Row.Note -> {
                sans.color = dimText
                drawFitted(c, r.text, if (r.indent) dp(40f) else pad, w - pad, mid, sans)
            }
        }
    }
}
