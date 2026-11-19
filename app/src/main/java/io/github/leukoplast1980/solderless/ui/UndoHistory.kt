/*
 * Solderless - undo and redo for the editor. Typing and deleting runs
 * are merged into one step, as in most editors.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

class UndoHistory(private val editText: EditText) : TextWatcher {

    class Edit(val start: Int, val before: String, val after: String, val time: Long)

    /** One document's history. */
    class Stacks {
        val undo = ArrayDeque<Edit>()
        val redo = ArrayDeque<Edit>()
    }

    var stacks = Stacks()

    /** True while text changes should not be recorded (undo, redo, loading a file). */
    var suspended = false

    private var pendingStart = 0
    private var pendingBefore = ""

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        if (suspended) return
        pendingStart = start
        pendingBefore = s.subSequence(start, start + count).toString()
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        if (suspended) return
        record(Edit(start, pendingBefore, s.subSequence(start, start + count).toString(), SystemClock.uptimeMillis()))
    }

    override fun afterTextChanged(s: Editable) {}

    private fun record(e: Edit) {
        if (e.before == e.after) return
        val last = stacks.undo.lastOrNull()
        val merged = when {
            last == null || e.time - last.time > MERGE_MS -> null
            // Typing: one more inserted piece right after the last one, no line break.
            e.before.isEmpty() && last.before.isEmpty() && e.start == last.start + last.after.length &&
                !e.after.contains('\n') -> Edit(last.start, "", last.after + e.after, e.time)
            // Backspacing: one more piece deleted right before the last one.
            e.after.isEmpty() && last.after.isEmpty() && e.start + e.before.length == last.start ->
                Edit(e.start, e.before + last.before, "", e.time)
            else -> null
        }
        if (merged != null) stacks.undo.removeLast()
        stacks.undo.addLast(merged ?: e)
        while (stacks.undo.size > MAX) stacks.undo.removeFirst()
        stacks.redo.clear()
    }

    fun undo() {
        val e = stacks.undo.removeLastOrNull() ?: return
        replace(e.start, e.after.length, e.before)
        stacks.redo.addLast(e)
    }

    fun redo() {
        val e = stacks.redo.removeLastOrNull() ?: return
        replace(e.start, e.before.length, e.after)
        stacks.undo.addLast(e)
    }

    private fun replace(start: Int, length: Int, text: String) {
        val t = editText.text
        if (start > t.length) return
        suspended = true
        t.replace(start, minOf(start + length, t.length), text)
        suspended = false
        editText.setSelection(minOf(start + text.length, t.length))
    }

    companion object {
        private const val MERGE_MS = 1000L
        private const val MAX = 500
    }
}
