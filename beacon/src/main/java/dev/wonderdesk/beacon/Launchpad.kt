package dev.wonderdesk.beacon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Beacon itself (what its icon opens): every app over a dimmed, blurred wallpaper, with folders (shared with the
 * Wonderdesk Launcher), six sort orders and the whole of Beacon search. The launcher's swipe up, Dock button
 * and search open this. Leaving it (opening an app, Home, Back, swiping down) closes it, so it always opens fresh.
 */
class LaunchpadActivity : ComponentActivity() {
    private val searchRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            if (windowManager.isCrossWindowBlurEnabled) { window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); window.attributes = window.attributes.apply { blurBehindRadius = 48 } }
        }
        if (intent?.getBooleanExtra("search", false) == true) searchRequest.intValue = 1
        setContent { WonderTheme { LaunchpadScreen(searchRequest.intValue, onClose = ::close) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("search", false)) searchRequest.intValue++
    }

    /** Opening an app or going home leaves Launchpad; it shouldn't be waiting there next time. */
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }

    fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }
}

private var lastTap: Offset? = null

/** Apps load once per process and refresh in the background, so Launchpad opens instantly. */
private object AppCache {
    var apps by mutableStateOf<List<AppHit>>(emptyList())
    var at = 0L
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LaunchpadScreen(searchRequest: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.prefs("beacon") }
    var q by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<AppGroup>>(emptyList()) }
    var launches by remember { mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap()) }
    var hidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sort by remember { mutableStateOf(prefs.getString("lpSort", "name") ?: "name") }
    var foldersFirst by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<AppHit?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var newFolderFor by remember { mutableStateOf<AppHit?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val resumed = rememberIsResumed()
    val apps = AppCache.apps

    LaunchedEffect(Unit) {
        if (AppCache.apps.isEmpty() || System.currentTimeMillis() - AppCache.at > 15_000) {
            AppCache.apps = withContext(Dispatchers.IO) { runCatching { loadApps(context) }.getOrDefault(AppCache.apps) }; AppCache.at = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tick, resumed) {
        if (!resumed) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val g = runCatching { Folders.groups(context) }.getOrDefault(emptyList())
            val l = runCatching { Folders.launches(context) }.getOrDefault(emptyMap())
            val h = runCatching { Folders.hidden(context) }.getOrDefault(emptySet())
            val ff = runCatching { Folders.foldersFirst(context) }.getOrDefault(true)
            val sh = Folders.sort(context)
            withContext(Dispatchers.Main) { groups = g; launches = l; hidden = h; foldersFirst = ff; if (sh != null) sort = sh }
        }
    }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { delay(250); runCatching { focus.requestFocus(); keyboard?.show() } } }
    LaunchedEffect(message) { if (message != null) { delay(2200); message = null } }

    // Opens with a gentle zoom, like Launchpad on a Mac.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = .9f, stiffness = 500f)) }
    fun close() { focusManager.clearFocus(); keyboard?.hide(); scope.launch { enter.animateTo(0f, tween(140)); onClose() } }
    BackHandler { when { openGroup != null -> openGroup = null; q.isNotEmpty() -> q = ""; else -> close() } }

    fun open(a: AppHit) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // The app grows out of the icon you tapped.
        val opts = (context as? android.app.Activity)?.window?.decorView?.let { v -> lastTap?.let { pt ->
            val sz = (64 * context.resources.displayMetrics.density).toInt()
            android.app.ActivityOptions.makeScaleUpAnimation(v, pt.x.toInt() - sz / 2, pt.y.toInt() - sz / 2, sz, sz).toBundle() } }
        if (runCatching { context.startActivity(i, opts) }.isFailure) context.launchSafely(i) { message = it }
        scope.launch(Dispatchers.IO) { Folders.recordLaunch(context, a.key) }
        onClose()
    }

    val visible = remember(apps, hidden) { apps.filter { it.key !in hidden } }
    val inGroups = remember(groups) { groups.flatMap { it.apps }.toSet() }
    val loose = remember(visible, sort, launches, inGroups) { sorted(visible.filter { it.key !in inGroups }, sort, launches) }
    val liveGroups = remember(groups, visible) { groups.map { g -> g.copy(apps = g.apps.filter { k -> visible.any { it.key == k } }) }.filter { it.apps.isNotEmpty() } }

    var pull by remember { mutableFloatStateOf(0f) }
    val closeOnPull = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.Drag) { pull += available.y; if (pull > 240) { pull = 0f; close() } } else if (consumed.y != 0f) pull = 0f
                return Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) { awaitPointerEventScope { while (true) { val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); e.changes.firstOrNull()?.let { if (it.pressed) lastTap = it.position } } } }
        .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }
        .background(Brush.verticalGradient(listOf(Color(0xCC10161E), Color(0xE60A0D12))))
        .pointerInput(Unit) { detectTapGestures { close() } }
        .pointerInput(Unit) { var dy = 0f; detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 200) close() }) { _, d -> dy += d } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 14.dp)
            .graphicsLayer { val s = 1.06f - .06f * enter.value; scaleX = s; scaleY = s }) {
            Row(Modifier.padding(vertical = 12.dp).widthIn(max = 560.dp).align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).focusRequester(focus)) {
                    SearchField(q, { q = it }, "Search apps, settings, files, sums…", onSubmit = {
                        val top = visible.map { it to appScore(it, q) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
                        if (top != null) open(top)
                    })
                }
                Box {
                    Icon(WonderIcons.Sort, "Sort and options", Modifier.padding(start = 6.dp).clip(CircleShape).clickable { sortMenu = true }.padding(10.dp), tint = Color.White)
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SORTS.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { sortMenu = false; sort = id; prefs.edit().putString("lpSort", id).apply(); scope.launch(Dispatchers.IO) { Folders.setSort(context, id) } },
                            trailingIcon = if (id == sort) ({ Icon(WonderIcons.Check, null) }) else null) }
                        HorizontalDivider()
                        DropdownMenuItem({ Text(if (hidden.isEmpty()) "No hidden apps" else "Hidden apps (${hidden.size})") }, { sortMenu = false; showHidden = true }, enabled = hidden.isNotEmpty(), leadingIcon = { Icon(WonderIcons.VisibilityOff, null) })
                        DropdownMenuItem({ Text("Search settings and file index") }, { sortMenu = false; context.launchSafely(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}; onClose() }, leadingIcon = { Icon(WonderIcons.Settings, null) })
                    }
                }
            }
            if (q.isNotBlank()) LaunchpadSearch(q, visible, launches, onApp = ::open, onMenu = { menuFor = it }, onDone = onClose)
            else if (apps.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            else LazyVerticalGrid(GridCells.Adaptive(86.dp), Modifier.fillMaxSize().nestedScroll(closeOnPull), state = rememberLazyGridState(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (foldersFirst) items(liveGroups, key = { "g" + it.id }) { g -> FolderTile(g, apps) { openGroup = g.id } }
                items(loose, key = { "a" + it.key }) { a -> LaunchpadTile(a, onClick = { open(a) }, onLong = { menuFor = a }) }
                if (!foldersFirst) items(liveGroups, key = { "g" + it.id }) { g -> FolderTile(g, apps) { openGroup = g.id } }
            }
        }
        openGroup?.let { id -> liveGroups.find { it.id == id }?.let { g -> FolderPanel(g, apps, sort, launches, onApp = ::open, onMenu = { menuFor = it }, onChanged = { tick++ }) { openGroup = null } } ?: run { openGroup = null } }
        message?.let { m -> Text(m, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xEE2A3038)).padding(16.dp, 10.dp), color = Color.White) }
    }

    menuFor?.let { a -> AppMenu(a, groups, onDismiss = { menuFor = null }, onNewFolder = { newFolderFor = a }, onChanged = { tick++ }, say = { message = it }, onOpen = { open(a) }) }
    newFolderFor?.let { a -> InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { newFolderFor = null }) { n -> scope.launch(Dispatchers.IO) { Folders.create(context, n.ifBlank { "Folder" }, a.key); withContext(Dispatchers.Main) { tick++ } } } }
    if (showHidden) WonderDialog(onDismissRequest = { showHidden = false }, title = { Text("Hidden apps") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Hidden apps stay installed. They don't show in the grid but still come up when you search for them.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
            Spacer(Modifier.height(8.dp))
            Panel { apps.filter { it.key in hidden }.forEachIndexed { i, a ->
                if (i > 0) PanelDivider()
                ListRow(a.label, leading = { a.icon?.let { Image(it, null, Modifier.size(32.dp)) } }, trailing = { TextButton(onClick = { scope.launch(Dispatchers.IO) { Folders.hide(context, a.key, false); withContext(Dispatchers.Main) { tick++ } } }) { Text("Show") } })
            } }
        }
    }, confirmButton = { TextButton(onClick = { showHidden = false }) { Text("Done") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LaunchpadTile(a: AppHit, size: Dp = 58.dp, onClick: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        a.icon?.let { Image(it, a.label, Modifier.size(size)) } ?: Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .2f)))
        Text(a.label, Modifier.padding(top = 4.dp).width(82.dp), style = MaterialTheme.typography.labelMedium.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FolderTile(g: AppGroup, apps: List<AppHit>, onOpen: () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = .22f)).border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(15.dp)).padding(7.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                g.apps.mapNotNull { k -> apps.find { it.key == k } }.take(4).chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { a -> a.icon?.let { Image(it, null, Modifier.size(20.dp)) } } } }
            }
        }
        Text(g.name, Modifier.padding(top = 4.dp).width(82.dp), style = MaterialTheme.typography.labelMedium.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FolderPanel(g: AppGroup, apps: List<AppHit>, sort: String, launches: Map<String, Pair<Int, Long>>, onApp: (AppHit) -> Unit, onMenu: (AppHit) -> Unit, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, spring(dampingRatio = .82f, stiffness = 520f)) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f * p.value.coerceIn(0f, 1f))).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).widthIn(max = 480.dp).graphicsLayer { val s = .85f + .15f * p.value; scaleX = s; scaleY = s; alpha = p.value.coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(24.dp)).background(Color(0xEE232A33)).border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(24.dp))
            .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, Modifier.weight(1f).clickable { rename = true }, style = MaterialTheme.typography.titleLarge.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(WonderIcons.Edit, "Rename folder", Modifier.clip(CircleShape).clickable { rename = true }.padding(8.dp), tint = Color.White)
                Icon(WonderIcons.Delete, "Delete folder", Modifier.clip(CircleShape).clickable { confirmDelete = true }.padding(8.dp), tint = Color.White)
            }
            LazyVerticalGrid(GridCells.Adaptive(86.dp), Modifier.heightIn(max = 460.dp)) {
                items(sorted(g.apps.distinct().mapNotNull { k -> apps.find { it.key == k } }, sort, launches), key = { it.key }) { a -> LaunchpadTile(a, onClick = { onApp(a) }, onLong = { onMenu(a) }) }
            }
        }
    }
    if (rename) InputDialog("Rename folder", "Name", initial = g.name, onDismiss = { rename = false }) { n -> scope.launch(Dispatchers.IO) { Folders.rename(context, g.id, n); withContext(Dispatchers.Main) { onChanged() } } }
    if (confirmDelete) ConfirmDialog("Delete “${g.name}”?", "The apps stay installed and go back into Launchpad.", "Delete folder",
        onConfirm = { scope.launch(Dispatchers.IO) { Folders.delete(context, g.id); withContext(Dispatchers.Main) { onChanged(); onClose() } } }, onDismiss = { confirmDelete = false })
}

