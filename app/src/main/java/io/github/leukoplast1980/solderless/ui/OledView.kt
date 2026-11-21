package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A 128 x 64 monochrome display as the controller lights it: blue-white
 * pixels on black, dimmer at low contrast. With [grid], each pixel is drawn
 * as a square with a gap, for the close-up.
 */
class OledView(context: Context, private val grid: Boolean = false) : View(context) {
    private val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    private val argb = IntArray(128 * 64)
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val px = Paint()
    private var pixels: ByteArray? = null
    private var contrast = 0x7F

    fun show(pixels: ByteArray?, contrast: Int) {
        this.pixels = pixels?.copyOf()
        this.contrast = contrast
        invalidate()
    }

    private fun lit(): Int {
        val a = (110 + 145 * contrast / 255).coerceIn(60, 255)
        return (a shl 24) or 0xCFE6FF
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w / 2)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF050A10.toInt())
        val p = pixels ?: return
        val on = lit()
        if (!grid) {
            for (i in 0 until 128 * 64) argb[i] = if (p[i].toInt() != 0) on else 0
            bitmap.setPixels(argb, 0, 128, 0, 0, 128, 64)
            c.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            return
        }
        val s = width / 128f
        val gap = if (s >= 3f) 1f else 0f
        for (y in 0 until 64) for (x in 0 until 128) {
            px.color = if (p[y * 128 + x].toInt() != 0) on else 0xFF101A24.toInt()
            c.drawRect(x * s, y * s, (x + 1) * s - gap, (y + 1) * s - gap, px)
        }
    }
}
