/*
 * Solderless - momentum scrolling for the editor. Android's text fields
 * only scroll while the finger moves; this adds the glide after a fling,
 * a tap that only stops the glide, and the edge effects at the ends.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.EdgeEffect
import android.widget.OverScroller
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * [sideways] tells whether the text scrolls sideways (line wrap off).
 * The owner forwards onTouchEvent (see [onTouch] and [afterTouch]),
 * computeScroll and draw.
 */
internal class EditorFling(private val view: TextView, private val sideways: () -> Boolean) {

    private val scroller = OverScroller(view.context)
    private var tracker: VelocityTracker? = null
    private val config = ViewConfiguration.get(view.context)
    private val slop = config.scaledTouchSlop
    private val minFling = config.scaledMinimumFlingVelocity
    private val maxFling = config.scaledMaximumFlingVelocity

    private val edgeTop = EdgeEffect(view.context)
    private val edgeBottom = EdgeEffect(view.context)
    private val edgeLeft = EdgeEffect(view.context)
    private val edgeRight = EdgeEffect(view.context)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downScrollX = 0
    private var downScrollY = 0
    private var maxXAtDown = 0
    private var dragging = false

    /** This gesture caught a glide: it is scrolled here, and the text field never sees it. */
    private var catching = false

    private var pendingVx = 0f
    private var pendingVy = 0f
    private var pending = false

    /** Direction of the running glide per axis, until it hits an edge, and its limits. */
    private var glideX = 0
    private var glideY = 0
    private var glideMaxX = 0
    private var glideMaxY = 0

    fun stop() {
        if (!scroller.isFinished) scroller.forceFinished(true)
    }

    /** Before the text field sees the event. True: used up here, don't pass it on. */
    fun onTouch(e: MotionEvent): Boolean {
        val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
        t.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downScrollX = view.scrollX; downScrollY = view.scrollY
                maxXAtDown = maxX()
                dragging = false
                pending = false
                catching = !scroller.isFinished
                if (catching) scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) dragging = true
                if (dragging) {
                    pull(dx, dy, e)
                    if (catching) scrollClamped(-dx, -dy)
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                release()
                val scrolled = view.scrollX != downScrollX || view.scrollY != downScrollY
                // A drag that selected text (long press, then drag) must not glide.
                if (dragging && (catching || (scrolled && !view.hasSelection()))) {
                    t.computeCurrentVelocity(1000, maxFling.toFloat())
                    pendingVx = -t.xVelocity
                    pendingVy = -t.yVelocity
                    pending = true
                }
                recycle()
                val used = catching
                catching = false
                if (used) afterTouch(e)
                return used
            }
            MotionEvent.ACTION_CANCEL -> {
                release()
                recycle()
                val used = catching
                catching = false
                return used
            }
        }
        return catching
    }

    /** After the text field handled the event: a finished fling starts gliding. */
    fun afterTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP || !pending) return
        pending = false
        val mx = maxX()
        val my = maxY()
        val vx = if (mx > 0 && abs(pendingVx) >= minFling) pendingVx else 0f
        val vy = if (my > 0 && abs(pendingVy) >= minFling) pendingVy else 0f
        if (vx == 0f && vy == 0f) return
        glideX = vx.sign.toInt()
        glideY = vy.sign.toInt()
        glideMaxX = mx
        glideMaxY = my
        scroller.fling(view.scrollX, view.scrollY, vx.toInt(), vy.toInt(), 0, mx, 0, my)
        view.postInvalidateOnAnimation()
    }

    /** From computeScroll. False when there is nothing to animate. */
    fun computeScroll(): Boolean {
        if (!scroller.computeScrollOffset()) return false
        val x = scroller.currX
        val y = scroller.currY
        view.scrollTo(x, y)
        val v = scroller.currVelocity.toInt()
        if (glideY < 0 && y <= 0) { edgeTop.onAbsorb(v); glideY = 0 }
        if (glideY > 0 && y >= glideMaxY) { edgeBottom.onAbsorb(v); glideY = 0 }
        if (glideX < 0 && x <= 0) { edgeLeft.onAbsorb(v); glideX = 0 }
        if (glideX > 0 && x >= glideMaxX) { edgeRight.onAbsorb(v); glideX = 0 }
        view.postInvalidateOnAnimation()
        return true
    }

    /** From draw(), after the text: the edge effects, in view coordinates. */
    fun drawEdges(canvas: Canvas) {
        val w = view.width
        val h = view.height
        val sx = view.scrollX.toFloat()
        val sy = view.scrollY.toFloat()
        var more = false
        more = drawEdge(canvas, edgeTop, sx, sy, 0f, w, h) or more
        more = drawEdge(canvas, edgeBottom, sx + w, sy + h, 180f, w, h) or more
        more = drawEdge(canvas, edgeLeft, sx, sy + h, 270f, h, w) or more
        more = drawEdge(canvas, edgeRight, sx + w, sy, 90f, h, w) or more
        if (more) view.postInvalidateOnAnimation()
    }

    private fun drawEdge(canvas: Canvas, edge: EdgeEffect, x: Float, y: Float, angle: Float, w: Int, h: Int): Boolean {
        if (edge.isFinished) return false
        val saved = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        edge.setSize(w, h)
        val more = edge.draw(canvas)
        canvas.restoreToCount(saved)
        return more
    }

    /** Dragging past an end stretches (or lights up) that edge. */
    private fun pull(dx: Float, dy: Float, e: MotionEvent) {
        val w = view.width.toFloat().coerceAtLeast(1f)
        val h = view.height.toFloat().coerceAtLeast(1f)
        if (maxY() > 0) {
            if (dy > 0 && view.scrollY <= 0) edgeTop.onPull(dy / h, e.x / w)
            else if (dy < 0 && !edgeTop.isFinished) edgeTop.onRelease()
            if (dy < 0 && view.scrollY >= maxY()) edgeBottom.onPull(-dy / h, 1 - e.x / w)
            else if (dy > 0 && !edgeBottom.isFinished) edgeBottom.onRelease()
        }
        if (maxXAtDown > 0) {
            if (dx > 0 && view.scrollX <= 0) edgeLeft.onPull(dx / w, 1 - e.y / h)
            else if (dx < 0 && !edgeLeft.isFinished) edgeLeft.onRelease()
            if (dx < 0 && view.scrollX >= maxXAtDown) edgeRight.onPull(-dx / w, e.y / h)
            else if (dx > 0 && !edgeRight.isFinished) edgeRight.onRelease()
        }
        view.postInvalidateOnAnimation()
    }

    private fun release() {
        edgeTop.onRelease(); edgeBottom.onRelease(); edgeLeft.onRelease(); edgeRight.onRelease()
        view.postInvalidateOnAnimation()
    }

    private fun recycle() {
        tracker?.recycle()
        tracker = null
    }

    private fun scrollClamped(dx: Float, dy: Float) {
        val x = (view.scrollX + dx.toInt()).coerceIn(0, maxXAtDown)
        val y = (view.scrollY + dy.toInt()).coerceIn(0, maxY())
        view.scrollTo(x, y)
    }

    private fun maxY(): Int {
        val lay = view.layout ?: return 0
        return max(0, lay.height - (view.height - view.totalPaddingTop - view.totalPaddingBottom))
    }

    private fun maxX(): Int {
        if (!sideways()) return 0
        val lay = view.layout ?: return 0
        var widest = 0f
        for (i in 0 until lay.lineCount) widest = max(widest, lay.getLineRight(i))
        return max(0, widest.toInt() - (view.width - view.totalPaddingLeft - view.totalPaddingRight))
    }
}
