package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import io.github.leukoplast1980.solderless.R
import kotlin.math.abs

/**
 * A pinned pot in the floating card: a pill filled as far as the pot is
 * turned, with its name and voltage. Dragging along it turns the pot (the
 * finger's place is the position); a tap opens the wide slider.
 */
class PotPill(context: Context) : android.view.View(context) {
    /** 0..1000 */
    var value = 500
        set(v) { val c = v.coerceIn(0, 1000); if (c != field) { field = c; invalidate() } }
    var name = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    /** Whether the wide slider is open (the arrow points down to it) */
    var open = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var onChange: (Int) -> Unit = {}
    var onTap: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val analog = ContextCompat.getColor(context, R.color.analog)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = analog }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (analog and 0xFFFFFF) or (0x5C shl 24) }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        color = ContextCompat.getColor(context, R.color.bar_text)
    }
    private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        color = analog; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.RIGHT
    }
    private val box = RectF()
    private val clip = Path()

    init {
        isFocusable = true
        isClickable = true
        minimumWidth = (168 * d).toInt()
    }

    private fun volts() = "%.2f V".format(java.util.Locale.ROOT, value * 3.3 / 1000)

    override fun onDraw(c: Canvas) {
        val i = stroke.strokeWidth / 2
        box.set(i, i, width - i, height - i)
        val r = box.height() / 2
        clip.reset(); clip.addRoundRect(box, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, width * value / 1000f, height.toFloat(), fill)
        c.restore()
        c.drawRoundRect(box, r, r, stroke)
        val base = height / 2f - (nameP.descent() + nameP.ascent()) / 2
        c.drawText(name, 14 * d, base, nameP)
        c.drawText(volts() + if (open) " ▾" else " ▴", width - 12 * d, base, valueP)
    }

    // --- touch: drag turns, tap opens the slider ---------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false

    private fun valueAt(x: Float): Int {
        val inset = height / 2f                     // the round ends: 0 and 1000 are reachable
        return (((x - inset) / (width - 2 * inset)) * 1000).toInt().coerceIn(0, 1000)
    }

    @SuppressLint("ClickableViewAccessibility")       // a tap goes through performClick
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; dragging = false; isPressed = true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragging) { val v = valueAt(e.x); if (v != value) { value = v; onChange(v) } }
            }
            MotionEvent.ACTION_UP -> { isPressed = false; if (!dragging) performClick() }
            MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }

    // --- accessibility: a range, stepped by 5 % ---------------------------

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, 1000f, value.toFloat())
        if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = volts()
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val by = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 50
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -50
            else -> return super.performAccessibilityAction(action, args)
        }
        value += by; onChange(value)
        return true
    }
}
