package com.app.xvideoplayer.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import com.app.xvideoplayer.util.formatFileSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.ui.components.*
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.hasVideoPermission

// ── Videos tab ──────────────────────────────────────────────────────────

@Composable
fun VideosTab(onPlay: (VideoItem) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val videos by viewModel.videos.collectAsState()
    val count by viewModel.libraryCount.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val resume by viewModel.resumeCandidate.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val continueEnabled by viewModel.showContinueWatching.collectAsState()
    val panelHidden by viewModel.continuePanelHidden.collectAsState()
    var showSort by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidePanel = maxWidth > maxHeight && maxWidth > 700.dp && continueEnabled
        Column(Modifier.fillMaxSize()) {
            if (selection.isNotEmpty()) SelectionBar(viewModel, videos.orEmpty())
            else LibraryHeader(
                title = "Videos",
                subtitle = count?.let { "$it video${if (it == 1) "" else "s"}" },
                searchQuery = query, onSearchChange = viewModel::setSearchQuery,
                actions = {
                    val orientation by viewModel.orientation.collectAsState()
                    OrientationIconButton(orientation, viewModel::setOrientation)
                    IconButton(onClick = { showSort = true }) { Icon(Icons.Rounded.SwapVert, "Sort and filter", tint = TextPrimary) }
                    LayoutButton(viewModel)
                    if (sidePanel) IconButton(onClick = { viewModel.setContinuePanelHidden(!panelHidden) }) {
                        Icon(Icons.Rounded.History, "Continue watching panel", tint = if (!panelHidden) Accent else TextPrimary)
                    }
                    RefreshButton(viewModel)
                }
            )
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    LibraryBody(viewModel, onPlay, showContinue = !sidePanel, videos = videos, onOpenFolder = {}, showSortSheet = showSort, onSortSheet = { showSort = it })
                    // floating play: resumes the last video, with its name underneath
                    resume?.let { v ->
                        if (selection.isEmpty() && query.isBlank()) Column(
                            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).widthIn(max = 140.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            FloatingActionButton(onClick = { onPlay(v) }, containerColor = Accent, contentColor = OnAccent, shape = androidx.compose.foundation.shape.CircleShape) {
                                Icon(Icons.Rounded.PlayArrow, "Resume ${v.title}", modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                v.title, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                if (sidePanel && !panelHidden) ContinuePanel(viewModel, onPlay)
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }
}

/** Landscape: continue watching lives in a column on the right instead of a row above the list. */
@Composable
private fun ContinuePanel(viewModel: LibraryViewModel, onPlay: (VideoItem) -> Unit) {
    val items by viewModel.continueWatching.collectAsState()
    Column(Modifier.width(300.dp).fillMaxHeight().background(SurfaceElevated2).padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Continue watching", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.setContinuePanelHidden(true) }) { Icon(Icons.Rounded.Close, "Hide panel", tint = TextSecondary) }
        }
        if (items.isEmpty()) Text("Nothing in progress.", color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { v -> ContinueCard(v, onClick = { onPlay(v) }, onLongClick = {}, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

/** One button that cycles grid, list and one-per-row list. */
@Composable
private fun LayoutButton(viewModel: LibraryViewModel) {
    val viewMode by viewModel.viewMode.collectAsState()
    IconButton(onClick = viewModel::toggleViewMode) {
        AnimatedContent(viewMode, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "viewMode") { mode ->
            Icon(
                when (mode) { "GRID" -> Icons.Rounded.GridView; "LIST1" -> Icons.Rounded.ViewList; else -> Icons.Rounded.ViewAgenda },
                "Layout: " + when (mode) { "GRID" -> "grid"; "LIST1" -> "one per row"; else -> "list" }, tint = TextPrimary
            )
        }
    }
}

/** Replaces the header while videos are selected. */
@Composable
private fun SelectionBar(viewModel: LibraryViewModel, videos: List<VideoItem>) {
    val selection by viewModel.selection.collectAsState()
    val fileActions = rememberFileActions()
    var confirm by remember { mutableStateOf(false) }
    val chosen = videos.filter { it.id in selection }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 4.dp, end = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Rounded.Close, "Cancel selection", tint = TextPrimary) }
        Text("${selection.size} selected", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
        IconButton(onClick = { viewModel.selectAll(videos.map { it.id }) }) { Icon(Icons.Rounded.SelectAll, "Select all", tint = TextPrimary) }
        IconButton(onClick = { viewModel.favoriteSelected(videos) }) { Icon(Icons.Rounded.FavoriteBorder, "Favorite", tint = TextPrimary) }
        IconButton(onClick = { viewModel.watchedSelected(videos) }) { Icon(Icons.Rounded.Check, "Mark watched", tint = TextPrimary) }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = ErrorRed) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete ${chosen.size} video${if (chosen.size == 1) "" else "s"}?") },
        text = { Text("They will be deleted from the device.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { confirm = false; fileActions.delete(chosen) { viewModel.clearSelection() } }) { Text("Delete", color = ErrorRed) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2
    )
}

// ── Single folder page ──────────────────────────────────────────────────

@Composable
fun FolderScreen(onBack: () -> Unit, onPlay: (VideoItem) -> Unit, onOpenFolder: (String) -> Unit = {}, viewModel: LibraryViewModel = hiltViewModel()) {
    val videos by viewModel.videos.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val path = viewModel.folderPath.orEmpty()
    var showSort by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(SurfacePure)) {
        if (selection.isNotEmpty()) SelectionBar(viewModel, videos.orEmpty())
        else LibraryHeader(
            title = path.substringAfterLast('/'),
            subtitle = path.removePrefix("/storage/emulated/0/").substringBeforeLast('/', "Internal storage"),
            searchQuery = query, onSearchChange = viewModel::setSearchQuery, onBack = onBack,
            actions = {
                val orientation by viewModel.orientation.collectAsState()
                OrientationIconButton(orientation, viewModel::setOrientation)
                IconButton(onClick = { showSort = true }) { Icon(Icons.Rounded.SwapVert, "Sort and filter", tint = TextPrimary) }
                FolderViewButton(viewModel)
                LayoutButton(viewModel)
                RefreshButton(viewModel)
            }
        )
        LibraryBody(viewModel, onPlay, showContinue = false, videos = videos, onOpenFolder = onOpenFolder, showSubfolders = true, showSortSheet = showSort, onSortSheet = { showSort = it })
    }
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }
}

/** Folders as a list or as a grid of tiles. */
@Composable
private fun FolderViewButton(viewModel: LibraryViewModel) {
    val fv by viewModel.folderView.collectAsState()
    IconButton(onClick = viewModel::toggleFolderView) {
        Icon(if (fv == "GRID") Icons.Rounded.Dashboard else Icons.Rounded.ViewList, "Folders as ${if (fv == "GRID") "list" else "grid"}", tint = TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryBody(
    viewModel: LibraryViewModel, onPlay: (VideoItem) -> Unit, showContinue: Boolean, videos: List<VideoItem>?,
    onOpenFolder: (String) -> Unit, showSubfolders: Boolean = false,
    showSortSheet: Boolean, onSortSheet: (Boolean) -> Unit
) {
    val subfolders by viewModel.subfolders.collectAsState()
    val count by viewModel.libraryCount.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val continueEnabled by viewModel.showContinueWatching.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val folderView by viewModel.folderView.collectAsState()
    val sort by viewModel.sortConfig.collectAsState()
    val filter by viewModel.filterConfig.collectAsState()
    val selection by viewModel.selection.collectAsState()

    var selected by remember { mutableStateOf<VideoItem?>(null) }
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(sync.isSyncing) { if (!sync.isSyncing) userRefreshing = false }

    PermissionGate(onGranted = viewModel::onPermissionGranted) {
        PullToRefreshBox(
            isRefreshing = userRefreshing,
            onRefresh = { userRefreshing = true; viewModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                videos == null -> Unit // Room answers within a frame; draw nothing rather than flash a spinner
                count == 0 && !sync.hasSyncedOnce -> FirstScanState()
                count == 0 -> EmptyState(
                    Icons.Rounded.VideoLibrary, "No videos yet", "Videos you record or download will show up here automatically.",
                    action = { Button(onClick = { userRefreshing = true; viewModel.refresh() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Check again") } }
                )
                else -> {
                    val grid = viewMode == "GRID"
                    LazyVerticalGrid(
                        columns = when (viewMode) { "GRID" -> GridCells.Adaptive(168.dp); "LIST1" -> GridCells.Fixed(1); else -> GridCells.Adaptive(400.dp) },
                        contentPadding = PaddingValues(start = if (grid) 16.dp else 0.dp, end = if (grid) 16.dp else 0.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(if (grid) 18.dp else 0.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (showContinue && continueEnabled && continueWatching.isNotEmpty() && query.isBlank()) {
                            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    SectionTitle("Continue watching", startPadding = if (grid) 4.dp else 20.dp)
                                    LazyRow(contentPadding = PaddingValues(horizontal = if (grid) 0.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(continueWatching, key = { it.id }) { v ->
                                            ContinueCard(v, onClick = { onPlay(v) }, onLongClick = { selected = v }, modifier = Modifier.animateItem())
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                        item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(start = if (grid) 4.dp else 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(sortSummary(sort, filter), style = MaterialTheme.typography.labelMedium, color = TextTertiary, modifier = Modifier.weight(1f))
                                Text("${videos.size}", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                            }
                        }
                        val subs = if (showSubfolders) subfolders.orEmpty() else emptyList()
                        if (subs.isNotEmpty()) {
                            item(key = "subs_title", span = { GridItemSpan(maxLineSpan) }) {
                                SectionTitle("Folders", startPadding = if (grid) 4.dp else 20.dp)
                            }
                            if (folderView == "GRID") items(subs, key = { "sub:" + it.folder.path }, contentType = { "foldercard" }) { e ->
                                FolderCard(e.folder, e.cover, onClick = { onOpenFolder(e.folder.path) }, modifier = Modifier.padding(horizontal = if (grid) 0.dp else 16.dp).animateItem())
                            }
                            else items(subs, key = { "sub:" + it.folder.path }, span = { GridItemSpan(maxLineSpan) }, contentType = { "folder" }) { e ->
                                FolderRow(e.folder, e.cover, onClick = { onOpenFolder(e.folder.path) }, modifier = Modifier.animateItem())
                            }
                            if (videos.isNotEmpty()) item(key = "vids_title", span = { GridItemSpan(maxLineSpan) }) {
                                SectionTitle("Videos here", startPadding = if (grid) 4.dp else 20.dp)
                            }
                        }
                        if (videos.isEmpty() && subs.isEmpty()) {
                            item(key = "nomatch", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(Icons.Rounded.SearchOff, "Nothing matches", "Try a different filter or search.", modifier = Modifier.height(320.dp))
                            }
                        }
                        items(videos, key = { it.id }, contentType = { "video" }) { v ->
                            val isSel = v.id in selection
                            val inSelectMode = selection.isNotEmpty()
                            val click = { if (inSelectMode) viewModel.toggleSelect(v.id) else onPlay(v) }
                            val long = { if (inSelectMode) viewModel.toggleSelect(v.id) else viewModel.startSelect(v.id) }
                            if (grid) VideoCard(v, onClick = click, onMore = { selected = v }, selected = isSel, onLongClick = long, modifier = Modifier.animateItem())
                            else VideoRow(v, onClick = click, onMore = { selected = v }, selected = isSel, onLongClick = long, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }

    VideoActionsHost(selected, onDismiss = { selected = null }, onPlay = onPlay, viewModel = viewModel)
    if (showSortSheet) SortFilterSheet(sort, filter, viewModel::setSortConfig, viewModel::setFilterConfig, onDismiss = { onSortSheet(false) })
}

private fun sortSummary(sort: SortConfig, filter: FilterConfig): String {
    val field = when (sort.field) {
        SortField.TITLE, SortField.FOLDER -> "Name"; SortField.DATE_ADDED -> "Newest"; SortField.LAST_PLAYED -> "Recently played"
        SortField.DURATION -> "Length"; SortField.SIZE -> "Size"; SortField.PLAY_COUNT -> "Most played"
    }
    val extra = listOfNotNull(if (filter.favoritesOnly) "favorites" else null, filter.status?.name?.lowercase()).joinToString(", ")
    return field + (if (sort.order == SortOrder.ASCENDING) " ↑" else " ↓") + if (extra.isNotEmpty()) "  ·  $extra" else ""
}

// ── Folders tab ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersTab(onOpenFolder: (String) -> Unit, onOpenCached: () -> Unit = {}, viewModel: LibraryViewModel = hiltViewModel()) {
    val flatFolders by viewModel.folders.collectAsState()
    val nestedFolders by viewModel.subfolders.collectAsState()
    val folderMode by viewModel.folderMode.collectAsState()
    val folderView by viewModel.folderView.collectAsState()
    val folders = if (folderMode == "NESTED") nestedFolders else flatFolders
    val offlineItems by androidx.hilt.navigation.compose.hiltViewModel<com.app.xvideoplayer.ui.cloud.OfflineViewModel>().items.collectAsState()
    val cachedGroups = remember(offlineItems) { com.app.xvideoplayer.ui.cloud.cachedGroups(offlineItems) }
    val count by viewModel.libraryCount.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val orientation by viewModel.orientation.collectAsState()
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(sync.isSyncing) { if (!sync.isSyncing) userRefreshing = false }

    Column(Modifier.fillMaxSize()) {
        LibraryHeader(
            title = "Folders",
            subtitle = folders?.let { "${it.size} folder${if (it.size == 1) "" else "s"}" },
            searchQuery = query, onSearchChange = viewModel::setSearchQuery,
            actions = {
                OrientationIconButton(orientation, viewModel::setOrientation)
                IconButton(onClick = { viewModel.setFolderMode(if (folderMode == "NESTED") "ALL" else "NESTED") }) {
                    Icon(Icons.Rounded.AccountTree, if (folderMode == "NESTED") "Nested folders (on)" else "Nested folders (off)", tint = if (folderMode == "NESTED") Accent else TextPrimary)
                }
                FolderViewButton(viewModel)
                RefreshButton(viewModel)
            }
        )
        PermissionGate(onGranted = viewModel::onPermissionGranted) {
            PullToRefreshBox(isRefreshing = userRefreshing, onRefresh = { userRefreshing = true; viewModel.refresh() }, modifier = Modifier.fillMaxSize()) {
                val list = folders
                when {
                    list == null -> Unit
                    count == 0 && !sync.hasSyncedOnce -> FirstScanState()
                    list.isEmpty() -> EmptyState(Icons.Rounded.FolderOff, "No folders", "Nothing here matches.", modifier = Modifier.height(320.dp))
                    folderView == "GRID" -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(168.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()
                    ) {
                        if (cachedGroups.isNotEmpty()) item(key = "cached", contentType = "cachedcard") { CachedFolderCard(cachedGroups, onOpenCached) }
                        items(list, key = { it.folder.path }, contentType = { "foldercard" }) { e ->
                            FolderCard(e.folder, e.cover, onClick = { onOpenFolder(e.folder.path) }, modifier = Modifier.animateItem())
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
                        if (cachedGroups.isNotEmpty()) item(key = "cached", contentType = "cachedrow") { CachedFolderRow(cachedGroups, onOpenCached) }
                        items(list, key = { it.folder.path }, contentType = { "folder" }) { e ->
                            FolderRow(e.folder, e.cover, onClick = { onOpenFolder(e.folder.path) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

/** "Cached" entry next to the local folders: opens the offline copies grouped by their server folder */
@Composable
private fun CachedFolderCard(groups: List<com.app.xvideoplayer.ui.cloud.CachedGroup>, onClick: () -> Unit) {
    val videos = groups.sumOf { it.items.size }
    Column(Modifier.pressable(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp)).background(PlayedGreen.copy(alpha = 0.16f)).border(1.dp, PlayedGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.OfflinePin, null, tint = PlayedGreen, modifier = Modifier.size(44.dp))
            Text("$videos", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("Cached", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.padding(horizontal = 2.dp))
        Text("${groups.size} folder${if (groups.size == 1) "" else "s"}  ·  ${groups.sumOf { it.size }.formatFileSize()}", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun CachedFolderRow(groups: List<com.app.xvideoplayer.ui.cloud.CachedGroup>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(PlayedGreen.copy(alpha = 0.16f)).border(1.dp, PlayedGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.OfflinePin, null, tint = PlayedGreen, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Cached", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text("${groups.sumOf { it.items.size }} videos in ${groups.size} folder${if (groups.size == 1) "" else "s"}  ·  ${groups.sumOf { it.size }.formatFileSize()}", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────

@Composable
fun VideoActionsHost(selected: VideoItem?, onDismiss: () -> Unit, onPlay: (VideoItem) -> Unit, viewModel: LibraryViewModel) {
    var rename by remember { mutableStateOf<VideoItem?>(null) }
    var delete by remember { mutableStateOf<VideoItem?>(null) }
    var info by remember { mutableStateOf<VideoItem?>(null) }
    selected?.let { v ->
        VideoActionsSheet(
            video = v, onDismiss = onDismiss, onPlay = { onPlay(v) },
            onToggleFavorite = { viewModel.toggleFavorite(v) },
            onMarkWatched = { viewModel.setPlayedStatus(v.id, PlayedStatus.WATCHED) },
            onMarkUnwatched = { viewModel.setPlayedStatus(v.id, PlayedStatus.UNWATCHED) },
            onRename = { rename = v }, onDelete = { delete = v }, onInfo = { info = v }
        )
    }
    val fileActions = rememberFileActions()
    rename?.let { v -> RenameDialog(v.title, onConfirm = { fileActions.rename(v, it); rename = null }, onDismiss = { rename = null }) }
    delete?.let { v ->
        DeleteDialog(v.title, onConfirm = { fromDisk ->
            if (fromDisk) fileActions.delete(listOf(v)) else viewModel.removeFromLibrary(v)
            delete = null
        }, onDismiss = { delete = null })
    }
    info?.let { v -> VideoInfoDialog(v, onDismiss = { info = null }) }
}

@Composable
private fun PermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasVideoPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
        if (granted) onGranted()
    }
    if (granted) content()
    else EmptyState(
        Icons.Rounded.Lock, "Allow access to videos", "xVideoPlayer needs permission to find the videos on this device.",
        action = {
            Button(
                onClick = { launcher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) { Text("Allow access") }
        }
    )
}

/** Tap: refresh this screen's scope (a folder, or everything). Long-press: refresh everything. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RefreshButton(viewModel: LibraryViewModel) {
    val sync by viewModel.syncState.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val spin = rememberInfiniteTransition(label = "refresh")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(
                onClick = { viewModel.refresh() },
                onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); viewModel.refreshAll() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Refresh, "Refresh (long-press: everything)", tint = if (sync.isSyncing) Accent else TextPrimary,
            modifier = Modifier.graphicsLayer { rotationZ = if (sync.isSyncing) angle else 0f }
        )
    }
}
