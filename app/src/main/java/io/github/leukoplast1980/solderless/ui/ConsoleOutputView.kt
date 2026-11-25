/*
 * Solderless - the console's output: a TextView whose accessibility events
 * and node carry only the end of its text. A plain TextView puts all of it
 * (up to 100 000 characters) into an event at every append; with output
 * streaming in, the binder buffers ran out and took the system down on the
 * test emulator. A screen reader wants the newest lines anyway.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.content.Context
import android.util.AttributeSet
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatTextView

class ConsoleOutputView(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        trim(event)
    }

    override fun onPopulateAccessibilityEvent(event: AccessibilityEvent) {
        super.onPopulateAccessibilityEvent(event)
        trim(event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val t = info.text ?: return
        if (t.length > LIMIT) info.text = t.subSequence(t.length - LIMIT, t.length)
    }

    private fun trim(event: AccessibilityEvent) {
        val items = event.text
        var trimmed = false
        for (i in items.indices) {
            val s = items[i] ?: continue
            if (s.length > LIMIT) { items[i] = s.subSequence(s.length - LIMIT, s.length); trimmed = true }
        }
        if (trimmed || event.itemCount > LIMIT) {
            event.itemCount = minOf(event.itemCount, LIMIT)
            event.fromIndex = -1
            event.toIndex = -1
            event.beforeText = null
        }
    }

    companion object {
        /** Characters an accessibility event or node carries: the last lines */
        const val LIMIT = 2000
    }
}