@Composable
private fun AppMenu(a: AppHit, groups: List<AppGroup>, onDismiss: () -> Unit, onNewFolder: () -> Unit, onChanged: () -> Unit, say: (String) -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = groups.find { a.key in it.apps }
    val shared = remember { Folders.shared(context) }
    fun bg(work: () -> Unit, after: () -> Unit = {}) { onDismiss(); scope.launch(Dispatchers.IO) { runCatching(work); withContext(Dispatchers.Main) { onChanged(); after() } } }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(a.label) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Panel {
                ListRow("Open", icon = WonderIcons.OpenInNew, onClick = { onDismiss(); onOpen() }); PanelDivider()
                if (shared) {
                    ListRow("Add to home screen", icon = WonderIcons.Home, tint = Color(0xFF2E9E44), onClick = {
                        onDismiss(); scope.launch { val ok = withContext(Dispatchers.IO) { Folders.addToHome(context, a.key) }; say(if (ok) "Added to your home screen" else "No room on that home screen page") }
                    }); PanelDivider()
                    ListRow("Add to Dock", icon = WonderIcons.Dashboard, tint = Color(0xFF16A085), onClick = {
                        onDismiss(); scope.launch { val ok = withContext(Dispatchers.IO) { Folders.addToDock(context, a.key) }; say(if (ok) "Added to the Dock" else "The Dock holds 8 apps") }
                    }); PanelDivider()
                }
                groups.filter { it.id != current?.id }.forEach { g -> ListRow("Move to “${g.name}”", icon = WonderIcons.Folder, tint = Color(0xFF4A90E2), onClick = { bg({ Folders.addTo(context, g.id, a.key) }) }); PanelDivider() }
                ListRow("New folder with this app", icon = WonderIcons.CreateNewFolder, tint = Color(0xFF4A90E2), onClick = { onDismiss(); onNewFolder() }); PanelDivider()
                if (current != null) { ListRow("Take out of “${current.name}”", icon = WonderIcons.FolderOpen, tint = Color(0xFF7A7F87), onClick = { bg({ Folders.takeOut(context, a.key) }) }); PanelDivider() }
                ListRow("Hide from all apps", icon = WonderIcons.VisibilityOff, tint = Color(0xFF7A7F87), onClick = { bg({ Folders.hide(context, a.key, true) }) { say("Hidden. Find it again under ⋮ › Hidden apps") } }); PanelDivider()
                ListRow("App info", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), onClick = { onDismiss(); context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {} }); PanelDivider()
                ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = { onDismiss(); context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {} })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Apps first, then sums, conversions, settings, contacts, files and the web, all in Launchpad's style. */
