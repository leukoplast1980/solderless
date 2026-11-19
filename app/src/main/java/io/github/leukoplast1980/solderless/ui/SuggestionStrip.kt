/*
 * Solderless - the row of matching words above the keyboard.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.leukoplast1980.solderless.R

class SuggestionStrip(
    private val activity: Activity,
    private val scroll: HorizontalScrollView,
    private val container: LinearLayout,
) {
    private val userColor = activity.getColor(R.color.accent)

    fun update(target: InputTarget?) {
        container.removeAllViews()
        if (target == null) {
            scroll.visibility = View.GONE
            return
        }
        val token = target.currentToken()
        val matches = target.completionDictionary().complete(token)
        if (matches.isEmpty() || (matches.size == 1 && matches[0].name == token)) {
            scroll.visibility = View.GONE
            return
        }
        val inflater = LayoutInflater.from(activity)
        for (w in matches) {
            val chip = inflater.inflate(R.layout.suggestion, container, false) as TextView
            chip.text = w.name
            if (w.user) chip.setTextColor(userColor)
            chip.setOnClickListener { target.applyCompletion(token, w.name, true) }
            container.addView(chip)
        }
        scroll.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
    }
}
