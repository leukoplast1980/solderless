/*
 * Solderless - splits Forth source into coloured tokens, the way the
 * Forth interpreter reads it: words separated by whitespace, with a few
 * words that consume the following text (comments and strings).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

enum class TokenKind {
    COMMENT,       // \ ...  and ( ... )
    STRING,        // ." ..."  s" ..."  .( ... )
    NUMBER,        // 42  -7  $FF  %1010  #10  3,14  123.
    DEFINING,      // :  ;  constant  variable  create  does> ...
    DEFINED_NAME,  // the name after a defining word
    CONTROL,       // if then begin until do loop ...
    SPECIAL,       // compiletoflash  immediate  inline ...
    CORE_WORD,     // known word from the Mecrisp-Stellaris core
    USER_WORD,     // word defined by the user (dictionary or this text)
    UNKNOWN,       // not found anywhere
}

class Token(var kind: TokenKind, val start: Int, val end: Int)

class LexResult(
    val tokens: List<Token>,
    /** Names this text defines (case-folded), for completion and highlighting. */
    val definedNames: Set<String>,
    /** The same names as written. */
    val definedNamesAsWritten: List<String>,
)

/** How a word is known to Forth. */
enum class WordOrigin { CORE, USER }

object ForthLexer {

    /** Words whose next word is the name of a new definition. */
    private val NAMING = setOf(
        ":", "constant", "2constant", "variable", "2variable", "nvariable",
        "buffer:", "create", "<builds",
    )
    private val DEFINING = NAMING + setOf(";", "does>", ":noname", "[", "]", "exit")
    private val CONTROL = setOf(
        "if", "else", "then", "begin", "until", "while", "repeat", "again",
        "do", "?do", "loop", "+loop", "leave", "unloop", "i", "j", "k",
        "case", "of", "endof", "endcase", "?of", "recurse",
    )
    private val SPECIAL = setOf(
        "compiletoflash", "compiletoram", "eraseflash", "eraseflashfrom",
        "immediate", "inline", "compileonly", "foldable", "init",
        "0-foldable", "1-foldable", "2-foldable", "3-foldable",
        "4-foldable", "5-foldable", "6-foldable", "7-foldable",
    )
    /** Strings that end at the next double quote on the same line. */
    private val QUOTED = setOf(".\"", "s\"", "c\"", "abort\"")
    /** The next word is a character, not a word. */
    private val CHAR_WORDS = setOf("char", "[char]")

    /**
     * Splits [text] into tokens. [lookup] tells whether a case-folded word is
     * known to Forth.
     */
    fun lex(text: CharSequence, lookup: (String) -> WordOrigin?): LexResult {
        val tokens = ArrayList<Token>()
        val plain = ArrayList<Pair<Token, String>>()
        val defined = HashSet<String>()
        val definedAsWritten = ArrayList<String>()
        val n = text.length
        var i = 0
        var expectName = false
        var expectChar = false

        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            val end = i
            val word = text.subSequence(start, end).toString()
            val folded = fold(word)

            when {
                expectName -> {
                    tokens.add(Token(TokenKind.DEFINED_NAME, start, end))
                    if (defined.add(folded)) definedAsWritten.add(word)
                    expectName = false
                }
                expectChar -> {
                    tokens.add(Token(TokenKind.NUMBER, start, end))
                    expectChar = false
                }
                folded == "\\" -> {
                    val e = lineEnd(text, end)
                    tokens.add(Token(TokenKind.COMMENT, start, e))
                    i = e
                }
                folded == "(" || folded == ".(" -> {
                    val close = indexOf(text, ')', end, n)
                    val e = if (close < 0) n else close + 1
                    tokens.add(Token(if (folded == "(") TokenKind.COMMENT else TokenKind.STRING, start, e))
                    i = e
                }
                folded in QUOTED -> {
                    val le = lineEnd(text, end)
                    val close = indexOf(text, '"', end + 1, le)
                    val e = if (close < 0) le else close + 1
                    tokens.add(Token(TokenKind.STRING, start, e))
                    i = e
                }
                folded in NAMING -> {
                    tokens.add(Token(TokenKind.DEFINING, start, end))
                    expectName = true
                }
                folded in DEFINING -> tokens.add(Token(TokenKind.DEFINING, start, end))
                folded in CONTROL -> tokens.add(Token(TokenKind.CONTROL, start, end))
                folded in SPECIAL -> tokens.add(Token(TokenKind.SPECIAL, start, end))
                folded in CHAR_WORDS -> {
                    tokens.add(Token(TokenKind.CORE_WORD, start, end))
                    expectChar = true
                }
                isNumber(word) -> tokens.add(Token(TokenKind.NUMBER, start, end))
                else -> {
                    val t = Token(TokenKind.UNKNOWN, start, end)
                    tokens.add(t)
                    plain.add(t to folded)
                }
            }
        }

        // Words are classified after the whole text is read, so a word defined
        // further down still counts as the user's.
        for ((t, folded) in plain) {
            t.kind = when {
                folded in defined -> TokenKind.USER_WORD
                else -> when (lookup(folded)) {
                    WordOrigin.CORE -> TokenKind.CORE_WORD
                    WordOrigin.USER -> TokenKind.USER_WORD
                    null -> TokenKind.UNKNOWN
                }
            }
        }
        return LexResult(tokens, defined, definedAsWritten)
    }

    /**
     * Mecrisp number syntax: optional sign, optional base prefix (# decimal,
     * $ hex, % binary), digits, optional fixed-point part after a comma, or a
     * trailing dot for a double number.
     */
    fun isNumber(word: String): Boolean {
        var s = word
        if (s.startsWith("-")) s = s.substring(1)
        var base = 10
        when (s.firstOrNull()) {
            '#' -> { base = 10; s = s.substring(1) }
            '$' -> { base = 16; s = s.substring(1) }
            '%' -> { base = 2; s = s.substring(1) }
        }
        if (s.startsWith("-")) s = s.substring(1)
        if (s.endsWith(".")) s = s.dropLast(1)
        if (s.isEmpty()) return false
        val comma = s.indexOf(',')
        val whole = if (comma < 0) s else s.substring(0, comma)
        val frac = if (comma < 0) "" else s.substring(comma + 1)
        if (whole.isEmpty() || frac.contains(',')) return false
        return whole.all { Character.digit(it, base) >= 0 } && frac.all { Character.digit(it, base) >= 0 }
    }

    /** Mecrisp is case-insensitive for the letters a-z only. */
    fun fold(s: String): String = Dictionary.foldCase(s)

    private fun lineEnd(text: CharSequence, from: Int): Int {
        var e = from
        while (e < text.length && text[e] != '\n') e++
        return e
    }

    private fun indexOf(text: CharSequence, c: Char, from: Int, until: Int): Int {
        var k = from
        while (k < until) {
            if (text[k] == c) return k
            k++
        }
        return -1
    }
}