@Composable
private fun LaunchpadSearch(q: String, apps: List<AppHit>, launches: Map<String, Pair<Int, Long>>, onApp: (AppHit) -> Unit, onMenu: (AppHit) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val index = remember { FileIndex(context) }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileIndex.Hit>>(emptyList()) }
    val term = q.trim()
    val appHits = remember(term, apps, launches) {
        apps.map { it to appScore(it, term) }.filter { it.second > 0 }.sortedByDescending { it.second + (launches[it.first.key]?.first ?: 0).coerceAtMost(50) }.map { it.first }.take(16)
    }
    LaunchedEffect(term) {
        delay(120)
        hits = withContext(Dispatchers.IO) { runCatching { (listOfNotNull(Engine.convert(term), Engine.math(term)) + Engine.settings(term) + Engine.contacts(context, term)).sortedByDescending { it.score } }.getOrDefault(emptyList()) }
        files = if (term.length >= 2) withContext(Dispatchers.IO) { runCatching { index.byName(term).take(12) }.getOrDefault(emptyList()) } else emptyList()
    }
    fun go(i: Intent?) { i?.let { context.launchSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {} }; onDone() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        hits.filter { it.kind == "math" || it.kind == "convert" }.forEach { h ->
            item { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .13f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.subtitle, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
                    Text(h.title, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                IconButton(onClick = { context.copyText("Result", h.title) }) { Icon(WonderIcons.ContentCopy, "Copy", tint = Color.White) }
            } }
        }
        if (appHits.isNotEmpty()) item { LazyRow(Modifier.fillMaxWidth()) { items(appHits, key = { it.key }) { a -> LaunchpadTile(a, onClick = { onApp(a) }, onLong = { onMenu(a) }) } } }
        fun section(title: String) = item { Text(title, Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp), color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelLarge) }
        val settings = hits.filter { it.kind == "setting" }
        if (settings.isNotEmpty()) { section("Settings"); items(settings.take(6)) { h -> ResultRow(WonderIcons.Settings, h.title, h.subtitle) { go(h.intent) } } }
        val people = hits.filter { it.kind == "contact" }
        if (people.isNotEmpty()) { section("Contacts"); items(people.take(6)) { h -> ResultRow(WonderIcons.Person, h.title, h.subtitle) { go(h.intent) } } }
        if (files.isNotEmpty()) { section("Files"); items(files) { f -> ResultRow(if (f.dir) WonderIcons.Folder else WonderIcons.InsertDriveFile, f.name, File(f.path).parent?.substringAfter("/emulated/0").orEmpty()) {
            if (f.dir) showInWayfinder(context, f.path) else openFile(context, File(f.path)); onDone() } } }
        section("Web")
        item { ResultRow(WonderIcons.TravelExplore, "Search the web for “$term”", "DuckDuckGo") { go(Engine.web(term).intent) } }
    }
}

@Composable
private fun ResultRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
