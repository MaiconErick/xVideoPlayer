package com.app.xvideoplayer.ui.vault

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import com.app.xvideoplayer.data.model.OrientationFilter
import com.app.xvideoplayer.data.model.SortConfig
import com.app.xvideoplayer.data.model.SortField
import com.app.xvideoplayer.data.model.SortOrder
import com.app.xvideoplayer.data.model.FilterConfig
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.vault.PrivateVault
import com.app.xvideoplayer.data.vault.tagLabel
import com.app.xvideoplayer.data.vault.tagName
import com.app.xvideoplayer.ui.components.*
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatDuration
import com.app.xvideoplayer.util.formatFileSize
import java.io.File

val StarGold = Color(0xFFFFC94D)
private const val FAVORITES = "\u0000favorites"
private const val RECENT = "\u0000recent"

private enum class Section(val label: String, val icon: ImageVector) {
    TAGGED("Tagged", Icons.Rounded.Sell),
    FOLDERS("Hidden folders", Icons.Rounded.FolderSpecial)
}

@Composable
fun PrivateScreen(
    onBack: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onOpenTagger: () -> Unit,
    vm: PrivateViewModel = hiltViewModel()
) {
    val unlocked by vm.unlocked.collectAsState()
    val pinLength by vm.pinLength.collectAsState()
    Box(Modifier.fillMaxSize().background(SurfacePure)) {
        AnimatedContent(unlocked, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lock") { open ->
            if (!open) {
                BackHandler(onBack = onBack)
                PinPad(
                    title = "Private", subtitle = "Enter your PIN", length = pinLength,
                    onEntered = { pin ->
                        val ok = vm.tryUnlock(pin)
                        if (ok) kotlinx.coroutines.delay(600)
                        ok
                    },
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)
                )
                IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            } else PrivateContent(vm, onBack, onPlay, onOpenTagger)
        }
    }
}

