/*
 * Solderless - finds what to colour in a finished console line: the
 * " ok." prompt, Forth's error messages and its warnings.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

enum class MessageKind { OK, ERROR, WARNING }

data class MessageRange(val start: Int, val end: Int, val kind: MessageKind)

object ConsoleStyle {

    // The messages are the ones Mecrisp-Stellaris prints. Each error ends
    // its line (the core prints it and returns to `quit`).

    private val ok = Regex(""" (ok\.)\s*$""")

    /** "foo not found." and "foo is compile-only.": the word itself is part of the message. */
    private val wordError = Regex("""(\S+ (?:not found\.|is compile-only\.))\s*$""")

    private val error = Regex(
        "(" + listOf(
            "Stack underflow", "Stack not balanced\\.",
            "Structures don't match", "Jump too far",
            "Ram full", "Flash full",
            "Create needs name !", "Variables collide with dictionary",
            "Cannot write into core !", "Flash cannot be written twice",
            "8flash! needs 8-aligned address", "hflash! needs even addresses\\.",
            "Too many scattered Flash writes\\.", "Too many unpaired 16-bit Flash writes\\.",
            "Unpaired 16-bit Flash write\\.", "Unhandled Interrupt .*",
        ).joinToString("|") + """)\s*$"""
    )

    private val redefine = Regex("""Redefine \S+\.""")
    private val notice = Regex("""(Finished\. Reset !)\s*$""")

    /**
     * What to colour in [line] (without its line break), looking only at the
     * part from [from] on, which skips the echo of what was typed.
     */
    fun messages(line: String, from: Int = 0): List<MessageRange> {
        val out = ArrayList<MessageRange>()
        val start = from.coerceIn(0, line.length)
        for (m in redefine.findAll(line, start)) out.add(MessageRange(m.range.first, m.range.last + 1, MessageKind.WARNING))

        val tail = line.substring(start)
        val found = ok.find(tail)?.let { it to MessageKind.OK }
            ?: wordError.find(tail)?.let { it to MessageKind.ERROR }
            ?: error.find(tail)?.let { it to MessageKind.ERROR }
            ?: notice.find(tail)?.let { it to MessageKind.WARNING }
        if (found != null) {
            val g = found.first.groups[1]!!
            out.add(MessageRange(start + g.range.first, start + g.range.last + 1, found.second))
        }
        return out
    }

    /**
     * True if [text] (a console line so far, from its start) begins with the
     * echo of [sent]: the line itself, with tabs echoed as spaces, and the
     * space Forth prints for Enter.
     */
    fun startsWithEcho(text: CharSequence, sent: String): Boolean {
        if (text.length < sent.length + 1) return false
        for (i in sent.indices) {
            val c = if (sent[i] == '\t') ' ' else sent[i]
            if (text[i] != c) return false
        }
        return text[sent.length] == ' '
    }
}
