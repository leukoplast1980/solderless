/*
 * Solderless - projects: one emulated board each, with its firmware, flash
 * contents and board settings, kept under filesDir/projects/<name>/.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.files

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

class Projects(context: Context) {

    private val root = File(context.filesDir, "projects")
    private val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)

    /** What runs on the chip */
    data class FirmwareInfo(
        /** "stellaris" for the bundled Mecrisp-Stellaris, "custom" for a loaded file */
        val kind: String,
        val name: String,
        val sha256: String,
    ) {
        val isStellaris: Boolean get() = kind == KIND_STELLARIS
    }

    /** Parts of the board that are not on the chip */
    data class Board(val hseHz: Int = 0, val lse: Boolean = false)

    data class Project(val name: String, val firmware: FirmwareInfo, val board: Board) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("firmware", JSONObject().put("kind", firmware.kind).put("name", firmware.name).put("sha256", firmware.sha256))
            .put("board", JSONObject().put("hseHz", board.hseHz).put("lse", board.lse))

        companion object {
            fun fromJson(o: JSONObject): Project {
                val f = o.getJSONObject("firmware")
                val b = o.optJSONObject("board") ?: JSONObject()
                return Project(
                    o.getString("name"),
                    FirmwareInfo(f.getString("kind"), f.getString("name"), f.optString("sha256")),
                    Board(b.optInt("hseHz", 0), b.optBoolean("lse", false)),
                )
            }
        }
    }

    fun dir(name: String) = File(root, name)
    fun flashFile(name: String) = File(dir(name), "flash.img")
    private fun jsonFile(name: String) = File(dir(name), "project.json")

    /** Names of all projects, sorted. */
    fun list(): List<String> =
        root.listFiles()?.filter { File(it, "project.json").isFile }?.map { it.name }?.sortedBy { it.lowercase() }
            ?: emptyList()

    var current: String?
        get() = prefs.getString(KEY_CURRENT, null)?.takeIf { jsonFile(it).isFile }
        set(v) { prefs.edit().putString(KEY_CURRENT, v).apply() }

    fun load(name: String): Project? = try {
        Project.fromJson(JSONObject(jsonFile(name).readText()))
    } catch (e: Exception) {
        null
    }

    fun save(p: Project) {
        dir(p.name).mkdirs()
        writeAtomically(jsonFile(p.name), p.toJson().toString(2).toByteArray())
    }

    /** Returns an error message, or null if the name can be used for a new project. */
    fun checkNewName(name: String): String? = when {
        name.isBlank() -> "Give the project a name."
        name.length > 40 -> "Use at most 40 characters."
        name.any { it in "/\\:*?\"<>|" } || name.startsWith(".") -> "Letters, digits, spaces, dashes and dots only."
        dir(name).exists() -> "A project with this name exists."
        else -> null
    }

    fun create(name: String, firmware: FirmwareInfo, flash: ByteArray, board: Board = Board()): Project {
        val p = Project(name.trim(), firmware, board)
        save(p)
        writeAtomically(flashFile(p.name), flash)
        return p
    }

    fun delete(name: String): Boolean = dir(name).deleteRecursively()

    fun readFlash(name: String, size: Int): ByteArray? = try {
        val f = flashFile(name)
        if (f.length() == size.toLong()) f.readBytes() else null
    } catch (e: IOException) {
        null
    }

    fun writeFlash(name: String, image: ByteArray) = writeAtomically(flashFile(name), image)

    private fun writeAtomically(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(data)
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) throw IOException("rename failed: $f")
    }

    companion object {
        const val KIND_STELLARIS = "stellaris"
        const val KIND_CUSTOM = "custom"
        const val FIRST_PROJECT = "My board"
        private const val KEY_CURRENT = "current"
    }
}
