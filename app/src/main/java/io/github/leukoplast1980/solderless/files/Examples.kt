/*
 * Solderless - example programs that come with the app (assets/examples).
 * The editor works on the app's own copy of an example, so edits can be
 * saved and are kept.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.files

import android.content.Context
import java.io.File

object Examples {
    private const val DIR = "examples"

    /** Opened on the very first start. */
    const val FIRST = "first-steps.fs"

    /** Names of the bundled examples. */
    fun list(context: Context): List<String> =
        context.assets.list(DIR)?.filter { it.endsWith(".fs") }?.sorted() ?: emptyList()

    /** The app's copy of an example, made from the bundled one on first use. Not on the UI thread. */
    fun copy(context: Context, name: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val f = File(dir, name)
        if (!f.exists()) {
            context.assets.open("$DIR/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }
}
