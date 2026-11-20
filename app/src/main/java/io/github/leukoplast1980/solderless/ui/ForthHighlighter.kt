/*
 * Solderless - colours Forth source (editor, REPL input and echo) from
 * ForthLexer tokens, and Forth's messages in the console.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import io.github.leukoplast1980.solderless.R
import io.github.leukoplast1980.solderless.forth.LexResult
import io.github.leukoplast1980.solderless.forth.MessageKind
import io.github.leukoplast1980.solderless.forth.MessageRange
import io.github.leukoplast1980.solderless.forth.TokenKind

/** Our colour spans, so we only ever remove our own. */
class ForthSpan(color: Int) : ForegroundColorSpan(color)

/** Background of the line Forth rejected during a send. */
class ErrorLineSpan(color: Int) : BackgroundColorSpan(color)

class ForthHighlighter(context: Context) {

    private val colors = mapOf(
        TokenKind.COMMENT to context.getColor(R.color.syntax_comment),
        TokenKind.STRING to context.getColor(R.color.syntax_string),
        TokenKind.NUMBER to context.getColor(R.color.syntax_number),
        TokenKind.DEFINING to context.getColor(R.color.syntax_defining),
        TokenKind.DEFINED_NAME to context.getColor(R.color.syntax_name),
        TokenKind.CONTROL to context.getColor(R.color.syntax_control),
        TokenKind.SPECIAL to context.getColor(R.color.syntax_special),
        TokenKind.USER_WORD to context.getColor(R.color.accent),
        TokenKind.UNKNOWN to context.getColor(R.color.syntax_unknown),
    )
    private val errorColor = context.getColor(R.color.error_line)

    private val messageColors = mapOf(
        MessageKind.OK to context.getColor(R.color.accent),
        MessageKind.ERROR to context.getColor(R.color.syntax_unknown),
        MessageKind.WARNING to context.getColor(R.color.changed),
    )

    /**
     * Colours all of [text]. An unknown word ending at [typingAt] is left
     * alone: it is probably still being typed.
     */
    fun apply(text: Editable, result: LexResult, typingAt: Int = -1) {
        for (span in text.getSpans(0, text.length, ForthSpan::class.java)) text.removeSpan(span)
        val len = text.length
        for (t in result.tokens) {
            if (t.kind == TokenKind.UNKNOWN && t.end == typingAt) continue
            val color = colors[t.kind] ?: continue
            if (t.end <= len) text.setSpan(ForthSpan(color), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Colours tokens lexed from a piece of [text] that starts at [offset]. */
    fun applyAt(text: Editable, offset: Int, result: LexResult) {
        val len = text.length
        for (t in result.tokens) {
            val color = colors[t.kind] ?: continue
            if (offset + t.end <= len) {
                text.setSpan(ForthSpan(color), offset + t.start, offset + t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** Colours Forth's " ok.", errors and warnings in a console line starting at [offset]. */
    fun applyMessages(text: Editable, offset: Int, ranges: List<MessageRange>) {
        val len = text.length
        for (r in ranges) {
            if (offset + r.end <= len) {
                text.setSpan(ForthSpan(messageColors.getValue(r.kind)), offset + r.start, offset + r.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun markErrorLine(text: Editable, line: Int) {
        clearErrors(text)
        var start = 0
        var current = 1
        while (current < line && start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return
            start = nl + 1
            current++
        }
        var end = text.indexOf('\n', start)
        if (end < 0) end = text.length
        if (end > start) text.setSpan(ErrorLineSpan(errorColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun clearErrors(text: Editable) {
        for (span in text.getSpans(0, text.length, ErrorLineSpan::class.java)) text.removeSpan(span)
    }
}
