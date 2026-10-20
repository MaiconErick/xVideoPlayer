package com.app.xvideoplayer.ui.cloud

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.repository.ServerRepository
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.remote.*
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.ui.components.EmptyState
import com.app.xvideoplayer.ui.components.pressable
import com.app.xvideoplayer.util.formatFileSize
import com.app.xvideoplayer.util.formatRelativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val videoRepository: VideoRepository,
    private val smbClient: SmbClient,
    private val ftpClient: FtpClient,
    private val listingCache: RemoteListingCache,
    val downloads: SmbDownloads
) : ViewModel() {

    private val serverId: Long = savedStateHandle["serverId"] ?: 0L

    private val _server = MutableStateFlow<ServerConfig?>(null)
    val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _pathStack = mutableListOf<String>()

    private val _files = MutableStateFlow<List<RemoteFileEntry>>(emptyList())
    val files: StateFlow<List<RemoteFileEntry>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When the list on screen is the saved copy: when it was saved, and whether the live refresh failed */
    data class CacheNote(val savedAt: Long, val offline: Boolean)
    private val _cacheNote = MutableStateFlow<CacheNote?>(null)
    val cacheNote: StateFlow<CacheNote?> = _cacheNote.asStateFlow()

    /** Long-press multi-select (entry paths) */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var browseJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            val config = serverRepository.getServerById(serverId)
            _server.value = config
            config?.let {
                _currentPath.value = it.remotePath
                browse(it.remotePath)
            }
        }
    }

    /** Shows the saved listing right away, then replaces it with the live one (and saves that). */
    fun browse(path: String) {
        val config = _server.value ?: return
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _error.value = null
            val cached = listingCache.load(serverId, path)
            if (cached != null) {
                _files.value = cached.entries
                _currentPath.value = path
                _cacheNote.value = CacheNote(cached.savedAt, offline = false)
            }
            _isLoading.value = true
            try {
                val entries = when (config.type) {
                    ServerType.SMB -> smbClient.listFiles(config, path)
                    ServerType.FTP -> ftpClient.listFiles(config, path)
                }
                _files.value = entries
                _currentPath.value = path
                _cacheNote.value = null
                listingCache.save(serverId, path, entries)
                serverRepository.updateLastConnected(serverId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("xvp-browse", "list failed", e)
                if (cached != null) _cacheNote.value = CacheNote(cached.savedAt, offline = true)
                else _error.value = (e.message ?: "Connection failed") + "  [" + e.javaClass.simpleName + "]"
            }
            _isLoading.value = false
        }
    }

    fun navigateInto(entry: RemoteFileEntry) {
        if (entry.isDirectory) {
            _pathStack.add(_currentPath.value)
            clearSelection()
            browse(entry.path)
        }
    }

    fun navigateUp(): Boolean {
        return if (_pathStack.isNotEmpty()) {
            clearSelection()
            browse(_pathStack.removeLast())
            true
        } else false
    }

    fun toggleSelect(entry: RemoteFileEntry) { _selection.update { if (entry.path in it) it - entry.path else it + entry.path } }
    fun selectAllVideos() { _selection.value = _files.value.filter { !it.isDirectory }.map { it.path }.toSet() }
    fun clearSelection() { _selection.value = emptySet() }

    val canCache get() = _server.value?.type == ServerType.SMB

    /** Queues the selected videos, plus the videos directly inside selected folders, for whole-file offline copies. */
    fun cacheSelected() {
        val config = _server.value ?: return
        if (config.type != ServerType.SMB) { _messages.tryEmit("Offline copies work for SMB servers"); return }
        val picked = _files.value.filter { it.path in _selection.value }
        clearSelection()
        viewModelScope.launch {
            val videos = picked.filter { !it.isDirectory }.toMutableList()
            for (dir in picked.filter { it.isDirectory }) {
                val inside = runCatching { smbClient.listFiles(config, dir.path) }.getOrElse { listingCache.load(serverId, dir.path)?.entries.orEmpty() }
                videos += inside.filter { !it.isDirectory }
            }
            if (videos.isEmpty()) { _messages.tryEmit("No videos to cache"); return@launch }
            downloads.enqueue(config, videos)
            _messages.tryEmit("Caching ${videos.size} video${if (videos.size == 1) "" else "s"} (${videos.sumOf { it.size }.formatFileSize()})")
        }
    }

    /** Deletes the offline copies (or stops the downloads) of the selected videos */
    fun removeSelectedCopies() {
        val uris = _files.value.filter { it.path in _selection.value && !it.isDirectory }.map { getVideoUri(it) }
        clearSelection()
        downloads.remove(uris)
    }

    fun getVideoUri(entry: RemoteFileEntry): String {
        val config = _server.value ?: return ""
        return when (config.type) {
            ServerType.SMB -> smbClient.getSmbUri(config, entry.path)
            ServerType.FTP -> {
                val auth = if (config.username.isBlank()) "" else "${android.net.Uri.encode(config.username)}:${android.net.Uri.encode(config.password)}@"
                "ftp://$auth${config.host}:${config.port}${entry.path}"
            }
        }
    }

    fun getVideoSource(): String {
        return _server.value?.type?.name ?: "SMB"
    }

    suspend fun getOrCreateVideoId(entry: RemoteFileEntry): Long {
        val existing = videoRepository.getVideoByUri(getVideoUri(entry))
        if (existing != null) return existing.id

        val video = VideoItem(
            title = entry.name.substringBeforeLast('.'),
            path = entry.path,
            uri = getVideoUri(entry),
            size = entry.size,
            source = if (_server.value?.type == ServerType.SMB) VideoSource.SMB else VideoSource.FTP,
            serverId = serverId
        )
        return videoRepository.insertVideo(video)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerBrowserScreen(
    serverId: Long,
    onBack: () -> Unit,
    onPlayVideo: (Long, String, String) -> Unit,
    viewModel: ServerBrowserViewModel = hiltViewModel()
) {
    val server by viewModel.server.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val cacheNote by viewModel.cacheNote.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val downloads by viewModel.downloads.items.collectAsState()
    val byUri = remember(downloads) { downloads.associateBy { it.uri } }
    val askNotifications = rememberNotificationAsk()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { viewModel.messages.collect { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() } }

    androidx.activity.compose.BackHandler {
        when {
            selection.isNotEmpty() -> viewModel.clearSelection()
            !viewModel.navigateUp() -> onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(SurfacePure)) {
        if (selection.isNotEmpty()) Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Rounded.Close, "Cancel selection", tint = TextPrimary) }
            val picked = files.filter { it.path in selection }
            val size = picked.sumOf { it.size }
            Column(Modifier.weight(1f)) {
                Text("${selection.size} selected", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(if (picked.any { it.isDirectory }) "folders: their videos" + if (size > 0) "  ·  ${size.formatFileSize()} + folders" else "" else size.formatFileSize(), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
            IconButton(onClick = viewModel::selectAllVideos) { Icon(Icons.Rounded.SelectAll, "Select all videos", tint = TextPrimary) }
            if (picked.any { !it.isDirectory && byUri[viewModel.getVideoUri(it)] != null })
                IconButton(onClick = viewModel::removeSelectedCopies) { Icon(Icons.Rounded.DeleteOutline, "Remove offline copies", tint = TextPrimary) }
            if (viewModel.canCache) Button(
                onClick = { askNotifications(); viewModel.cacheSelected() },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent), contentPadding = PaddingValues(horizontal = 14.dp)
            ) { Icon(Icons.Rounded.DownloadForOffline, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cache") }
        } else Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!viewModel.navigateUp()) onBack() }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text(server?.name ?: "Server", style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1)
                Text(currentPath, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1)
            }
            IconButton(onClick = { viewModel.browse(currentPath) }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = TextPrimary) }
        }
        AnimatedVisibility(isLoading, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = SurfaceElevated2)
        }
        cacheNote?.let { n ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (n.offline) Icons.Rounded.CloudOff else Icons.Rounded.History, null, tint = if (n.offline) WarningAmber else TextTertiary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (n.offline) "Server unreachable — saved list from " else "Saved list from ") + n.savedAt.formatRelativeTime() + if (n.offline) "" else ", refreshing…",
                    style = MaterialTheme.typography.labelSmall, color = if (n.offline) WarningAmber else TextTertiary
                )
            }
        }
        AnimatedContent(
            targetState = Triple(currentPath, error, files),
            transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally { it / 12 }) togetherWith fadeOut(tween(120)) },
            modifier = Modifier.weight(1f),
            label = "dir"
        ) { (_, err, list) ->
            when {
                err != null -> EmptyState(
                    Icons.Rounded.WifiOff, "Couldn't connect", err,
                    action = { Button(onClick = { viewModel.browse(currentPath) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Retry") } }
                )
                list.isEmpty() && !isLoading -> EmptyState(Icons.Rounded.FolderOff, "Empty folder", "No videos or folders here.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(list, key = { it.path }) { entry ->
                        val dl = if (entry.isDirectory) null else byUri[viewModel.getVideoUri(entry)]
                        RemoteFileRow(
                            entry, dl, selected = entry.path in selection,
                            onClick = {
                                when {
                                    selection.isNotEmpty() -> viewModel.toggleSelect(entry)
                                    entry.isDirectory -> viewModel.navigateInto(entry)
                                    else -> onPlayVideo(0, viewModel.getVideoUri(entry), viewModel.getVideoSource())
                                }
                            },
                            onLongClick = { viewModel.toggleSelect(entry) }
                        )
                    }
                }
            }
        }
        OverallDownloadBar(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun RemoteFileRow(entry: RemoteFileEntry, dl: SmbDownloads.Item?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cached = dl?.state == SmbDownloads.State.DONE
    Row(
        Modifier.fillMaxWidth().background(if (selected) AccentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .pressable(onClick, onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(
                when { selected -> Accent; entry.isDirectory -> SurfaceElevated3; cached -> PlayedGreen.copy(alpha = 0.18f); else -> AccentSoft }
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when { selected -> Icons.Rounded.Check; entry.isDirectory -> Icons.Rounded.Folder; cached -> Icons.Rounded.OfflinePin; else -> Icons.Rounded.PlayArrow },
                null, tint = when { selected -> OnAccent; entry.isDirectory -> TextSecondary; cached -> PlayedGreen; else -> Accent }
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2)
            if (!entry.isDirectory) {
                if (dl != null && !cached) ItemProgress(dl, Modifier.padding(top = 4.dp, end = 8.dp))
                else Text(entry.size.formatFileSize() + if (cached) "  ·  offline copy" else "", style = MaterialTheme.typography.bodySmall, color = if (cached) PlayedGreen else TextTertiary)
            }
        }
        if (entry.isDirectory) Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
    }
}