@Composable
private fun PrivateContent(vm: PrivateViewModel, onBack: () -> Unit, onPlay: (VideoItem) -> Unit, onOpenTagger: () -> Unit) {
    val index by vm.index.collectAsState()
    var section by rememberSaveable { mutableStateOf(Section.TAGGED) }
    var tagPath by rememberSaveable { mutableStateOf("") }           // "" = tag root, FAVORITES = the star list
    var folderPath by rememberSaveable { mutableStateOf<String?>(null) } // null = list of hidden folders
    var showSettings by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }          // "root" | "hidden"
    var selected by remember { mutableStateOf<PVideo?>(null) }
    var allFiles by remember { mutableStateOf(PrivateVault.hasAllFilesAccess()) }
    var askOrder by remember { mutableStateOf<List<PVideo>?>(null) }  // several videos: pick the order first
    var folderMenu by remember { mutableStateOf<String?>(null) }        // tag folder whose Rename/Move sheet is open
    var sortSheet by remember { mutableStateOf(false) }
    val viewMode by vm.viewMode.collectAsState()
    val folderView by vm.folderView.collectAsState()
    val sort by vm.sortConfig.collectAsState()
    val orientation by vm.orientation.collectAsState()
    val look = PrivateLook(viewMode, folderView, sort, orientation)
    val ctx = LocalContext.current

    // re-check all-files access when coming back from Android's settings
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) allFiles = PrivateVault.hasAllFilesAccess() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    BackHandler {
        when {
            section == Section.TAGGED && (tagPath == FAVORITES || tagPath == RECENT) -> tagPath = ""
            section == Section.TAGGED && tagPath.isNotEmpty() -> tagPath = tagPath.substringBeforeLast('/', "")
            section == Section.FOLDERS && folderPath != null -> folderPath = folderPath?.let { p -> if (index?.hidden?.contains(p) != false) null else p.substringBeforeLast('/') }
            else -> onBack()
        }
    }

    val idx = index
    fun play(list: List<PVideo>, v: PVideo) { vm.setPlaylist(list); onPlay(v.video) }
    fun tag(list: List<PVideo>) {
        when {
            idx?.root == null -> picker = "root"
            !allFiles -> openAllFilesSettings(ctx)
            list.size > 1 -> askOrder = list
            list.isNotEmpty() -> { vm.queueForTagging(list); onOpenTagger() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // header
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("Private", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                idx?.let { Text("${it.tagged.size} tagged  ·  ${it.loose.size} in hidden folders", style = MaterialTheme.typography.bodySmall, color = TextTertiary) }
            }
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = TextPrimary) }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Private settings", tint = TextPrimary) }
            IconButton(onClick = { vm.lock(); onBack() }) { Icon(Icons.Rounded.Lock, "Lock", tint = AccentBright) }
        }
        // section switch
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Section.entries.forEach { s ->
                    val n = when (s) { Section.TAGGED -> idx?.tagged?.size; Section.FOLDERS -> idx?.loose?.size }
                    AppChip("${s.label}${n?.let { "  $it" } ?: ""}", selected = section == s, icon = s.icon) { section = s }
                }
            }
            // same layout buttons as the Videos/Folders tabs (shared settings)
            OrientationIconButton(orientation, vm::setOrientation)
            IconButton(onClick = { sortSheet = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = TextPrimary) }
            IconButton(onClick = vm::toggleViewMode) {
                Icon(
                    when (viewMode) { "GRID" -> Icons.Rounded.GridView; "LIST1" -> Icons.Rounded.ViewList; else -> Icons.Rounded.ViewAgenda },
                    "Videos: " + when (viewMode) { "GRID" -> "grid"; "LIST1" -> "one per row"; else -> "list" }, tint = TextPrimary
                )
            }
            IconButton(onClick = vm::toggleFolderView) {
                Icon(if (folderView == "GRID") Icons.Rounded.Dashboard else Icons.Rounded.FolderCopy, "Folders as ${if (folderView == "GRID") "list" else "grid"}", tint = TextPrimary)
            }
        }
        if (!allFiles) Banner(
            Icons.Rounded.FolderOpen, "Allow all-files access", "Needed to move videos into your tag folders.", "Allow"
        ) { openAllFilesSettings(ctx) }

        if (idx == null) return@Column
        when (section) {
            Section.TAGGED -> TaggedBody(idx, look, tagPath, onOpen = { tagPath = it }, onPick = { picker = "root" }, onPlay = ::play, onMore = { selected = it }, onTag = ::tag, onFolderMenu = { folderMenu = it })
            Section.FOLDERS -> FoldersBody(idx, look, folderPath, onOpen = { folderPath = it }, onAdd = { picker = "hidden" }, onPlay = ::play, onMore = { selected = it }, onTag = ::tag)
        }
    }

    if (sortSheet) SortFilterSheet(sort, FilterConfig(), vm::setSortConfig, {}, onDismiss = { sortSheet = false }, showFilter = false)
    selected?.let { v -> PrivateActions(v, vm, onDismiss = { selected = null }, onPlay = { play(listOf(v), v) }, onTag = { tag(listOf(v)) }) }
    if (showSettings) PrivateSettings(vm, idx, onDismiss = { showSettings = false }, onPickRoot = { picker = "root" }, onAddHidden = { picker = "hidden" })
    folderMenu?.let { t ->
        TagFolderSheet(t, idx?.tree.orEmpty(), vm, onDismiss = { folderMenu = null }) { newTag ->
            // keep the open folder in view when it (or one of its parents) was the one renamed/moved
            if (tagPath == t || tagPath.startsWith("$t/")) tagPath = newTag + tagPath.removePrefix(t)
        }
    }
    askOrder?.let { list ->
        TagOrderDialog(list.size, onDismiss = { askOrder = null }) { order ->
            askOrder = null
            vm.queueForTagging(
                when (order) {
                    TagOrder.RANDOM -> list.shuffled()
                    TagOrder.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.video.title })
                    TagOrder.NEWEST -> list.sortedByDescending { it.video.dateAdded }
                    TagOrder.LARGEST -> list.sortedByDescending { it.video.size }
                    TagOrder.SMALLEST -> list.sortedBy { it.video.size }
                }
            )
            onOpenTagger()
        }
    }
    picker?.let { kind ->
        FolderPicker(
            title = if (kind == "root") "Choose the tagged folder" else "Hide a folder",
            hint = if (kind == "root") "Its subfolders become your tags. Tagged videos are moved in here." else "Videos in it (and its subfolders) only show up in Private.",
            start = idx?.root?.let { File(it).parentFile } ?: File("/storage/emulated/0"),
            onPick = { path -> if (kind == "root") vm.setRoot(path) else vm.addHidden(path); picker = null },
            onDismiss = { picker = null }
        )
    }
}

private fun openAllFilesSettings(ctx: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}

// ── Tagged ──────────────────────────────────────────────────────────────

