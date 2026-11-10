package dev.wonderdesk.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

data class FileHit(val name: String, val path: String, val uri: Uri, val size: Long, val isText: Boolean)

/** Searches the media store and the public Download/Documents folders for
 *  names matching the query. Bounded so typing is fast; pure helpers below
 *  are unit-testable. */
fun searchFiles(context: Context, query: String, limit: Int = 30): List<FileHit> {
    val term = query.trim()
    if (term.length < 2) return emptyList()
    val hits = mutableListOf<FileHit>()
    val seen = mutableSetOf<String>()

    fun add(uri: Uri, name: String, size: Long, isText: Boolean) {
        if (hits.size >= limit || !seen.add(uri.toString())) return
        if (name.contains(term, ignoreCase = true)) {
            hits += FileHit(name, displayPath(uri), uri, size, isText)
        }
    }

    // Documents and downloads via MediaStore.Files (all visible files).
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
    )
    runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val rel = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (c.moveToNext() && hits.size < limit) {
                val uri = Uri.withAppendedPath(collection, c.getLong(id).toString())
                val n = c.getString(name) ?: continue
                val mimeType = c.getString(mime) ?: "application/octet-stream"
                add(uri, n, c.getLong(size), mimeType.startsWith("text/") || isTextExt(n))
            }
        }
    }

    // Public folders fallback (files MediaStore may not have indexed yet).
    val roots = listOf(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
    )
    for (root in roots) {
        runCatching { root.walkTopDown().maxDepth(6).filter { it.isFile && it.name.contains(term, ignoreCase = true) } }
            .getOrDefault(emptySequence())
            .forEach { f ->
                if (hits.size >= limit) return@forEach
                val key = f.canonicalPath
                if (seen.add(key)) {
                    hits += FileHit(f.name, f.parent ?: root.path, Uri.fromFile(f), f.length(), isTextExt(f.name))
                }
            }
    }
    return hits.take(limit)
}

private fun isTextExt(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "log", "csv", "json", "xml", "html", "kt", "java", "py", "sh", "ini", "conf", "yml", "yaml")

private fun displayPath(uri: Uri): String {
    val p = uri.path ?: return uri.toString()
    return p.substringAfter("/storage/emulated/0/", p)
}

fun mimeForName(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

fun openFileIntent(context: Context, hit: FileHit) {
    val type = mimeForName(hit.name)
    val open = Intent(Intent.ACTION_VIEW).setDataAndType(hit.uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (hit.isText) {
        // Text first: open in any text-capable editor.
        val text = Intent(Intent.ACTION_VIEW).setDataAndType(hit.uri, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { context.startActivity(Intent.createChooser(text, "Open text file")) }.isSuccess) return
    }
    context.launchSafely(Intent.createChooser(open, "Open file"), {})
}
