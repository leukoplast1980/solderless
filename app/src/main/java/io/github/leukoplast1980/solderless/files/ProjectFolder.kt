/*
 * Solderless - the user's project folder, chosen once through the
 * system folder picker (Storage Access Framework) and visible to other apps.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

class ProjectFolder(private val context: Context) {

    private val prefs = context.getSharedPreferences("project", Context.MODE_PRIVATE)

    /** Always available, also before a folder is chosen. */
    val scratchFile = File(context.filesDir, "scratch.fs")

    val treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    fun setTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    private fun root(): DocumentFile? =
        treeUri?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canRead() }

    val folderName: String? get() = root()?.name

    /** Files in the folder, sorted by name. Slow on some providers: call off the UI thread. */
    fun list(): List<DocumentFile> =
        root()?.listFiles()?.filter { it.isFile }?.sortedBy { it.name?.lowercase() } ?: emptyList()

    fun read(uri: Uri): String =
        if (uri.scheme == "file") File(uri.path!!).readText()
        else context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw IOException("cannot open $uri")

    fun write(uri: Uri, text: String) {
        if (uri.scheme == "file") {
            File(uri.path!!).writeText(text)
            return
        }
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Creates an empty file. A generic type keeps providers from adding ".txt". */
    fun create(name: String): DocumentFile? = root()?.createFile("application/octet-stream", name)

    fun rename(uri: Uri, name: String): Uri? {
        val f = DocumentFile.fromSingleUri(context, uri) ?: return null
        return if (f.renameTo(name)) f.uri else null
    }

    fun delete(uri: Uri): Boolean = DocumentFile.fromSingleUri(context, uri)?.delete() ?: false

    fun nameOf(uri: Uri): String =
        if (uri.scheme == "file") File(uri.path!!).name
        else DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "?"

    companion object {
        private const val KEY_TREE = "tree"
    }
}