@Composable
private fun TaggedBody(
    idx: PrivateIndex, look: PrivateLook, path: String, onOpen: (String) -> Unit, onPick: () -> Unit,
    onPlay: (List<PVideo>, PVideo) -> Unit, onMore: (PVideo) -> Unit, onTag: (List<PVideo>) -> Unit, onFolderMenu: (String) -> Unit
) {
    if (idx.root == null) {
        EmptyState(
            Icons.Rounded.Sell, "Pick your tagged folder",
            "Choose (or create) a folder like Movies/Private. Its subfolders are your tags — Muscle, Muscle › Shirtless…",
            action = { Button(onClick = onPick, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Choose folder") } }
        )
        return
    }
    val favorites = path == FAVORITES
    val recent = path == RECENT
    val children = if (favorites || recent) emptyList() else idx.childrenOf(path)
    val raw = when {
        favorites -> idx.favorites
        recent -> idx.recent
        path.isEmpty() -> idx.untaggedInRoot
        else -> idx.videosAt(path)
    }
    // Recent edits keep their own order (newest edit first); only the shape filter applies there
    val videos = remember(raw, look.sort, look.orientation, recent) { raw.arranged(if (recent) null else look.sort, look.orientation) }
    PrivateGrid(look) { span ->
        item(key = "crumbs", span = { GridItemSpan(maxLineSpan) }) { Crumbs(idx.root, path, onOpen) }
        if (path.isEmpty()) {
            folderItem(look, span, "fav") {
                TagFolderEntry(look, Icons.Rounded.Star, "Favorites", "${idx.favorites.size} video${if (idx.favorites.size == 1) "" else "s"}", idx.favorites.firstOrNull()?.video, tint = StarGold) { onOpen(FAVORITES) }
            }
            if (idx.recent.isNotEmpty()) folderItem(look, span, "recent") {
                TagFolderEntry(look, Icons.Rounded.History, "Recent edits", "${idx.recent.size} saved by the tagger  ·  tap one to edit it", idx.recent.first().video, tint = AccentBright) { onOpen(RECENT) }
            }
        }
        children.forEach { t ->
            folderItem(look, span, "t:$t") {
                val subs = idx.childrenOf(t).size
                val n = idx.countUnder(t)
                val cover = idx.tagged.firstOrNull { v -> v.tags.any { it == t || it.startsWith("$t/") } }?.video
                TagFolderEntry(
                    look, Icons.Rounded.Folder, tagName(t),
                    "$n video${if (n == 1) "" else "s"}" + if (subs > 0) "  ·  $subs subtag${if (subs == 1) "" else "s"}" else "",
                    cover, onMore = { onFolderMenu(t) }
                ) { onOpen(t) }
            }
        }
        if (children.isEmpty() && videos.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(
                if (favorites) Icons.Rounded.StarBorder else Icons.Rounded.Sell,
                if (favorites) "No favorites yet" else if (raw.isNotEmpty()) "Nothing matches the shape filter" else if (path.isEmpty()) "No tags yet" else "Nothing tagged ${tagName(path)} yet",
                if (favorites) "Tap the star in the player, or long-press a video." else if (raw.isNotEmpty()) "Switch the shape button back to All shapes." else "Open Hidden folders and start tagging — tags are created as you type.",
                modifier = Modifier.height(300.dp)
            )
        }
        if (videos.isNotEmpty()) {
            item(key = "vtitle", span = { GridItemSpan(maxLineSpan) }) {
                val title = when { favorites -> "Favorites"; recent -> "Recent edits — tap to edit"; path.isEmpty() -> "Not tagged yet"; else -> "Tagged ${tagName(path)}" }
                if (path.isEmpty()) SectionTitle(title) { AppChip("Tag them", icon = Icons.Rounded.Sell) { onTag(videos) } }
                else SectionTitle(title)
            }
            videoItems(look, span, videos) { v ->
                // chips explain why a video is here when it isn't obvious: several tags, or a list mixing tags
                val showTags = if (favorites || recent) v.tags.isNotEmpty() else v.tags.size >= 2
                // Recent edits exist to fix tags: a tap reopens the tagger on that video
                PrivateVideoEntry(look, v, showTags, context = path.takeUnless { favorites || recent }, onClick = { if (recent) onTag(listOf(v)) else onPlay(videos, v) }, onMore = { onMore(v) })
            }
        }
    }
}

@Composable
private fun Crumbs(root: String, path: String, onOpen: (String) -> Unit) {
    val crumbs = if (path == FAVORITES || path == RECENT) listOf("", path) else listOf("") + path.split('/').filter { it.isNotEmpty() }.runningReduce { a, b -> "$a/$b" }
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
            Text(
                when (c) { "" -> root.substringAfterLast('/'); FAVORITES -> "★ Favorites"; RECENT -> "Recent edits"; else -> tagName(c) },
                style = MaterialTheme.typography.labelLarge, color = if (last) OnAccent else AccentBright,
                modifier = Modifier.clip(CircleShape).then(if (last) Modifier.background(Accent) else Modifier).clickable { onOpen(c) }.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun TagFolderRow(icon: ImageVector, name: String, subtitle: String, cover: VideoItem?, tint: Color = Color.White, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick, onMore).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 10f)) {
            Box(Modifier.matchParentSize().padding(horizontal = 8.dp).offset(y = (-5).dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated4))
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp))) {
                if (cover != null) VideoThumbnail(cover, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(SurfaceElevated3))
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                Icon(icon, null, tint = tint, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        if (onMore != null) IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "Rename or move", tint = TextTertiary, modifier = Modifier.size(20.dp)) }
        else Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
    }
}

