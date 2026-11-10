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

private const val ABOUT = "Beacon searches your whole phone from one box: apps, settings, contacts, file names and the words inside your documents and notes. " +
    "It can also do quick sums and search the web when you ask. The file index is built and kept on this phone."

data class AppHit(val pkg: String, val label: String, val icon: ImageBitmap?, val aliases: List<String>)

private fun loadApps(c: Context): List<AppHit> {
    val pm = c.packageManager
    val aliases = runCatching { SharedCatalog.aliases(c) }.getOrDefault(emptyMap())
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { r ->
        val pkg = r.activityInfo.packageName
        AppHit(pkg, r.loadLabel(pm).toString(), runCatching { r.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
            if (pkg.startsWith("dev.wonderdesk.")) aliases[pkg.substringAfterLast('.')].orEmpty() else emptyList())
    }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}

private val SETTINGS = listOf(
    Triple("Wi-Fi", "wifi wireless internet", Settings.ACTION_WIFI_SETTINGS), Triple("Bluetooth", "bluetooth pair headphones", Settings.ACTION_BLUETOOTH_SETTINGS),
    Triple("Display", "brightness screen dark theme font", Settings.ACTION_DISPLAY_SETTINGS), Triple("Sound", "volume ringtone vibrate", Settings.ACTION_SOUND_SETTINGS),
    Triple("Battery", "battery power saver", Intent.ACTION_POWER_USAGE_SUMMARY), Triple("Storage", "storage space free up", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    Triple("Apps", "apps applications uninstall permissions", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), Triple("Notifications", "notifications alerts", "android.settings.NOTIFICATION_SETTINGS"),
    Triple("Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS), Triple("Security", "lock screen pin fingerprint", Settings.ACTION_SECURITY_SETTINGS),
    Triple("Accessibility", "accessibility talkback", Settings.ACTION_ACCESSIBILITY_SETTINGS), Triple("Date and time", "date time zone", Settings.ACTION_DATE_SETTINGS),
    Triple("Language", "language keyboard input", Settings.ACTION_LOCALE_SETTINGS), Triple("Mobile data", "data usage cellular sim", Settings.ACTION_DATA_USAGE_SETTINGS),
    Triple("Airplane mode", "airplane flight", Settings.ACTION_AIRPLANE_MODE_SETTINGS), Triple("Developer options", "developer usb debugging", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    Triple("About phone", "about model android version", Settings.ACTION_DEVICE_INFO_SETTINGS), Triple("Default apps", "default browser launcher home", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
)

data class Contact(val id: Long, val name: String, val phone: String?)

private fun contacts(c: Context, q: String): List<Contact> = runCatching {
    c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?", arrayOf("%$q%", "%$q%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cur ->
        buildList { while (cur.moveToNext() && size < 8) add(Contact(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2))) }.distinctBy { it.id }
    }.orEmpty()
}.getOrDefault(emptyList())

@Composable
fun BeaconApp() {
    val context = LocalContext.current
    val prefs = remember { context.prefs("beacon") }
    val index = remember { FileIndex(context) }
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppHit>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileIndex.Hit>>(emptyList()) }
    var inside by remember { mutableStateOf<List<FileIndex.Hit>>(emptyList()) }
    var people by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var indexing by remember { mutableStateOf<String?>(null) }
    var counts by remember { mutableStateOf(0 to 0) }
    var history by remember { mutableStateOf(runCatching { JSONArray(prefs.getString("history", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())) }
    var allFiles by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    var contactsOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOk = it }
    val focus = remember { FocusRequester() }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allFiles = android.os.Environment.isExternalStorageManager() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
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
        runCatching { delay(200); focus.requestFocus() }
    }
    LaunchedEffect(allFiles) { if (allFiles && System.currentTimeMillis() - index.lastIndexed() > 6 * 3_600_000L) reindex() }
    LaunchedEffect(q) {
        val term = q.trim()
        if (term.length < 2) { files = emptyList(); inside = emptyList(); people = emptyList(); return@LaunchedEffect }
        delay(150)
        files = withContext(Dispatchers.IO) { runCatching { index.byName(term) }.getOrDefault(emptyList()) }
        inside = withContext(Dispatchers.IO) { runCatching { index.inside(term) }.getOrDefault(emptyList()) }
        if (contactsOk) people = withContext(Dispatchers.IO) { contacts(context, term) }
    }
    fun remember(term: String) {
        val t = term.trim(); if (t.length < 2) return
        history = (listOf(t) + history.filter { it != t }).take(12); prefs.edit().putString("history", JSONArray(history).toString()).apply()
    }
    WonderApp(appName = "Beacon", about = ABOUT,
        settings = {
            var contents by remember { mutableStateOf(prefs.getBoolean("contents", true)) }
            Panel(title = "Search", footer = "Beacon reads text files to find words inside them: notes, Markdown, code, subtitles, spreadsheets saved as CSV and more. The index stays on this phone.") {
                SwitchRow("Search inside files", contents, { contents = it; prefs.edit().putBoolean("contents", it).apply() }, icon = WonderIcons.TextSnippet, tint = Color(0xFF8E44AD))
                PanelDivider()
                ListRow("Rebuild the index now", subtitle = "%,d files and folders · %,d documents with text".format(counts.first, counts.second), icon = WonderIcons.Refresh, tint = Color(0xFF1E7FE8), onClick = { index.clear(); reindex() })
                PanelDivider()
                ListRow("Clear search history", icon = WonderIcons.DeleteSweep, tint = Color(0xFF7A7F87), onClick = { history = emptyList(); prefs.edit().remove("history").apply() })
            }
        }) {
        val p = wonderPalette()
        val toast = LocalToast.current
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp)) {
                SearchFieldFocus(q, { q = it }, "Search apps, files, words, contacts…", focus) { remember(q); results(context, q, apps, files).firstOrNull()?.invoke() }
            }
            indexing?.let { Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent); Spacer(Modifier.width(8.dp))
                Text("Indexing · $it", style = MaterialTheme.typography.labelSmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            val term = q.trim()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                if (term.isEmpty()) {
                    if (!allFiles) item {
                        Panel(Modifier.padding(bottom = 14.dp)) { ListRow("Search your files too", subtitle = "Allow all files access so Beacon can find documents by name and by the words inside them",
                            icon = WonderIcons.FolderOpen, tint = Color(0xFF4A90E2), chevron = true, onClick = {
                                context.launchSafely(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) {} }) }
                    }
                    if (history.isNotEmpty()) {
                        item { SectionLabel("Recent searches", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) { items(history) { h -> Chip(h, false, { q = h }, WonderIcons.History) } } }
                    }
                    item { SectionLabel("Apps", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    item {
                        val recent = remember(apps) { prefs.getString("launched", "")!!.split(',').filter { it.isNotBlank() }.mapNotNull { pk -> apps.find { it.pkg == pk } }.ifEmpty { apps.filter { it.pkg.startsWith("dev.wonderdesk.") } }.take(8) }
                        Panel { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            recent.take(4).forEach { a -> AppTile(a) { launch(context, a.pkg, prefs) } }
                        } ; if (recent.size > 4) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) { recent.drop(4).forEach { a -> AppTile(a) { launch(context, a.pkg, prefs) } } } }
                    }
                    item { Text("%,d files indexed. Beacon finds words inside %,d documents.".format(counts.first, counts.second), Modifier.fillMaxWidth().padding(top = 18.dp),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
                    return@LazyColumn
                }
                // calculator
                val calc = runCatching { Expression.format(Expression.evaluate(term)) }.getOrNull()?.takeIf { term.any { c -> c in "+-*/^()" } }
                if (calc != null) item {
                    Panel(Modifier.padding(bottom = 14.dp)) { ListRow("= $calc", subtitle = term, icon = WonderIcons.Calculate, tint = Color(0xFFE67E22), trailing = {
                        IconButton(onClick = { context.copyText("Result", calc); toast("Copied $calc") }) { Icon(WonderIcons.ContentCopy, "Copy") } }) }
                }
                val appHits = apps.filter { a -> a.label.contains(term, true) || a.aliases.any { it.contains(term, true) } }
                    .sortedWith(compareByDescending<AppHit> { it.label.startsWith(term, true) }.thenBy { it.label.length }).take(6)
                if (appHits.isNotEmpty()) {
                    item { SectionLabel("Apps", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(appHits, key = { _, a -> "a" + a.pkg }) { i, a ->
                        ListRow(a.label, Modifier.panelItem(i, appHits.size), subtitle = a.aliases.firstOrNull { it.contains(term, true) && !a.label.contains(term, true) }?.let { "Also known as $it" },
                            leading = { a.icon?.let { Image(it, null, Modifier.size(36.dp)) } }, onClick = { remember(term); launch(context, a.pkg, prefs) })
                    }
                    item { Spacer(Modifier.height(14.dp)) }
                }
                val settingHits = SETTINGS.filter { it.first.contains(term, true) || it.second.contains(term.lowercase()) }.take(4)
                if (settingHits.isNotEmpty()) {
                    item { SectionLabel("Settings", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(settingHits) { i, (name, _, action) -> ListRow(name, Modifier.panelItem(i, settingHits.size), icon = WonderIcons.Settings, tint = Color(0xFF7A7F87), chevron = true,
                        onClick = { remember(term); context.launchSafely(Intent(action)) { toast(it) } }) }
                    item { Spacer(Modifier.height(14.dp)) }
                }
                if (people.isNotEmpty()) {
                    item { SectionLabel("Contacts", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                    itemsIndexed(people) { i, c -> ListRow(c.name, Modifier.panelItem(i, people.size), subtitle = c.phone, icon = WonderIcons.Person, tint = Color(0xFF16A085),
                        trailing = { c.phone?.let { ph -> IconButton(onClick = { context.launchSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ph"))) {} }) { Icon(WonderIcons.Phone, "Call", tint = Color(0xFF2E9E44)) } } },
                        onClick = { context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, c.id.toString()))) {} }) }
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
                if (appHits.isEmpty() && files.isEmpty() && inside.isEmpty() && settingHits.isEmpty() && calc == null && people.isEmpty())
                    item { Text("Nothing on this phone matches “$term”.", Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center, color = p.secondaryText) }
            }
        }
    }
}

/** The first result, for the keyboard's search key. */
private fun results(c: Context, q: String, apps: List<AppHit>, files: List<FileIndex.Hit>): List<() -> Unit> {
    val t = q.trim()
    return listOfNotNull(apps.firstOrNull { it.label.startsWith(t, true) }?.let { a -> { launch(c, a.pkg, c.prefs("beacon")) } },
        files.firstOrNull()?.let { h -> { openFile(c, File(h.path)) } })
}

private fun launch(c: Context, pkg: String, prefs: android.content.SharedPreferences) {
    c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.launchSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {} }
    val list = (listOf(pkg) + prefs.getString("launched", "")!!.split(',').filter { it.isNotBlank() && it != pkg }).take(8)
    prefs.edit().putString("launched", list.joinToString(",")).apply()
}

@Composable
private fun AppTile(a: AppHit, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        a.icon?.let { Image(it, null, Modifier.size(48.dp)) }
        Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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

private fun showInWayfinder(c: Context, path: String) {
    c.launchSafely(Intent("dev.wonderdesk.wayfinder.SHOW").setPackage("dev.wonderdesk.wayfinder").putExtra("path", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {
        c.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thoroftroy/Wayfinder/releases"))) {}
    }
}

private fun openWith(c: Context, f: File, pkg: String, type: String) {
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    c.launchSafely(i) { openFile(c, f) }
}

private fun openFile(c: Context, f: File) {
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
