/*
 * Solderless - the extra key row: Forth symbols, Tab and arrows.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import io.github.leukoplast1980.solderless.R

class ExtraKeys(container: LinearLayout, private val target: () -> InputTarget?) {

    init {
        val inflater = LayoutInflater.from(container.context)
        for (label in KEYS) {
            val key = inflater.inflate(R.layout.extra_key, container, false) as TextView
            key.text = label
            key.setOnClickListener { press(label) }
            container.addView(key)
        }
    }

    private fun press(label: String) {
        val t = target() ?: return
        when (label) {
            "Tab" -> Completion.tab(t)
            "←" -> t.arrow(KeyEvent.KEYCODE_DPAD_LEFT)
            "→" -> t.arrow(KeyEvent.KEYCODE_DPAD_RIGHT)
            "↑" -> t.arrow(KeyEvent.KEYCODE_DPAD_UP)
            "↓" -> t.arrow(KeyEvent.KEYCODE_DPAD_DOWN)
            else -> t.insertText(label)
        }
    }

    companion object {
        private val KEYS = listOf(
            "Tab", ":", ";", "(", ")", "@", "!", ".", "\"", "'", "$", "#", "%",
            "←", "→", "↑", "↓",
        )
    }
}