// ── Hidden folders ──────────────────────────────────────────────────────

@Composable
private fun FoldersBody(
    idx: PrivateIndex, look: PrivateLook, path: String?, onOpen: (String?) -> Unit, onAdd: () -> Unit,
    onPlay: (List<PVideo>, PVideo) -> Unit, onMore: (PVideo) -> Unit, onTag: (List<PVideo>) -> Unit
) {
    PrivateGrid(look) { span ->
        if (path == null) {
            if (idx.loose.isNotEmpty()) item(key = "tagall", span = { GridItemSpan(maxLineSpan) }) {
                Banner(Icons.Rounded.Sell, "${idx.loose.size} video${if (idx.loose.size == 1) "" else "s"} waiting to be tagged", "Tag them one by one; each is moved into its first tag's folder.", "Start") { onTag(idx.loose) }
            }
            idx.hidden.forEach { h ->
                folderItem(look, span, "h:$h") {
                    val vids = idx.looseUnder(h)
                    TagFolderEntry(look, Icons.Rounded.FolderSpecial, h.substringAfterLast('/'), "${vids.size} video${if (vids.size == 1) "" else "s"}  ·  ${vids.sumOf { it.video.size }.formatFileSize()}", vids.firstOrNull()?.video) { onOpen(h) }
                }
            }
            item(key = "add", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onAdd).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, null, tint = AccentBright)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Hide a folder", color = AccentBright, style = MaterialTheme.typography.titleSmall)
                        Text("Its videos disappear from Videos/Folders and live here", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            // browse inside a hidden folder: subfolders that hold videos, then the videos right here
            val under = idx.looseUnder(path)
            val subs = under.map { it.video.folderPath }.filter { it != path }.map { "$path/" + it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
            val here = under.filter { it.video.folderPath == path }.arranged(look.sort, look.orientation)
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onOpen(if (path in idx.hidden) null else path.substringBeforeLast('/')) }) { Icon(Icons.Rounded.ArrowUpward, "Up", tint = TextSecondary) }
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (under.isNotEmpty()) AppChip("Tag all ${under.size}", icon = Icons.Rounded.Sell) { onTag(under) }
                }
            }
            subs.forEach { sub ->
                folderItem(look, span, "s:$sub") {
                    val vids = idx.looseUnder(sub)
                    TagFolderEntry(look, Icons.Rounded.Folder, sub.substringAfterLast('/'), "${vids.size} video${if (vids.size == 1) "" else "s"}", vids.firstOrNull()?.video) { onOpen(sub) }
                }
            }
            videoItems(look, span, here) { v -> PrivateVideoEntry(look, v, showTags = false, context = null, onClick = { onPlay(here, v) }, onMore = { onMore(v) }) }
            if (under.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Rounded.FolderOff, "No videos here", "Everything in this folder has been tagged.", modifier = Modifier.height(300.dp)) }
        }
    }
}

// ── Layout: grid / list / one per row, shared with the Videos and Folders tabs ──

private class PrivateLook(val viewMode: String, val folderView: String, val sort: SortConfig, val orientation: OrientationFilter) {
    val videoGrid get() = viewMode == "GRID"
    val folderGrid get() = folderView == "GRID"
}

/** How many grid columns a tile (card) or a list row takes. */
private class Spans(val card: Int, val row: Int)

