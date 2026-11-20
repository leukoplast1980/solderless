/*
 * Solderless - what the board looks like right now (see jni.c boardRead).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

class BoardState(val raw: IntArray = IntArray(SIZE)) {
    fun mv(slot: Int) = raw[slot]
    fun flags(slot: Int) = raw[SLOTS + slot]
    fun mode(slot: Int) = raw[2 * SLOTS + slot]
    /** Time high since the last read, per mille */
    fun duty(slot: Int) = raw[3 * SLOTS + slot]
    fun freqMilliHz(slot: Int) = raw[4 * SLOTS + slot].toLong() and 0xFFFFFFFFL
    fun pwmDuty(slot: Int) = raw[5 * SLOTS + slot]
    /** Average current through attachment [i] in uA */
    fun currentUa(i: Int) = raw[6 * SLOTS + i]

    fun high(slot: Int) = flags(slot) and HIGH != 0
    fun floating(slot: Int) = flags(slot) and FLOATING != 0
    fun pwm(slot: Int) = flags(slot) and PWM != 0
    fun shorted(slot: Int) = flags(slot) and SHORT != 0
    fun undefined(slot: Int) = flags(slot) and UNDEFINED != 0

    companion object {
        const val SLOTS = 112
        const val MAX_ATTACH = 64
        const val SIZE = 6 * SLOTS + MAX_ATTACH + 2
        const val HIGH = 1; const val FLOATING = 2; const val SHORT = 4; const val PWM = 8; const val UNDEFINED = 16
        const val MODE_INPUT = 0; const val MODE_OUTPUT = 1; const val MODE_AF = 2; const val MODE_ANALOG = 3
    }
}
