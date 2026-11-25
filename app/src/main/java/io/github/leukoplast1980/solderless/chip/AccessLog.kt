/*
 * Solderless - what the firmware did to the chip, for the Chip tab's Log:
 * every register write (from the emulator's access log) and the chip
 * model's messages, oldest first, the last few thousand.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.chip

class AccessLog {

    sealed class Entry { abstract val timeNs: Long }

    /** Writes from one instruction to one register: [count] of them, [lastNs] the last */
    class Write(
        override val timeNs: Long, var lastNs: Long, val pc: Long, val addr: Long,
        var value: Long, var mask: Long, val before: Long, var after: Long, var changed: Long, var count: Long,
    ) : Entry() {
        /** Made from the Registers view, not by the firmware */
        val debug: Boolean get() = pc == DEBUG
    }

    class Message(override val timeNs: Long, val text: String) : Entry()

    private val entries = ArrayDeque<Entry>()

    /** Entries the emulator dropped (more than it keeps came between two reads) */
    var lost = 0L
        private set

    /** Goes up whenever the log changes */
    var generation = 0L
        private set

    val size: Int get() = entries.size
    operator fun get(i: Int): Entry = entries[i]

    /** [n] entries as the JNI's accessRead gives them (after out[0], 12 ints each) */
    fun addWrites(raw: IntArray, n: Int) {
        lost += raw[0].toLong() and MASK
        for (i in 0 until n) {
            val p = 1 + 12 * i
            fun u(k: Int) = raw[p + k].toLong() and MASK
            add(Write(
                u(0) or (u(1) shl 32), u(2) or (u(3) shl 32), u(4), u(5),
                u(6), u(7), u(8), u(9), u(10), u(11),
            ))
        }
        if (n > 0 || raw[0] != 0) generation++
    }

    /**
     * A write: counted into a recent entry from the same instruction to the
     * same register, which moves to the end (the log is in the order of the
     * last write).
     */
    fun add(w: Write) {
        for (k in 1..minOf(FOLD, entries.size)) {
            val i = entries.size - k
            val e = entries[i] as? Write ?: continue
            if (e.pc == w.pc && e.addr == w.addr) {
                e.lastNs = w.lastNs
                e.value = w.value
                e.mask = e.mask or w.mask
                e.changed = e.changed or w.changed or (e.after xor w.before)
                e.after = w.after
                e.count += w.count
                if (k > 1) { entries.removeAt(i); entries.addLast(e) }
                return
            }
        }
        push(w)
    }

    /** Chip messages, one per line, at about [timeNs] */
    fun addMessages(text: String, timeNs: Long) {
        for (l in text.lines()) if (l.isNotBlank()) push(Message(timeNs, l.trim()))
        generation++
    }

    fun clear() {
        entries.clear()
        lost = 0
        generation++
    }

    /** The newest write to [addr] that changed a bit of [mask] (or wrote one, for write-only registers) */
    fun lastWrite(addr: Long, mask: Long, writeOnly: Boolean): Write? {
        for (i in entries.indices.reversed()) {
            val e = entries[i] as? Write ?: continue
            if (e.addr != addr) continue
            if (if (writeOnly) e.value and mask != 0L else e.changed and mask != 0L) return e
        }
        return null
    }

    private fun push(e: Entry) {
        entries.addLast(e)
        while (entries.size > MAX) entries.removeFirst()
    }

    companion object {
        const val DEBUG = 0xFFFFFFFFL
        const val MAX = 3000
        private const val FOLD = 16
        private const val MASK = 0xFFFFFFFFL
    }
}