private fun List<PVideo>.arranged(sort: SortConfig?, orientation: OrientationFilter): List<PVideo> {
    var list = when (orientation) {
        OrientationFilter.ALL -> this
        OrientationFilter.PORTRAIT -> filter { it.video.isPortrait }
        OrientationFilter.LANDSCAPE -> filter { it.video.hasDimensions && !it.video.isPortrait }
    }
    if (sort == null) return list
    list = when (sort.field) {
        SortField.TITLE, SortField.FOLDER -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.video.title })
        SortField.DATE_ADDED -> list.sortedBy { it.video.dateAdded }
        SortField.LAST_PLAYED -> list.sortedBy { it.video.lastPlayed ?: 0L }
        SortField.DURATION -> list.sortedBy { it.video.duration }
        SortField.SIZE -> list.sortedBy { it.video.size }
        SortField.PLAY_COUNT -> list.sortedBy { it.video.playCount }
    }
    return if (sort.order == SortOrder.DESCENDING) list.reversed() else list
}

/**
 * One grid for folders and videos: tiles are ~168 dp wide (like the library grid), list rows take a whole line,
 * or half of it on wide screens in "list" mode (like the library's adaptive 400 dp list).
 */
@Composable
private fun PrivateGrid(look: PrivateLook, content: LazyGridScope.(Spans) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tiles = ((maxWidth - 16.dp) / 184.dp).toInt().coerceAtLeast(1)
        val rowsPerLine = if (look.viewMode == "LIST1") 1 else (maxWidth / 400.dp).toInt().coerceAtLeast(1)
        val cols = tiles * rowsPerLine
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()
        ) { content(Spans(card = rowsPerLine, row = tiles)) }
    }
}

private fun LazyGridScope.folderItem(look: PrivateLook, span: Spans, key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(if (look.folderGrid) span.card else maxLineSpan) }) { content() }

private fun LazyGridScope.videoItems(look: PrivateLook, span: Spans, videos: List<PVideo>, content: @Composable (PVideo) -> Unit) =
    gridItems(videos, key = { "v:" + it.video.id }, span = { GridItemSpan(if (look.videoGrid) span.card else span.row) }) { content(it) }

@Composable
private fun TagFolderEntry(look: PrivateLook, icon: ImageVector, name: String, subtitle: String, cover: VideoItem?, tint: Color = Color.White, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    if (look.folderGrid) TagFolderCard(icon, name, subtitle, cover, tint, onMore, onClick)
    else TagFolderRowEdge { TagFolderRow(icon, name, subtitle, cover, tint, onMore, onClick) }
}

@Composable
private fun PrivateVideoEntry(look: PrivateLook, v: PVideo, showTags: Boolean, context: String?, onClick: () -> Unit, onMore: () -> Unit) {
    if (look.videoGrid) PrivateVideoCard(v, showTags, context, onClick, onMore)
    else TagFolderRowEdge { PrivateVideoRow(v, showTags, context, onClick, onMore) }
}

/** List rows bring their own 16 dp side padding; cancel the grid's 8 dp so they line up with the rest of the app. */
@Composable
private fun TagFolderRowEdge(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().layoutEdge()) { content() }
}

private fun Modifier.layoutEdge(): Modifier = this.then(Modifier.layout { m, c ->
    val extra = 16.dp.roundToPx()
    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
})

