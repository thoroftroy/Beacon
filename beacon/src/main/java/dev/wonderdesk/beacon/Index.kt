package dev.wonderdesk.beacon

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import java.io.File

/** Full-text index of file names and the text inside text-type files. */
class FileIndex(context: Context) : SQLiteOpenHelper(context, "beacon-index.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files (path TEXT PRIMARY KEY, name TEXT, size INTEGER, mtime INTEGER, dir INTEGER)")
        db.execSQL("CREATE INDEX files_name ON files(name)")
        db.execSQL("CREATE VIRTUAL TABLE content USING fts4(path, body, tokenize=unicode61)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { listOf("files", "content", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }; onCreate(db) }

    data class Hit(val path: String, val name: String, val size: Long, val mtime: Long, val dir: Boolean, val snippet: String? = null)

    val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "ini", "cfg", "conf", "yaml", "yml", "toml", "srt", "vtt",
        "kt", "java", "py", "js", "ts", "c", "cpp", "h", "rs", "go", "sh", "css", "sql", "tex", "rtf", "org", "gradle", "kts", "properties")

    @Volatile var cancel = false

    fun lastIndexed(): Long = readableDatabase.rawQuery("SELECT v FROM meta WHERE k='at'", null).use { if (it.moveToFirst()) it.getString(0).toLong() else 0L }
    fun count(): Pair<Int, Int> = readableDatabase.let { db ->
        db.rawQuery("SELECT COUNT(*) FROM files", null).use { it.moveToFirst(); it.getInt(0) } to db.rawQuery("SELECT COUNT(*) FROM content", null).use { it.moveToFirst(); it.getInt(0) }
    }

    /** Walks storage and refreshes changed entries. Reports (files seen, current folder). */
    fun rebuild(contents: Boolean, onProgress: (Int, String) -> Unit) {
        cancel = false
        val db = writableDatabase
        val known = HashMap<String, Long>()
        db.rawQuery("SELECT path, mtime FROM files", null).use { while (it.moveToNext()) known[it.getString(0)] = it.getLong(1) }
        val seen = HashSet<String>()
        var n = 0
        db.beginTransaction()
        try {
            fun walk(d: File, depth: Int) {
                if (cancel || depth > 24) return
                val kids = d.listFiles() ?: return
                if (n % 200 == 0) onProgress(n, d.path)
                for (f in kids) {
                    if (f.name.startsWith(".")) continue
                    val path = f.path; seen += path; n++
                    val mtime = f.lastModified()
                    if (known[path] != mtime) {
                        db.insertWithOnConflict("files", null, ContentValues().apply { put("path", path); put("name", f.name.lowercase()); put("size", if (f.isDirectory) 0 else f.length()); put("mtime", mtime); put("dir", if (f.isDirectory) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
                        if (contents && f.isFile && f.extension.lowercase() in TEXT_EXT && f.length() in 1..2_000_000) {
                            val body = runCatching { f.readText().take(400_000) }.getOrNull()
                            db.delete("content", "path = ?", arrayOf(path))
                            if (body != null) db.insert("content", null, ContentValues().apply { put("path", path); put("body", body) })
                        }
                    }
                    if (f.isDirectory && !path.endsWith("/Android/data") && !path.endsWith("/Android/obb")) walk(f, depth + 1)
                }
            }
            walk(Environment.getExternalStorageDirectory(), 0)
            if (!cancel) {
                known.keys.filter { it !in seen }.forEach { p -> db.delete("files", "path = ?", arrayOf(p)); db.delete("content", "path = ?", arrayOf(p)) }
                db.execSQL("INSERT OR REPLACE INTO meta VALUES('at', ?)", arrayOf(System.currentTimeMillis().toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun byName(q: String, limit: Int = 40): List<Hit> = readableDatabase.rawQuery(
        "SELECT path, name, size, mtime, dir FROM files WHERE name LIKE ? ORDER BY (name = ?) DESC, (name LIKE ?) DESC, mtime DESC LIMIT $limit",
        arrayOf("%${q.lowercase()}%", q.lowercase(), "${q.lowercase()}%")).use { c ->
        buildList { while (c.moveToNext()) add(Hit(c.getString(0), File(c.getString(0)).name, c.getLong(2), c.getLong(3), c.getInt(4) == 1)) }
    }

    fun inside(q: String, limit: Int = 30): List<Hit> {
        val terms = q.trim().split(Regex("\\s+")).filter { it.length >= 2 }.joinToString(" ") { t -> "\"" + t.replace("\"", "") + "\"*" }
        if (terms.isEmpty()) return emptyList()
        return runCatching {
            readableDatabase.rawQuery("SELECT path, snippet(content, '[', ']', '…', -1, 12) FROM content WHERE body MATCH ? LIMIT $limit", arrayOf(terms)).use { c ->
                buildList { while (c.moveToNext()) { val f = File(c.getString(0)); add(Hit(f.path, f.name, f.length(), f.lastModified(), false, c.getString(1))) } }
            }
        }.getOrDefault(emptyList())
    }

    fun clear() { writableDatabase.let { it.delete("files", null, null); it.delete("content", null, null); it.delete("meta", null, null) } }
}
