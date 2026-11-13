package dev.wonderdesk.beacon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class MainActivity : WonderActivity() {
    @Composable override fun Content() = BeaconApp()
}

/** What Android must allow for this app to work fully; shown as a banner when off and in Settings. */
private val APP_NEEDS by lazy { listOf(
    WonderNeed("All files access", "Finds documents by name and by the words inside them", required = false, access = SpecialAccess.Kind.ALL_FILES),
    WonderNeed("Contacts", "Finds people as you type", required = false, permissions = listOf(android.Manifest.permission.READ_CONTACTS)),
) }

private const val ABOUT = "Beacon is the front door to your phone: every app with folders and sorting, and one search box for apps, settings, contacts, files, the words inside files, sums, unit conversions and the web. " +
    "The Wonderdesk Launcher uses Beacon for its search. The file index is built and kept on this phone."

/* --------------------------------------------------- apps ------------------------------------------------- */

data class AppHit(val key: String, val pkg: String, val component: android.content.ComponentName, val label: String, val icon: ImageBitmap?, val aliases: List<String>, val installed: Long, val category: Int)

internal fun loadApps(c: Context): List<AppHit> {
    val pm = c.packageManager
    val aliases = runCatching { SharedCatalog.aliases(c) }.getOrDefault(emptyMap())
    val la = c.getSystemService(android.content.pm.LauncherApps::class.java)
    return la.getActivityList(null, android.os.Process.myUserHandle()).map { ai ->
        val pkg = ai.componentName.packageName
        // Wonderdesk apps show their full artwork; others their own icon.
        val art = if (pkg.startsWith("dev.wonderdesk.")) runCatching {
            val res = pm.getResourcesForApplication(pkg)
            val id = listOf("wonderdesk_${pkg.substringAfterLast('.')}_icon", "wonderdesk_icon").map { res.getIdentifier(it, "drawable", pkg) }.first { it != 0 }
            android.graphics.BitmapFactory.decodeResource(res, id, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.let { android.graphics.Bitmap.createScaledBitmap(it, 144, (144f * it.height / it.width).toInt(), true).asImageBitmap() }
        }.getOrNull() else null
        AppHit(ai.componentName.flattenToShortString(), pkg, ai.componentName, ai.label.toString(), art ?: runCatching { ai.getIcon(c.resources.displayMetrics.densityDpi).toBitmap(144, 144).asImageBitmap() }.getOrNull(),
            if (pkg.startsWith("dev.wonderdesk.")) aliases[pkg.substringAfterLast('.')].orEmpty() else emptyList(),
            runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L), ai.applicationInfo.category)
    }.sortedBy { it.label.lowercase() }
}

data class AppGroup(val id: String, val name: String, val apps: List<String>)

/**
 * App folders. With the Wonderdesk Launcher installed they're the same folders as its Launchpad
 * (kept by the Launcher); otherwise Beacon keeps its own.
 */
object Folders {
    private val URI = Uri.parse("content://dev.wonderdesk.launcher.apps")
    fun shared(c: Context) = c.packageManager.resolveContentProvider("dev.wonderdesk.launcher.apps", 0) != null
    private fun local(c: Context) = c.getSharedPreferences("beacon-folders", Context.MODE_PRIVATE)

