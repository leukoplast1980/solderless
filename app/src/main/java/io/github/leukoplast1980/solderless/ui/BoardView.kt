/*
 * Solderless - the Board tab's drawing: the chip's pins as two headers
 * along the long edges, the parts in between, wires in the colour of
 * their level (design section 4.6).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
        set(v) { field = v; attOrder = null; requestLayout(); invalidate() }
    /** Spread the header rows when parts need room (a setting) */
    var autoSpread = true
        set(v) { field = v; requestLayout(); invalidate() }
    var state: BoardState? = null
    /** A part being proposed: drawn dashed */
    var ghost: Part? = null
        set(v) { field = v; invalidate() }
    var highlightPins: Set<String> = emptySet()
        set(v) { field = v; invalidate() }

    // ---- what touches do ----------------------------------------------
    var onPress: (Part, Boolean) -> Unit = { _, _ -> }
    /** An encoder turned by some detents, clockwise when positive */
    var onTurn: (Part, Int) -> Unit = { _, _ -> }
    /** What an encoder's timer counts now, as the board shows it */
    val encoderCounts = HashMap<Int, Long>()
    var onPot: (Part, Int) -> Unit = { _, _ -> }
    var onLongPress: (Part) -> Unit = {}
    /** A tap on a part that does not react to touch (not a button or a pot), or on a button's label */
    var onTap: (Part) -> Unit = {}
    var onPinTap: (ChipPins.Pin) -> Unit = {}
    /** Last known pot positions (per mille), drawn and dragged */
    val potValue = HashMap<Int, Int>()
    /** Displays: lit pixels (128 x 64, one byte each) and contrast, by part id; the controller fills them */
    val devicePixels = HashMap<Int, ByteArray>()
    val deviceContrast = HashMap<Int, Int>()
    private val oledBitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    private val oledRow = IntArray(128 * 64)
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val baseRow get() = dp(19f)
    private var rowH = 0f
    private val headerW get() = dp(84f)
    private val top get() = dp(8f)
    val bottomPad get() = dp(118f)

    private val cBg = 0xFF101418.toInt(); private val cPanel = 0xFF161C21.toInt()
    private val cLine = 0xFFC8D0C4.toInt(); private val cLabel = 0xFF8A9690.toInt(); private val cText = 0xFFE8EEE4.toInt()
    private val cDimText = 0xFF56636A.toInt(); private val cDim = 0xFF3A4650.toInt(); private val cNum = 0xFF4A555C.toInt()
    private val cHigh = 0xFF8FD18F.toInt(); private val cLow = 0xFF5E6A70.toInt(); private val cPwm = 0xFFC49BE0.toInt()
    private val cAnalog = 0xFF80DEEA.toInt(); private val cWarn = 0xFFFFB454.toInt(); private val cDanger = 0xFFFF6B6B.toInt()
    private val cBus = 0xFFE6B673.toInt()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)

    private var attOrder: List<Triple<Int, Int, Int>>? = null
    private val pinY = HashMap<String, Float>()
    private val pinSide = HashMap<String, Int>()     // +1 left header, -1 right header
    private val hit = ArrayList<Pair<RectF, Part>>()
    /** A button's label: long-press there for its settings (the contacts press it) */
    private val hitLabel = ArrayList<Pair<RectF, Part>>()
    private val rowHit = ArrayList<Pair<RectF, ChipPins.Pin>>()

    /** The view's width as placement sees it (onMeasure runs before width is set) */
    private var boardW = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        boardW = w.toFloat()
        rowH = baseRow
        if (autoSpread && chip != null) {
            // Smallest row height at which every part sits next to its pins
            var r = baseRow
            while (r < dp(56f)) {
                computePins(r)
                val placed = placeParts()
                val worst = placed.filter { it.first.id !in sidestepped }
                    .maxOfOrNull { (p, cy, _) -> abs(cy - anchor(p)) } ?: 0f
                val bottom = placed.maxOfOrNull { (p, cy, _) -> cy + extent(p).second } ?: 0f
                if (worst <= r * 1.2f && bottom <= top + 32 * r + dp(8f)) break
                r += dp(1f)
            }
            rowH = r
        }
        setMeasuredDimension(w, (top + 32 * rowH + bottomPad).toInt())
    }

    /** Pin rows and sides for a row height (the headers draw the same) */
    private fun computePins(r: Float) {
        val (left, right) = chip?.headers(order) ?: return
        for ((i, p) in left.withIndex()) { pinY[p.name] = top + (i + 0.5f) * r; pinSide[p.name] = 1 }
        for ((i, p) in right.withIndex()) { pinY[p.name] = top + (i + 0.5f) * r; pinSide[p.name] = -1 }
    }

    /** How far a part reaches in from its header */
    private fun widthOf(p: Part): Float = when (p.type) {
        PartType.OLED -> dp(30f) + minOf(dp(146f), boardW - 2 * headerW - dp(36f))
        PartType.RGB_LED -> dp(124f)
        PartType.POT -> dp(100f)
        PartType.LED -> dp(92f)
        PartType.BUTTON -> dp(86f)
        PartType.PIEZO, PartType.SPEAKER -> dp(72f)
        PartType.FLASH -> dp(124f)
        PartType.MATRIX -> dp(30f) + matrixWidth(p)
        PartType.BARO, PartType.CO2 -> dp(30f) + dp(74f) + dp(64f)
        PartType.ENCODER -> dp(30f) + dp(96f)
        PartType.TFT -> dp(30f) + tftWidth()
    }

    /** A 4-in-1 matrix as wide as fits; a single 8 x 8 a quarter of that */
    private fun matrixWidth(p: Part): Float {
        val full = minOf(dp(150f), boardW - 2 * headerW - dp(36f))
        return if (p.matrixChips == 1) full * 0.45f else full
    }

    private fun tftWidth(): Float = minOf(dp(120f), boardW - 2 * headerW - dp(36f))

    /** Parts placement moved off their pins to keep clear of the other side or the
     * USB-serial strip; a taller row would not bring them back, so spreading ignores them */
    private val sidestepped = HashSet<Int>()

    /** Parts per side, each centred on its pins, pushed apart vertically. A part
     * reaching far in also keeps clear of what the other side has at that height,
     * and every part of the USB-serial strip at PA2/PA3: it goes above or below,
     * whichever is nearer its pins. */
    private fun placeParts(): List<Triple<Part, Float, Int>> {
        if (boardW == 0f) boardW = width.toFloat()
        sidestepped.clear()
        val placed = ArrayList<Triple<Part, Float, Int>>()
        val inner = boardW - 2 * headerW
        // (top, bottom, reach) of what is drawn, per side
        val taken = mapOf(1 to ArrayList<Triple<Float, Float, Float>>(), -1 to ArrayList())
        val y2 = pinY["PA2"]; val y3 = pinY["PA3"]
        val strip = if (y2 != null && y3 != null)
            Triple(minOf(y2, y3) - dp(14f), maxOf(y2, y3) + dp(14f), dp(62f)) else null
        val stripSide = pinSide["PA2"] ?: 1
        for (side in listOf(1, -1)) {
            val parts = (board.parts + listOfNotNull(ghost)).filter { p -> sideOf(p) == side }
                .map { it to anchor(it) }.sortedBy { it.second }
            var floor = top
            val anchors = ArrayList<Float>()
            for ((p, y) in parts) {
                val (above, below) = extent(p)
                val w = widthOf(p)
                // parts on the same bus pins cannot all sit at them: taller rows would not help
                if (anchors.any { kotlin.math.abs(it - y) < 1f }) sidestepped += p.id
                anchors += y
                val blocks = taken[-side]!!.filter { it.third + w > inner - dp(6f) }.toMutableList()
                if (strip != null && (side == stripSide || strip.third + w > inner - dp(6f))) blocks += strip
                blocks.sortBy { it.first }
                var cy = maxOf(y, floor + above)
                for (b in blocks) {
                    if (cy - above >= b.second || cy + below <= b.first) continue
                    val up = b.first - below - dp(6f)
                    val down = b.second + above + dp(6f)
                    cy = if (up - above >= floor && abs(up - y) < abs(down - y)) up else down
                    sidestepped += p.id
                }
                placed += Triple(p, cy, side)
                taken[side]!! += Triple(cy - above, cy + below, w)
                floor = cy + below + dp(6f)
            }
        }
        return placed
    }

    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val chip = chip ?: return
        canvas.drawColor(cBg)
        if (rowH == 0f) rowH = baseRow
        hit.clear(); hitLabel.clear(); knobs.clear(); rowHit.clear(); pinY.clear(); pinSide.clear()
        val (left, right) = chip.headers(order)
        drawHeader(canvas, left, true)
        drawHeader(canvas, right, false)
        val used = (board.parts + listOfNotNull(ghost)).flatMap { it.pins }.map { it.pin }.toSet()
        // The console adapter is always there
        drawConsole(canvas)
        for ((p, cy, side) in placeParts()) drawPart(canvas, p, cy, side, p === ghost)
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
        PartType.OLED -> dp(56f) to dp(52f)
        PartType.FLASH -> dp(58f) to dp(50f)
        PartType.MATRIX -> dp(58f) to dp(50f)
        PartType.BARO -> dp(52f) to dp(40f)
        PartType.ENCODER -> dp(46f) to dp(52f)
        PartType.CO2 -> dp(44f) to dp(32f)
        PartType.TFT -> if (p.tftRows == 320) dp(98f) to dp(94f) else dp(80f) to dp(76f)
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
        val st = ghostCol ?: if (brightness > 0.02f) colour else blend(cLine, colour, 0.45f)
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
    private fun feed(c: Canvas, pin: String, tx: Float, ty: Float, dashed: Boolean, colour: Int? = null, jog: Float = 9f) {
        val (x0, sg) = padX(pin)
        val y = pinY[pin] ?: return
        val col = colour ?: wireColour(pin)
        if (abs(y - ty) < 0.5f) wire(c, col, dashed, x0, y, tx, ty)
        else wire(c, col, dashed, x0, y, x0 + sg * dp(jog), y, x0 + sg * dp(jog), ty, tx, ty)
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
                box.set(x0, cy - dp(16f), end + sg * dp(10f), cy + dp(24f))
                if (!isGhost) hitLabel += RectF(m - dp(34f), cy - dp(42f), m + dp(34f), cy - dp(16f)) to p
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
                    label(c, "%.2f V".format(java.util.Locale.ROOT, st.mv(s) / 1000f), (x0 + px) / 2 - sg * dp(2f), cy - dp(6f), 8.5f, cAnalog)
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
                    label(c, "%d Hz".format(java.util.Locale.ROOT, st.freqMilliHz(s) / 1000), xl + dp(8f), cy + dp(21f), 8.5f, cPwm)
                box.set(x0, cy - dp(26f), e + sg * dp(26f), cy + dp(34f))
            }
            PartType.RGB_LED -> {
                val rows = listOf(cy - dp(30f), cy, cy + dp(30f))
                val cols = listOf(0xFFFF6B6B.toInt(), 0xFF6FCF7F.toInt(), 0xFF7FB8FF.toInt())
                var mixR = 0f; var mixG = 0f; var mixB = 0f
                // Rows follow the order of the pins on the header, so no two wires cross
                val roles = (0..2).filter { p.pins.getOrNull(it) != null }
                fun py(r: Int) = pinY[p.pins[r].pin] ?: cy
                val rowOf = HashMap<Int, Float>()
                roles.sortedBy { py(it) }.forEachIndexed { i, r -> rowOf[r] = rows[i] }
                // Each wire turns in a column of its own: of the wires going down, the
                // one starting highest turns farthest in; going up, the lowest one.
                val jog = HashMap<Int, Float>()
                roles.filter { rowOf[it]!! - py(it) > 0.5f }.sortedByDescending { py(it) }
                    .forEachIndexed { k, r -> jog[r] = 6f + 5f * k }
                roles.filter { rowOf[it]!! - py(it) < -0.5f }.sortedBy { py(it) }
                    .forEachIndexed { k, r -> jog[r] = 6f + 5f * k }
                for (r in roles) {
                    val pin = p.pins[r].pin
                    val ry = rowOf[r]!!
                    feed(c, pin, x0 + sg * dp(22f), ry, dashed, gc, jog[r] ?: 9f)
                    resistorH(c, x0 + sg * dp(22f), x0 + sg * dp(46f), ry, Passives.format(Passives.ledResistor(p.vfMv(r), p.currentMa)), line, dashed)
                    wire(c, gc ?: wireColour(pin), dashed, x0 + sg * dp(46f), ry, x0 + sg * dp(56f), ry)
                    val lit = if (isGhost) 0f else brightness(p, r)
                    when (r) { 0 -> mixR = lit; 1 -> mixG = lit; else -> mixB = lit }
                    ledSymbol(c, x0 + sg * dp(56f), ry, sg, cols[r], lit, dashed, gc)
                    wire(c, gc ?: cLabel, dashed, x0 + sg * dp(70f), ry, x0 + sg * dp(82f), ry)
                    // which colour this wire drives
                    label(c, "RGB"[r].toString(), x0 + sg * dp(91f), ry + dp(4f), 11f, gc ?: cols[r],
                        if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT, true)
                }
                wire(c, gc ?: cLabel, dashed, x0 + sg * dp(82f), rows[0], x0 + sg * dp(82f), rows[2] + dp(10f))
                gnd(c, x0 + sg * dp(82f), rows[2] + dp(10f), gc ?: cLabel, dashed)
                label(c, "RGB LED", x0 + sg * dp(63f), rows[0] - dp(20f), 10f, labelCol, bold = true)
                if (!isGhost && mixR + mixG + mixB > 0.05f) {
                    val col = (0xFF shl 24) or ((255 * mixR).toInt() shl 16) or ((255 * mixG).toInt() shl 8) or (255 * mixB).toInt()
                    fill.color = col; c.drawCircle(x0 + sg * dp(110f), cy, dp(7f), fill)
                    label(c, "mix", x0 + sg * dp(110f), cy + dp(20f), 8f, cLabel)
                }
                box.set(x0, rows[0] - dp(30f), x0 + sg * dp(120f), rows[2] + dp(26f))
            }
            PartType.OLED -> drawOled(c, p, cy, x0, sg, isGhost, box)
            PartType.FLASH -> drawFlash(c, p, cy, x0, sg, isGhost, box)
            PartType.MATRIX -> drawMatrix(c, p, cy, x0, sg, isGhost, box)
            PartType.BARO, PartType.CO2 -> drawSensor(c, p, cy, x0, sg, isGhost, box)
            PartType.ENCODER -> drawEncoder(c, p, cy, x0, sg, isGhost, box)
            PartType.TFT -> drawTft(c, p, cy, x0, sg, isGhost, box)
        }
        box.sort()
        if (!isGhost) hit += box to p
    }

    /** An SSD1306 module: its four pins face the header, the screen shows what the controller lights */
    private fun drawOled(c: Canvas, p: Part, cy: Float, x0: Float, sg: Int, isGhost: Boolean, box: RectF) {
        val gc = if (isGhost) cHigh else null
        val avail = width - 2 * headerW - dp(36f)
        val mw = minOf(dp(146f), avail)                  // module width
        val near = x0 + sg * dp(30f)                     // the module's edge with the pins
        val far = near + sg * mw
        val top = cy - dp(46f); val bottom = cy + dp(46f)
        val l = minOf(near, far); val r = maxOf(near, far)
        fill.color = 0xFF14324A.toInt()
        if (!isGhost) c.drawRoundRect(RectF(l, top, r, bottom), dp(6f), dp(6f), fill)
        setStroke(gc ?: 0xFF4A6A86.toInt(), isGhost, 1.4f)
        c.drawRoundRect(RectF(l, top, r, bottom), dp(6f), dp(6f), stroke)
        // Pins: GND and VCC, then SCL and SDA in the order their header pins come, so the wires do not cross
        val scl = p.pins.getOrNull(0)?.pin; val sda = p.pins.getOrNull(1)?.pin
        val sclFirst = (pinY[scl] ?: 0f) <= (pinY[sda] ?: 0f)
        val names = listOf("GND", "VCC") + if (sclFirst) listOf("SCL", "SDA") else listOf("SDA", "SCL")
        val ys = listOf(cy - dp(30f), cy - dp(15f), cy + dp(10f), cy + dp(25f))
        text.typeface = Typeface.MONOSPACE
        for ((i, n) in names.withIndex()) {
            fill.color = 0xFFC9A24A.toInt(); c.drawCircle(near, ys[i], dp(3f), fill)
            label(c, n, near + sg * dp(6f), ys[i] + dp(2.5f), 6.5f, 0xFFB9C9D9.toInt(), if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT)
        }
        val ySig = mapOf(names[2] to ys[2], names[3] to ys[3])
        for ((role, pin) in listOf(scl, sda).withIndex()) {
            if (pin == null) continue
            val y = ySig[if (role == 0) "SCL" else "SDA"]!!
            val jog = if ((role == 0) == sclFirst) 16f else 9f
            feed(c, pin, near, y, isGhost, gc ?: busColour(pin), jog)
        }
        // The screen, 2:1, pixel for pixel
        val sx0 = minOf(near + sg * dp(26f), far - sg * dp(6f)); val sx1 = maxOf(near + sg * dp(26f), far - sg * dp(6f))
        val sw = sx1 - sx0; val sh = sw / 2f
        val sy0 = cy - sh / 2 + dp(4f)
        fill.color = 0xFF050A10.toInt(); c.drawRect(sx0 - dp(2f), sy0 - dp(2f), sx1 + dp(2f), sy0 + sh + dp(2f), fill)
        val px = devicePixels[p.id]
        if (!isGhost && px != null) {
            pixelPaint.isFilterBitmap = sw < 128f      // smaller than the panel: blend rather than drop lines
            val contrast = deviceContrast[p.id] ?: 0x7F
            val a = (110 + 145 * contrast / 255).coerceIn(60, 255)
            val on = (a shl 24) or 0xCFE6FF
            for (i in 0 until 128 * 64) oledRow[i] = if (px[i].toInt() != 0) on else 0
            oledBitmap.setPixels(oledRow, 0, 128, 0, 0, 128, 64)
            c.drawBitmap(oledBitmap, null, RectF(sx0, sy0, sx1, sy0 + sh), pixelPaint)
        }
        label(c, "OLED " + "0x%02X".format(java.util.Locale.ROOT, p.i2cAddr), (l + r) / 2, top - dp(5f), 9.5f, gc ?: cText, bold = true)
        box.set(x0, top - dp(18f), far, bottom + dp(4f))
        if (!isGhost) hitLabel += RectF(l, top - dp(20f), r, bottom) to p
    }

    /** Pins along a module's edge facing the header: VCC and GND first, then the signals
     * in the order their header pins come, each wire turning in a column of its own */
    private fun modulePins(c: Canvas, p: Part, near: Float, sg: Int, ys: List<Float>, names: List<String>, isGhost: Boolean,
                           colourOf: (Int, String) -> Int) {
        val gc = if (isGhost) cHigh else null
        val al = if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT
        text.typeface = Typeface.MONOSPACE
        for ((i, n) in listOf("VCC", "GND").withIndex()) {
            fill.color = 0xFFC9A24A.toInt(); c.drawCircle(near, ys[i], dp(3f), fill)
            label(c, n, near + sg * dp(6f), ys[i] + dp(2.5f), 6.5f, 0xFFB9C9D9.toInt(), al)
        }
        val roles = p.pins.indices.sortedBy { pinY[p.pins[it].pin] ?: 0f }
        val rowOf = HashMap<Int, Float>()
        roles.forEachIndexed { i, role -> rowOf[role] = ys[i + 2] }
        fun py(role: Int) = pinY[p.pins[role].pin] ?: 0f
        val jog = HashMap<Int, Float>()
        roles.filter { rowOf[it]!! - py(it) > 0.5f }.sortedByDescending { py(it) }.forEachIndexed { k, role -> jog[role] = 6f + 5f * k }
        roles.filter { rowOf[it]!! - py(it) < -0.5f }.sortedBy { py(it) }.forEachIndexed { k, role -> jog[role] = 6f + 5f * k }
        for (role in roles) {
            val y = rowOf[role]!!
            fill.color = 0xFFC9A24A.toInt(); c.drawCircle(near, y, dp(3f), fill)
            label(c, names[role], near + sg * dp(6f), y + dp(2.5f), 6.5f, 0xFFB9C9D9.toInt(), al)
            val pin = p.pins[role].pin
            feed(c, pin, near, y, isGhost, gc ?: colourOf(role, pin), jog[role] ?: 9f)
        }
    }

    private val matrixPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A MAX7219 matrix module: red LEDs on a dark board, its pin strip facing the header */
    private fun drawMatrix(c: Canvas, p: Part, cy: Float, x0: Float, sg: Int, isGhost: Boolean, box: RectF) {
        val gc = if (isGhost) cHigh else null
        val near = x0 + sg * dp(30f)
        val mw = matrixWidth(p)
        val far = near + sg * mw
        val l = minOf(near, far); val r = maxOf(near, far)
        val strip = dp(26f)                                   // the pins and their names
        val gx0 = if (sg > 0) l + strip else l + dp(4f)
        val gx1 = if (sg > 0) r - dp(4f) else r - strip
        val w = 8 * p.matrixChips
        val dot = (gx1 - gx0) / w
        val gh = dot * 8
        val top = cy - maxOf(gh / 2 + dp(6f), dp(40f)); val bottom = cy + maxOf(gh / 2 + dp(6f), dp(40f))
        fill.color = 0xFF1A1416.toInt()
        if (!isGhost) c.drawRoundRect(RectF(l, top, r, bottom), dp(4f), dp(4f), fill)
        setStroke(gc ?: 0xFF5A3434.toInt(), isGhost, 1.4f)
        c.drawRoundRect(RectF(l, top, r, bottom), dp(4f), dp(4f), stroke)
        val px = devicePixels[p.id]
        val gy0 = cy - gh / 2
        for (y in 0 until 8) for (x in 0 until w) {
            val v = if (!isGhost && px != null && y * w + x < px.size) px[y * w + x].toInt() and 0xFF else 0
            matrixPaint.color = if (isGhost) 0x553A2224 else Panels.ledColour(v)
            c.drawCircle(gx0 + (x + 0.5f) * dot, gy0 + (y + 0.5f) * dot, dot * 0.36f, matrixPaint)
        }
        val ys = (0 until 5).map { cy - dp(26f) + it * dp(13f) }
        modulePins(c, p, near, sg, ys, listOf("CS", "CLK", "DIN"), isGhost) { role, pin -> if (role == 0) wireColour(pin) else cBus }
        val title = if (p.matrixChips == 1) "LED matrix · MAX7219" else "LED matrix · MAX7219 × ${p.matrixChips}"
        label(c, title, (l + r) / 2, top - dp(5f), 9.5f, gc ?: cText, bold = true)
        box.set(x0, top - dp(18f), far, bottom + dp(4f))
        if (!isGhost) hitLabel += RectF(l, top - dp(20f), r, bottom) to p
    }

    private var tftBitmap: Bitmap? = null
    private var tftArgb = IntArray(0)

    /** An ST7789 module: the screen shows the panel as it is lit */
    private fun drawTft(c: Canvas, p: Part, cy: Float, x0: Float, sg: Int, isGhost: Boolean, box: RectF) {
        val gc = if (isGhost) cHigh else null
        val near = x0 + sg * dp(30f)
        val mw = tftWidth()
        val far = near + sg * mw
        val l = minOf(near, far); val r = maxOf(near, far)
        val sx0 = if (sg > 0) l + dp(28f) else l + dp(5f)
        val sx1 = if (sg > 0) r - dp(5f) else r - dp(28f)
        val sw = sx1 - sx0
        val sh = sw * p.tftRows / 240f
        val top = cy - sh / 2 - dp(14f); val bottom = cy + sh / 2 + dp(14f)
        fill.color = 0xFF14324A.toInt()
        if (!isGhost) c.drawRoundRect(RectF(l, top, r, bottom), dp(6f), dp(6f), fill)
        setStroke(gc ?: 0xFF4A6A86.toInt(), isGhost, 1.4f)
        c.drawRoundRect(RectF(l, top, r, bottom), dp(6f), dp(6f), stroke)
        val sy0 = cy - sh / 2
        fill.color = 0xFF000000.toInt(); c.drawRect(sx0 - dp(2f), sy0 - dp(2f), sx1 + dp(2f), sy0 + sh + dp(2f), fill)
        val px = devicePixels[p.id]
        if (!isGhost && px != null && px.size >= 240 * p.tftRows * 2) {
            val bm = tftBitmap?.takeIf { it.height == p.tftRows } ?: Bitmap.createBitmap(240, p.tftRows, Bitmap.Config.ARGB_8888).also { tftBitmap = it }
            if (tftArgb.size != 240 * p.tftRows) tftArgb = IntArray(240 * p.tftRows)
            Panels.rgb565(px, tftArgb, 240 * p.tftRows)
            bm.setPixels(tftArgb, 0, 240, 0, 0, 240, p.tftRows)
            pixelPaint.isFilterBitmap = true
            c.drawBitmap(bm, null, RectF(sx0, sy0, sx1, sy0 + sh), pixelPaint)
        }
        val ys = (0 until 7).map { cy - dp(39f) + it * dp(13f) }
        modulePins(c, p, near, sg, ys, listOf("CS", "SCL", "SDA", "DC", "RES"), isGhost) { role, pin -> if (role == 1 || role == 2) cBus else wireColour(pin) }
        label(c, "ST7789 · 240 × ${p.tftRows}", (l + r) / 2, top - dp(5f), 9.5f, gc ?: cText, bold = true)
        box.set(x0, top - dp(18f), far, bottom + dp(4f))
        if (!isGhost) hitLabel += RectF(l, top - dp(20f), r, bottom) to p
    }

    /** What a sensor reads now, as its module shows it next to it: "1013.2 hPa", "21.5 °C" */
    val deviceReadings = HashMap<Int, List<String>>()

    /** A sensor module on I2C: its pins facing the header, the chip, what it reads now */
    private fun drawSensor(c: Canvas, p: Part, cy: Float, x0: Float, sg: Int, isGhost: Boolean, box: RectF) {
        val gc = if (isGhost) cHigh else null
        val baro = p.type == PartType.BARO
        val near = x0 + sg * dp(30f)
        val far = near + sg * dp(74f)
        val rows = if (baro) 6 else 4
        val top = cy - dp(6.5f) * rows - dp(6f); val bottom = cy + dp(6.5f) * rows + dp(6f)
        val l = minOf(near, far); val r = maxOf(near, far)
        fill.color = if (baro) 0xFF3A1F4A.toInt() else 0xFF1E3A2A.toInt()      // the purple BMP280 board, the green SCD41
        if (!isGhost) c.drawRoundRect(RectF(l, top, r, bottom), dp(5f), dp(5f), fill)
        setStroke(gc ?: if (baro) 0xFF7A5A8E.toInt() else 0xFF4E8A62.toInt(), isGhost, 1.4f)
        c.drawRoundRect(RectF(l, top, r, bottom), dp(5f), dp(5f), stroke)
        val ys = (0 until rows).map { cy - dp(6.5f) * (rows - 1) + it * dp(13f) }
        // VCC, GND, then SCL and SDA in header order; the BMP280 also has CSB and SDO, strapped on the module
        val names = listOf("SCL", "SDA")
        modulePins(c, p, near, sg, ys.take(4), names, isGhost) { _, pin -> busColour(pin) }
        if (baro) {
            val al = if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT
            for ((k, n) in listOf("CSB", "SDO").withIndex()) {
                fill.color = 0xFF7A6A4A.toInt(); c.drawCircle(near, ys[4 + k], dp(3f), fill)
                label(c, n, near + sg * dp(6f), ys[4 + k] + dp(2.5f), 6.5f, 0xFF8A7A9A.toInt(), al)
            }
        }
        // the chip: the BMP280's metal can, the SCD41's square with its opening
        val cx = near + sg * dp(52f)
        fill.color = if (baro) 0xFFB8BEC2.toInt() else 0xFF2A2F33.toInt()
        val half = dp(if (baro) 7f else 10f)
        c.drawRoundRect(RectF(cx - half, cy - half, cx + half, cy + half), dp(2f), dp(2f), fill)
        if (!baro) { fill.color = 0xFF0E1012.toInt(); c.drawCircle(cx, cy, dp(4f), fill) }
        else { fill.color = 0xFF6A7074.toInt(); c.drawCircle(cx + dp(3f), cy - dp(3f), dp(1.2f), fill) }
        val name = if (baro) "BMP280 · " + "0x%02X".format(java.util.Locale.ROOT, p.i2cAddr) else "SCD41 · 0x62"
        label(c, name, (l + r) / 2, top - dp(5f), 9.5f, gc ?: cText, bold = true)
        // the readings, beyond the module
        val lines = deviceReadings[p.id] ?: if (baro) listOf("%.1f hPa".format(java.util.Locale.ROOT, p.pressureHpa),
            "%.1f °C".format(java.util.Locale.ROOT, p.tempC)) else listOf("${p.co2Ppm} ppm", "%.1f °C · %d %%".format(java.util.Locale.ROOT, p.tempC, p.rhPct))
        val tx = far + sg * dp(6f)
        val tal = if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT
        if (!isGhost) for ((k, t) in lines.withIndex()) label(c, t, tx, cy - dp(4f) + k * dp(13f), 9f, cAnalog, tal)
        box.set(x0, top - dp(18f), far + sg * dp(64f), bottom + dp(4f))
        if (!isGhost) hitLabel += RectF(minOf(l, tx - dp(64f)), top - dp(20f), maxOf(r, tx + dp(64f)), bottom) to p
    }

    /** Detents each encoder was turned, for drawing its knob */
    private val encoderTurned = HashMap<Int, Int>()
    /** Where each encoder's knob is, for touches */
    private val knobs = HashMap<Int, RectF>()

    /** A KY-040 encoder: its pins facing the header, the knob with its pointer, what the timer counts */
    private fun drawEncoder(c: Canvas, p: Part, cy: Float, x0: Float, sg: Int, isGhost: Boolean, box: RectF) {
        val gc = if (isGhost) cHigh else null
        val near = x0 + sg * dp(30f)
        val far = near + sg * dp(96f)
        val top = cy - dp(40f); val bottom = cy + dp(40f)
        val l = minOf(near, far); val r = maxOf(near, far)
        fill.color = 0xFF16304A.toInt()
        if (!isGhost) c.drawRoundRect(RectF(l, top, r, bottom), dp(5f), dp(5f), fill)
        setStroke(gc ?: 0xFF4A6A86.toInt(), isGhost, 1.4f)
        c.drawRoundRect(RectF(l, top, r, bottom), dp(5f), dp(5f), stroke)
        val ys = (0 until 5).map { cy - dp(26f) + it * dp(13f) }
        modulePins(c, p, near, sg, ys, listOf("CLK", "DT", "SW"), isGhost) { _, pin -> wireColour(pin) }
        // the knob: a shaft in a nut, a pointer that turns 18° a detent
        val kx = near + sg * dp(62f); val kr = dp(21f)
        fill.color = 0xFF2A333A.toInt(); c.drawCircle(kx, cy, kr, fill)
        setStroke(gc ?: 0xFF8A9690.toInt(), isGhost, 1.4f); c.drawCircle(kx, cy, kr, stroke)
        val pressed = pressedParts.contains(p.id)
        fill.color = if (pressed) 0xFF4E5A62.toInt() else 0xFF3C474E.toInt(); c.drawCircle(kx, cy, dp(10f), fill)
        setStroke(gc ?: 0xFFB9C2BC.toInt(), isGhost, 1.2f); c.drawCircle(kx, cy, dp(10f), stroke)
        val a = Math.toRadians(-90.0 + 18.0 * (encoderTurned[p.id] ?: 0))
        setStroke(gc ?: cText, isGhost, 2f)
        c.drawLine(kx, cy, kx + (dp(16f) * Math.cos(a)).toFloat(), cy + (dp(16f) * Math.sin(a)).toFloat(), stroke)
        if (!isGhost) knobs[p.id] = RectF(kx - kr - dp(6f), cy - kr - dp(6f), kx + kr + dp(6f), cy + kr + dp(6f))
        label(c, "Encoder · KY-040", (l + r) / 2, top - dp(5f), 9.5f, gc ?: cText, bold = true)
        if (!isGhost) {
            val t = (p.pins.firstOrNull()?.function as? Function.Timer)?.timer
            val cnt = encoderCounts[p.id]
            label(c, if (cnt != null && t != null) "count $cnt · $t" else "swipe to turn, tap to push",
                (l + r) / 2, bottom + dp(12f), 8.5f, if (cnt != null) cHigh else cLabel)
        }
        box.set(x0, top - dp(18f), far, bottom + dp(16f))
        if (!isGhost) hitLabel += RectF(l, top - dp(20f), r, top + dp(2f)) to p
    }

    /** Parts that report being busy (a flash erasing or programming) */
    val deviceBusy = HashSet<Int>()

    /** A W25Q module: VCC and GND, then its four signals in the order their header
     * pins come, so no two wires cross; the chip itself in the middle */
    private fun drawFlash(c: Canvas, p: Part, cy: Float, x0: Float, sg: Int, isGhost: Boolean, box: RectF) {
        val gc = if (isGhost) cHigh else null
        val near = x0 + sg * dp(30f)
        val far = near + sg * dp(92f)
        val top = cy - dp(44f); val bottom = cy + dp(44f)
        val l = minOf(near, far); val r = maxOf(near, far)
        fill.color = 0xFF14324A.toInt()
        if (!isGhost) c.drawRoundRect(RectF(l, top, r, bottom), dp(6f), dp(6f), fill)
        setStroke(gc ?: 0xFF4A6A86.toInt(), isGhost, 1.4f)
        c.drawRoundRect(RectF(l, top, r, bottom), dp(6f), dp(6f), stroke)
        val names = listOf("CS", "CLK", "DI", "DO")
        val roles = p.pins.indices.sortedBy { pinY[p.pins[it].pin] ?: 0f }
        val ys = (0 until 6).map { cy - dp(32.5f) + it * dp(13f) }
        val rowOf = HashMap<Int, Float>()
        roles.forEachIndexed { i, role -> rowOf[role] = ys[i + 2] }
        text.typeface = Typeface.MONOSPACE
        val al = if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT
        for ((i, n) in listOf("VCC", "GND").withIndex()) {
            fill.color = 0xFFC9A24A.toInt(); c.drawCircle(near, ys[i], dp(3f), fill)
            label(c, n, near + sg * dp(6f), ys[i] + dp(2.5f), 6.5f, 0xFFB9C9D9.toInt(), al)
        }
        // each wire turns in a column of its own, as for the RGB LED
        fun py(role: Int) = pinY[p.pins[role].pin] ?: cy
        val jog = HashMap<Int, Float>()
        roles.filter { rowOf[it]!! - py(it) > 0.5f }.sortedByDescending { py(it) }.forEachIndexed { k, role -> jog[role] = 6f + 5f * k }
        roles.filter { rowOf[it]!! - py(it) < -0.5f }.sortedBy { py(it) }.forEachIndexed { k, role -> jog[role] = 6f + 5f * k }
        for (role in roles) {
            val y = rowOf[role]!!
            fill.color = 0xFFC9A24A.toInt(); c.drawCircle(near, y, dp(3f), fill)
            label(c, names[role], near + sg * dp(6f), y + dp(2.5f), 6.5f, 0xFFB9C9D9.toInt(), al)
            val pin = p.pins[role].pin
            feed(c, pin, near, y, isGhost, gc ?: if (role == 0) wireColour(pin) else cBus, jog[role] ?: 9f)
        }
        // the chip: an SO-8 body with its legs
        val bx0 = minOf(near + sg * dp(34f), near + sg * dp(84f)); val bx1 = maxOf(near + sg * dp(34f), near + sg * dp(84f))
        val by0 = cy - dp(22f); val by1 = cy + dp(22f)
        fill.color = 0xFF9AA4AA.toInt()
        for (k in 0 until 4) {
            val ly = by0 + dp(7f) + k * dp(10f)
            c.drawRect(bx0 - dp(4f), ly - dp(1.5f), bx0, ly + dp(1.5f), fill)
            c.drawRect(bx1, ly - dp(1.5f), bx1 + dp(4f), ly + dp(1.5f), fill)
        }
        fill.color = 0xFF1B2024.toInt(); c.drawRoundRect(RectF(bx0, by0, bx1, by1), dp(2f), dp(2f), fill)
        fill.color = 0xFF3A4246.toInt(); c.drawCircle(bx0 + dp(5f), by0 + dp(5f), dp(1.8f), fill)
        val cx = (bx0 + bx1) / 2
        label(c, "W25Q", cx, cy - dp(2f), 8f, gc ?: 0xFFD8E0D0.toInt(), bold = true)
        label(c, "${p.flashMbit}JV", cx, cy + dp(9f), 7f, gc ?: 0xFF8A9690.toInt())
        if (!isGhost && p.id in deviceBusy) label(c, "busy", (l + r) / 2, bottom + dp(13f), 8.5f, cWarn, bold = true)
        label(c, "Flash · ${p.flashName}", (l + r) / 2, top - dp(5f), 9.5f, gc ?: cText, bold = true)
        box.set(x0, top - dp(18f), far, bottom + dp(4f))
        if (!isGhost) hitLabel += RectF(l, top - dp(20f), r, bottom) to p
    }

    /** I2C lines: the bus colour while they idle high, as any wire otherwise */
    private fun busColour(pin: String): Int {
        val st = state ?: return cBus
        val s = slot(pin)
        if (s < 0) return cBus
        return if (st.high(s) && !st.floating(s)) cBus else wireColour(pin)
    }

    private fun drawConsole(c: Canvas) {
        val y2 = pinY["PA2"] ?: return
        val y3 = pinY["PA3"] ?: return
        val (x0, sg) = padX("PA2")
        val bx = x0 + sg * dp(26f)
        val bw = dp(34f)
        val t = minOf(y2, y3) - dp(12f); val b = maxOf(y2, y3) + dp(12f)
        if (pinSide["PA3"] == pinSide["PA2"]) {
            wire(c, wireColour("PA2"), false, x0, y2, bx, y2)
            wire(c, wireColour("PA3"), false, x0, y3, bx, y3)
        }
        val l = minOf(bx, bx + sg * bw)
        fill.color = cPanel; c.drawRoundRect(RectF(l, t, l + bw, b), dp(5f), dp(5f), fill)
        setStroke(cLine, false, 1.4f); c.drawRoundRect(RectF(l, t, l + bw, b), dp(5f), dp(5f), stroke)
        val al = if (sg > 0) Paint.Align.LEFT else Paint.Align.RIGHT
        label(c, "RX", bx + sg * dp(3f), y2 + dp(3f), 7.5f, cLabel, al)
        label(c, "TX", bx + sg * dp(3f), y3 + dp(3f), 7.5f, cLabel, al)
        // the name, turned, along the far side of the box
        text.typeface = Typeface.DEFAULT
        val tx = bx + sg * dp(25f)
        c.save()
        c.rotate(-90f, tx, (t + b) / 2)
        label(c, "USB-serial", tx, (t + b) / 2 + dp(3.5f), 9.5f, cText, bold = true)
        c.restore()
        text.typeface = Typeface.MONOSPACE
    }

    // ------------------------------------------------------------------
    // Touch

    private val pressedParts = HashSet<Int>()
    private var dragPart: Part? = null
    private var knobPart: Part? = null
    private var knobX = 0f; private var knobY = 0f
    private var knobSent = 0
    private var dragStartY = 0f
    private var dragStartValue = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            hitLabel.lastOrNull { it.first.contains(e.x, e.y) }?.let { onLongPress(it.second); return }
            val p = partAt(e.x, e.y) ?: return
            if (p.type == PartType.BUTTON) return            // holding a button's contacts is pressing it
            dragPart = null
            onLongPress(p)
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hitLabel.lastOrNull { it.first.contains(e.x, e.y) }?.let { onTap(it.second); return true }
            if (knobs.values.any { it.contains(e.x, e.y) }) return true      // the knob turns and pushes
            partAt(e.x, e.y)?.let { p -> if (p.type != PartType.BUTTON && p.type != PartType.POT) onTap(p); return true }
            if (partAt(e.x, e.y) == null && hitLabel.none { it.first.contains(e.x, e.y) }) rowHit.firstOrNull { it.first.contains(e.x, e.y) }?.let { onPinTap(it.second) }
            return true
        }
    })

    /** Where a part is drawn, in view coordinates (for the UI probe) */
    fun partBounds(id: Int): RectF? = hit.lastOrNull { it.second.id == id }?.first?.let { RectF(it) }

    private fun partAt(x: Float, y: Float): Part? = hit.lastOrNull { it.first.contains(x, y) }?.second

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                knobs.entries.firstOrNull { it.value.contains(e.x, e.y) }?.let { (id, _) ->
                    knobPart = board.parts.firstOrNull { it.id == id }
                    knobX = e.x; knobY = e.y; knobSent = 0
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val p = partAt(e.x, e.y) ?: return true
                when (p.type) {
                    PartType.BUTTON -> { pressedParts += p.id; onPress(p, true); invalidate(); parent?.requestDisallowInterceptTouchEvent(true) }
                    PartType.POT -> { dragPart = p; dragStartY = e.y; dragStartValue = potValue[p.id] ?: 500; parent?.requestDisallowInterceptTouchEvent(true) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> knobPart?.let { p ->
                // a swipe turns it: right or up is clockwise, one detent every 16 dp
                val steps = ((e.x - knobX) - (e.y - knobY)) / dp(16f)
                val want = steps.toInt()
                if (want != knobSent) {
                    onTurn(p, want - knobSent)
                    encoderTurned[p.id] = (encoderTurned[p.id] ?: 0) + want - knobSent
                    knobSent = want
                    invalidate()
                }
            } ?: dragPart?.let { p ->
                val v = (dragStartValue + ((dragStartY - e.y) / dp(120f) * 1000)).toInt().coerceIn(0, 1000)
                if (v != potValue[p.id]) { potValue[p.id] = v; onPot(p, v); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobPart?.let { p ->
                    // a tap on the knob pushes it, for a moment
                    val moved = Math.hypot((e.x - knobX).toDouble(), (e.y - knobY).toDouble()) > dp(8f)
                    if (!moved && knobSent == 0 && e.actionMasked == MotionEvent.ACTION_UP) {
                        pressedParts += p.id; onPress(p, true); invalidate()
                        postDelayed({ pressedParts -= p.id; onPress(p, false); invalidate() }, 180)
                    }
                    knobPart = null
                    return true
                }
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
