/*
 * Solderless - remembers open files, cursor positions, the active view
 * and the end of the console output between runs of the app.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class SessionStore(context: Context) {

    class OpenFile(val uri: String, val selection: Int, val scrollY: Int)

    class Session(
        val files: List<OpenFile> = emptyList(),
        val activeFile: Int = 0,
        val screen: String = "REPL",
        val rawMode: Boolean = false,
    )

    private val sessionFile = File(context.filesDir, "session.json")
    private val consoleFile = File(context.filesDir, "console.txt")

    /** True until the first session is saved: the app has never been closed yet. */
    val firstStart: Boolean get() = !sessionFile.exists()

    fun load(): Session = try {
        val o = JSONObject(sessionFile.readText())
        val arr = o.optJSONArray("files") ?: JSONArray()
        Session(
            files = (0 until arr.length()).map {
                val f = arr.getJSONObject(it)
                OpenFile(f.getString("uri"), f.optInt("selection"), f.optInt("scrollY"))
            },
            activeFile = o.optInt("activeFile"),
            screen = o.optString("screen", "REPL"),
            rawMode = o.optBoolean("rawMode"),
        )
    } catch (e: Exception) {
        Session()
    }

    fun save(s: Session) {
        val o = JSONObject()
        o.put("files", JSONArray().apply {
            for (f in s.files) put(JSONObject().put("uri", f.uri).put("selection", f.selection).put("scrollY", f.scrollY))
        })
        o.put("activeFile", s.activeFile)
        o.put("screen", s.screen)
        o.put("rawMode", s.rawMode)
        writeAtomically(sessionFile, o.toString())
    }

    fun loadConsole(): String = try { consoleFile.readText() } catch (e: IOException) { "" }

    fun saveConsole(text: CharSequence) = writeAtomically(consoleFile, text.toString())

    private fun writeAtomically(f: File, text: String) {
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (e: IOException) {
            // Losing the session is not fatal.
        }
    }
}
