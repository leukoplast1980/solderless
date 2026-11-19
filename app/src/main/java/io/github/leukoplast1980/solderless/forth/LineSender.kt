/*
 * Solderless - loads source into Forth line by line, stopping at the
 * first line Forth rejects (the way e4thcom and folie send files).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

/** Forth's answer to one line. TOO_LONG: longer than Forth's input buffer, not sent. */
enum class LineResult { OK, ERROR, TIMEOUT, TOO_LONG }

class SourceLine(val text: String, val file: String, val number: Int)

sealed class SendResult {
    class Done(val lines: Int) : SendResult()
    /** [reason] is ERROR, TIMEOUT or TOO_LONG. */
    class Failed(val line: SourceLine, val reason: LineResult) : SendResult()
    object Cancelled : SendResult()
}

class LineSender(private val send: (String) -> LineResult) {

    @Volatile var cancelled = false

    /** Sends the lines in order. Blocks until done: call off the UI thread. */
    fun run(lines: List<SourceLine>): SendResult {
        for (line in lines) {
            if (cancelled) return SendResult.Cancelled
            val r = send(line.text)
            if (r != LineResult.OK) return SendResult.Failed(line, r)
        }
        return SendResult.Done(lines.size)
    }

    companion object {
        /** Splits text into source lines numbered from [firstLine]. */
        fun lines(text: CharSequence, file: String, firstLine: Int = 1): List<SourceLine> =
            text.toString().replace("\r\n", "\n").split('\n')
                .mapIndexed { i, t -> SourceLine(t, file, firstLine + i) }
                .let { if (it.size > 1 && it.last().text.isEmpty()) it.dropLast(1) else it }
    }
}
