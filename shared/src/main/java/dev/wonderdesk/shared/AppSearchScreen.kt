package dev.wonderdesk.shared

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

data class LaunchableApp(val component: ComponentName, val title: String, val icon: Bitmap?, val version: String, val aliases: List<String> = emptyList()) {
    val key: String get() = component.flattenToString()
    fun matches(query: String): Boolean {
        val term = query.trim()
        return title.contains(term, true) || component.packageName.contains(term, true) || aliases.any { it.contains(term, true) }
    }
}

@Suppress("DEPRECATION")
fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val aliases = SharedCatalog.aliases(context)
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL).map { resolve ->
        val info = resolve.activityInfo
        LaunchableApp(ComponentName(info.packageName, info.name), resolve.loadLabel(pm).toString(), runCatching { resolve.loadIcon(pm).toBitmap(96, 96) }.getOrNull(), runCatching { pm.getPackageInfo(info.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown"), if (info.packageName.startsWith("dev.wonderdesk.")) aliases[info.packageName.substringAfterLast('.')].orEmpty() else emptyList())
    }.distinctBy { it.key }.sortedWith(compareBy<LaunchableApp> { it.title.lowercase() }.thenBy { it.key })
}

fun Context.launchApp(app: LaunchableApp, report: (String) -> Unit) = launchSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), report)

/** Most-recent-first, case-insensitive dedupe, bounded. Pure for testing. */
fun updateRecentSearches(current: List<String>, query: String, limit: Int = 8): List<String> {
    val term = query.trim().take(128)
    if (term.isEmpty()) return current
    return (listOf(term) + current.filterNot { it.equals(term, ignoreCase = true) }).take(limit.coerceIn(1, 32))
}

@Composable
fun AppSearchScreen(manage: Boolean = false) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val op = rememberOperation()
    var apps by remember { mutableStateOf(listOf<LaunchableApp>()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    val searchPrefs = remember { context.getSharedPreferences("beacon", Context.MODE_PRIVATE) }
    var recents by remember {
        mutableStateOf(runCatching {
            val array = org.json.JSONArray(searchPrefs.getString("recent", "[]"))
            (0 until minOf(array.length(), 32)).map { array.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList()))
    }
    fun rememberSearch(term: String) {
        if (manage) return
        recents = updateRecentSearches(recents, term)
        searchPrefs.edit().putString("recent", org.json.JSONArray(recents).toString()).apply()
    }
    val matches = remember(apps, query) { apps.filter { it.matches(query) } }
    var fileHits by remember { mutableStateOf(listOf<FileHit>()) }
    var searchedTerm by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < 2) { fileHits = emptyList(); searchedTerm = ""; return@LaunchedEffect }
        if (term == searchedTerm) return@LaunchedEffect
        searchedTerm = term
        val found = runCatching { searchFiles(context, term) }.getOrDefault(emptyList())
        fileHits = found
    }
    fun refresh() { op.run("Reading launchable installed apps", { loadLaunchableApps(context) }) { apps = it; op.notice("${it.size} launchable apps. Android may hide other packages.") } }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        activity.lifecycle.addObserver(observer)
        refresh()
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it.take(512) }, Modifier.fillMaxWidth(), label = { Text(if (manage) "Search installed apps" else "Search apps, aliases or calculate") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        if (!manage) {
            val calculation = remember(query) { runCatching { Expression.format(Expression.evaluate(query)) }.getOrNull() }
            if (calculation != null) Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("= $calculation", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall); TextButton(onClick = { context.copyText("Calculation result", calculation) }) { Text("Copy") } }
            }
            if (query.isNotBlank()) TextButton(onClick = { rememberSearch(query); context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")), op::notice) }) { Text("Search web in browser (sends query)") }
            if (query.isBlank() && recents.isNotEmpty()) {
                Text("Recent searches (this app only)", style = MaterialTheme.typography.bodySmall)
                recents.forEach { recent -> TextButton(onClick = { query = recent }) { Text(recent) } }
            }
            Text("Apps, calculations and files with matching names. File contents are not searched. Web sends the query only when you tap it.", style = MaterialTheme.typography.bodySmall)
        } else Text("Installed launchable apps only; no store or update service. Android asks before uninstalling anything.", style = MaterialTheme.typography.bodySmall)
        OperationStatus(op)
        if (query.isNotBlank() && !op.busy) Text("${matches.size} matching apps", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!manage && fileHits.isNotEmpty()) {
                item { Text("Files", style = MaterialTheme.typography.titleSmall) }
                items(fileHits, key = { it.uri.toString() }) { hit ->
                    Card(onClick = { rememberSearch(query); openFileIntent(context, hit) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.name)
                                Text("${hit.path} · ${android.text.format.Formatter.formatShortFileSize(context, hit.size)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            items(matches, key = { it.key }) { app ->
                Card(onClick = { if (manage) selected = app else { rememberSearch(query); context.launchApp(app, op::notice) } }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp)) }
                        Column(Modifier.weight(1f)) { Text(app.title); Text(if (manage) "${app.version} · ${app.component.packageName}" else app.component.packageName, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    selected?.let { app -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.title) }, text = {
        Column {
            Text("${app.component.packageName}\nVersion ${app.version}")
            TextButton(onClick = { context.launchApp(app, op::notice); selected = null }) { Text("Open") }
            TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")), op::notice); selected = null }) { Text("Android app details / permissions") }
            if (app.component.packageName != context.packageName) TextButton(onClick = { context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}")), op::notice); selected = null }) { Text("Request uninstall…") }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
}
