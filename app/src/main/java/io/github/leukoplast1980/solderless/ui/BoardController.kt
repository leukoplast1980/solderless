/*
 * Solderless - the Board tab: keeps the drawing live, adds and edits
 * parts through the recommender, and floats pinned parts over the other
 * tabs.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.widget.FrameLayout
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.board.LedColour
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.board.Passives
import io.github.leukoplast1980.solderless.board.Recommender
import io.github.leukoplast1980.solderless.board.Samples
import io.github.leukoplast1980.solderless.board.Wiring
import io.github.leukoplast1980.solderless.settings.Settings
import io.github.leukoplast1980.solderless.vm.BoardState
import io.github.leukoplast1980.solderless.vm.VmHost
import java.io.File

class BoardController(
    private val activity: AppCompatActivity,
    private val vm: VmHost,
    private val settings: Settings,
    private val openFile: (File) -> Unit,
) {
    val view: BoardView = activity.findViewById(R.id.boardView)
    private val warn: TextView = activity.findViewById(R.id.boardWarn)
    private val pinned: LinearLayout = activity.findViewById(R.id.pinned)
    private val state = BoardState()
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val pinnedViews = HashMap<Int, View>()

    // A pinned pot's wide slider: the card holds a small button, the slider opens above the input line
    private val potStrip: View = activity.findViewById(R.id.potStrip)
    private val potStripName: TextView = activity.findViewById(R.id.potStripName)
    private val potStripValue: TextView = activity.findViewById(R.id.potStripValue)
    private val potSlider: Slider = activity.findViewById(R.id.potSlider)
    /** Displays: what each controller holds, read with the board */
    private val oledBuf = ByteArray(128 * 64)
    private val deviceText = HashMap<Int, String>()
    /** The close-up open now: part id, its screen and its rows of state */
    private var closeUp: Triple<Int, OledView, LinearLayout>? = null

    /** The pot whose slider is open, or -1 */
    private var openPot = -1
    private var bottomInset = 0

    init {
        makeDraggable()
        potSlider.addOnChangeListener { _, v, fromUser -> if (fromUser && openPot >= 0) setPot(openPot, v.toInt()) }
        stepOnHold(activity.findViewById(R.id.potMinus), -10)
        stepOnHold(activity.findViewById(R.id.potPlus), 10)
        view.chip = vm.chip
        view.order = settings.headerOrder
        view.autoSpread = settings.boardSpread
        view.board = vm.board
        view.onPress = { p, down -> vm.boardInput(p.id, if (down) 1 else 0) }
        view.onPot = { p, v -> vm.boardInput(p.id, v) }
        view.onLongPress = { showSettings(it) }
        view.onTap = { if (it.type == PartType.OLED) showOled(it) else showSample(it) }
        view.onPinTap = { showPin(it) }
        activity.findViewById<View>(R.id.boardAdd).setOnClickListener { showAdd() }
        activity.findViewById<View>(R.id.boardSupport).setOnClickListener { showSupport() }
        rebuildPinned()
    }

    /** The project changed underneath (another project opened). */
    fun reload() {
        view.board = vm.board
        view.order = settings.headerOrder
        view.autoSpread = settings.boardSpread
        view.potValue.clear()
        rebuildPinned()
    }

    /** Called often: [boardVisible] while the Board tab shows. */
    /** [bottomInset]: the height of the bar along the bottom of the shown tab (the
     * Console's input line, the Editor's buttons), which the card and the pot's
     * slider stay above. */
    fun refresh(boardVisible: Boolean, overlayAllowed: Boolean, bottomInset: Int = 0) {
        val anyPinned = vm.board.parts.any { it.pinnedOverTabs }
        pinned.visibility = if (!boardVisible && overlayAllowed && anyPinned) View.VISIBLE else View.GONE
        this.bottomInset = bottomInset
        showPotStrip()
        if (view.board !== vm.board) { view.board = vm.board; rebuildPinned() }
        if (!boardVisible && pinned.visibility != View.VISIBLE) return
        vm.readBoard(state)
        readDevices()
        if (boardVisible) {
            view.state = state
            view.invalidate()
            val n = warnings()
            warn.visibility = if (n > 0) View.VISIBLE else View.GONE
            warn.text = activity.getString(R.string.board_warnings, n)
        } else updatePinned()
    }

    /** Pixels and state of the displays on the board, for the board, the card and the close-up */
    private fun readDevices() {
        for (p in vm.board.parts.filter { it.type == PartType.OLED }) {
            val text = vm.readDevice(p.id, oledBuf) ?: continue
            val contrast = Regex("Contrast\t0x([0-9A-F]{2})").find(text)?.groupValues?.get(1)?.toInt(16) ?: 0x7F
            view.devicePixels[p.id] = oledBuf.copyOf()
            view.deviceContrast[p.id] = contrast
            deviceText[p.id] = text
            (pinnedViews[p.id] as? OledView)?.show(oledBuf, contrast)
            closeUp?.let { (id, screen, rows) ->
                if (id == p.id) { screen.show(oledBuf, contrast); fillState(rows, text) }
            }
        }
    }

    private fun warnings(): Int = vm.board.parts.flatMap { it.pins }.count { u ->
        val s = vm.chip.gpio(u.pin)?.slot ?: return@count false
        state.shorted(s) || ((state.floating(s) || state.undefined(s)) && state.mode(s) == BoardState.MODE_INPUT)
    }

    private fun setBoard(b: Board) {
        vm.setBoard(b)
        view.board = b
        view.ghost = null
        view.highlightPins = emptySet()
        rebuildPinned()
    }

    // ------------------------------------------------------------------
    // Sheet helpers

    private fun sheet(dim: Float = 0.5f, build: (LinearLayout, BottomSheetDialog) -> Unit): BottomSheetDialog {
        val dlg = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        build(col, dlg)
        dlg.setContentView(ScrollView(activity).apply { addView(col) })
        dlg.window?.setDimAmount(dim)
        dlg.show()
        return dlg
    }

    private fun title(parent: LinearLayout, s: String, sub: String? = null) {
        parent.addView(TextView(activity).apply { text = s; textSize = 20f; setTextColor(color(R.color.bar_text)) })
        if (sub != null) parent.addView(body(sub, R.color.label))
    }

    private fun body(s: String, c: Int = R.color.console_text, mono: Boolean = false) = TextView(activity).apply {
        text = s; textSize = if (mono) 12f else 13.5f; setTextColor(color(c))
        if (mono) typeface = Typeface.MONOSPACE
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun section(parent: LinearLayout, s: String) {
        parent.addView(TextView(activity).apply {
            text = s.uppercase(); textSize = 12f; letterSpacing = 0.06f; setTextColor(color(R.color.label))
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)

    /** Forth source for code(): comments in the comment colour */
    private fun forth(text: String): List<Pair<CharSequence, Int>> = text.trimEnd().lines().map { l ->
        val at = when {
            l.trimStart().startsWith("\\") -> l.indexOf('\\')
            else -> l.indexOf(" \\ ").let { if (it < 0) -1 else it + 1 }
        }
        val sp = android.text.SpannableString(l)
        if (at >= 0) sp.setSpan(android.text.style.ForegroundColorSpan(color(R.color.syntax_comment)), at, l.length, 0)
        sp to color(R.color.console_text)
    }

    private fun code(parent: LinearLayout, lines: List<Pair<CharSequence, Int>>, copy: Boolean = false, label: String = "wiring.fs") {
        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        for ((l, c) in lines) inner.addView(TextView(activity).apply {
            text = l; typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextColor(c)
            setSingleLine(); setHorizontallyScrolling(true)
        })
        // long lines scroll sideways rather than wrap, so the comments stay in their column
        val box = HorizontalScrollView(activity).apply {
            background = GradientDrawable().apply { setColor(color(R.color.console_background)); cornerRadius = dp(10).toFloat() }
            isHorizontalScrollBarEnabled = false
            addView(inner)
        }
        if (!copy) { parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }); return }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_copy)
            contentDescription = activity.getString(R.string.copy_code)
            setOnClickListener {
                val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, lines.joinToString("\n") { it.first.toString() } + "\n"))
                android.widget.Toast.makeText(activity, R.string.code_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun buttons(parent: LinearLayout, vararg b: Pair<String, () -> Unit>, primaryLast: Boolean = true) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        for ((i, x) in b.withIndex()) {
            val primary = primaryLast && i == b.size - 1 && b.size > 1
            val btn = MaterialButton(activity, null,
                if (primary) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = x.first
            btn.setOnClickListener { x.second() }
            row.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        parent.addView(row)
    }

    private fun choice(parent: LinearLayout, label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        section(parent, label)
        val g = ChipGroup(activity).apply { isSingleSelection = true; isSelectionRequired = true }
        for ((i, o) in options.withIndex()) g.addView(Chip(activity).apply {
            text = o; isCheckable = true; isChecked = i == selected
            setOnClickListener { onPick(i) }
        })
        parent.addView(g)
    }

    private fun switch(parent: LinearLayout, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(MaterialSwitch(activity).apply {
            text = label; isChecked = on; setTextColor(color(R.color.console_text))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    // ------------------------------------------------------------------
    // Adding a part

    private fun showAdd(): BottomSheetDialog = sheet { col, dlg ->
        title(col, activity.getString(R.string.board_add_title), activity.getString(R.string.board_add_subtitle))
        val needs = mapOf(
            PartType.BUTTON to "input with interrupt", PartType.LED to "output or PWM", PartType.RGB_LED to "3 PWM channels, one timer",
            PartType.POT to "ADC input", PartType.SPEAKER to "DAC or PWM, transistor", PartType.PIEZO to "PWM",
            PartType.OLED to "I2C: SCL and SDA",
        )
        val grid = android.widget.GridLayout(activity).apply { columnCount = 2 }
        for ((t, n) in needs) grid.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "${t.title}\n$n"; isAllCaps = false; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(76)
            cornerRadius = dp(14)
            setOnClickListener { dlg.dismiss(); propose(Part(vm.board.nextId(), t, emptyList())) }
        }, android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
            width = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        col.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        col.addView(body(activity.getString(R.string.board_add_next), R.color.syntax_comment))
    }

    private fun propose(part: Part, pickedPin: String? = null, role: Int = 0) {
        val rec = vm.recommender()
        val board = vm.board
        val pr = if (pickedPin == null) rec.propose(board.parts, part) else rec.proposeWithPin(board.parts, part, role, pickedPin)
        if (pr == null) {
            sheet { col, _ -> title(col, part.type.title); col.addView(body(activity.getString(R.string.board_no_pin))) }
            return
        }
        if (pr.moves.isNotEmpty()) { showRewire(pr, pickedPin); return }
        view.ghost = pr.part
        view.highlightPins = pr.part.pins.map { it.pin }.toSet()
        val dlg = sheet(0.12f) { col, dlg ->
            title(col, pr.part.type.title, describe(pr.part))
            for ((r, u) in pr.part.pins.withIndex()) {
                val rn = pr.part.type.roles[r]
                col.addView(body((if (rn.isEmpty()) "" else "$rn: ") + u.pin + " · " + u.function, R.color.accent, mono = true).apply { textSize = 15f })
                col.addView(body(pr.why[r]))
            }
            for (x in Passives.forPart(pr.part)) col.addView(body("${x.value}: ${x.reason}"))
            // The other pins stay behind a button: the list would push the rest off the screen
            val opts = rec.options(board.parts, pr.part.copy(pins = emptyList()), role).filter { o -> pr.part.pins.none { it.pin == o.pin } }
            val others = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val g = ChipGroup(activity)
            for (o in opts) {
                g.addView(Chip(activity).apply {
                    text = if (o.note.isEmpty()) o.pin else "${o.pin} · ${o.note}"
                    typeface = Typeface.MONOSPACE
                    when (o.status) {
                        Recommender.Status.SCARCE -> setChipStrokeColorResource(R.color.chip_message)
                        Recommender.Status.TAKEN -> alpha = 0.7f
                        else -> {}
                    }
                    chipStrokeWidth = d
                    setOnClickListener { dlg.dismiss(); propose(part, o.pin, role) }
                })
            }
            others.addView(g)
            others.addView(body(activity.getString(R.string.board_other_pins_legend), R.color.syntax_comment))
            if (opts.isNotEmpty()) {
                val rn = pr.part.type.roles[role]
                val label = activity.getString(R.string.board_other_pins_button, opts.size) + if (rn.isEmpty()) "" else " ($rn)"
                col.addView(MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "$label  ▾"
                    isAllCaps = false
                    setPadding(0, paddingTop, paddingRight, paddingBottom)
                    setOnClickListener {
                        val open = others.visibility != View.VISIBLE
                        others.visibility = if (open) View.VISIBLE else View.GONE
                        text = "$label  " + if (open) "▴" else "▾"
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                col.addView(others)
            }
            buttons(col, activity.getString(android.R.string.cancel) to { dlg.dismiss() },
                activity.getString(R.string.board_add) to { dlg.dismiss(); setBoard(board.apply(pr)) })
            section(col, activity.getString(R.string.board_sample_code))
            col.addView(body(activity.getString(R.string.board_sample_hint), R.color.label))
            val after = board.with(pr.part)
            code(col, forth(Samples.forPart(after, vm.chip, pr.part)), copy = true, label = after.nameOf(pr.part) + " sample")
        }
        dlg.setOnDismissListener { if (view.ghost === pr.part) { view.ghost = null; view.highlightPins = emptySet() } }
    }

    /** "name\tvalue" lines as a two-column table */
    private fun fillState(rows: LinearLayout, text: String) {
        val lines = text.trimEnd().lines().filter { '\t' in it }
        if (rows.childCount != lines.size) {
            rows.removeAllViews()
            for (l in lines) rows.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(activity).apply { textSize = 12.5f; setTextColor(color(R.color.label)) }, LinearLayout.LayoutParams(dp(104), -2))
                addView(TextView(activity).apply { textSize = 12.5f; typeface = Typeface.MONOSPACE; setTextColor(color(R.color.console_text)) },
                    LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        for ((i, l) in lines.withIndex()) {
            val row = rows.getChildAt(i) as LinearLayout
            val (k, v) = l.split('\t', limit = 2)
            (row.getChildAt(0) as TextView).text = k
            val tv = row.getChildAt(1) as TextView
            if (tv.text.toString() != v) tv.text = v
        }
    }

    /** A display close up: every pixel, and what its controller holds */
    private fun showOled(p: Part): BottomSheetDialog {
        val dlg = sheet { col, dlg ->
            title(col, p.type.title, "SSD1306 · 128 × 64 · " + (p.i2c ?: "I2C") + " " + "0x%02X".format(java.util.Locale.ROOT, p.i2cAddr))
            val screen = OledView(activity, grid = true)
            col.addView(screen, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            col.addView(body(activity.getString(R.string.oled_closeup_hint), R.color.label))
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(rows)
            closeUp = Triple(p.id, screen, rows)
            view.devicePixels[p.id]?.let { screen.show(it, view.deviceContrast[p.id] ?: 0x7F) }
            deviceText[p.id]?.let { fillState(rows, it) }
            switch(col, activity.getString(R.string.board_pin_over_tabs), p.pinnedOverTabs) {
                setBoard(vm.board.with(vm.board.parts.first { it.id == p.id }.copy(pinnedOverTabs = it)))
            }
            buttons(col, activity.getString(R.string.board_sample_code) to { dlg.dismiss(); showSample(p) },
                activity.getString(R.string.board_part_settings) to { dlg.dismiss(); showSettings(p) }, primaryLast = false)
        }
        dlg.setOnDismissListener { closeUp = null }
        return dlg
    }

    /** A part's sample code, from a tap on the part or its sheet */
    private fun showSample(p: Part): BottomSheetDialog = sheet { col, dlg ->
        val b = vm.board
        val n = b.nameOf(p)
        title(col, activity.getString(R.string.board_sample_title, p.type.title),
            "$n · " + p.pins.joinToString(" · ") { it.pin } + " · " + describe(p))
        col.addView(body(activity.getString(R.string.board_sample_hint), R.color.label))
        code(col, forth(Samples.forPart(b, vm.chip, p)), copy = true, label = "$n sample")
        buttons(col, activity.getString(R.string.board_part_settings) to { dlg.dismiss(); showSettings(p) })
    }

    private fun describe(p: Part): String = when (p.type) {
        PartType.LED -> "${p.colour.title} · ${p.sizeMm} mm · ${p.currentMa} mA"
        PartType.RGB_LED -> "common ${if (p.activeHigh) "cathode" else "anode"} · ${p.currentMa} mA"
        PartType.BUTTON -> "to ${if (p.toVcc) "3V3" else "GND"} · ${if (p.externalPull) "10 kΩ" else "internal"} pull" +
            if (p.bounceUs > 0) " · bounces" else ""
        PartType.POT -> "10 kΩ linear"
        PartType.SPEAKER -> if (p.dacDrive) "DAC drive" else "PWM drive"
        PartType.PIEZO -> "passive"
        PartType.OLED -> "SSD1306 · ${p.i2c ?: "I2C"} · address " + "0x%02X".format(java.util.Locale.ROOT, p.i2cAddr)
    }

    private fun showRewire(pr: Recommender.Proposal, pin: String?) {
        val board = vm.board
        val after = board.apply(pr)
        view.ghost = pr.part
        view.highlightPins = (pr.moves.map { it.to.pin } + pr.part.pins.map { it.pin }).toSet()
        val dlg = sheet(0.3f) { col, dlg ->
            title(col, "Use ${pin ?: pr.part.pins.first().pin} for the ${pr.part.type.title}?")
            for (m in pr.moves) {
                val q = board.parts.first { it.id == m.partId }
                val rn = q.type.roles[m.role]
                col.addView(body("${q.type.title}${if (rn.isEmpty()) "" else ", $rn"} is on ${m.from.pin} (${m.from.function}). " +
                    "It moves to ${m.to.pin} (${m.to.function})."))
            }
            section(col, activity.getString(R.string.board_changes))
            for (m in pr.moves) {
                val q = board.parts.first { it.id == m.partId }
                col.addView(body("${q.type.title} ${q.type.roles[m.role]}   ${m.from.pin} → ${m.to.pin}", mono = true))
            }
            col.addView(body("${pr.part.type.title} (new)   → ${pr.part.pins.joinToString { it.pin }}", mono = true))
            section(col, "wiring.fs")
            val before = Wiring.wiringFs(board, vm.chip, vm.project.name).lines().filter { "constant" in it }.toSet()
            val now = Wiring.wiringFs(after, vm.chip, vm.project.name).lines().filter { "constant" in it }.toSet()
            code(col, (before - now).map { "- $it" to 0xFFFF8A8A.toInt() } + (now - before).map { "+ $it" to color(R.color.accent) })
            col.addView(body(activity.getString(R.string.board_rewire_note), R.color.label))
            buttons(col, activity.getString(android.R.string.cancel) to { dlg.dismiss() },
                activity.getString(R.string.board_rewire) to { dlg.dismiss(); setBoard(after) })
        }
        dlg.setOnDismissListener { view.ghost = null; view.highlightPins = emptySet() }
    }

    // ------------------------------------------------------------------
    // Part settings

    private fun showSettings(p: Part): BottomSheetDialog = sheet { col, dlg ->
        val pinText = p.pins.joinToString(" · ") { u -> vm.chip.gpio(u.pin)?.let { "${u.pin} (pin ${it.number})" } ?: u.pin }
        title(col, p.type.title, "on $pinText")
        fun update(n: Part) {
            dlg.dismiss()
            val needsChanged = n.needs() != p.needs()
            if (needsChanged) {
                val pr = vm.recommender().propose(vm.board.without(p.id).parts, n.copy(pins = emptyList()))
                if (pr != null) setBoard(vm.board.without(p.id).apply(pr)) else return
            } else setBoard(vm.board.with(n))
            showSettings(vm.board.parts.first { it.id == p.id })
        }
        when (p.type) {
            PartType.LED -> {
                choice(col, "Colour", LedColour.entries.map { "${it.title} ${"%.1f".format(java.util.Locale.ROOT, it.vfMv / 1000.0)} V" }, p.colour.ordinal) { update(p.copy(colour = LedColour.entries[it])) }
                choice(col, "Size", listOf("3 mm", "5 mm"), if (p.sizeMm == 3) 0 else 1) { update(p.copy(sizeMm = if (it == 0) 3 else 5)) }
                choice(col, "Current", listOf("2 mA", "5 mA", "10 mA"), listOf(2, 5, 10).indexOf(p.currentMa)) { update(p.copy(currentMa = listOf(2, 5, 10)[it])) }
                choice(col, "Lights when the pin is", listOf("high", "low"), if (p.activeHigh) 0 else 1) { update(p.copy(activeHigh = it == 0)) }
                switch(col, "Dimmable (on a timer channel)", p.dimmable) { update(p.copy(dimmable = it)) }
            }
            PartType.RGB_LED -> {
                choice(col, "Common", listOf("cathode", "anode"), if (p.activeHigh) 0 else 1) { update(p.copy(activeHigh = it == 0)) }
                choice(col, "Current", listOf("2 mA", "5 mA", "10 mA"), listOf(2, 5, 10).indexOf(p.currentMa)) { update(p.copy(currentMa = listOf(2, 5, 10)[it])) }
            }
            PartType.BUTTON -> {
                choice(col, "Other side to", listOf("GND", "3V3"), if (p.toVcc) 1 else 0) { update(p.copy(toVcc = it == 1)) }
                choice(col, "Pull resistor", listOf("inside the chip", "10 kΩ"), if (p.externalPull) 1 else 0) { update(p.copy(externalPull = it == 1)) }
                switch(col, "Contacts bounce (2 ms), as real ones do", p.bounceUs > 0) { update(p.copy(bounceUs = if (it) 2000 else 0)) }
                switch(col, "Own interrupt line (EXTI)", p.interrupt) { update(p.copy(interrupt = it)) }
                if (!p.externalPull) col.addView(body("Your code turns on the ${if (p.toVcc) "pull-down" else "pull-up"}: " +
                    "GPIO${'A' + (vm.chip.gpio(p.pins[0].pin)?.port ?: 0)}_PUPDR = ${if (p.toVcc) "10" else "01"} for ${p.pins[0].pin}.", R.color.label))
            }
            PartType.SPEAKER -> choice(col, "Driven by", listOf("DAC", "PWM"), if (p.dacDrive) 0 else 1) { update(p.copy(dacDrive = it == 0)) }
            PartType.OLED -> choice(col, "Address (the SA0 pad)", listOf("0x3C · to GND", "0x3D · to 3V3"), if (p.i2cAddr == 0x3D) 1 else 0) {
                update(p.copy(i2cAddr = if (it == 1) 0x3D else 0x3C)) }
            else -> {}
        }
        for (x in Passives.forPart(p)) col.addView(body("${x.value}: ${x.reason}"))
        switch(col, activity.getString(R.string.board_pin_over_tabs), p.pinnedOverTabs) { update(p.copy(pinnedOverTabs = it)) }
        buttons(col, activity.getString(R.string.board_sample_code) to { dlg.dismiss(); showSample(p) })
        buttons(col, activity.getString(R.string.board_change_pin) to { dlg.dismiss(); changePin(p) },
            activity.getString(R.string.board_remove) to { dlg.dismiss(); setBoard(vm.board.without(p.id)) }, primaryLast = false)
    }

    private fun changePin(p: Part): BottomSheetDialog = sheet { col, dlg ->
        title(col, activity.getString(R.string.board_change_pin), p.type.title)
        val rec = vm.recommender()
        for (role in p.type.roles.indices) {
            if (p.type.roles.size > 1) section(col, p.type.roles[role])
            val g = ChipGroup(activity)
            for (o in rec.options(vm.board.parts, p, role)) g.addView(Chip(activity).apply {
                text = if (o.note.isEmpty()) o.pin else "${o.pin} · ${o.note}"
                isCheckable = true; isChecked = p.pins.getOrNull(role)?.pin == o.pin
                if (o.status == Recommender.Status.SCARCE) { setChipStrokeColorResource(R.color.chip_message); chipStrokeWidth = d }
                if (o.status == Recommender.Status.TAKEN) alpha = 0.7f
                setOnClickListener {
                    dlg.dismiss()
                    val rest = vm.board.without(p.id)
                    val pr = rec.proposeWithPin(rest.parts, p.copy(pins = emptyList()), role, o.pin) ?: return@setOnClickListener
                    if (pr.moves.isEmpty()) setBoard(rest.apply(pr))
                    else { vm.setBoard(rest); view.board = rest; showRewire(pr, o.pin) }
                }
            })
            col.addView(g)
        }
    }

    // ------------------------------------------------------------------

    private fun showSupport(): BottomSheetDialog = sheet { col, dlg ->
        title(col, activity.getString(R.string.board_support_title), activity.getString(R.string.board_support_subtitle))
        for ((v, w) in Passives.support) {
            col.addView(body(v, R.color.bar_text, mono = true))
            col.addView(body(w, R.color.label).apply { setPadding(0, 0, 0, dp(4)) })
        }
        col.addView(body(activity.getString(R.string.board_support_clock)))
        buttons(col,
            activity.getString(R.string.board_open_wiring) to { dlg.dismiss(); openFile(vm.wiringFile()) },
            activity.getString(R.string.board_export) to { dlg.dismiss(); export() }, primaryLast = false)
    }

    private fun export() {
        val name = vm.project.name
        val text = Wiring.wiringList(vm.board, vm.chip, name) + "\n\n" + Wiring.partsList(vm.board, name) +
            "\n\nwiring.fs\n\n" + Wiring.wiringFs(vm.board, vm.chip, name)
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$name: wiring and parts")
            putExtra(Intent.EXTRA_TEXT, text)
        }, activity.getString(R.string.board_export)))
    }

    private fun showPin(pin: ChipPins.Pin): BottomSheetDialog = sheet { col, dlg ->
        title(col, pin.name, "pin ${pin.number}")
        val reserved = vm.recommender().reserved[pin.name]
        if (reserved != null) col.addView(body("Kept free: $reserved", R.color.chip_message))
        // What is connected: tap for the part's settings (and Remove)
        vm.board.parts.filter { p -> p.pins.any { it.pin == pin.name } }.forEach { p ->
            col.addView(body("${p.type.title} (${p.pins.first { it.pin == pin.name }.function})  ›", R.color.accent).apply {
                textSize = 15f
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                val tv = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                isClickable = true
                contentDescription = "${p.type.title} settings"
                setOnClickListener { dlg.dismiss(); showSettings(p) }
            })
        }
        if (pin.isGpio && vm.board.parts.none { p -> p.pins.any { it.pin == pin.name } })
            switch(col, activity.getString(R.string.scope_show), pin.name in ScopeController.extraPins(activity, vm.project.name)) {
                ScopeController.setWatched(activity, vm.project.name, pin.name, it)
            }
        if (pin.af.isNotEmpty()) {
            section(col, "Alternate functions")
            col.addView(body(pin.af.joinToString("\n") { "AF${it.af}  ${it.name}" }, mono = true))
        }
        if (pin.analog.isNotEmpty()) {
            section(col, "Analog")
            col.addView(body(pin.analog.joinToString("\n"), mono = true))
        }
    }

    // ------------------------------------------------------------------
    // Pinned parts over the other tabs

    /** The card can be dragged anywhere over the panes; where it was left is kept. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable() {
        val prefs = activity.getSharedPreferences("board", android.content.Context.MODE_PRIVATE)
        pinned.translationX = prefs.getFloat("pinnedX", 0f)
        pinned.translationY = prefs.getFloat("pinnedY", 0f)
        var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f
        pinned.setOnTouchListener { v, e ->
            val parent = v.parent as View
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startTx = v.translationX; startTy = v.translationY }
                MotionEvent.ACTION_MOVE -> {
                    // The card sits at bottom|end: it can move left and up
                    v.translationX = (startTx + e.rawX - downX).coerceIn(-(parent.width - v.width).toFloat(), 0f)
                    v.translationY = (startTy + e.rawY - downY).coerceIn(-(parent.height - v.height).toFloat(), 0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    prefs.edit().putFloat("pinnedX", v.translationX).putFloat("pinnedY", v.translationY).apply()
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildPinned() {
        pinned.removeAllViews()
        pinnedViews.clear()
        // A grip shows the card can be moved
        pinned.addView(TextView(activity).apply {
            text = "⠿"; textSize = 14f; setTextColor(color(R.color.label)); gravity = Gravity.CENTER
            contentDescription = activity.getString(R.string.pinned_drag)
        }, LinearLayout.LayoutParams(-1, dp(20)))
        for (p in vm.board.parts.filter { it.pinnedOverTabs }) {
            val v: View = when (p.type) {
                PartType.BUTTON -> Button(activity).apply {
                    text = nameAndPins(p); isAllCaps = false
                    setOnTouchListener { b, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { vm.boardInput(p.id, 1); view.setPressed(p.id, true); b.isPressed = true }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vm.boardInput(p.id, 0); view.setPressed(p.id, false); b.isPressed = false }
                        }
                        true
                    }
                }
                PartType.POT -> PotPill(activity).apply {
                    contentDescription = activity.getString(R.string.pot_open, vm.board.nameOf(p))
                    onChange = { setPot(p.id, it) }
                    onTap = { openPot = if (openPot == p.id) -1 else p.id; showPotStrip(); updatePotButtons() }
                }
                PartType.OLED -> OledView(activity)
                else -> TextView(activity).apply {
                    textSize = 12f; setTextColor(color(R.color.console_text)); gravity = Gravity.CENTER_VERTICAL
                    compoundDrawablePadding = dp(8)
                    text = nameAndPins(p)
                }
            }
            // A 0.96" OLED's glass is about 22 mm wide: 138 dp is that on any screen
            if (p.type == PartType.OLED) pinned.addView(v, LinearLayout.LayoutParams(dp(138), dp(69)).apply { topMargin = dp(4); bottomMargin = dp(2) })
            else pinned.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (p.type == PartType.POT) dp(40) else dp(36))
                .apply { if (p.type == PartType.POT) topMargin = dp(4) })
            pinnedViews[p.id] = v
        }
        if (vm.board.parts.none { it.id == openPot && it.pinnedOverTabs && it.type == PartType.POT }) openPot = -1
        updatePotButtons()
        showPotStrip()
    }

    /** "led  PB5": the name, and its pins small and grey */
    private fun nameAndPins(p: Part): CharSequence {
        val sb = SpannableStringBuilder(vm.board.nameOf(p)).append("  ")
        val at = sb.length
        sb.append(p.pins.joinToString(" ") { it.pin })
        sb.setSpan(ForegroundColorSpan(color(R.color.label)), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.85f), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan("monospace"), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun potValue(id: Int) = view.potValue[id] ?: 500
    private fun volts(v: Int) = "%.2f V".format(java.util.Locale.ROOT, v * 3.3 / 1000)

    private fun setPot(id: Int, v: Int) {
        val c = v.coerceIn(0, 1000)
        view.potValue[id] = c
        vm.boardInput(id, c)
        updatePotButtons()
        showPotStrip()
    }

    /** The card's pots: how far each is turned, and whether its wide slider is open */
    private fun updatePotButtons() {
        for (p in vm.board.parts.filter { it.pinnedOverTabs && it.type == PartType.POT }) {
            val pill = pinnedViews[p.id] as? PotPill ?: continue
            pill.name = vm.board.nameOf(p)
            pill.value = potValue(p.id)
            pill.open = openPot == p.id
        }
    }

    /** The wide slider for [openPot], shown while the card is; the card moves up above it */
    private fun showPotStrip() {
        val p = vm.board.parts.firstOrNull { it.id == openPot }
        val show = p != null && pinned.visibility == View.VISIBLE
        if (show) {
            val v = potValue(p!!.id)
            val name = SpannableStringBuilder(vm.board.nameOf(p)).append("  ")
            val at = name.length
            name.append(p.pins[0].pin + " · " + p.pins[0].function)
            name.setSpan(ForegroundColorSpan(color(R.color.label)), at, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            name.setSpan(TypefaceSpan("monospace"), at, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (potStripName.text.toString() != name.toString()) potStripName.text = name
            potStripValue.text = volts(v) + " · " + (v + 5) / 10 + " %"
            if (potSlider.value.toInt() != v) potSlider.value = v.toFloat()
        }
        val vis = if (show) View.VISIBLE else View.GONE
        if (potStrip.visibility != vis) potStrip.visibility = vis
        placeOverlays()
    }

    /** The slider just above the tab's bottom bar, the card above both */
    private fun placeOverlays() {
        val sp = potStrip.layoutParams as FrameLayout.LayoutParams
        if (sp.bottomMargin != bottomInset) { sp.bottomMargin = bottomInset; potStrip.layoutParams = sp }
        val stripH = if (potStrip.visibility == View.VISIBLE) potStrip.height else 0
        if (potStrip.visibility == View.VISIBLE && stripH == 0) { potStrip.post { placeOverlays() }; return }
        val want = bottomInset + stripH + dp(12)
        val lp = pinned.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != want) { lp.bottomMargin = want; pinned.layoutParams = lp }
    }

    /** -/+ by 1 %; held down, they keep going */
    @SuppressLint("ClickableViewAccessibility")
    private fun stepOnHold(b: View, by: Int) {
        val step = Runnable { if (openPot >= 0) setPot(openPot, potValue(openPot) + by) }
        val again = object : Runnable { override fun run() { step.run(); b.postDelayed(this, 70) } }
        b.setOnClickListener { step.run() }                // also what accessibility services press
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; step.run(); v.postDelayed(again, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; v.removeCallbacks(again) }
            }
            true
        }
    }

    private fun updatePinned() {
        updatePotButtons()          // the pot may have been turned on the Board meanwhile
        val order = vm.board.attachmentOrder(vm.chip)
        for (p in vm.board.parts.filter { it.pinnedOverTabs }) {
            val v = pinnedViews[p.id] as? TextView ?: continue
            if (v is Button) continue
            val name = vm.board.nameOf(p)
            when (p.type) {
                PartType.LED, PartType.RGB_LED -> {
                    var r = 0f; var g = 0f; var b = 0f
                    for (role in p.pins.indices) {
                        val i = order.indexOfFirst { it.first == p.id && it.second == role && it.third == Board.ATT_LED }
                        val lit = if (i < 0) 0f else (state.currentUa(i) / (p.currentMa * 1000f)).coerceIn(0f, 1f)
                        val c = if (p.type == PartType.RGB_LED) listOf(0xFF6B6B, 0x6FCF7F, 0x7FB8FF)[role] else (p.colour.argb.toInt() and 0xFFFFFF)
                        r += lit * ((c shr 16) and 255); g += lit * ((c shr 8) and 255); b += lit * (c and 255)
                    }
                    val dot = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setSize(dp(12), dp(12))
                        setColor((0xFF shl 24) or (minOf(255f, r).toInt() shl 16) or (minOf(255f, g).toInt() shl 8) or minOf(255f, b).toInt())
                        setStroke(1, 0xFF3A4650.toInt())
                    }
                    v.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                }
                PartType.PIEZO, PartType.SPEAKER -> {
                    val s = vm.chip.gpio(p.pins[0].pin)?.slot ?: continue
                    v.text = if (state.pwm(s)) SpannableStringBuilder(nameAndPins(p)).append("  ${state.freqMilliHz(s) / 1000} Hz") else nameAndPins(p)
                }
                else -> {}
            }
        }
    }
}
