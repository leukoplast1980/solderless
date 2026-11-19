/*
 * Solderless - command history that survives restarts.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.forth

import java.io.File
import java.io.IOException

class History(private val file: File, private val max: Int = 500) {

    private val lines = ArrayList<String>()
    private var dirty = false

    /** Position while browsing with up/down; lines.size means "new line". */
    var position = 0
        private set

    init {
        try {
            if (file.exists()) lines.addAll(file.readLines().filter { it.isNotBlank() }.takeLast(max))
        } catch (e: IOException) {
            // Start with an empty history.
        }
        position = lines.size
    }

    fun add(line: String) {
        if (line.isNotBlank() && lines.lastOrNull() != line) {
            lines.add(line)
            if (lines.size > max) lines.removeAt(0)
            dirty = true
        }
        position = lines.size
    }

    /** Moves through the history; returns the line to show. */
    fun move(direction: Int): String {
        position = (position + direction).coerceIn(0, lines.size)
        return if (position < lines.size) lines[position] else ""
    }

    fun save() {
        if (!dirty) return
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(lines.joinToString("\n", postfix = "\n"))
            tmp.renameTo(file)
            dirty = false
        } catch (e: IOException) {
            // Not fatal: history is a convenience.
        }
    }
}
