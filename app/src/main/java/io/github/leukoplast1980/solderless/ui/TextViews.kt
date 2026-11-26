/*
 * Solderless - setting a label only when its text changes. TextView lays
 * the window out again at every setText, even of the text it already
 * shows; a label refreshed many times a second kept the app drawing frames
 * while nothing changed.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import android.text.TextUtils
import android.widget.TextView

/** Shows [s] unless the view already shows the same characters (for plain text, not styled) */
fun TextView.setTextIfChanged(s: CharSequence) {
    if (!TextUtils.equals(text, s)) text = s
}
