/*
 * Solderless - the user's settings: text size, line wrap, keep screen on.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.settings

import android.content.Context

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Text size of the editor and the REPL, in sp. */
    var textSizeSp: Int
        get() = prefs.getInt(TEXT_SIZE, DEFAULT_TEXT_SIZE).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        set(v) = prefs.edit().putInt(TEXT_SIZE, v.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)).apply()

    /** Wrap long lines in the editor; off scrolls sideways instead. */
    var lineWrap: Boolean
        get() = prefs.getBoolean(LINE_WRAP, true)
        set(v) = prefs.edit().putBoolean(LINE_WRAP, v).apply()

    /** Keep the screen on while the app is in front. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON, false)
        set(v) = prefs.edit().putBoolean(KEEP_SCREEN_ON, v).apply()

    /** Order of the chip's pins along the Board tab's edges */
    var headerOrder: io.github.leukoplast1980.solderless.board.HeaderOrder
        get() = runCatching { io.github.leukoplast1980.solderless.board.HeaderOrder.valueOf(prefs.getString(HEADER_ORDER, "")!!) }
            .getOrDefault(io.github.leukoplast1980.solderless.board.HeaderOrder.ADAPTER)
        set(v) = prefs.edit().putString(HEADER_ORDER, v.name).apply()

    /** The Board tab grows taller when parts need room */
    var boardSpread: Boolean
        get() = prefs.getBoolean(BOARD_SPREAD, true)
        set(v) = prefs.edit().putBoolean(BOARD_SPREAD, v).apply()

    companion object {
        private const val BOARD_SPREAD = "boardSpread"
        private const val HEADER_ORDER = "headerOrder"
        const val MIN_TEXT_SIZE = 10
        const val MAX_TEXT_SIZE = 24
        const val DEFAULT_TEXT_SIZE = 14
        private const val TEXT_SIZE = "textSizeSp"
        private const val LINE_WRAP = "lineWrap"
        private const val KEEP_SCREEN_ON = "keepScreenOn"
    }
}
