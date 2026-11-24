/*
 * Solderless - the Pins section: the LQFP64 package with each pin coloured
 * by how it is set up, a legend, and the pins in use as rows.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.chip.PinSetup
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class PinListView(context: Context, attrs: AttributeSet? = null) : RowsView(context, attrs) {

    sealed class Row {
        /** The package: every pin by number (GPIO pins with their setup) */
        class Package(val names: Map<Int, String>, val setups: Map<Int, PinSetup>) : Row()
        object Legend : Row()
        class Pin(val setup: PinSetup) : Row()
        class Note(val text: String, val tappable: Boolean) : Row()
    }

    private val text = color(R.color.console_text)
    private val label = color(R.color.label)
    private val dimText = color(R.color.console_hint)
    private val rule = color(R.color.divider)
    private val high = color(R.color.accent)
    private val low = 0xFF4F7A55.toInt()
    private val alt = color(R.color.syntax_string)
    private val inputColour = 0xFF9AA4AA.toInt()
    private val analog = color(R.color.analog)
    private val atReset = 0xFF4A565E.toInt()
    private val supply = 0xFF2E363C.toInt()
    private val body = 0xFF232A30.toInt()
    private val warn = color(R.color.chip_message)
    private val wiredColour = color(R.color.syntax_defining)

    private val nameBold = paint(sp(13f), Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    private val sans = paint(sp(12.5f), Typeface.DEFAULT)
    private val small = paint(sp(11.5f), Typeface.DEFAULT)
    private val pinLabel = paint(sp(9.5f), Typeface.MONOSPACE)
    private val chipName = paint(sp(12f), Typeface.DEFAULT_BOLD)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    var rows: List<Row> = emptyList()
        private set
    override val rowCount get() = rows.size

    fun show(newRows: List<Row>) {
        val heights = newRows.size != rows.size || newRows.indices.any { newRows[it]::class != rows[it]::class ||
            (newRows[it] as? Row.Pin)?.let { p -> twoLines(p) != twoLines(rows[it] as Row.Pin) } == true }
        rows = newRows
        changed(heights)
    }

    private fun twoLines(r: Row.Pin) = r.setup.wired.isNotEmpty() || r.setup.mismatch != null
    private val line get() = sp(12.5f) * 1.5f

    override fun rowHeight(i: Int, width: Int): Float = when (val r = rows[i]) {
        is Row.Package -> min(width.toFloat(), dp(360f))
        is Row.Legend -> sp(12f) * 2.4f
        is Row.Pin -> line * (if (twoLines(r)) 2 else 1) + dp(10f)
        is Row.Note -> max(dp(36f), sp(12f) * 2.6f)
    }

    override fun tappable(i: Int) = rows[i].let { it is Row.Pin || it is Row.Package || (it is Row.Note && it.tappable) }

    fun colourOf(s: PinSetup?): Int = when {
        s == null -> supply
        s.clockOff || s.atReset -> atReset
        s.mode == PinSetup.MODE_OUTPUT -> if (s.output) high else low
        s.mode == PinSetup.MODE_AF -> alt
        s.mode == PinSetup.MODE_INPUT -> inputColour
        else -> analog
    }

    // ------------------------------------------------------------------
    // The package: pin 1 at the top of the left side, counting anticlockwise

    private class Geometry(val cx: Float, val cy: Float, val half: Float, val pitch: Float, val len: Float)

    private fun geometry(width: Float, height: Float): Geometry {
        val side = min(width, height)
        val half = side / 2 - dp(46f)
        return Geometry(width / 2, height / 2, half, (2 * half - dp(16f)) / 15f, dp(9f))
    }

    /** The middle of pin [n]'s lead, and which side: 0 left, 1 bottom, 2 right, 3 top */
    private fun pinAt(g: Geometry, n: Int): Triple<Float, Float, Int> {
        val k = (n - 1) % 16
        val off = -g.half + dp(8f) + k * g.pitch
        val out = g.half + g.len / 2
        return when ((n - 1) / 16) {
            0 -> Triple(g.cx - out, g.cy + off, 0)
            1 -> Triple(g.cx + off, g.cy + out, 1)
            2 -> Triple(g.cx + out, g.cy - off, 2)
            else -> Triple(g.cx - off, g.cy - out, 3)
        }
    }

    /** The pin nearest to a tap in the package row, or 0 */
    fun pinNear(i: Int, x: Float, y: Float): Int {
        val r = rows.getOrNull(i) as? Row.Package ?: return 0
        val g = geometry(width.toFloat(), rowHeight(i, width))
        var best = 0
        var dist = dp(20f)
        for (n in 1..64) {
            if (n !in r.setups) continue
            val (px, py) = pinAt(g, n)
            val d = hypot(px - x, py - y)
            if (d < dist) { dist = d; best = n }
        }
        return best
    }

    private fun drawPackage(c: Canvas, r: Row.Package, top: Float, bottom: Float) {
        val g = geometry(width.toFloat(), bottom - top)
        c.withTranslation(0f, top) {
            fill.color = body
            drawRect(g.cx - g.half, g.cy - g.half, g.cx + g.half, g.cy + g.half, fill)
            fill.color = supply
            drawCircle(g.cx - g.half + dp(9f), g.cy - g.half + dp(9f), dp(3f), fill)
            chipName.color = label
            val t = "STM32G431RB"
            drawText(t, g.cx - chipName.measureText(t) / 2, g.cy, chipName)
            small.color = dimText
            drawText("LQFP64", g.cx - small.measureText("LQFP64") / 2, g.cy + sp(16f), small)
            val w = g.pitch * 0.55f
            for (n in 1..64) {
                val s = r.setups[n]
                val (px, py, side) = pinAt(g, n)
                fill.color = colourOf(s)
                if (side == 0 || side == 2) drawRect(px - g.len / 2, py - w / 2, px + g.len / 2, py + w / 2, fill)
                else drawRect(px - w / 2, py - g.len / 2, px + w / 2, py + g.len / 2, fill)
                if (s?.mismatch != null) {
                    stroke.color = warn
                    stroke.strokeWidth = dp(1.5f)
                    drawCircle(px, py, g.len * 0.8f, stroke)
                }
                // Names of the pins in use, outside the package
                if (s == null || (s.atReset && s.wired.isEmpty())) continue
                val name = r.names[n] ?: continue
                pinLabel.color = if (s.mismatch != null) warn else text
                val gap = g.len / 2 + dp(3f)
                val tw = pinLabel.measureText(name)
                val mid = -(pinLabel.descent() + pinLabel.ascent()) / 2
                when (side) {
                    0 -> drawText(name, px - gap - tw, py + mid, pinLabel)
                    2 -> drawText(name, px + gap, py + mid, pinLabel)
                    1 -> withRotation(90f, px, py) { drawText(name, px + gap, py + mid, pinLabel) }
                    else -> withRotation(-90f, px, py) { drawText(name, px + gap, py + mid, pinLabel) }
                }
            }
        }
    }

    override fun drawRow(c: Canvas, i: Int, top: Float, bottom: Float) {
        val w = width.toFloat()
        val pad = dp(12f)
        when (val r = rows[i]) {
            is Row.Package -> drawPackage(c, r, top, bottom)
            is Row.Legend -> {
                var x = pad
                val mid = (top + bottom) / 2
                for ((col, name) in listOf(high to "output", alt to "alternate", inputColour to "input", analog to "analog", atReset to "reset")) {
                    fill.color = col
                    c.drawRect(x, mid - dp(5f), x + dp(10f), mid + dp(5f), fill)
                    small.color = label
                    c.drawText(name, x + dp(14f), baseline(small, mid), small)
                    x += dp(14f) + small.measureText(name) + dp(12f)
                }
            }
            is Row.Pin -> {
                val s = r.setup
                fill.color = rule
                c.drawRect(pad, top, w - pad, top + dp(1f), fill)
                var mid = top + dp(5f) + line / 2
                fill.color = colourOf(s)
                c.drawRect(pad, mid - dp(5f), pad + dp(4f), mid + dp(5f), fill)
                nameBold.color = text
                c.drawText(s.name, pad + dp(10f), baseline(nameBold, mid), nameBold)
                val x = pad + dp(10f) + nameBold.measureText("PA15") + dp(12f)
                val lv = s.level
                small.color = if (lv == "high") high else label
                val lw = small.measureText(lv)
                if (lv.isNotEmpty()) c.drawText(lv, w - pad - lw, baseline(small, mid), small)
                sans.color = text
                drawFitted(c, s.summary, x, w - pad - lw - dp(8f), mid, sans)
                if (twoLines(r)) {
                    mid += line
                    if (s.mismatch != null) {
                        small.color = warn
                        drawFitted(c, "▲ " + s.mismatch, x, w - pad, mid, small)
                    } else {
                        small.color = wiredColour
                        drawFitted(c, s.wired.joinToString(", "), x, w - pad, mid, small)
                    }
                }
            }
            is Row.Note -> {
                sans.color = if (r.tappable) high else dimText
                drawFitted(c, r.text, pad, w - pad, (top + bottom) / 2, sans)
            }
        }
    }
}
