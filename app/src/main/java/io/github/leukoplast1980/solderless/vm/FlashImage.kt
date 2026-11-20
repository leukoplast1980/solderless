/*
 * Solderless - facts about a flash image.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

object FlashImage {
    const val SIZE = 128 * 1024

    /** Mecrisp-Stellaris' flash dictionary starts here; its core lies below. */
    const val STELLARIS_USER_START = 0x5000

    /** True if no words were compiled into flash: the user area is erased ($FF). */
    fun userAreaEmpty(image: ByteArray): Boolean {
        for (i in STELLARIS_USER_START until image.size) if (image[i] != 0xFF.toByte()) return false
        return true
    }

    /** True if [image] starts with [core] (the rest may hold flash words). */
    fun startsWith(image: ByteArray, core: ByteArray): Boolean {
        if (image.size < core.size) return false
        for (i in core.indices) if (image[i] != core[i]) return false
        return true
    }

    /** A full flash image: [data] followed by erased flash. */
    fun pad(data: ByteArray): ByteArray {
        val out = ByteArray(SIZE) { 0xFF.toByte() }
        data.copyInto(out, 0, 0, minOf(data.size, SIZE))
        return out
    }
}
