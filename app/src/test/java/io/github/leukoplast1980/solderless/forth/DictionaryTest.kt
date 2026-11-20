/*
 * Solderless - tests for completion and word lookup.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryTest {

    // Core words at low addresses, a user flash word and a RAM word above.
    private val dict = Dictionary(
        listOf(
            Word("my-dup", 0x40534, 0x40540, 0, user = true),
            Word("dump", 0x8000, 0x800c, 0, user = true),
            Word("dup", 0x190, 0x19c, 0x461, user = false),
            Word("drop", 0x1a8, 0x1b8, 0x461, user = false),
            Word("DUPX", 0x1c4, 0x1d4, 0, user = false),
        )
    )

    @Test
    fun completesCaseInsensitivelyWithUserWordsFirst() {
        assertEquals(listOf("dump", "dup", "DUPX"), dict.complete("du").map { it.name })
        assertEquals(listOf("dump", "dup", "DUPX"), dict.complete("DU").map { it.name })
        assertEquals(listOf("my-dup"), dict.complete("my").map { it.name })
        assertEquals(emptyList<Word>(), dict.complete(""))
    }

    @Test
    fun findsTheWordContainingAnAddress() {
        assertEquals("dup", dict.wordAt(0x19c)?.name)
        assertEquals("dup", dict.wordAt(0x1a4)?.name)
        assertEquals("drop", dict.wordAt(0x1c0)?.name)
        assertEquals("my-dup", dict.wordAt(0x40550)?.name)
        // Inside a header, not code
        assertNull(dict.wordAt(0x1a8))
        // Before any word
        assertNull(dict.wordAt(0x10))
    }

    @Test
    fun foldsOnlyAsciiLetters() {
        assertEquals("abc-ÄÖ", Dictionary.foldCase("ABC-ÄÖ"))
    }
}
