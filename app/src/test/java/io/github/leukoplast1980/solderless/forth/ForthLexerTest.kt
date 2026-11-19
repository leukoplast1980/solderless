/*
 * Solderless - tests for the Forth highlighter's tokenizer.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForthLexerTest {

    private val core = setOf("dup", "*", "+", ".", "emit", "drop", "swap")
    private val user = setOf("blink")

    private fun lookup(w: String): WordOrigin? = when (w) {
        in core -> WordOrigin.CORE
        in user -> WordOrigin.USER
        else -> null
    }

    /** "text" -> list of "word:KIND" for compact assertions. */
    private fun kinds(src: String): List<String> =
        ForthLexer.lex(src, ::lookup).tokens.map { "${src.substring(it.start, it.end)}:${it.kind}" }

    @Test
    fun colonDefinition() {
        assertEquals(
            listOf("::DEFINING", "sq:DEFINED_NAME", "dup:CORE_WORD", "*:CORE_WORD", ";:DEFINING"),
            kinds(": sq dup * ;"),
        )
    }

    @Test
    fun commentsAndStrings() {
        assertEquals(
            listOf("( n -- n2 ):COMMENT", "dup:CORE_WORD", "\\ rest of line:COMMENT", "drop:CORE_WORD"),
            kinds("( n -- n2 ) dup \\ rest of line\ndrop"),
        )
        assertEquals(listOf(".\" Hello Pocket\":STRING", "emit:CORE_WORD"), kinds(".\" Hello Pocket\" emit"))
        assertEquals(listOf(".( loading):STRING"), kinds(".( loading)"))
        // A string without its closing quote ends at the line end.
        assertEquals(listOf("s\" open:STRING", "dup:CORE_WORD"), kinds("s\" open\ndup"))
    }

    @Test
    fun parenNeedsToBeAWord() {
        // "(foo)" is a word, not a comment.
        assertEquals(listOf("(foo):UNKNOWN"), kinds("(foo)"))
    }

    @Test
    fun numbers() {
        for (n in listOf("42", "-7", "\$FF", "%1010", "#10", "3,14", "123.", "\$-1F", "-3,5")) {
            assertTrue(n, ForthLexer.isNumber(n))
        }
        for (n in listOf("-", "$", "%2", "\$G", "1,2,3", "abc", "1-", ",5")) {
            assertFalse(n, ForthLexer.isNumber(n))
        }
    }

    @Test
    fun userAndUnknownWords() {
        assertEquals(
            listOf("blink:USER_WORD", "later:USER_WORD", "nope:UNKNOWN", "::DEFINING", "later:DEFINED_NAME", ";:DEFINING"),
            kinds("blink later nope\n: later ;"),
        )
    }

    @Test
    fun controlAndSpecialWords() {
        assertEquals(
            listOf("compiletoflash:SPECIAL", "IF:CONTROL", "Then:CONTROL", "immediate:SPECIAL"),
            kinds("compiletoflash IF Then immediate"),
        )
    }

    @Test
    fun charLiteral() {
        assertEquals(listOf("char:CORE_WORD", "A:NUMBER", "emit:CORE_WORD"), kinds("char A emit"))
    }

    @Test
    fun definedNamesAreCollected() {
        val r = ForthLexer.lex("variable Count 5 constant five : Go ;", ::lookup)
        assertEquals(setOf("count", "five", "go"), r.definedNames)
        assertEquals(listOf("Count", "five", "Go"), r.definedNamesAsWritten)
    }
}