    fun groups(c: Context): List<AppGroup> = if (shared(c)) runCatching {
        c.contentResolver.query(Uri.withAppendedPath(URI, "groups"), null, null, null, null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(AppGroup(cur.getString(0), cur.getString(1), JSONArray(cur.getString(2)).let { a -> (0 until a.length()).map { a.getString(it) } })) }
        }
    }.getOrNull().orEmpty() else runCatching {
        JSONArray(local(c).getString("groups", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o -> AppGroup(o.getString("id"), o.getString("name"), o.getJSONArray("apps").let { x -> (0 until x.length()).map { x.getString(it) } }) } }
    }.getOrDefault(emptyList())

    private fun saveLocal(c: Context, list: List<AppGroup>) = local(c).edit().putString("groups", JSONArray(list.map { g -> org.json.JSONObject().put("id", g.id).put("name", g.name).put("apps", JSONArray(g.apps)) }).toString()).apply()

    private fun call(c: Context, method: String, arg: String?, extras: android.os.Bundle? = null) = runCatching { c.contentResolver.call(URI, method, arg, extras) }

    fun addTo(c: Context, group: String, key: String) = if (shared(c)) call(c, "addToGroup", key, android.os.Bundle().apply { putString("group", group) }).let { }
        else saveLocal(c, groups(c).map { g -> if (g.id == group) g.copy(apps = (g.apps + key).distinct()) else g.copy(apps = g.apps - key) }.filter { it.apps.isNotEmpty() })
    fun create(c: Context, name: String, key: String) = if (shared(c)) call(c, "newGroup", key, android.os.Bundle().apply { putString("name", name) }).let { }
        else saveLocal(c, groups(c).map { it.copy(apps = it.apps - key) }.filter { it.apps.isNotEmpty() } + AppGroup(java.util.UUID.randomUUID().toString(), name, listOf(key)))
    /** A new folder holding both apps (drop one app onto another); returns its id. */
    fun createWith(c: Context, name: String, first: String, second: String): String? {
        create(c, name, first)
        val g = groups(c).find { first in it.apps } ?: return null
        addTo(c, g.id, second); return g.id
    }
    fun takeOut(c: Context, key: String) = if (shared(c)) call(c, "removeFromGroups", key).let { } else saveLocal(c, groups(c).map { it.copy(apps = it.apps - key) }.filter { it.apps.isNotEmpty() })
    fun rename(c: Context, group: String, name: String) = if (shared(c)) call(c, "renameGroup", name, android.os.Bundle().apply { putString("group", group) }).let { } else saveLocal(c, groups(c).map { if (it.id == group) it.copy(name = name) else it })
    fun delete(c: Context, group: String) = if (shared(c)) call(c, "deleteGroup", group).let { } else saveLocal(c, groups(c).filter { it.id != group })
    fun launches(c: Context): Map<String, Pair<Int, Long>> = if (shared(c)) runCatching {
        c.contentResolver.query(Uri.withAppendedPath(URI, "launches"), null, null, null, null)?.use { cur -> buildMap { while (cur.moveToNext()) put(cur.getString(0), cur.getInt(1) to cur.getLong(2)) } }
    }.getOrNull().orEmpty() else emptyMap()
    fun recordLaunch(c: Context, key: String) { if (shared(c)) call(c, "recordLaunch", key) }
    fun addToHome(c: Context, key: String): Boolean = shared(c) && call(c, "addToHome", key).getOrNull()?.getBoolean("ok", true) == true
    fun addToDock(c: Context, key: String): Boolean = shared(c) && call(c, "addToDock", key).getOrNull()?.getBoolean("ok", true) == true
    private fun keys(c: Context, path: String): Set<String> = runCatching {
        c.contentResolver.query(Uri.withAppendedPath(URI, path), null, null, null, null)?.use { cur -> buildSet { while (cur.moveToNext()) add(cur.getString(0)) } }
    }.getOrNull().orEmpty()
    fun hidden(c: Context): Set<String> = if (shared(c)) keys(c, "hidden") else local(c).getStringSet("hidden", emptySet())!!.toSet()
    fun dock(c: Context): Set<String> = if (shared(c)) keys(c, "dock") else emptySet()
    fun hide(c: Context, key: String, hide: Boolean) { if (shared(c)) call(c, if (hide) "hide" else "unhide", key) else local(c).edit().putStringSet("hidden", if (hide) hidden(c) + key else hidden(c) - key).apply() }
    fun foldersFirst(c: Context): Boolean = !shared(c) || runCatching { c.contentResolver.query(Uri.withAppendedPath(URI, "settings"), null, null, null, null)?.use { if (it.moveToFirst() && it.columnCount > 1) it.getInt(1) == 1 else true } }.getOrNull() ?: true
    fun sort(c: Context): String? = if (shared(c)) runCatching { c.contentResolver.query(Uri.withAppendedPath(URI, "settings"), null, null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() else null
    fun setSort(c: Context, sort: String) { if (shared(c)) call(c, "setSort", sort) }
}

internal val SORTS = listOf("name" to "A to Z", "name_desc" to "Z to A", "used" to "Most used", "recent" to "Recently used", "installed" to "Newest", "category" to "By category")

internal fun categoryName(cat: Int) = when (cat) {
    android.content.pm.ApplicationInfo.CATEGORY_GAME -> "Games"; android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "Music & audio"; android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "Video"
    android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "Photos"; android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "Social"; android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "News"
    android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "Maps & travel"; android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"; else -> "Other"
}

internal fun sorted(list: List<AppHit>, sort: String, launches: Map<String, Pair<Int, Long>>) = when (sort) {
    "name_desc" -> list.sortedByDescending { it.label.lowercase() }
    "used" -> list.sortedWith(compareByDescending<AppHit> { launches[it.key]?.first ?: 0 }.thenBy { it.label.lowercase() })
    "recent" -> list.sortedWith(compareByDescending<AppHit> { launches[it.key]?.second ?: 0L }.thenBy { it.label.lowercase() })
    "installed" -> list.sortedByDescending { it.installed }
    "category" -> list.sortedWith(compareBy<AppHit> { categoryName(it.category) }.thenBy { it.label.lowercase() })
    else -> list.sortedBy { it.label.lowercase() }
}

internal fun appScore(a: AppHit, q: String): Int {
    val t = q.trim().lowercase(); val n = a.label.lowercase()
    return when {
        n == t -> 1000; n.startsWith(t) -> 800 - n.length; n.split(' ', '-').any { it.startsWith(t) } -> 600 - n.length
        a.aliases.any { it.lowercase().startsWith(t) } -> 550; t in n -> 400 - n.indexOf(t); a.aliases.any { t in it.lowercase() } -> 350
        else -> { var i = 0; for (ch in n) if (i < t.length && ch == t[i]) i++; if (i == t.length && t.length >= 3) 100 else 0 }
    }
}

/* -------------------------------------------------- screen ------------------------------------------------ */

@Composable
fun BeaconApp() {
    val context = LocalContext.current
    val prefs = remember { context.prefs("beacon") }
    val index = remember { FileIndex(context) }
    val scope = rememberCoroutineScope()
    val tab = "search"
    var q by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppHit>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileIndex.Hit>>(emptyList()) }
    var inside by remember { mutableStateOf<List<FileIndex.Hit>>(emptyList()) }
    var others by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var indexing by remember { mutableStateOf<String?>(null) }
    var counts by remember { mutableStateOf(0 to 0) }
    var history by remember { mutableStateOf(runCatching { JSONArray(prefs.getString("history", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())) }
    val resumed = rememberIsResumed()
    var allFiles by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    var contactsOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOk = it }
    var filesGuide by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var groups by remember { mutableStateOf<List<AppGroup>>(emptyList()) }
    var launches by remember { mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumed) { if (resumed) { allFiles = android.os.Environment.isExternalStorageManager(); tick++ } }
    fun reindex() {
        if (indexing != null || !allFiles) return
        indexing = "Starting…"
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { index.rebuild(prefs.getBoolean("contents", true)) { n, path -> scope.launch { indexing = "%,d items · %s".format(n, path.removePrefix(Environment.getExternalStorageDirectory().path).ifBlank { "/" }) } } } }
            counts = withContext(Dispatchers.IO) { index.count() }
            indexing = null
        }
    }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
        counts = withContext(Dispatchers.IO) { index.count() }
        if (tab == "search") runCatching { delay(200); focus.requestFocus() }
    }
    LaunchedEffect(tick) { groups = withContext(Dispatchers.IO) { Folders.groups(context) }; launches = withContext(Dispatchers.IO) { Folders.launches(context) } }
    LaunchedEffect(allFiles) { if (allFiles && System.currentTimeMillis() - index.lastIndexed() > 6 * 3_600_000L) reindex() }
    LaunchedEffect(q) {
        val term = q.trim()
        if (term.isEmpty()) { files = emptyList(); inside = emptyList(); others = emptyList(); return@LaunchedEffect }
        delay(120)
        others = withContext(Dispatchers.IO) { (listOfNotNull(Engine.convert(term), Engine.math(term)) + Engine.settings(term) + Engine.contacts(context, term)).sortedByDescending { it.score } }
        if (term.length >= 2) {
            files = withContext(Dispatchers.IO) { runCatching { index.byName(term) }.getOrDefault(emptyList()) }
            inside = withContext(Dispatchers.IO) { runCatching { index.inside(term) }.getOrDefault(emptyList()) }
        }
    }
    fun remember(term: String) {
        val t = term.trim(); if (t.length < 2) return
        history = (listOf(t) + history.filter { it != t }).take(12); prefs.edit().putString("history", JSONArray(history).toString()).apply()
    }
    fun open(a: AppHit) { context.launchSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) {}; Folders.recordLaunch(context, a.key) }
    WonderApp(needs = APP_NEEDS, appName = "Beacon", about = ABOUT,
        title = "Beacon search",
        settings = {
            var contents by remember { mutableStateOf(prefs.getBoolean("contents", true)) }
            Panel(title = "Search", footer = "Beacon reads text files to find words inside them: notes, Markdown, code, subtitles, spreadsheets saved as CSV and more. The index stays on this phone.") {
                SwitchRow("Search inside files", contents, { contents = it; prefs.edit().putBoolean("contents", it).apply() }, icon = WonderIcons.TextSnippet, tint = Color(0xFF8E44AD))
                PanelDivider()
                ListRow("Rebuild the index now", subtitle = "%,d files and folders · %,d documents with text".format(counts.first, counts.second), icon = WonderIcons.Refresh, tint = Color(0xFF1E7FE8), onClick = { index.clear(); reindex() })
                PanelDivider()
                ListRow("Clear search history", icon = WonderIcons.DeleteSweep, tint = Color(0xFF7A7F87), onClick = { history = emptyList(); prefs.edit().remove("history").apply() })
            }
            Panel(title = "Everywhere", footer = "Add Beacon's tile to Quick Settings (swipe down twice, tap the pencil) or its search bar widget to the home screen. The Wonderdesk Launcher's search uses Beacon automatically.") {
                ListRow(if (Folders.shared(context)) "Folders shared with Wonderdesk Launcher" else "Install Wonderdesk Launcher to share folders", icon = WonderIcons.Apps, tint = Color(0xFF16A085))
            }
        }) {
        val p = wonderPalette()
        val toast = LocalToast.current
        if (filesGuide) AccessGuide(SpecialAccess.Kind.ALL_FILES, "Beacon uses it to find documents by name and by the words inside them. The index never leaves your phone.", onDismiss = { filesGuide = false }, onGranted = { allFiles = true })

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp)) {
                SearchFieldFocus(q, { q = it }, "Search apps, settings, files, sums…", focus) {
                    remember(q)
                    val top = apps.maxByOrNull { appScore(it, q) }?.takeIf { appScore(it, q) > 0 }
                    when { top != null -> open(top); others.firstOrNull() != null -> others.first().intent?.let { context.launchSafely(it) {} }; files.isNotEmpty() -> openFile(context, File(files.first().path)) }
                }
            }
            indexing?.let { Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent); Spacer(Modifier.width(8.dp))
                Text("Indexing · $it", style = MaterialTheme.typography.labelSmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            val term = q.trim()
            LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp)) {
                if (term.isEmpty()) {
                    if (!allFiles) item {
                        Panel(Modifier.padding(bottom = 14.dp)) { ListRow("Search your files too", subtitle = "Allow all files access so Beacon can find documents by name and by the words inside them",
                            icon = WonderIcons.FolderOpen, tint = Color(0xFF4A90E2), chevron = true, onClick = { filesGuide = true }) }
                    }
                    if (history.isNotEmpty()) {
                        item { SectionLabel("Recent searches", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) { items(history) { h -> Chip(h, false, { q = h }, WonderIcons.History) } } }
                    }
                    item { SectionLabel(if (launches.isEmpty()) "Wonderdesk apps" else "Most used", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    item {
                        val top = remember(apps, launches) { if (launches.isEmpty()) apps.filter { it.pkg.startsWith("dev.wonderdesk.") }.take(8) else sorted(apps, "used", launches).take(8) }
                        Panel { top.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) { row.forEach { a -> AppTile(a) { open(a) } }; repeat(4 - row.size) { Spacer(Modifier.width(76.dp)) } } } }
                    }
                    item { Text("Try a sum like 15% of 80, a conversion like 5 km in miles, or any word inside your files.", Modifier.fillMaxWidth().padding(top = 18.dp),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
                    item { Text("%,d files indexed. Beacon finds words inside %,d documents.".format(counts.first, counts.second), Modifier.fillMaxWidth().padding(top = 6.dp),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
                    return@LazyColumn
                }
                others.filter { it.kind == "math" || it.kind == "convert" }.forEach { h ->
                    item {
                        Panel(Modifier.padding(bottom = 14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.subtitle, style = MaterialTheme.typography.bodyMedium, color = p.secondaryText)
                                    Text(h.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                                }
                                IconButton(onClick = { context.copyText("Result", h.title); toast("Copied ${h.title}") }) { Icon(WonderIcons.ContentCopy, "Copy") }
                            }
                        }
                    }
                }
                val appHits = apps.map { it to appScore(it, term) }.filter { it.second > 0 }.sortedByDescending { it.second + (launches[it.first.key]?.first ?: 0).coerceAtMost(50) }.map { it.first }.take(8)
                if (appHits.isNotEmpty()) {
                    item { SectionLabel("Apps", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(appHits, key = { _, a -> "a" + a.key }) { i, a ->
                        ListRow(a.label, Modifier.panelItem(i, appHits.size), subtitle = a.aliases.firstOrNull { it.contains(term, true) && !a.label.contains(term, true) }?.let { "Also known as $it" },
                            leading = { a.icon?.let { Image(it, null, Modifier.size(36.dp)) } }, onClick = { remember(term); open(a) })
                    }
                    item { Spacer(Modifier.height(14.dp)) }
                }
                val settingHits = others.filter { it.kind == "setting" }
                if (settingHits.isNotEmpty()) {
                    item { SectionLabel("Settings", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(settingHits) { i, h -> ListRow(h.title, Modifier.panelItem(i, settingHits.size), icon = WonderIcons.Settings, tint = Color(0xFF7A7F87), chevron = true,
                        onClick = { remember(term); h.intent?.let { context.launchSafely(it) { m -> toast(m) } } }) }
                    item { Spacer(Modifier.height(14.dp)) }
                }
                val people = others.filter { it.kind == "contact" }
                if (people.isNotEmpty()) {
                    item { SectionLabel("Contacts", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(people) { i, c -> ListRow(c.title, Modifier.panelItem(i, people.size), subtitle = c.subtitle, icon = WonderIcons.Person, tint = Color(0xFF16A085),
                        trailing = { if (c.subtitle.isNotBlank()) IconButton(onClick = { context.launchSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.subtitle}"))) {} }) { Icon(WonderIcons.Phone, "Call", tint = Color(0xFF2E9E44)) } },
                        onClick = { c.intent?.let { context.launchSafely(it) {} } }) }
                    item { Spacer(Modifier.height(14.dp)) }
                } else if (!contactsOk) item {
                    Text("Search contacts too", Modifier.clickable { askContacts.launch(Manifest.permission.READ_CONTACTS) }.padding(6.dp), color = p.accent, style = MaterialTheme.typography.labelLarge)
                }
                if (files.isNotEmpty()) {
                    item { SectionLabel("Files", Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(files.take(15), key = { _, h -> "f" + h.path }) { i, h -> FileRow(h, Modifier.panelItem(i, files.size.coerceAtMost(15)), term, onOpen = { remember(term) }) }
                    item { Spacer(Modifier.height(14.dp)) }
                }
                if (inside.isNotEmpty()) {
                    item { SectionLabel("Inside files", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(inside, key = { _, h -> "c" + h.path }) { i, h -> FileRow(h, Modifier.panelItem(i, inside.size), term, onOpen = { remember(term) }) }
                    item { Spacer(Modifier.height(14.dp)) }
                }
                item {
                    Panel { ListRow("Search the web for “$term”", subtitle = "Opens DuckDuckGo in your browser", icon = WonderIcons.TravelExplore, tint = Color(0xFFDE5833), chevron = true,
                        onClick = { remember(term); context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(term)))) { toast(it) } }) }
                }
            }
        }
    }
}

/** Every app, with folders (shared with the Launcher's Launchpad) and six ways to sort. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AllApps(apps: List<AppHit>, groups: List<AppGroup>, launches: Map<String, Pair<Int, Long>>, onOpen: (AppHit) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val toast = LocalToast.current
    val prefs = remember { context.prefs("beacon") }
    var sort by remember { mutableStateOf(prefs.getString("sort", "name") ?: "name") }
    var filter by remember { mutableStateOf("") }
    var openGroup by remember { mutableStateOf<AppGroup?>(null) }
    var menuFor by remember { mutableStateOf<AppHit?>(null) }
    var newFolderFor by remember { mutableStateOf<AppHit?>(null) }
    val inGroups = groups.flatMap { it.apps }.toSet()
    val loose = remember(apps, sort, launches, inGroups, filter) { sorted(apps.filter { it.key !in inGroups && (filter.isBlank() || appScore(it, filter) > 0) }, sort, launches) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(filter, { filter = it }, "Filter apps")
            Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SORTS.forEach { (id, name) -> Chip(name, sort == id, { sort = id; prefs.edit().putString("sort", id).apply() }) }
            }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), state = rememberTabTopGrid(), contentPadding = PaddingValues(12.dp)) {
            if (filter.isBlank()) items(groups.size, key = { "g" + groups[it].id }) { i ->
                val g = groups[i]
                Column(Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).clickable { openGroup = g }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(p.selection.copy(alpha = .15f)).padding(6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            g.apps.mapNotNull { k -> apps.find { it.key == k } }.take(4).chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { row.forEach { a -> a.icon?.let { Image(it, null, Modifier.size(20.dp)) } } } }
                        }
                    }
                    Text(g.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            items(loose.size, key = { "a" + loose[it].key }) { i -> val a = loose[i]; AppGridTile(a, onClick = { onOpen(a) }, onLong = { menuFor = a }) }
        }
    }
    openGroup?.let { g ->
        val live = groups.find { it.id == g.id } ?: g
        var renaming by remember { mutableStateOf(false) }
        WonderDialog(onDismissRequest = { openGroup = null }, title = { Text(live.name, Modifier.clickable { renaming = true }) }, text = {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(84.dp), Modifier.heightIn(max = 460.dp)) {
                val list = sorted(live.apps.mapNotNull { k -> apps.find { it.key == k } }, sort, launches)
                items(list.size) { i -> val a = list[i]; AppGridTile(a, onClick = { openGroup = null; onOpen(a) }, onLong = { menuFor = a }) }
            }
        }, confirmButton = { TextButton(onClick = { openGroup = null }) { Text("Close") } }, dismissButton = { TextButton(onClick = { Folders.delete(context, live.id); openGroup = null; onChanged() }) { Text("Delete folder", color = p.danger) } })
        if (renaming) InputDialog("Rename folder", "Name", initial = live.name, onDismiss = { renaming = false }) { Folders.rename(context, live.id, it); onChanged() }
    }
    menuFor?.let { a ->
        val current = groups.find { a.key in it.apps }
        WonderDialog(onDismissRequest = { menuFor = null }, title = { Text(a.label) }, text = {
            Column(Modifier.verticalScrollCompat()) {
                Panel {
                    ListRow("Open", icon = WonderIcons.OpenInNew, onClick = { menuFor = null; onOpen(a) }); PanelDivider()
                    if (Folders.shared(context)) { ListRow("Add to home screen", icon = WonderIcons.Home, tint = Color(0xFF2E9E44), onClick = { menuFor = null; if (Folders.addToHome(context, a.key)) toast("Added to your home screen") }); PanelDivider() }
                    groups.filter { it.id != current?.id }.forEach { g -> ListRow("Move to “${g.name}”", icon = WonderIcons.Folder, tint = Color(0xFF4A90E2), onClick = { menuFor = null; Folders.addTo(context, g.id, a.key); onChanged() }); PanelDivider() }
                    ListRow("New folder with this app", icon = WonderIcons.CreateNewFolder, tint = Color(0xFF4A90E2), onClick = { menuFor = null; newFolderFor = a }); PanelDivider()
                    if (current != null) { ListRow("Take out of “${current.name}”", icon = WonderIcons.FolderOpen, tint = Color(0xFF7A7F87), onClick = { menuFor = null; Folders.takeOut(context, a.key); onChanged() }); PanelDivider() }
                    ListRow("App info", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), onClick = { menuFor = null; context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) {} }); PanelDivider()
                    ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = { menuFor = null; context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) {} })
                }
            }
        }, confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } })
    }
    newFolderFor?.let { a -> InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { newFolderFor = null }) { n -> Folders.create(context, n, a.key); onChanged() } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppGridTile(a: AppHit, onClick: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        a.icon?.let { Image(it, null, Modifier.size(56.dp)) }
        Text(a.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable private fun Modifier.horizontalScrollCompat() = this.horizontalScroll(androidx.compose.foundation.rememberScrollState())
@Composable private fun Modifier.verticalScrollCompat() = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

@Composable
private fun AppTile(a: AppHit, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        a.icon?.let { Image(it, null, Modifier.size(48.dp)) }
        Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchFieldFocus(value: String, onChange: (String) -> Unit, placeholder: String, focus: FocusRequester, onSubmit: () -> Unit) {
    Box(Modifier.focusRequester(focus)) { SearchField(value, onChange, placeholder, onSubmit = onSubmit) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(h: FileIndex.Hit, modifier: Modifier, term: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var menu by remember { mutableStateOf(false) }
    val ext = h.name.substringAfterLast('.', "").lowercase()
    val (icon, tint) = when {
        h.dir -> WonderIcons.Folder to Color(0xFF4A90E2)
        ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") -> WonderIcons.Image to Color(0xFF2E9E44)
        ext in setOf("mp4", "mkv", "webm", "mov") -> WonderIcons.Movie to Color(0xFFE5483C)
        ext in setOf("mp3", "flac", "m4a", "ogg", "wav") -> WonderIcons.MusicNote to Color(0xFF8E44AD)
        ext == "pdf" -> WonderIcons.PictureAsPdf to Color(0xFFD63031)
        ext in setOf("zip", "tar", "gz", "7z", "rar") -> WonderIcons.FolderZip to Color(0xFFE67E22)
        else -> WonderIcons.Description to Color(0xFF1E7FE8)
    }
    val where = File(h.path).parent?.removePrefix(Environment.getExternalStorageDirectory().path)?.ifBlank { "/" } ?: ""
    Box {
        Row(modifier.combinedClickable(onClick = { onOpen(); if (h.dir) showInWayfinder(context, h.path) else openFile(context, File(h.path)) }, onLongClick = { menu = true })
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (h.snippet != null) Text(buildAnnotatedString {
                    var rest = h.snippet.replace('\n', ' ')
                    while (rest.isNotEmpty()) { val s = rest.indexOf('['); val e = rest.indexOf(']', s + 1)
                        if (s < 0 || e < 0) { append(rest); break }; append(rest.substring(0, s)); withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = p.accent)) { append(rest.substring(s + 1, e)) }; rest = rest.substring(e + 1) }
                }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$where · ${if (h.dir) "folder" else formatBytes(h.size)} · ${relativeTime(h.mtime)}", style = MaterialTheme.typography.labelSmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { menu = true }) { Icon(WonderIcons.MoreVert, "Options", tint = p.secondaryText) }
        }
        DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
            if (!h.dir) DropdownMenuItem({ Text("Open") }, { menu = false; onOpen(); openFile(context, File(h.path)) }, leadingIcon = { Icon(WonderIcons.OpenInNew, null) })
            DropdownMenuItem({ Text("Show in Wayfinder") }, { menu = false; showInWayfinder(context, h.path) }, leadingIcon = { Icon(WonderIcons.FolderOpen, null) })
            if (!h.dir && ext in FileIndex(context).TEXT_EXT) DropdownMenuItem({ Text("Edit in Scribble") }, { menu = false; openWith(context, File(h.path), "dev.wonderdesk.scribble", "text/plain") }, leadingIcon = { Icon(WonderIcons.Edit, null) })
            if (!h.dir) DropdownMenuItem({ Text("Share") }, { menu = false; context.share(FileProvider.getUriForFile(context, "${context.packageName}.files", File(h.path)), mime(File(h.path))) {} }, leadingIcon = { Icon(WonderIcons.Share, null) })
        }
    }
}

private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

internal fun showInWayfinder(c: Context, path: String) {
    c.launchSafely(Intent("dev.wonderdesk.wayfinder.SHOW").setPackage("dev.wonderdesk.wayfinder").putExtra("path", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {
        c.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thoroftroy/Wayfinder/releases"))) {}
    }
}

private fun openWith(c: Context, f: File, pkg: String, type: String) {
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    c.launchSafely(i) { openFile(c, f) }
}

internal fun openFile(c: Context, f: File) {
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    val ext = f.extension.lowercase()
    val preferred = when {
        ext == "pdf" -> "dev.wonderdesk.pages"; ext in setOf("zip", "tar", "gz", "tgz") -> "dev.wonderdesk.packrat"
        ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") -> "dev.wonderdesk.memories"; ext in setOf("mp4", "mkv", "webm", "mov") -> "dev.wonderdesk.cinema"
        ext in setOf("txt", "md", "markdown", "log", "csv", "json") -> "dev.wonderdesk.scribble"; else -> null
    }
    val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (preferred != null && c.packageManager.queryIntentActivities(Intent(base).setPackage(preferred), 0).isNotEmpty()) { c.launchSafely(Intent(base).setPackage(preferred)) {}; return }
    c.launchSafely(Intent.createChooser(base, "Open ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}
}
