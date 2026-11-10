package dev.wonderdesk.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    // Pass 1: MediaStore documents/downloads, no permission needed.
    runCatching {
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        context.contentResolver.query(
            collection,
            arrayOf(
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.SIZE,
            ),
            "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("%$term%"),
            "${android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID)
            val name = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.SIZE)
            while (c.moveToNext() && hits.size < limit) {
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(id))
                val n = c.getString(name) ?: continue
                val rel = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH), null, null, null)?.use { r -> if (r.moveToFirst()) r.getString(0) else null }
                }.getOrNull() ?: ""
                if (seen.add(uri.toString())) {
                    hits += FileHit(n, rel ?: "", uri, c.getLong(size), isTextExt(n))
                }
            }
        }
    }

    // Pass 2: direct walk of the public folders for files MediaStore missed.
    val roots = mutableListOf<File>()
    for (name in listOf(
        android.os.Environment.DIRECTORY_DOWNLOADS,
        android.os.Environment.DIRECTORY_DOCUMENTS,
        android.os.Environment.DIRECTORY_DCIM,
        android.os.Environment.DIRECTORY_PICTURES,
        android.os.Environment.DIRECTORY_MUSIC,
        android.os.Environment.DIRECTORY_MOVIES,
        "Alarms", "Notifications", "Ringtones"
    )) {
        runCatching { android.os.Environment.getExternalStoragePublicDirectory(name) }.getOrNull()?.let { roots.add(it) }
    }
    roots.add(android.os.Environment.getExternalStorageDirectory())
    for (root in roots.distinctBy { it.absolutePath }) {
        if (hits.size >= limit) break
        runCatching {
            root.walkTopDown().maxDepth(6).forEach { f ->
                if (hits.size >= limit) return@forEach
                if (!f.isFile) return@forEach
                if (!f.name.contains(term, ignoreCase = true)) return@forEach
                val key = f.canonicalPath
                if (seen.add(key)) {
                    hits += FileHit(f.name, f.parent ?: root.path, Uri.fromFile(f), f.length(), isTextExt(f.name))
                }
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
