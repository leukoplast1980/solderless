/*
 * Solderless - the Board tab's drawing: the chip's pins as two headers
 * along the long edges, the parts in between, wires in the colour of
 * their level (design section 4.6).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.board.Function
import io.github.leukoplast1980.solderless.board.HeaderOrder
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.board.Passives
import io.github.leukoplast1980.solderless.vm.BoardState
import kotlin.math.abs
import kotlin.math.sqrt

class BoardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // ---- what to draw -------------------------------------------------
    var chip: ChipPins? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var order = HeaderOrder.ADAPTER
        set(v) { field = v; invalidate() }
    var board = Board()
        set(v) { field = v; attOrder = null; invalidate() }
    var state: BoardState? = null
    /** A part being proposed: drawn dashed */
    var ghost: Part? = null
        set(v) { field = v; invalidate() }
    var highlightPins: Set<String> = emptySet()
        set(v) { field = v; invalidate() }

    // ---- what touches do ----------------------------------------------
    var onPress: (Part, Boolean) -> Unit = { _, _ -> }
    var onPot: (Part, Int) -> Unit = { _, _ -> }
    var onLongPress: (Part) -> Unit = {}
    var onPinTap: (ChipPins.Pin) -> Unit = {}
    /** Last known pot positions (per mille), drawn and dragged */
    val potValue = HashMap<Int, Int>()

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val rowH get() = dp(19f)
    private val headerW get() = dp(84f)
    private val top get() = dp(8f)
    val bottomPad get() = dp(118f)

    private val cBg = 0xFF101418.toInt(); private val cPanel = 0xFF161C21.toInt()
    private val cLine = 0xFFC8D0C4.toInt(); private val cLabel = 0xFF8A9690.toInt(); private val cText = 0xFFE8EEE4.toInt()
    private val cDimText = 0xFF56636A.toInt(); private val cDim = 0xFF3A4650.toInt(); private val cNum = 0xFF4A555C.toInt()
    private val cHigh = 0xFF8FD18F.toInt(); private val cLow = 0xFF5E6A70.toInt(); private val cPwm = 0xFFC49BE0.toInt()
    private val cAnalog = 0xFF80DEEA.toInt(); private val cWarn = 0xFFFFB454.toInt(); private val cDanger = 0xFFFF6B6B.toInt()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)

    private var attOrder: List<Triple<Int, Int, Int>>? = null
    private val pinY = HashMap<String, Float>()
    private val pinSide = HashMap<String, Int>()     // +1 left header, -1 right header
    private val hit = ArrayList<Pair<RectF, Part>>()
    private val rowHit = ArrayList<Pair<RectF, ChipPins.Pin>>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (top + 32 * rowH + bottomPad).toInt())
    }

    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val chip = chip ?: return
        canvas.drawColor(cBg)
        hit.clear(); rowHit.clear(); pinY.clear(); pinSide.clear()
        val (left, right) = chip.headers(order)
        drawHeader(canvas, left, true)
        drawHeader(canvas, right, false)
        val used = (board.parts + listOfNotNull(ghost)).flatMap { it.pins }.map { it.pin }.toSet()
        // The console adapter is always there
        drawConsole(canvas)
        // Lay parts out per side, pushed apart vertically
        val placed = ArrayList<Triple<Part, Float, Int>>()   // part, centre y, side
        for (side in listOf(1, -1)) {
            val parts = (board.parts + listOfNotNull(ghost)).filter { p -> sideOf(p) == side }
                .map { it to anchor(it) }.sortedBy { it.second }
            var floor = top
            for ((p, y) in parts) {
                val (above, below) = extent(p)
                val cy = maxOf(y, floor + above)
                placed += Triple(p, cy, side)
                floor = cy + below + dp(6f)
            }
        }
        for ((p, cy, side) in placed) drawPart(canvas, p, cy, side, p === ghost)
        if (used.isEmpty() && ghost == null) {
            text.textSize = dp(13f); text.color = cLabel; text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            canvas.drawText("Add parts with + Part", width / 2f, top + 8 * rowH, text)
            text.typeface = Typeface.MONOSPACE
        }
    }

    private fun drawHeader(c: Canvas, pins: List<ChipPins.Pin>, left: Boolean) {
        val x0 = if (left) 0f else width - headerW
        fill.color = cPanel
        c.drawRoundRect(RectF(x0, top - dp(4f), x0 + headerW, top + 32 * rowH + dp(4f)), dp(6f), dp(6f), fill)
        val used = board.parts.flatMap { it.pins }.map { it.pin }.toSet()
        for ((i, p) in pins.withIndex()) {
            val y = top + (i + 0.5f) * rowH
            pinY[p.name] = y
            pinSide[p.name] = if (left) 1 else -1
            val power = !p.isGpio
            val isUsed = p.name in used || p.name == "PA2" || p.name == "PA3"
            text.typeface = Typeface.MONOSPACE
            text.textSize = dp(7.5f); text.color = cNum
            text.textAlign = if (left) Paint.Align.LEFT else Paint.Align.RIGHT
            c.drawText(p.number.toString(), if (left) x0 + dp(4f) else x0 + headerW - dp(4f), y + dp(3f), text)
            text.textSize = if (power) dp(9f) else dp(10.5f)
            text.color = if (isUsed) cText else if (power) cNum else cDimText
            text.isFakeBoldText = isUsed
            text.textAlign = Paint.Align.LEFT
            c.drawText(p.name, if (left) x0 + dp(19f) else x0 + dp(18f), y + dp(3.5f), text)
            text.isFakeBoldText = false
            val px = if (left) x0 + headerW - dp(11.5f) else x0 + dp(4.5f)
            if (p.name in highlightPins) {
                stroke.color = cHigh; stroke.pathEffect = null
                c.drawCircle(px + dp(3.5f), y, dp(7f), stroke)
            }
            when {
                power -> { fill.color = cDim; c.drawRect(px + dp(1.5f), y - dp(2f), px + dp(5.5f), y + dp(2f), fill) }
                isUsed -> { fill.color = wireColour(p.name); c.drawRect(px, y - dp(3.5f), px + dp(7f), y + dp(3.5f), fill) }
                else -> { stroke.color = cDim; stroke.strokeWidth = dp(1.1f); stroke.pathEffect = null
                    c.drawRect(px, y - dp(3.5f), px + dp(7f), y + dp(3.5f), stroke); stroke.strokeWidth = dp(1.6f) }
            }
            if (p.name == "PA13" || p.name == "PA14") {
                text.textSize = dp(7.5f); text.color = cDimText
                text.textAlign = if (left) Paint.Align.LEFT else Paint.Align.RIGHT
                c.drawText("SWD", if (left) x0 + headerW + dp(3f) else x0 - dp(3f), y + dp(3f), text)
            }
            rowHit += RectF(x0, y - rowH / 2, x0 + headerW, y + rowH / 2) to p
        }
    }

    /** Where wires leave a pin, and which way is inward */
    private fun padX(pin: String): Pair<Float, Int> {
        val s = pinSide[pin] ?: 1
        return (if (s > 0) headerW - dp(4f) else width - headerW + dp(4f)) to s
    }

    private fun slot(pin: String) = chip?.gpio(pin)?.slot ?: -1

    private fun wireColour(pin: String): Int {
        val st = state ?: return cLow
        val s = slot(pin)
        if (s < 0) return cLow
        return when {
            st.shorted(s) -> cDanger
            st.pwm(s) -> cPwm
            st.floating(s) || st.undefined(s) -> cWarn
            st.mode(s) == BoardState.MODE_ANALOG -> cAnalog
            st.high(s) -> cHigh
            else -> cLow
        }
    }

    private fun sideOf(p: Part): Int {
        val sides = p.pins.mapNotNull { pinSide[it.pin] }
        return if (sides.count { it < 0 } > sides.size / 2) -1 else 1
    }

    private fun anchor(p: Part): Float {
        val ys = p.pins.mapNotNull { pinY[it.pin] }
        return if (ys.isEmpty()) top else ys.average().toFloat()
    }

    private fun extent(p: Part): Pair<Float, Float> = when (p.type) {
        PartType.RGB_LED -> dp(52f) to dp(52f)
        PartType.POT -> dp(42f) to dp(42f)
        else -> dp(26f) to dp(24f)
    }

    // ------------------------------------------------------------------
    // Parts

    private fun setStroke(colour: Int, dashed: Boolean, w: Float = 1.6f) {
        stroke.color = colour; stroke.strokeWidth = dp(w); stroke.pathEffect = if (dashed) dash else null
    }

    private fun wire(c: Canvas, colour: Int, dashed: Boolean, vararg pts: Float) {
        setStroke(colour, dashed)
        val path = Path(); path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        c.drawPath(path, stroke)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) {
        text.textSize = dp(size); text.color = colour; text.textAlign = align; text.isFakeBoldText = bold
        c.drawText(s, x, y, text)
        text.isFakeBoldText = false
    }

    private fun gnd(c: Canvas, x: Float, y: Float, colour: Int, dashed: Boolean) {
        setStroke(colour, dashed, 1.4f)
        c.drawLine(x, y, x, y + dp(5f), stroke)
        for ((i, w) in listOf(14f, 9f, 4f).withIndex()) c.drawLine(x - dp(w / 2), y + dp(5f + i * 3.5f), x + dp(w / 2), y + dp(5f + i * 3.5f), stroke)
    }

    private fun vcc(c: Canvas, x: Float, y: Float, colour: Int, dashed: Boolean) {
        setStroke(colour, dashed, 1.4f)
        c.drawLine(x, y, x, y - dp(6f), stroke)
        c.drawLine(x - dp(7f), y - dp(6f), x + dp(7f), y - dp(6f), stroke)
        label(c, "3V3", x, y - dp(10f), 8f, cLabel)
    }

    private fun resistorH(c: Canvas, x0: Float, x1: Float, y: Float, value: String?, colour: Int, dashed: Boolean) {
        val l = minOf(x0, x1); val r = maxOf(x0, x1)
        fill.color = cBg; c.drawRect(l, y - dp(4.5f), r, y + dp(4.5f), fill)
        setStroke(colour, dashed, 1.4f); c.drawRect(l, y - dp(4.5f), r, y + dp(4.5f), stroke)
        if (value != null) label(c, value, (l + r) / 2, y - dp(7.5f), 8.5f, cLabel)
    }

    private fun ledSymbol(c: Canvas, x: Float, y: Float, sg: Int, colour: Int, brightness: Float, dashed: Boolean, ghostCol: Int?) {
        val st = ghostCol ?: if (brightness > 0.02f) colour else cLine
        if (brightness > 0.02f && ghostCol == null) {
            glow.shader = RadialGradient(x + sg * dp(7f), y, dp(16f), (colour and 0x00FFFFFF) or ((0xA0 * brightness).toInt() shl 24),
                colour and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(x + sg * dp(7f), y, dp(16f), glow)
        }
        val tri = Path().apply { moveTo(x, y - dp(8f)); lineTo(x, y + dp(8f)); lineTo(x + sg * dp(14f), y); close() }
        fill.color = if (brightness > 0.02f && ghostCol == null) blend(cBg, colour, 0.35f + 0.65f * brightness) else cBg
        if (ghostCol == null) c.drawPath(tri, fill)
        setStroke(st, dashed, 1.4f); c.drawPath(tri, stroke)
        c.drawLine(x + sg * dp(14f), y - dp(8f), x + sg * dp(14f), y + dp(8f), stroke)
        setStroke(st, dashed, 1f)
        c.drawLine(x + sg * dp(5f), y - dp(11f), x + sg * dp(10f), y - dp(16f), stroke)
        c.drawLine(x + sg * dp(10f), y - dp(11f), x + sg * dp(15f), y - dp(16f), stroke)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private fun brightness(p: Part, role: Int): Float {
        val st = state ?: return 0f
        val ord = attOrder ?: board.attachmentOrder(chip!!).also { attOrder = it }
        val i = ord.indexOfFirst { it.first == p.id && it.second == role && it.third == Board.ATT_LED }
        if (i < 0) return 0f
        val ua = st.currentUa(i)
        return sqrt((ua / (p.currentMa * 1000f)).coerceIn(0f, 1f))
    }

    /** Wire from a pin to a terminal at (tx, ty), via a jog just off the header. */
    private fun feed(c: Canvas, pin: String, tx: Float, ty: Float, dashed: Boolean, colour: Int? = null) {
        val (x0, sg) = padX(pin)
        val y = pinY[pin] ?: return
        val col = colour ?: wireColour(pin)
        if (abs(y - ty) < 0.5f) wire(c, col, dashed, x0, y, tx, ty)
        else wire(c, col, dashed, x0, y, x0 + sg * dp(9f), y, x0 + sg * dp(9f), ty, tx, ty)
    }

    private fun drawPart(c: Canvas, p: Part, cy: Float, side: Int, isGhost: Boolean) {
        val chip = chip ?: return
        val x0 = if (side > 0) headerW - dp(4f) else width - headerW + dp(4f)
        val sg = side
        val gc = if (isGhost) cHigh else null
        val line = gc ?: cLine
        val dashed = isGhost
        val pin0 = p.pins.firstOrNull()?.pin ?: return
        val box = RectF()
        val labelCol = gc ?: cText
        fun warnUnder(x: Float, y: Float, pin: String) {
            val st = state ?: return
            val s = slot(pin)
            if (s < 0 || isGhost) return
            when {
                st.shorted(s) -> label(c, "▲ short", x, y, 8.5f, cDanger, bold = true)
                (st.floating(s) || st.undefined(s)) && st.mode(s) == BoardState.MODE_INPUT -> label(c, "▲ floating", x, y, 8.5f, cWarn, bold = true)
            }
        }
        when (p.type) {
            PartType.LED -> {
                val r = Passives.format(Passives.ledResistor(p.vfMv(0), p.currentMa))
                feed(c, pin0, x0 + sg * dp(16f), cy, dashed, gc)
                resistorH(c, x0 + sg * dp(16f), x0 + sg * dp(40f), cy, r, line, dashed)
                wire(c, gc ?: wireColour(pin0), dashed, x0 + sg * dp(40f), cy, x0 + sg * dp(50f), cy)
                val lit = if (isGhost) 0f else brightness(p, 0)
                val dir = if (p.activeHigh) sg else -sg
                val lx = if (p.activeHigh) x0 + sg * dp(50f) else x0 + sg * dp(64f)
                ledSymbol(c, lx, cy, dir, p.colour.argb.toInt(), lit, dashed, gc)
                val end = x0 + sg * dp(80f)
                wire(c, gc ?: cLabel, dashed, x0 + sg * dp(64f), cy, end, cy, end, cy + dp(6f))
                if (p.activeHigh) gnd(c, end, cy + dp(6f), gc ?: cLabel, dashed)
                else { wire(c, gc ?: cLabel, dashed, end, cy, end, cy - dp(4f)); vcc(c, end, cy - dp(4f), gc ?: cLabel, dashed) }
                label(c, "LED", x0 + sg * dp(57f), cy - dp(15f), 10f, labelCol, bold = true)
                box.set(x0, cy - dp(26f), x0 + sg * dp(90f), cy + dp(24f))
            }
            PartType.BUTTON -> {
                val a = x0 + sg * dp(20f); val b = x0 + sg * dp(52f); val m = (a + b) / 2
                val pressed = !isGhost && pressedParts.contains(p.id)
                feed(c, pin0, a, cy, dashed, gc)
                fill.color = cBg; setStroke(line, dashed, 1.2f)
                c.drawCircle(a, cy, dp(2.2f), fill); c.drawCircle(a, cy, dp(2.2f), stroke)
                c.drawCircle(b, cy, dp(2.2f), fill); c.drawCircle(b, cy, dp(2.2f), stroke)
                val by = if (pressed) cy - dp(2.5f) else cy - dp(8f)
                setStroke(if (pressed) cHigh else line, dashed)
                c.drawLine(a - sg * dp(2f), by, b + sg * dp(2f), by, stroke)
                c.drawLine(m, by, m, by - dp(7f), stroke)
                c.drawLine(m - dp(5f), by - dp(7f), m + dp(5f), by - dp(7f), stroke)
                val end = b + sg * dp(22f)
                if (p.toVcc) { wire(c, gc ?: cLabel, dashed, b, cy, end, cy, end, cy - dp(4f)); vcc(c, end, cy - dp(4f), gc ?: cLabel, dashed) }
                else { wire(c, gc ?: cLabel, dashed, b, cy, end, cy, end, cy + dp(6f)); gnd(c, end, cy + dp(6f), gc ?: cLabel, dashed) }
                label(c, "Button", m, cy - dp(19f), 10f, labelCol, bold = true)
                warnUnder(m, cy + dp(17f), pin0)
                box.set(x0, cy - dp(28f), end + sg * dp(10f), cy + dp(24f))
            }
            PartType.POT -> {
                val px = x0 + sg * dp(58f)
                val v = potValue[p.id] ?: 500
                feed(c, pin0, px - sg * dp(8f), cy, dashed, gc)
                fill.color = gc ?: cAnalog
                val arrow = Path().apply { moveTo(px - sg * dp(6f), cy); lineTo(px - sg * dp(12f), cy - dp(3.5f)); lineTo(px - sg * dp(12f), cy + dp(3.5f)); close() }
                c.drawPath(arrow, fill)
                fill.color = cBg; c.drawRect(px - dp(5f), cy - dp(19f), px + dp(5f), cy + dp(19f), fill)
                setStroke(line, dashed, 1.4f); c.drawRect(px - dp(5f), cy - dp(19f), px + dp(5f), cy + dp(19f), stroke)
                if (!isGhost) { fill.color = cAnalog; val ky = cy + dp(19f) - dp(38f) * v / 1000f; c.drawRect(px - dp(5f), ky - dp(1.5f), px + dp(5f), ky + dp(1.5f), fill) }
                wire(c, gc ?: cLabel, dashed, px, cy - dp(19f), px, cy - dp(26f)); vcc(c, px, cy - dp(26f), gc ?: cLabel, dashed)
                wire(c, gc ?: cLabel, dashed, px, cy + dp(19f), px, cy + dp(24f)); gnd(c, px, cy + dp(24f), gc ?: cLabel, dashed)
                label(c, "Pot", px + sg * dp(12f), cy + dp(3.5f), 10f, labelCol, if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT, true)
                val st = state; val s = slot(pin0)
                if (st != null && s >= 0 && !isGhost && st.mv(s) >= 0)
                    label(c, "%.2f V".format(st.mv(s) / 1000f), (x0 + px) / 2 - sg * dp(2f), cy - dp(6f), 8.5f, cAnalog)
                box.set(x0, cy - dp(40f), px + sg * dp(40f), cy + dp(40f))
            }
            PartType.PIEZO, PartType.SPEAKER -> {
                val p0 = x0 + sg * dp(30f)
                feed(c, pin0, p0, cy, dashed, gc)
                val xl = minOf(p0, p0 + sg * dp(16f))
                fill.color = cBg; c.drawRect(xl, cy - dp(9f), xl + dp(16f), cy + dp(9f), fill)
                setStroke(line, dashed, 1.4f); c.drawRoundRect(RectF(xl, cy - dp(9f), xl + dp(16f), cy + dp(9f)), dp(2f), dp(2f), stroke)
                if (p.type == PartType.PIEZO) {
                    c.drawLine(xl + dp(5f), cy - dp(5f), xl + dp(5f), cy + dp(5f), stroke)
                    c.drawLine(xl + dp(11f), cy - dp(5f), xl + dp(11f), cy + dp(5f), stroke)
                } else {
                    val cone = Path().apply { moveTo(xl + dp(4f), cy - dp(3f)); lineTo(xl + dp(7f), cy - dp(3f)); lineTo(xl + dp(12f), cy - dp(7f))
                        lineTo(xl + dp(12f), cy + dp(7f)); lineTo(xl + dp(7f), cy + dp(3f)); lineTo(xl + dp(4f), cy + dp(3f)); close() }
                    c.drawPath(cone, stroke)
                }
                val e = p0 + sg * dp(16f)
                wire(c, gc ?: cLabel, dashed, e, cy, e + sg * dp(16f), cy, e + sg * dp(16f), cy + dp(6f)); gnd(c, e + sg * dp(16f), cy + dp(6f), gc ?: cLabel, dashed)
                label(c, if (p.type == PartType.PIEZO) "Piezo" else "Speaker", xl + dp(8f), cy - dp(14f), 10f, labelCol, bold = true)
                if (p.type == PartType.SPEAKER) label(c, "NPN stage", xl + dp(8f), cy + dp(30f), 7.5f, cLabel)
                val st = state; val s = slot(pin0)
                if (st != null && s >= 0 && st.pwm(s) && !isGhost)
                    label(c, "%d Hz".format(st.freqMilliHz(s) / 1000), xl + dp(8f), cy + dp(21f), 8.5f, cPwm)
                box.set(x0, cy - dp(26f), e + sg * dp(26f), cy + dp(34f))
            }
            PartType.RGB_LED -> {
                val rows = listOf(cy - dp(30f), cy, cy + dp(30f))
                val cols = listOf(0xFFFF6B6B.toInt(), 0xFF6FCF7F.toInt(), 0xFF7FB8FF.toInt())
                var mixR = 0f; var mixG = 0f; var mixB = 0f
                for (r in 0..2) {
                    val pin = p.pins.getOrNull(r)?.pin ?: continue
                    val ry = rows[r]
                    feed(c, pin, x0 + sg * dp(22f), ry, dashed, gc)
                    resistorH(c, x0 + sg * dp(22f), x0 + sg * dp(46f), ry, Passives.format(Passives.ledResistor(p.vfMv(r), p.currentMa)), line, dashed)
                    wire(c, gc ?: wireColour(pin), dashed, x0 + sg * dp(46f), ry, x0 + sg * dp(56f), ry)
                    val lit = if (isGhost) 0f else brightness(p, r)
                    when (r) { 0 -> mixR = lit; 1 -> mixG = lit; else -> mixB = lit }
                    ledSymbol(c, x0 + sg * dp(56f), ry, sg, cols[r], lit, dashed, gc)
                    wire(c, gc ?: cLabel, dashed, x0 + sg * dp(70f), ry, x0 + sg * dp(82f), ry)
                }
                wire(c, gc ?: cLabel, dashed, x0 + sg * dp(82f), rows[0], x0 + sg * dp(82f), rows[2] + dp(10f))
                gnd(c, x0 + sg * dp(82f), rows[2] + dp(10f), gc ?: cLabel, dashed)
                label(c, "RGB LED", x0 + sg * dp(63f), rows[0] - dp(20f), 10f, labelCol, bold = true)
                if (!isGhost && mixR + mixG + mixB > 0.05f) {
                    val col = (0xFF shl 24) or ((255 * mixR).toInt() shl 16) or ((255 * mixG).toInt() shl 8) or (255 * mixB).toInt()
                    fill.color = col; c.drawCircle(x0 + sg * dp(104f), cy, dp(7f), fill)
                    label(c, "mix", x0 + sg * dp(104f), cy + dp(20f), 8f, cLabel)
                }
                box.set(x0, rows[0] - dp(30f), x0 + sg * dp(115f), rows[2] + dp(26f))
            }
        }
        box.sort()
        if (!isGhost) hit += box to p
    }

    private fun drawConsole(c: Canvas) {
        val y2 = pinY["PA2"] ?: return
        val y3 = pinY["PA3"] ?: return
        val (x0, sg) = padX("PA2")
        val bx = x0 + sg * dp(30f)
        val bw = dp(76f)
        val t = minOf(y2, y3) - dp(16f); val b = maxOf(y2, y3) + dp(30f)
        if (pinSide["PA3"] == pinSide["PA2"]) {
            wire(c, wireColour("PA2"), false, x0, y2, bx, y2)
            wire(c, wireColour("PA3"), false, x0, y3, bx, y3)
        }
        val l = minOf(bx, bx + sg * bw)
        fill.color = cPanel; c.drawRoundRect(RectF(l, t, l + bw, b), dp(5f), dp(5f), fill)
        setStroke(cLine, false, 1.4f); c.drawRoundRect(RectF(l, t, l + bw, b), dp(5f), dp(5f), stroke)
        val al = if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT
        label(c, "RX", bx + sg * dp(6f), y2 + dp(3.5f), 8.5f, cLabel, al)
        label(c, "TX", bx + sg * dp(6f), y3 + dp(3.5f), 8.5f, cLabel, al)
        text.typeface = Typeface.DEFAULT
        label(c, "USB-serial", l + bw / 2, b - dp(9f), 10f, cText, bold = true)
        text.typeface = Typeface.MONOSPACE
    }

    // ------------------------------------------------------------------
    // Touch

    private val pressedParts = HashSet<Int>()
    private var dragPart: Part? = null
    private var dragStartY = 0f
    private var dragStartValue = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            val p = partAt(e.x, e.y) ?: return
            if (p.type == PartType.BUTTON) return            // holding a button is pressing it
            dragPart = null
            onLongPress(p)
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (partAt(e.x, e.y) == null) rowHit.firstOrNull { it.first.contains(e.x, e.y) }?.let { onPinTap(it.second) }
            return true
        }
    })

    private fun partAt(x: Float, y: Float): Part? = hit.lastOrNull { it.first.contains(x, y) }?.second

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = partAt(e.x, e.y) ?: return true
                when (p.type) {
                    PartType.BUTTON -> { pressedParts += p.id; onPress(p, true); invalidate(); parent?.requestDisallowInterceptTouchEvent(true) }
                    PartType.POT -> { dragPart = p; dragStartY = e.y; dragStartValue = potValue[p.id] ?: 500; parent?.requestDisallowInterceptTouchEvent(true) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> dragPart?.let { p ->
                val v = (dragStartValue + ((dragStartY - e.y) / dp(120f) * 1000)).toInt().coerceIn(0, 1000)
                if (v != potValue[p.id]) { potValue[p.id] = v; onPot(p, v); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (id in pressedParts) board.parts.firstOrNull { it.id == id }?.let { onPress(it, false) }
                pressedParts.clear()
                dragPart = null
                invalidate()
            }
        }
        return true
    }

    /** A pinned button pressed elsewhere: draw it pressed here too. */
    fun setPressed(id: Int, down: Boolean) { if (down) pressedParts += id else pressedParts -= id; invalidate() }
}
