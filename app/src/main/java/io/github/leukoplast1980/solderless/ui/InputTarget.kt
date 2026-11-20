/*
 * Solderless - what the suggestion strip and the extra keys type into:
 * the REPL input or the editor.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import io.github.leukoplast1980.solderless.forth.Dictionary
import io.github.leukoplast1980.solderless.forth.Word

interface InputTarget {
    /** Inserts text at the cursor (or sends it, in raw mode). */
    fun insertText(text: String)

    /** The partial word before the cursor. */
    fun currentToken(): String

    /** Replaces [token] before the cursor with [word]; adds a space if [finished]. */
    fun applyCompletion(token: String, word: String, finished: Boolean)

    /** Words to offer for completion. */
    fun completionDictionary(): Dictionary

    /** An arrow key from the extra key row (KeyEvent.KEYCODE_DPAD_*). */
    fun arrow(keyCode: Int)
}

object Completion {
    /** Tab: completes a unique match, or as far as all matches agree. */
    fun tab(target: InputTarget) {
        val token = target.currentToken()
        val matches = target.completionDictionary().complete(token, limit = 1000)
        if (matches.isEmpty()) return
        if (matches.size == 1) {
            target.applyCompletion(token, matches[0].name, true)
        } else {
            val prefix = commonPrefix(matches)
            if (prefix.length > token.length) target.applyCompletion(token, prefix, false)
        }
    }

    fun commonPrefix(words: List<Word>): String {
        var prefix = words[0].name
        for (w in words) {
            var n = 0
            while (n < prefix.length && n < w.name.length &&
                Dictionary.foldCase(prefix[n].toString()) == Dictionary.foldCase(w.name[n].toString())
            ) n++
            prefix = prefix.substring(0, n)
        }
        return prefix
    }
}