@Composable
private fun TagFolderCard(icon: ImageVector, name: String, subtitle: String, cover: VideoItem?, tint: Color, onMore: (() -> Unit)?, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp).pressable(onClick, onMore)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp))) {
            if (cover != null) VideoThumbnail(cover, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(SurfaceElevated3))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xB3000000))))
            Icon(icon, null, tint = tint, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(20.dp))
            if (onMore != null) IconButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(32.dp)) {
                Icon(Icons.Rounded.MoreVert, "Rename or move", tint = Color.White, modifier = Modifier.size(20.dp).background(Color(0x66000000), CircleShape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivateVideoCard(v: PVideo, showTags: Boolean, context: String?, onClick: () -> Unit, onMore: () -> Unit) {
    val video = v.video
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp).pressable(onClick, onMore)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            VideoThumbnail(video, Modifier.fillMaxSize())
            if (video.duration > 0) Text(
                video.duration.formatDuration(), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xB3000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
            if (v.favorite) Icon(Icons.Rounded.Star, "Favorite", tint = StarGold, modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(20.dp).background(Color(0x80000000), CircleShape).padding(2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(video.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(
            listOfNotNull(video.size.takeIf { it > 0 }?.formatFileSize(), if (video.hasDimensions) "${minOf(video.width, video.height)}p" else null).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        )
        if (showTags && v.tags.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            v.tags.forEachIndexed { i, t -> TagChip(t, primary = i == 0, current = t == context) }
        }
    }
}

// ── Video row with tag chips ────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivateVideoRow(v: PVideo, showTags: Boolean, context: String?, onClick: () -> Unit, onMore: () -> Unit) {
    val video = v.video
    Row(Modifier.fillMaxWidth().pressable(onClick, onMore).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(156.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            VideoThumbnail(video, Modifier.fillMaxSize())
            if (video.duration > 0) Text(
                video.duration.formatDuration(), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color(0xB3000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
            if (v.favorite) Icon(Icons.Rounded.Star, "Favorite", tint = StarGold, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp).background(Color(0x80000000), CircleShape).padding(2.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(video.size.takeIf { it > 0 }?.formatFileSize(), if (video.hasDimensions) "${minOf(video.width, video.height)}p" else null).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1
            )
            if (showTags && v.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    v.tags.forEachIndexed { i, t -> TagChip(t, primary = i == 0, current = t == context) }
                }
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "More", tint = TextTertiary, modifier = Modifier.size(20.dp)) }
    }
}

/** [Muscle › Shirtless] — the primary (where the file lives) is filled, secondary ones outlined */
@Composable
private fun TagChip(tag: String, primary: Boolean, current: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.clip(shape).background(if (primary) AccentSoft else Color.Transparent)
            .border(1.dp, if (current) AccentBright else Accent.copy(alpha = if (primary) 0.6f else 0.35f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (primary) Icons.Rounded.Folder else Icons.Rounded.Sell, null, tint = AccentBright, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text(tagLabel(tag), fontSize = 11.sp, color = if (primary) AccentBright else TextSecondary, maxLines = 1)
    }
}

// ── Long-press sheet ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateActions(v: PVideo, vm: PrivateViewModel, onDismiss: () -> Unit, onPlay: () -> Unit, onTag: () -> Unit) {
    val fileActions = rememberFileActions()
    var info by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated2, dragHandle = { SheetHandle() }) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) { VideoThumbnail(v.video, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(v.video.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (v.tags.isEmpty()) "Not tagged" else v.tags.joinToString("  ·  ") { tagLabel(it) }, style = MaterialTheme.typography.bodySmall, color = AccentBright, maxLines = 2)
            }
        }
        HorizontalDivider(color = Border)
        Column(Modifier.padding(vertical = 6.dp)) {
            SheetAction(Icons.Rounded.PlayArrow, "Play") { onDismiss(); onPlay() }
            SheetAction(if (v.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (v.favorite) "Remove from Favorites" else "Add to Favorites", tint = StarGold) { vm.toggleFavorite(v); onDismiss() }
            SheetAction(Icons.Rounded.Sell, if (v.tags.isEmpty()) "Tag…" else "Edit tags…") { onDismiss(); onTag() }
            SheetAction(Icons.Rounded.Info, "Details") { info = true }
            SheetAction(Icons.Rounded.DeleteOutline, "Delete file", tint = ErrorRed) { delete = true }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
    if (info) VideoInfoDialog(v.video, onDismiss = { info = false })
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("Delete this video?") },
        text = { Text("\"${v.video.title}\" will be deleted from the device.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { delete = false; onDismiss(); fileActions.delete(listOf(v.video)) }) { Text("Delete", color = ErrorRed) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2
    )
}

// ── Settings ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateSettings(vm: PrivateViewModel, idx: PrivateIndex?, onDismiss: () -> Unit, onPickRoot: () -> Unit, onAddHidden: () -> Unit) {
    var changePin by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated2, dragHandle = { SheetHandle() }) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Text("Private settings", style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            SheetAction(Icons.Rounded.Password, "Change PIN") { changePin = true }
            SheetAction(Icons.Rounded.Sell, "Tagged folder", trailing = idx?.root?.substringAfterLast('/') ?: "Choose") { onDismiss(); onPickRoot() }
            Text("HIDDEN FOLDERS", style = MaterialTheme.typography.labelSmall, color = TextTertiary, modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 4.dp))
            idx?.hidden?.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderSpecial, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.substringAfterLast('/'), color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(h.removePrefix("/storage/emulated/0/"), color = TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { vm.removeHidden(h) }) { Text("Unhide", color = ErrorRed) }
                }
            }
            SheetAction(Icons.Rounded.CreateNewFolder, "Hide a folder…") { onDismiss(); onAddHidden() }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if (changePin) Dialog(onDismissRequest = { changePin = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var first by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize().background(SurfacePure)) {
            PinPad(
                title = if (first == null) "New PIN" else "Confirm PIN",
                subtitle = if (first == null) "4 to 8 digits, then ✓" else "Type it again",
                length = first?.length,
                onEntered = { code ->
                    when {
                        first == null -> { first = code; true }
                        code == first -> { vm.changePin(code); changePin = false; true }
                        else -> { first = null; false }
                    }
                },
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)
            )
            IconButton(onClick = { changePin = false }, modifier = Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.Rounded.Close, "Cancel", tint = TextPrimary) }
        }
    }
}

