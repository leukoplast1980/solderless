/*
 * Solderless - the words Forth can currently find, for completion and
 * for naming the word the CPU is in.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

class Word(
    val name: String,
    val header: Int,
    val code: Int,
    val flags: Int,
    /** Defined by the user (RAM or user flash), not part of the core. */
    val user: Boolean,
) {
    val immediate: Boolean get() = (flags and FLAG_IMMEDIATE) != 0
    val variable: Boolean get() = (flags and FLAG_RAMALLOT) != 0

    companion object {
        const val FLAG_IMMEDIATE = 0x10
        const val FLAG_RAMALLOT = 0x80
    }
}

class Dictionary(val words: List<Word>) {

    /** Newest definition of each name (case-folded). */
    private val byName: Map<String, Word> = HashMap<String, Word>().also { m ->
        for (w in words) m.putIfAbsent(foldCase(w.name), w)
    }

    /** How Forth knows a case-folded name, or null if it does not. */
    fun lookup(folded: String): WordOrigin? =
        byName[folded]?.let { if (it.user) WordOrigin.USER else WordOrigin.CORE }

    /** Adds names Forth does not know yet (e.g. defined in an open file), for completion only. */
    fun plusNames(names: Collection<String>): Dictionary {
        val extra = names.filter { foldCase(it) !in byName }.map { Word(it, 0, 0, 0, user = true) }
        return if (extra.isEmpty()) this else Dictionary(words + extra)
    }

    /** Words sorted by code address, for [wordAt]. */
    private val byCode = words.sortedBy { it.code.toUInt() }
    private val headers = words.map { it.header.toUInt() }.sorted()

    /** Returns the word whose code contains [address], or null. */
    fun wordAt(address: Int): Word? {
        val a = address.toUInt()
        var lo = 0
        var hi = byCode.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (byCode[mid].code.toUInt() <= a) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (best < 0) return null
        val word = byCode[best]
        // The next header after this word's code must lie beyond the address.
        val next = headers.firstOrNull { it > word.code.toUInt() }
        return if (next == null || next > a) word else null
    }

    /**
     * Words starting with [prefix] (case-insensitive for a-z, as in Mecrisp),
     * user words first, then shorter names first. Each name appears once.
     */
    fun complete(prefix: String, limit: Int = 40): List<Word> {
        if (prefix.isEmpty()) return emptyList()
        val p = foldCase(prefix)
        val seen = HashSet<String>()
        return words.asSequence()
            .filter { foldCase(it.name).startsWith(p) && seen.add(foldCase(it.name)) }
            .sortedWith(compareBy<Word>({ !it.user }, { it.name.length }, { foldCase(it.name) }))
            .take(limit)
            .toList()
    }

    companion object {
        val EMPTY = Dictionary(emptyList())

        /** Mecrisp is case-insensitive for the letters a-z only. */
        fun foldCase(s: String): String {
            val b = StringBuilder(s.length)
            for (c in s) b.append(if (c in 'A'..'Z') c + 32 else c)
            return b.toString()
        }

        /** Decodes the arrays returned by NativeVm.dictionary. */
        fun fromNative(raw: Array<Any>?, userFlashStart: Int): Dictionary {
            if (raw == null || raw.size != 5) return EMPTY
            val names = raw[0] as ByteArray
            val lengths = raw[1] as IntArray
            val headers = raw[2] as IntArray
            val codes = raw[3] as IntArray
            val flags = raw[4] as IntArray
            val list = ArrayList<Word>(lengths.size)
            var off = 0
            for (i in lengths.indices) {
                val name = String(names, off, lengths[i], Charsets.UTF_8)
                off += lengths[i]
                val h = headers[i].toUInt()
                val user = h >= userFlashStart.toUInt()  // user flash and RAM lie above the core
                list.add(Word(name, headers[i], codes[i], flags[i], user))
            }
            return Dictionary(list)
        }
    }
}
