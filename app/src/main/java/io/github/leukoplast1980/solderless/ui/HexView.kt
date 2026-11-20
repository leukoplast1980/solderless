package io.github.leukoplast1980.solderless.ui

import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.leukoplast1980.solderless.R

/**
 * A memory part's contents as offset, hex and ASCII, eight bytes a row, with
 * editing: tap a byte, type two hex digits (or text); edited bytes stay
 * amber until applied. Applying writes them straight into the chip, as an
 * external programmer would, so a 0 can become a 1 again. "Written" shows
 * only the rows that hold something; "All" every row of the chip.
 */
class HexView(
    private val activity: AppCompatActivity,
    private val title: String,
    private val size: Int,
    private val read: (addr: Int, len: Int) -> ByteArray,
    private val write: (addr: Int, data: ByteArray) -> Unit,
) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val cText = 0xFFE8EEE4.toInt()
    private val cErased = 0xFF56636A.toInt()
    private val cEdited = 0xFFFFB454.toInt()
    private val cAscii = 0xFF8FD18F.toInt()
    private val cLabel = 0xFF8A9690.toInt()
    private val cSelBg = 0xFF2C3A33.toInt()
    private val cAccent = 0xFF8FD18F.toInt()

    private val edits = sortedMapOf<Int, Byte>()
    private var sel = 0
    private var half = -1                   // the first hex digit typed for the selected byte, or -1
    private var all = false
    /** Rows shown: the address of each, or -1 for "erased" between runs */
    private var rows: List<Int> = emptyList()
    private val pages = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, ByteArray>) = size > 64
    }

    private lateinit var dialog: Dialog
    private lateinit var list: ListView
    private lateinit var sub: TextView
    private lateinit var apply: MaterialButton
    private lateinit var adapter: Adapter
    private var cellW = dp(26f)

    private fun byteAt(a: Int): Int {
        edits[a]?.let { return it.toInt() and 0xFF }
        val page = pages.getOrPut(a shr 12) { read((a shr 12) shl 12, 4096) }
        val i = a and 0xFFF
        return if (i < page.size) page[i].toInt() and 0xFF else 0xFF
    }

    /** Rows of 8 bytes that hold something other than FF, with a marker between runs */
    private fun writtenRows(): List<Int> {
        val out = ArrayList<Int>()
        var last = -16
        val chunk = 1 shl 16
        var a = 0
        while (a < size) {
            val b = read(a, chunk)
            var r = 0
            while (r < b.size) {
                var any = false
                for (k in 0 until 8) if (r + k < b.size && b[r + k] != 0xFF.toByte()) { any = true; break }
                val addr = a + r
                if (any || edits.subMap(addr, addr + 8).isNotEmpty()) {
                    if (out.isNotEmpty() && addr != last + 8) out += -1
                    out += addr; last = addr
                }
                r += 8
            }
            a += chunk
        }
        // the selected byte's row, so there is always somewhere to type
        val sr = sel and 7.inv()
        if (sr !in out) {
            val i = out.indexOfFirst { it > sr }.let { if (it < 0) out.size else it }
            out.add(i, sr)
            if (i > 0 && out[i - 1] != -1 && out[i - 1] != sr - 8) out.add(i, -1)
            val j = out.indexOf(sr)
            if (j + 1 < out.size && out[j + 1] != -1 && out[j + 1] != sr + 8) out.add(j + 1, -1)
        }
        if (out.lastOrNull() != -1) out += -1
        return out
    }

    private fun rebuild() {
        rows = if (all) emptyList() else writtenRows()
        adapter.notifyDataSetChanged()
    }

    private fun rowCount() = if (all) size / 8 else rows.size
    private fun rowAddr(i: Int) = if (all) i * 8 else rows[i]

    private fun positionOf(addr: Int): Int = if (all) addr / 8 else rows.indexOf(addr and 7.inv())

    fun show() {
        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.console_background))
        }
        // The bar: back, the title and the selection, Apply
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(activity.getColor(R.color.bar_background))
            setPadding(dp(4f), 0, dp(8f), 0)
        }
        bar.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "←"; textSize = 20f; setTextColor(cText); contentDescription = activity.getString(R.string.hex_back)
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(activity).apply { text = title; textSize = 17f; setTextColor(cText) })
        sub = TextView(activity).apply { textSize = 11.5f; setTextColor(cLabel) }
        titles.addView(sub)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        apply = MaterialButton(activity).apply {
            isAllCaps = false
            setOnClickListener { applyEdits() }
        }
        bar.addView(apply, LinearLayout.LayoutParams(-2, dp(44f)))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56f)))

        // Go to, and Written / All
        val tools = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        tools.addView(TextView(activity).apply { text = activity.getString(R.string.hex_goto); textSize = 12f; setTextColor(cLabel) })
        val goto = EditText(activity).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(cText); setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter { src, _, _, _, _, _ ->
                src.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.toString().uppercase() })
            imeOptions = EditorInfo.IME_ACTION_GO
            setText("%06X".format(0))
            background = GradientDrawable().apply { setStroke(dp(1f), 0xFF3A4650.toInt()); cornerRadius = dp(8f).toFloat(); setColor(0xFF161C21.toInt()) }
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setOnEditorActionListener { v, _, _ ->
                val a = v.text.toString().toIntOrNull(16)
                if (a != null && a < size) { sel = a; half = -1; if (!all) rebuild(); scrollTo(sel); refresh() }
                true
            }
        }
        tools.addView(goto, LinearLayout.LayoutParams(dp(92f), -2).apply { marginStart = dp(8f); marginEnd = dp(12f) })
        val toggle = MaterialButtonToggleGroup(activity).apply { isSingleSelection = true; isSelectionRequired = true }
        val bWritten = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); text = activity.getString(R.string.hex_written); isAllCaps = false }
        val bAll = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); text = activity.getString(R.string.hex_all); isAllCaps = false }
        toggle.addView(bWritten); toggle.addView(bAll)
        toggle.check(bWritten.id)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            all = id == bAll.id
            rebuild(); scrollTo(sel)
        }
        tools.addView(toggle)
        root.addView(tools)

        // Column heads and the rows
        val avail = activity.resources.displayMetrics.widthPixels - dp(20f) - dp(54f) - dp(78f)
        cellW = (avail / 8).coerceIn(dp(22f), dp(34f))
        root.addView(rowView(header = true).also { fillHeader(it) })
        list = ListView(activity).apply {
            divider = null; dividerHeight = 0; isVerticalScrollBarEnabled = true
            setPadding(dp(10f), 0, dp(10f), 0); clipToPadding = false
        }
        adapter = Adapter()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.hex_note); textSize = 11.5f; setTextColor(cLabel)
            setPadding(dp(12f), dp(6f), dp(12f), dp(4f))
        })

        // The keypad: 0-F, previous and next byte, text
        val keys = GridLayout(activity).apply {
            columnCount = 4
            setBackgroundColor(0xFF161C21.toInt())
            setPadding(dp(8f), dp(8f), dp(8f), dp(12f))
        }
        fun key(label: String, span: Int = 1, mono: Boolean = true, desc: String? = null, act: () -> Unit) {
            keys.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; isAllCaps = false; textSize = if (mono) 16f else 13f
                if (mono) typeface = Typeface.MONOSPACE
                setTextColor(cText); insetTop = 0; insetBottom = 0
                if (desc != null) contentDescription = desc
                setOnClickListener { act() }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, span, 1f)).apply {
                width = 0; height = dp(44f); setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
            })
        }
        for (k in "0123456789ABCDEF") key(k.toString()) { digit(k.digitToInt(16)) }
        key("←", desc = activity.getString(R.string.hex_prev)) { move(-1) }
        key("→", desc = activity.getString(R.string.hex_next)) { move(1) }
        key(activity.getString(R.string.hex_type_text), span = 2, mono = false) { typeText() }
        root.addView(keys)

        dialog.setContentView(root)
        dialog.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { close(); true } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        rebuild()
        refresh()
    }

    private fun close() {
        if (edits.isEmpty()) { dialog.dismiss(); return }
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.hex_discard, edits.size))
            .setPositiveButton(R.string.hex_discard_yes) { _, _ -> dialog.dismiss() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        sub.text = activity.getString(R.string.hex_sub, size / (1 shl 20), "%06X".format(sel))
        apply.text = activity.getString(R.string.hex_apply, edits.size)
        apply.isEnabled = edits.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun scrollTo(addr: Int) {
        val p = positionOf(addr)
        if (p >= 0) list.post { list.setSelectionFromTop(p, list.height / 3) }
    }

    private fun digit(v: Int) {
        if (half < 0) {
            half = v
            edits[sel] = ((v shl 4) or (byteAt(sel) and 0x0F)).toByte()
        } else {
            edits[sel] = ((half shl 4) or v).toByte()
            half = -1
            if (sel + 1 < size) sel++
            ensureRow(sel)
        }
        refresh()
    }

    private fun move(by: Int) {
        half = -1
        sel = (sel + by).coerceIn(0, size - 1)
        ensureRow(sel)
        refresh()
    }

    /** In "Written", a row the selection reaches is shown */
    private fun ensureRow(a: Int) {
        if (!all && (a and 7.inv()) !in rows) { rebuild(); scrollTo(a) }
    }

    private fun typeText() {
        val input = EditText(activity).apply { setSingleLine(); hint = activity.getString(R.string.hex_text_hint) }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.hex_text_title, "%06X".format(sel)))
            .setView(LinearLayout(activity).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val bytes = input.text.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) { if (sel >= size) break; edits[sel] = b; if (sel + 1 < size) sel++ }
                half = -1
                if (!all) rebuild()
                scrollTo(sel); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyEdits() {
        if (edits.isEmpty()) return
        var start = -1; var prev = -2
        val run = java.io.ByteArrayOutputStream()
        fun flush() { if (start >= 0) write(start, run.toByteArray()); run.reset() }
        for ((a, b) in edits) {
            if (a != prev + 1) { flush(); start = a }
            run.write(b.toInt()); prev = a
        }
        flush()
        val n = edits.size
        edits.clear(); pages.clear(); half = -1
        rebuild(); refresh()
        android.widget.Toast.makeText(activity, activity.getString(R.string.hex_applied, n), android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- rows ----------------------------------------------------------

    private fun rowView(header: Boolean = false): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(if (header) dp(10f) else 0, 0, 0, 0)
        }
        row.addView(TextView(activity).apply { typeface = Typeface.MONOSPACE; textSize = if (header) 11f else 12f; setTextColor(if (header) cErased else cLabel) },
            LinearLayout.LayoutParams(dp(54f), -2))
        for (k in 0 until 8) row.addView(TextView(activity).apply {
            typeface = Typeface.MONOSPACE; textSize = if (header) 11f else 13f; gravity = Gravity.CENTER
            setTextColor(if (header) cErased else cText)
        }, LinearLayout.LayoutParams(cellW, if (header) dp(22f) else dp(30f)).apply { marginEnd = dp(1f) })
        row.addView(TextView(activity).apply { typeface = Typeface.MONOSPACE; textSize = if (header) 11f else 13f
            letterSpacing = 0.05f; setTextColor(if (header) cErased else cAscii); setPadding(dp(6f), 0, 0, 0) },
            LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun fillHeader(row: LinearLayout) {
        (row.getChildAt(0) as TextView).text = activity.getString(R.string.hex_offset)
        for (k in 0 until 8) (row.getChildAt(1 + k) as TextView).text = "+$k"
        (row.getChildAt(9) as TextView).text = activity.getString(R.string.hex_ascii)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rowCount()
        override fun getItem(position: Int): Any = rowAddr(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rowAddr(position) < 0) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val addr = rowAddr(position)
            if (addr < 0) {
                val tv = convertView as? TextView ?: TextView(activity).apply {
                    textSize = 11f; setTextColor(cErased); typeface = Typeface.MONOSPACE
                    setPadding(dp(56f), dp(4f), 0, dp(4f))
                }
                tv.text = activity.getString(R.string.hex_erased)
                return tv
            }
            val row = convertView as? LinearLayout ?: rowView()
            (row.getChildAt(0) as TextView).text = "%06X".format(addr)
            val ascii = SpannableStringBuilder()
            for (k in 0 until 8) {
                val a = addr + k
                val tv = row.getChildAt(1 + k) as TextView
                if (a >= size) { tv.text = ""; tv.setOnClickListener(null); continue }
                val v = byteAt(a)
                val edited = a in edits
                tv.text = if (a == sel && half >= 0) "%X_".format(half) else "%02X".format(v)
                tv.setTextColor(if (edited) cEdited else if (v == 0xFF) cErased else cText)
                tv.background = if (a == sel) GradientDrawable().apply {
                    setColor(cSelBg); setStroke(dp(1.5f), cAccent); cornerRadius = dp(4f).toFloat() } else null
                tv.contentDescription = activity.getString(R.string.hex_byte, "%06X".format(a))
                tv.setOnClickListener { sel = a; half = -1; refresh() }
                val ch = if (v in 32..126) v.toChar() else '·'
                val st = ascii.length
                ascii.append(ch)
                ascii.setSpan(ForegroundColorSpan(if (edited) cEdited else if (ch == '·') cErased else cAscii), st, st + 1, 0)
            }
            (row.getChildAt(9) as TextView).text = ascii
            return row
        }
    }
}
