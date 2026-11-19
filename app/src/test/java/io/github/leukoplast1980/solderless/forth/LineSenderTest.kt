/*
 * Solderless - tests for line-by-line loading.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSenderTest {

    @Test
    fun sendsAllLinesWhenForthAcceptsThem() {
        val sent = ArrayList<String>()
        val r = LineSender { sent.add(it); LineResult.OK }.run(LineSender.lines("a\nb\r\nc\n", "f.fs"))
        assertEquals(listOf("a", "b", "c"), sent)
        assertTrue(r is SendResult.Done && r.lines == 3)
    }

    @Test
    fun stopsAtTheFirstError() {
        val sent = ArrayList<String>()
        val r = LineSender { sent.add(it); if (it == "bad") LineResult.ERROR else LineResult.OK }
            .run(LineSender.lines("ok1\nbad\nnever", "f.fs", firstLine = 10))
        assertEquals(listOf("ok1", "bad"), sent)
        assertTrue(r is SendResult.Failed && r.reason == LineResult.ERROR && r.line.number == 11 && r.line.file == "f.fs")
    }

    @Test
    fun reportsTimeouts() {
        val r = LineSender { LineResult.TIMEOUT }.run(LineSender.lines("spin", "x"))
        assertTrue(r is SendResult.Failed && r.reason == LineResult.TIMEOUT)
    }

    @Test
    fun keepsEmptyLinesForLineNumbers() {
        assertEquals(listOf("", "x", ""), LineSender.lines("\nx\n\n", "f").map { it.text })
    }
}
