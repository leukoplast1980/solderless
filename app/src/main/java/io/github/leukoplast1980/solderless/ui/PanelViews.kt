package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Pixels of the display parts as the emulator reports them, turned into colours */
object Panels {
    /** MAX7219 LED: red, as bright as its value (0-255); a dark LED stays faintly visible */
    fun ledColour(v: Int): Int {
        if (v <= 0) return 0xFF3A2224.toInt()
        val k = 0.35f + 0.65f * (v / 255f)
        return (0xFF shl 24) or ((255 * k).toInt() shl 16) or ((74 * k).toInt() shl 8) or (61 * k).toInt()
    }

    /** ST7789 frame: RGB565 little-endian pairs to ARGB */
    fun rgb565(src: ByteArray, out: IntArray, n: Int) {
        for (i in 0 until n) {
            val c = (src[2 * i].toInt() and 0xFF) or ((src[2 * i + 1].toInt() and 0xFF) shl 8)
            val r = (c shr 11) and 31; val g = (c shr 5) and 63; val b = c and 31
            out[i] = (0xFF shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
        }
    }
}

/** An 8 x 8 LED matrix module, or a chain of them: round red LEDs on a dark board */
class MatrixView(context: Context, private val chips: Int) : View(context) {
    private var pixels: ByteArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(px: ByteArray?) { pixels = px?.copyOf(); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 8 / (8 * chips) + paddingTop + paddingBottom)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1A1416.toInt())
        val w = 8 * chips
        val s = width.toFloat() / w
        val p = pixels
        for (y in 0 until 8) for (x in 0 until w) {
            paint.color = Panels.ledColour(if (p != null && y * w + x < p.size) p[y * w + x].toInt() and 0xFF else 0)
            c.drawCircle((x + 0.5f) * s, (y + 0.5f) * s, s * 0.36f, paint)
        }
    }
}

/** An ST7789 panel, 240 x 240 or 240 x 320, as it shows its frame memory */
class TftView(context: Context, private val rows: Int) : View(context) {
    private val bitmap = Bitmap.createBitmap(240, rows, Bitmap.Config.ARGB_8888)
    private val argb = IntArray(240 * rows)
    private val paint = Paint().apply { isFilterBitmap = true }
    private var shown = false

    fun show(px: ByteArray?) {
        if (px == null || px.size < 240 * rows * 2) return
        Panels.rgb565(px, argb, 240 * rows)
        bitmap.setPixels(argb, 0, 240, 0, 0, 240, rows)
        shown = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * rows / 240)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF000000.toInt())
        if (shown) c.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }
}
