/*
 * Solderless - the editor: open files as tabs, Forth highlighting,
 * undo/redo, find/replace, and sending source to Forth line by line.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.content.DialogInterface
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.files.Examples
import io.github.leukoplast1980.solderless.files.ProjectFolder
import io.github.leukoplast1980.solderless.files.SessionStore
import io.github.leukoplast1980.solderless.forth.Dictionary
import io.github.leukoplast1980.solderless.forth.ForthLexer
import io.github.leukoplast1980.solderless.forth.LexResult
import io.github.leukoplast1980.solderless.forth.LineResult
import io.github.leukoplast1980.solderless.forth.LineSender
import io.github.leukoplast1980.solderless.forth.SendResult
import io.github.leukoplast1980.solderless.vm.VmHost
import java.util.concurrent.Executors

class EditorController(
    private val activity: AppCompatActivity,
    private val vm: VmHost,
    private val folder: ProjectFolder,
    /** Text or cursor changed: update the suggestion strip. */
    private val onInputChanged: () -> Unit,
    /** Something was sent to Forth: its dictionary may have changed. */
    private val onSent: () -> Unit,
    /** Opens the system folder picker. */
    private val pickFolder: () -> Unit,
) : InputTarget {

    class Doc(
        var uri: Uri,
        var name: String,
        var text: String,
        var selection: Int = 0,
        var scrollY: Int = 0,
        var dirty: Boolean = false,
        val stacks: UndoHistory.Stacks = UndoHistory.Stacks(),
    )

    val editor: ForthEditor = activity.findViewById(R.id.editor)
    private val fileTabs: LinearLayout = activity.findViewById(R.id.fileTabs)
    private val highlighter = ForthHighlighter(activity)
    private val undoHistory = UndoHistory(editor)
    private val handler = Handler(Looper.getMainLooper())

    private val docs = ArrayList<Doc>()
    private var active = -1
    private var loading = false
    private var lex = LexResult(emptyList(), emptySet(), emptyList())

    @Volatile private var sending: LineSender? = null

    /** File writes, one after another: two quick saves must not finish out of order. */
    private val writer = Executors.newSingleThreadExecutor()

    private val highlightRunnable = Runnable { highlightNow() }

    init {
        editor.addTextChangedListener(undoHistory)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (loading) return
                current()?.let { if (!it.dirty) { it.dirty = true; rebuildTabs() } }
                highlighter.clearErrors(s)
                scheduleHighlight()
                onInputChanged()
            }
        })
        editor.onCursorMoved = { onInputChanged() }

        activity.findViewById<View>(R.id.editorFiles).setOnClickListener { showFiles() }
        activity.findViewById<View>(R.id.editorSend).setOnClickListener { showSendMenu(it) }
        activity.findViewById<View>(R.id.editorUndo).setOnClickListener { undoHistory.undo() }
        activity.findViewById<View>(R.id.editorRedo).setOnClickListener { undoHistory.redo() }
        activity.findViewById<View>(R.id.editorFind).setOnClickListener { showFind() }
        activity.findViewById<View>(R.id.editorSave).setOnClickListener { saveActive(announce = true) }
    }

    private fun current(): Doc? = docs.getOrNull(active)

    // ------------------------------------------------------------------
    // Session

    /**
     * Opens the files of the last session. On the very first start there is
     * none: then the scratch file and the light show example open, with the
     * example in front.
     */
    fun restore(files: List<SessionStore.OpenFile>, activeIndex: Int, firstStart: Boolean) {
        if (firstStart && files.isEmpty()) {
            openFirstStart()
            return
        }
        Thread {
            val loaded = files.mapNotNull { f ->
                try {
                    val uri = Uri.parse(f.uri)
                    Doc(uri, folder.nameOf(uri), folder.read(uri), f.selection, f.scrollY)
                } catch (e: Exception) {
                    null  // moved, deleted, or permission gone
                }
            }
            activity.runOnUiThread {
                docs.addAll(loaded)
                if (docs.isEmpty()) openScratch() else activate(activeIndex.coerceIn(0, docs.size - 1))
            }
        }.start()
    }

    fun sessionFiles(): List<SessionStore.OpenFile> {
        captureActive()
        return docs.map { SessionStore.OpenFile(it.uri.toString(), it.selection, it.scrollY) }
    }

    fun activeIndex(): Int = active

    // ------------------------------------------------------------------
    // Documents

    private fun openScratch() {
        val f = folder.scratchFile
        if (!f.exists()) f.writeText(activity.getString(R.string.scratch_text))
        openUri(Uri.fromFile(f))
    }

    private fun openFirstStart() {
        Thread {
            val f = folder.scratchFile
            val uris = ArrayList<Uri>()
            runCatching {
                if (!f.exists()) f.writeText(activity.getString(R.string.scratch_text))
                uris.add(Uri.fromFile(f))
            }
            runCatching { uris.add(Uri.fromFile(Examples.copy(activity, Examples.FIRST))) }
            val loaded = uris.mapNotNull { uri ->
                runCatching { Doc(uri, folder.nameOf(uri), folder.read(uri)) }.getOrNull()
            }
            activity.runOnUiThread {
                docs.addAll(loaded)
                if (docs.isEmpty()) openScratch() else activate(docs.size - 1)
            }
        }.start()
    }

    private fun openExample(name: String) {
        Thread {
            val uri = runCatching { Uri.fromFile(Examples.copy(activity, name)) }.getOrNull()
            activity.runOnUiThread {
                if (uri != null) openUri(uri) else toast(activity.getString(R.string.cannot_open, name))
            }
        }.start()
    }

    fun openUri(uri: Uri) {
        val i = docs.indexOfFirst { it.uri == uri }
        if (i >= 0) {
            activate(i)
            return
        }
        Thread {
            try {
                val doc = Doc(uri, folder.nameOf(uri), folder.read(uri))
                activity.runOnUiThread {
                    docs.add(doc)
                    activate(docs.size - 1)
                }
            } catch (e: Exception) {
                toast(activity.getString(R.string.cannot_open, uri.lastPathSegment ?: ""))
            }
        }.start()
    }

    private fun captureActive() {
        val d = current() ?: return
        d.text = editor.text.toString()
        d.selection = editor.selectionStart.coerceAtLeast(0)
        d.scrollY = editor.scrollY
    }

    private fun activate(index: Int) {
        captureActive()
        active = index
        val d = docs[index]
        loading = true
        undoHistory.suspended = true
        editor.setText(d.text)
        undoHistory.suspended = false
        loading = false
        undoHistory.stacks = d.stacks
        editor.setSelection(d.selection.coerceIn(0, editor.text.length))
        editor.post { editor.scrollTo(0, d.scrollY) }
        rebuildTabs()
        highlightNow()
        onInputChanged()
    }

    private fun rebuildTabs() {
        fileTabs.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for ((i, d) in docs.withIndex()) {
            val tab = inflater.inflate(R.layout.file_tab, fileTabs, false)
            val name = tab.findViewById<TextView>(R.id.tabName)
            name.text = d.name
            name.setTextColor(activity.getColor(if (i == active) R.color.accent else R.color.label))
            tab.findViewById<View>(R.id.tabUnsaved).visibility = if (d.dirty) View.VISIBLE else View.GONE
            tab.contentDescription = if (d.dirty) "${d.name}, ${activity.getString(R.string.unsaved)}" else d.name
            tab.isSelected = i == active
            tab.setOnClickListener { if (i != active) activate(i) }
            tab.setOnLongClickListener { showTabMenu(it, i); true }
            tab.findViewById<View>(R.id.tabClose).setOnClickListener { requestClose(i) }
            fileTabs.addView(tab)
        }
        // Keep the active tab in view when there are more tabs than fit.
        fileTabs.getChildAt(active)?.let { tab ->
            tab.post { tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), false) }
        }
    }

    private fun showTabMenu(anchor: View, index: Int) {
        val d = docs[index]
        val isScratch = d.uri.scheme == "file"
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, R.string.save)
        if (!isScratch) menu.menu.add(0, 2, 1, R.string.rename)
        menu.menu.add(0, 3, 2, R.string.close)
        if (!isScratch) menu.menu.add(0, 4, 3, R.string.delete)
        menu.menu.add(0, 5, 4, red(activity.getString(R.string.clear_content)))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { if (index == active) captureActive(); save(d, announce = true) }
                2 -> renameDialog(index)
                3 -> requestClose(index)
                4 -> deleteDialog(index)
                5 -> clearDialog(index)
            }
            true
        }
        menu.show()
    }

    private fun red(text: String) = SpannableString(text).apply {
        setSpan(ForegroundColorSpan(activity.getColor(R.color.danger)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Empties a file after asking. It is an ordinary edit: Undo brings the text back. */
    private fun clearDialog(index: Int) {
        val d = docs[index]
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.clear_question, d.name))
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                val i = docs.indexOf(d)
                if (i < 0) return@setPositiveButton
                if (i != active) activate(i)
                editor.text.replace(0, editor.text.length, "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(activity.getColor(R.color.danger))
    }

    /** Closes a tab; asks first when it has unsaved changes. */
    private fun requestClose(index: Int) {
        if (index == active) captureActive()
        val d = docs[index]
        if (!d.dirty) {
            close(index)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.unsaved_question, d.name))
            .setPositiveButton(R.string.save) { _, _ ->
                save(d, announce = false)
                docs.indexOf(d).takeIf { it >= 0 }?.let { close(it) }
            }
            .setNeutralButton(R.string.discard) { _, _ ->
                d.dirty = false
                docs.indexOf(d).takeIf { it >= 0 }?.let { close(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun close(index: Int) {
        if (index == active) captureActive()
        val d = docs[index]
        if (d.dirty) save(d, announce = false)
        docs.removeAt(index)
        active = -1
        if (docs.isEmpty()) {
            fileTabs.removeAllViews()
            openScratch()
        } else {
            activate(index.coerceAtMost(docs.size - 1))
        }
    }

    // ------------------------------------------------------------------
    // Saving

    fun saveActive(announce: Boolean) {
        captureActive()
        current()?.let { save(it, announce) }
    }

    /** Saves every changed file. Called when the app goes to the background. */
    fun saveAll() {
        captureActive()
        for (d in docs) if (d.dirty) save(d, announce = false)
    }

    private fun save(d: Doc, announce: Boolean) {
        val text = d.text
        val uri = d.uri
        d.dirty = false
        rebuildTabs()
        writer.execute {
            try {
                folder.write(uri, text)
                if (announce) toast(activity.getString(R.string.saved, d.name))
            } catch (e: Exception) {
                activity.runOnUiThread { d.dirty = true; rebuildTabs() }
                toast(activity.getString(R.string.cannot_save, d.name))
            }
        }
    }

    // ------------------------------------------------------------------
    // Files dialog

    fun showFiles() {
        Thread {
            val files = folder.list()
            val folderName = folder.folderName
            activity.runOnUiThread { filesDialog(files.map { (it.name ?: "?") to it.uri }, folderName) }
        }.start()
    }

    private fun filesDialog(files: List<Pair<String, Uri>>, folderName: String?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(activity.getString(R.string.scratch_file)); actions.add { openScratch() }
        for (name in Examples.list(activity)) {
            labels.add(activity.getString(R.string.example_file, name)); actions.add { openExample(name) }
        }
        for ((name, uri) in files) { labels.add(name); actions.add { openUri(uri) } }
        if (folderName != null) { labels.add(activity.getString(R.string.new_file)); actions.add { newFileDialog() } }
        labels.add(activity.getString(if (folderName == null) R.string.choose_folder else R.string.change_folder))
        actions.add { pickFolder() }

        MaterialAlertDialogBuilder(activity)
            .setTitle(folderName ?: activity.getString(R.string.no_folder))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun nameDialog(title: Int, initial: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelection(0, initial.substringBeforeLast('.').length)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !name.contains('/')) onOk(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newFileDialog() = nameDialog(R.string.new_file, "untitled.fs") { name ->
        Thread {
            val f = folder.create(name)
            if (f == null) toast(activity.getString(R.string.cannot_create, name))
            else activity.runOnUiThread { openUri(f.uri) }
        }.start()
    }

    private fun renameDialog(index: Int) {
        val d = docs[index]
        nameDialog(R.string.rename, d.name) { name ->
            if (index == active) captureActive()
            Thread {
                val newUri = folder.rename(d.uri, name)
                activity.runOnUiThread {
                    if (newUri == null) {
                        toast(activity.getString(R.string.cannot_rename, d.name))
                    } else {
                        d.uri = newUri
                        d.name = folder.nameOf(newUri)
                        rebuildTabs()
                    }
                }
            }.start()
        }
    }

    private fun deleteDialog(index: Int) {
        val d = docs[index]
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.delete_question, d.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    val ok = folder.delete(d.uri)
                    activity.runOnUiThread {
                        if (!ok) {
                            toast(activity.getString(R.string.cannot_delete, d.name))
                        } else {
                            d.dirty = false
                            close(docs.indexOf(d))
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Sending to Forth

    private enum class What { SELECTION, LINE, FILE }

    private fun showSendMenu(anchor: View) {
        val menu = PopupMenu(activity, anchor)
        if (editor.selectionEnd > editor.selectionStart) menu.menu.add(0, 1, 0, R.string.send_selection)
        menu.menu.add(0, 2, 1, R.string.send_line)
        menu.menu.add(0, 3, 2, R.string.send_file)
        menu.setOnMenuItemClickListener { item ->
            send(when (item.itemId) { 1 -> What.SELECTION; 2 -> What.LINE; else -> What.FILE })
            true
        }
        menu.show()
    }

    /** Ctrl+Enter: the selection if there is one, else the current line. */
    fun sendSelectionOrLine() = send(if (editor.selectionEnd > editor.selectionStart) What.SELECTION else What.LINE)

    private fun send(what: What) {
        if (sending != null) {
            toast(activity.getString(R.string.still_sending))
            return
        }
        captureActive()
        val d = current() ?: return
        val text = editor.text.toString()
        val selStart = editor.selectionStart.coerceIn(0, text.length)
        val selEnd = editor.selectionEnd.coerceIn(selStart, text.length)
        val (body, firstLine) = when (what) {
            What.SELECTION -> text.substring(selStart, selEnd) to lineNumberAt(text, selStart)
            What.LINE -> {
                val ls = lineStartOf(text, selStart)
                var le = text.indexOf('\n', selStart)
                if (le < 0) le = text.length
                text.substring(ls, le) to lineNumberAt(text, selStart)
            }
            What.FILE -> text to 1
        }
        val lines = LineSender.lines(body, d.name, firstLine)
        val sender = LineSender { vm.sendLineChecked(it, LINE_TIMEOUT_MS) }
        sending = sender
        Thread {
            val result = sender.run(lines)
            activity.runOnUiThread {
                sending = null
                onSent()
                report(result, d, what, lines.size)
            }
        }.start()
    }

    private fun report(result: SendResult, d: Doc, what: What, count: Int) {
        when (result) {
            is SendResult.Done -> {
                if (what == What.LINE && d === current()) moveToNextLine()
                else if (count > 1) toast(activity.getString(R.string.sent_lines, count))
            }
            is SendResult.Failed -> {
                if (d === current()) highlighter.markErrorLine(editor.text, result.line.number)
                val msg = when (result.reason) {
                    LineResult.TIMEOUT -> activity.getString(R.string.send_timeout, result.line.file, result.line.number)
                    LineResult.TOO_LONG -> activity.getString(
                        R.string.send_too_long, result.line.file, result.line.number, VmHost.MAX_LINE_BYTES,
                    )
                    else -> activity.getString(R.string.send_error, result.line.file, result.line.number)
                }
                toast(msg)
            }
            SendResult.Cancelled -> Unit
        }
    }

    private fun moveToNextLine() {
        val text = editor.text
        val nl = text.indexOf('\n', editor.selectionStart.coerceAtLeast(0))
        editor.setSelection(if (nl < 0) text.length else nl + 1)
    }

    private fun lineStartOf(text: String, pos: Int): Int {
        var p = pos
        while (p > 0 && text[p - 1] != '\n') p--
        return p
    }

    private fun lineNumberAt(text: String, pos: Int): Int {
        var n = 1
        for (i in 0 until pos.coerceAtMost(text.length)) if (text[i] == '\n') n++
        return n
    }

    // ------------------------------------------------------------------
    // Find and replace

    fun showFind() {
        val view = LayoutInflater.from(activity).inflate(R.layout.find_dialog, null)
        val find = view.findViewById<EditText>(R.id.findText)
        val replace = view.findViewById<EditText>(R.id.replaceText)
        val sel = editor.text.substring(
            editor.selectionStart.coerceAtLeast(0), editor.selectionEnd.coerceAtLeast(0),
        )
        if (sel.isNotEmpty() && !sel.contains('\n')) find.setText(sel)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setPositiveButton(R.string.find_next, null)
            .setNegativeButton(R.string.replace, null)
            .setNeutralButton(R.string.replace_all, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { findNext(find.text.toString()) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            replaceCurrent(find.text.toString(), replace.text.toString())
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            replaceAll(find.text.toString(), replace.text.toString())
        }
    }

    private fun findNext(query: String): Boolean {
        if (query.isEmpty()) return false
        val text = editor.text.toString()
        var i = text.indexOf(query, editor.selectionEnd.coerceAtLeast(0), ignoreCase = true)
        if (i < 0) i = text.indexOf(query, 0, ignoreCase = true)
        if (i < 0) {
            toast(activity.getString(R.string.not_found, query))
            return false
        }
        editor.requestFocus()
        editor.setSelection(i, i + query.length)
        return true
    }

    private fun replaceCurrent(query: String, replacement: String) {
        val s = editor.selectionStart
        val e = editor.selectionEnd
        if (e > s && editor.text.substring(s, e).equals(query, ignoreCase = true)) {
            editor.text.replace(s, e, replacement)
            editor.setSelection(s + replacement.length)
        }
        findNext(query)
    }

    /** One edit for all replacements, so one Undo takes them all back. */
    private fun replaceAll(query: String, replacement: String) {
        if (query.isEmpty()) return
        val old = editor.text.toString()
        val pattern = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        val count = pattern.findAll(old).count()
        if (count > 0) {
            val cursor = editor.selectionStart
            editor.text.replace(0, old.length, pattern.replace(old, Regex.escapeReplacement(replacement)))
            editor.setSelection(cursor.coerceIn(0, editor.text.length))
        }
        toast(activity.getString(R.string.replaced, count))
    }

    // ------------------------------------------------------------------
    // Highlighting

    fun scheduleHighlight() {
        handler.removeCallbacks(highlightRunnable)
        handler.postDelayed(highlightRunnable, HIGHLIGHT_DELAY_MS)
    }

    private fun highlightNow() {
        val dict = vm.dictionary
        lex = ForthLexer.lex(editor.text, dict::lookup)
        highlighter.apply(editor.text, lex)
    }

    // ------------------------------------------------------------------
    // Undo, redo, keyboard

    fun undo() = undoHistory.undo()
    fun redo() = undoHistory.redo()

    /** Hardware keyboard shortcuts while the editor has focus. Returns true if handled. */
    fun onKeyShortcut(event: KeyEvent): Boolean {
        if (!event.isCtrlPressed) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_S -> saveActive(announce = true)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> sendSelectionOrLine()
            KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) redo() else undo()
            KeyEvent.KEYCODE_Y -> redo()
            KeyEvent.KEYCODE_F -> showFind()
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // InputTarget

    override fun insertText(text: String) {
        val s = editor.selectionStart.coerceAtLeast(0)
        val e = editor.selectionEnd.coerceAtLeast(0)
        editor.text.replace(minOf(s, e), maxOf(s, e), text)
        editor.requestFocus()
    }

    override fun currentToken(): String {
        val text = editor.text
        val end = editor.selectionStart.coerceIn(0, text.length)
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        return text.subSequence(start, end).toString()
    }

    override fun applyCompletion(token: String, word: String, finished: Boolean) {
        val end = editor.selectionStart.coerceIn(0, editor.text.length)
        val start = (end - token.length).coerceAtLeast(0)
        val insert = word + if (finished) " " else ""
        editor.text.replace(start, end, insert)
        editor.setSelection(start + insert.length)
        editor.requestFocus()
    }

    override fun completionDictionary(): Dictionary = vm.dictionary.plusNames(lex.definedNamesAsWritten)

    override fun arrow(keyCode: Int) {
        val layout = editor.layout ?: return
        val pos = editor.selectionStart.coerceAtLeast(0)
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> pos - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> pos + 1
            else -> {
                val line = layout.getLineForOffset(pos) + if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                if (line < 0 || line >= layout.lineCount) pos
                else layout.getOffsetForHorizontal(line, layout.getPrimaryHorizontal(pos))
            }
        }
        editor.setSelection(target.coerceIn(0, editor.text.length))
        editor.requestFocus()
    }

    private fun toast(msg: String) = activity.runOnUiThread {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val LINE_TIMEOUT_MS = 10_000
        private const val HIGHLIGHT_DELAY_MS = 250L
    }
}