// ── Folder picker (real filesystem) ─────────────────────────────────────

@Composable
private fun FolderPicker(title: String, hint: String, start: File, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val top = File("/storage")
    var dir by remember { mutableStateOf(start.takeIf { it.isDirectory } ?: File("/storage/emulated/0")) }
    var newFolder by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val dirs = remember(dir, version) {
        // /storage itself can't be listed: offer internal storage + SD cards
        if (dir == top) listOf(File("/storage/emulated/0")) + (top.listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }.orEmpty())
        else dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).orEmpty()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SurfacePure).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel", tint = TextPrimary) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 2)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(dir.absolutePath.replace("/storage/emulated/0", "Internal storage"), color = AccentBright, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (dir != top) item(key = "up") { PickerRow(Icons.Rounded.SubdirectoryArrowLeft, "Up") { dir = dir.parentFile?.takeIf { it.absolutePath.startsWith("/storage") && it.absolutePath != "/storage/emulated" } ?: top } }
                items(dirs, key = { it.absolutePath }) { d ->
                    PickerRow(Icons.Rounded.Folder, if (d.absolutePath == "/storage/emulated/0") "Internal storage" else d.name) { dir = d }
                }
                if (dirs.isEmpty()) item(key = "none") { Text("No subfolders", color = TextTertiary, modifier = Modifier.padding(20.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { newFolder = true }, enabled = dir != top, modifier = Modifier.weight(1f).height(50.dp), shape = CircleShape) {
                    Icon(Icons.Rounded.CreateNewFolder, null, tint = TextPrimary); Spacer(Modifier.width(8.dp)); Text("New folder", color = TextPrimary)
                }
                Button(
                    onClick = { onPick(dir.absolutePath) }, enabled = dir != top, modifier = Modifier.weight(1f).height(50.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                ) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text("Use this folder", maxLines = 1) }
            }
        }
    }
    if (newFolder) NewFolderDialog(
        parent = dir.name,
        onCreate = { name, setError ->
            val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val f = File(dir, clean)
            when {
                clean.isEmpty() -> setError("Name can't be empty")
                f.isDirectory || f.mkdirs() -> { newFolder = false; version++; dir = f }
                else -> setError("Couldn't create it — allow all-files access first")
            }
        },
        onDismiss = { newFolder = false }
    )
}

@Composable
private fun PickerRow(icon: ImageVector, name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Banner(icon: ImageVector, title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(AccentSoft)
            .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentBright)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onClick, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text(action) }
    }
}

private enum class TagOrder(val label: String, val hint: String, val icon: ImageVector) {
    RANDOM("Random", "Shuffled", Icons.Rounded.Shuffle),
    NAME("File name", "A → Z", Icons.Rounded.SortByAlpha),
    NEWEST("Last added", "Newest first", Icons.Rounded.Schedule),
    LARGEST("Size: largest", "Biggest files first", Icons.Rounded.ArrowDownward),
    SMALLEST("Size: smallest", "Smallest files first", Icons.Rounded.ArrowUpward)
}

@Composable
private fun TagOrderDialog(count: Int, onDismiss: () -> Unit, onPick: (TagOrder) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag $count videos") },
        text = {
            // scrolls: in landscape the five choices are taller than the dialog
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In what order?", color = TextSecondary)
                TagOrder.entries.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceElevated3).clickable { onPick(o) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(o.icon, null, tint = AccentBright)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.label, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                            Text(o.hint, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2
    )
}

// ── Tag folder: rename / move ───────────────────────────────────────────

/**
 * Rename or move a tag folder from inside the app, so every link follows it: the files move with
 * the folder on disk and the library rows, favorites and secondary tags are re-pointed in one go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagFolderSheet(tag: String, tree: List<String>, vm: PrivateViewModel, onDismiss: () -> Unit, onMoved: (String) -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf<String?>(null) } // "rename" | "move" | "delete"
    var mergeInto by remember { mutableStateOf<String?>(null) }   // target tag that already exists → ask to merge
    val parent = tag.substringBeforeLast('/', "")
    fun apply(newTag: String, merge: Boolean = false) {
        vm.moveTag(tag, newTag, merge) { done, err, exists ->
            when {
                done != null -> {
                    onMoved(done)
                    android.widget.Toast.makeText(ctx, (if (merge) "Merged into " else "Now ") + tagLabel(done) + " — tags kept", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                exists -> mergeInto = newTag
                else -> android.widget.Toast.makeText(ctx, err ?: "Couldn't do that", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    if (mode == null && mergeInto == null) ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated2, dragHandle = { SheetHandle() }) {
        Text(tagLabel(tag), style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        Text("Videos keep all their tags — do renames here, not in a file manager.", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(6.dp))
        SheetAction(Icons.Rounded.DriveFileRenameOutline, "Rename…") { mode = "rename" }
        SheetAction(Icons.Rounded.DriveFileMove, "Move into another folder…", trailing = if (parent.isEmpty()) "in root" else "in ${tagName(parent)}") { mode = "move" }
        SheetAction(Icons.Rounded.DeleteOutline, "Delete folder…", tint = ErrorRed) { mode = "delete" }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
    if (mode == "rename") {
        var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(tagName(tag), androidx.compose.ui.text.TextRange(0, tagName(tag).length))) }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
        val clean = name.text.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val submit = { if (clean.isNotEmpty()) apply(if (parent.isEmpty()) clean else "$parent/$clean") }
        AlertDialog(
            onDismissRequest = onDismiss, containerColor = SurfaceElevated2,
            title = { Text("Rename folder") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            },
            confirmButton = { TextButton(onClick = submit, enabled = clean.isNotEmpty() && clean != tagName(tag)) { Text("Rename", color = Accent) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
        )
    }
    if (mode == "move") {
        // folder view, like the one used while sorting: tap a folder to open it, "Move here" drops the folder in the open one
        var at by remember { mutableStateOf(parent) }
        val children = remember(tree, at) {
            tree.filter { it.substringBeforeLast('/', "") == at && it != tag && !it.startsWith("$tag/") }.sortedBy { tagName(it).lowercase() }
        }
        val canMoveHere = at != parent && at != tag && !at.startsWith("$tag/")
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f).clip(RoundedCornerShape(24.dp)).background(SurfaceElevated2).padding(16.dp)) {
                Text("Move “${tagName(tag)}” into…", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(if (at.isEmpty()) "Top level" else tagLabel(at), style = MaterialTheme.typography.bodySmall, color = AccentBright, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(104.dp), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (at.isNotEmpty()) item(key = "up") {
                        FolderTile(Icons.Rounded.ArrowUpward, "Go up", highlighted = false) { at = at.substringBeforeLast('/', "") }
                    }
                    gridItems(children, key = { it }) { t -> FolderTile(Icons.Rounded.Folder, tagName(t), highlighted = false) { at = t } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel", color = TextSecondary) }
                    Button(
                        onClick = { apply(if (at.isEmpty()) tagName(tag) else "$at/${tagName(tag)}") }, enabled = canMoveHere, modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                    ) { Text(if (at.isEmpty()) "Move to top level" else "Move here") }
                }
            }
        }
    }
    mergeInto?.let { target ->
        AlertDialog(
            onDismissRequest = { mergeInto = null }, containerColor = SurfaceElevated2,
            title = { Text("“${tagName(target)}” already exists") },
            text = { Text("Merge “${tagName(tag)}” into it? All videos end up in one folder; files with the same name get “(2)”, and tags are kept.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { val t = target; mergeInto = null; apply(t, merge = true) }) { Text("Merge", color = Accent) } },
            dismissButton = { TextButton(onClick = { mergeInto = null; onDismiss() }) { Text("Cancel", color = TextSecondary) } }
        )
    }
    if (mode == "delete") AlertDialog(
        onDismissRequest = onDismiss, containerColor = SurfaceElevated2,
        title = { Text("Delete “${tagName(tag)}”?") },
        text = { Text("The folder goes away; the videos inside move up into ${if (parent.isEmpty()) "the top level" else "“${tagName(parent)}”"}. No video is deleted. Other tags that pointed here are dropped.", color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = {
                vm.deleteTag(tag) { n, err ->
                    if (n != null) { onMoved(parent); android.widget.Toast.makeText(ctx, "Folder deleted, $n video${if (n == 1) "" else "s"} moved up", android.widget.Toast.LENGTH_SHORT).show(); onDismiss() }
                    else android.widget.Toast.makeText(ctx, err ?: "Couldn't delete", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Delete", color = ErrorRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}

@Composable
private fun FolderTile(icon: ImageVector, name: String, highlighted: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (highlighted) AccentSoft else SurfaceElevated3).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(name, color = TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, minLines = 2)
    }
}
