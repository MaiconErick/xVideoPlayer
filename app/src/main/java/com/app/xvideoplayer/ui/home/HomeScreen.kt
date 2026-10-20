package com.app.xvideoplayer.ui.home

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.repository.ServerRepository
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.ui.components.pressable
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class StorageInfo(val name: String, val path: String, val total: Long, val free: Long, val videoBytes: Long) {
    val used: Long get() = total - free
}

data class LibraryStats(
    val count: Int = 0, val bytes: Long = 0, val folders: Int = 0,
    val portrait: Int = 0, val watched: Int = 0, val inProgress: Int = 0, val totalDuration: Long = 0
)

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data class Online(val ms: Long) : ServerStatus
    data object Offline : ServerStatus
}

data class NetworkInfo(val kind: String, val downMbps: Int?, val connected: Boolean)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val videoRepository: VideoRepository,
    serverRepository: ServerRepository
) : AndroidViewModel(application) {

    val syncState = videoRepository.syncState

    private val local = videoRepository.getPublicLocalVideos()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stats: StateFlow<LibraryStats> = local.map { v ->
        LibraryStats(
            count = v.size, bytes = v.sumOf { it.size }, folders = v.map { it.folderPath }.distinct().size,
            portrait = v.count { it.isPortrait }, watched = v.count { it.playedStatus == PlayedStatus.WATCHED },
            inProgress = v.count { it.playedStatus == PlayedStatus.IN_PROGRESS }, totalDuration = v.sumOf { it.duration }
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LibraryStats())

    private val _storage = MutableStateFlow<List<StorageInfo>>(emptyList()); val storage = _storage.asStateFlow()
    private val _network = MutableStateFlow(NetworkInfo("…", null, true)); val network = _network.asStateFlow()

    val servers = serverRepository.getAllServers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _serverStatus = MutableStateFlow<Map<Long, ServerStatus>>(emptyMap()); val serverStatus = _serverStatus.asStateFlow()

    init {
        videoRepository.ensureLibrarySynced()
        viewModelScope.launch { local.collect { refreshStorage(it) } }
        viewModelScope.launch { servers.collect { checkServers(it) } }
        refreshNetwork()
    }

    /** Pull-to-refresh on Home: everything — library, storage, network, servers */
    fun refreshAll() {
        videoRepository.requestSync()
        refreshNetwork()
        viewModelScope.launch { refreshStorage(local.value); checkServers(servers.value) }
    }

    private suspend fun refreshStorage(videos: List<VideoItem>) = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val dirs: List<Pair<String, File>> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm.storageVolumes.mapNotNull { v -> v.directory?.let { (v.getDescription(ctx) ?: "Storage") to it } }
        } else listOf("Internal storage" to Environment.getExternalStorageDirectory())
        _storage.value = dirs.mapNotNull { (name, dir) ->
            runCatching {
                val st = StatFs(dir.path)
                val p = dir.absolutePath
                StorageInfo(name, p, st.totalBytes, st.availableBytes, videos.filter { it.path.startsWith("$p/") }.sumOf { it.size })
            }.getOrNull()
        }
    }

    fun refreshNetwork() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        _network.value = when {
            caps == null -> NetworkInfo("Offline", null, false)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkInfo("Wi-Fi", caps.linkDownstreamBandwidthKbps / 1000, true)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkInfo("Ethernet", caps.linkDownstreamBandwidthKbps / 1000, true)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkInfo("Mobile data", caps.linkDownstreamBandwidthKbps / 1000, true)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkInfo("VPN", null, true)
            else -> NetworkInfo("Connected", null, true)
        }
    }

    /** A plain TCP connect to each share's port: reachable + round-trip time */
    private suspend fun checkServers(list: List<ServerConfig>) = withContext(Dispatchers.IO) {
        _serverStatus.value = list.associate { it.id to ServerStatus.Checking }
        list.map { s ->
            async {
                val start = System.nanoTime()
                val status = runCatching {
                    Socket().use { it.connect(InetSocketAddress(s.host, s.port), 2500) }
                    ServerStatus.Online((System.nanoTime() - start) / 1_000_000)
                }.getOrElse { ServerStatus.Offline }
                _serverStatus.update { it + (s.id to status) }
            }
        }.awaitAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDuplicates: () -> Unit,
    onOpenPrivate: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenVideos: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val network by viewModel.network.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val statuses by viewModel.serverStatus.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(sync.isSyncing) { if (!sync.isSyncing) refreshing = false }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; viewModel.refreshAll() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Home", style = MaterialTheme.typography.headlineLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.height(38.dp).clip(CircleShape).background(SurfaceElevated2).border(1.dp, Border, CircleShape)
                            .pressable(onOpenPrivate, pressedScale = 0.94f).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Lock, null, tint = AccentBright, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Private", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                    }
                    IconButton(onClick = { refreshing = true; viewModel.refreshAll() }) { Icon(Icons.Rounded.Refresh, "Refresh everything", tint = if (sync.isSyncing) Accent else TextPrimary) }
                }
            }
            item {
                Card("Library", Icons.Rounded.VideoLibrary, onClick = onOpenVideos) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${stats.count}", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("videos  ·  ${stats.bytes.formatFileSize()}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Folders", "${stats.folders}", Modifier.weight(1f))
                        Stat("Portrait", "${stats.portrait}", Modifier.weight(1f))
                        Stat("Watched", "${stats.watched}", Modifier.weight(1f))
                        Stat("Hours", "${stats.totalDuration / 3_600_000}", Modifier.weight(1f))
                    }
                }
            }
            items(storage.size) { i ->
                val s = storage[i]
                Card(s.name, Icons.Rounded.SdStorage) {
                    val used by animateFloatAsState(if (s.total > 0) s.used.toFloat() / s.total else 0f, tween(600), label = "used")
                    val vid by animateFloatAsState(if (s.total > 0) s.videoBytes.toFloat() / s.total else 0f, tween(600), label = "vid")
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(SurfaceElevated3)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(used).background(BorderStrong))
                        Box(Modifier.fillMaxHeight().fillMaxWidth(vid).background(Accent))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Legend(Accent, "Videos ${s.videoBytes.formatFileSize()}", Modifier.weight(1f))
                        Legend(BorderStrong, "Other ${(s.used - s.videoBytes).coerceAtLeast(0).formatFileSize()}", Modifier.weight(1f))
                        Legend(SurfaceElevated4, "Free ${s.free.formatFileSize()}", Modifier.weight(1f))
                    }
                    Text("${s.used.formatFileSize()} of ${s.total.formatFileSize()} used", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                Card("Network", Icons.Rounded.Wifi, onClick = onOpenNetwork) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (network.connected) PlayedGreen else ErrorRed)
                        Spacer(Modifier.width(8.dp))
                        Text(network.kind, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        network.downMbps?.takeIf { it > 0 }?.let { Text("  ·  ~$it Mbps link", style = MaterialTheme.typography.bodySmall, color = TextTertiary) }
                    }
                    if (servers.isEmpty()) {
                        Text("No shares saved — add SMB/FTP servers in the Network tab", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(top = 8.dp))
                    }
                    servers.forEach { s ->
                        val st = statuses[s.id] ?: ServerStatus.Checking
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (s.type == ServerType.SMB) Icons.Rounded.Dns else Icons.Rounded.Storage, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.type.name}  ·  ${s.host}:${s.port}", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                            }
                            when (st) {
                                ServerStatus.Checking -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextTertiary)
                                is ServerStatus.Online -> { Dot(PlayedGreen); Spacer(Modifier.width(6.dp)); Text("${st.ms} ms", color = PlayedGreen, style = MaterialTheme.typography.labelMedium) }
                                ServerStatus.Offline -> { Dot(ErrorRed); Spacer(Modifier.width(6.dp)); Text("Offline", color = ErrorRed, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
            }
            item {
                Card("Tools", Icons.Rounded.Build) {
                    ToolRow(Icons.Rounded.ContentCopy, "Find duplicate videos", "Byte-identical copies, with space you can free up", onOpenDuplicates)
                    ToolRow(Icons.Rounded.Sync, "Rescan everything", if (sync.isSyncing) "Scanning…" else "Re-read the whole video library") { viewModel.refreshAll() }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, icon: ImageVector, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(22.dp)).background(SurfaceElevated1).border(1.dp, Border, RoundedCornerShape(22.dp))
            .then(if (onClick != null) Modifier.pressable(onClick, pressedScale = 0.985f) else Modifier)
            .padding(18.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary, modifier = Modifier.weight(1f))
            if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(SurfaceElevated2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

@Composable
private fun Legend(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
    }
}

@Composable
private fun Dot(color: Color) { Box(Modifier.size(9.dp).clip(CircleShape).background(color)) }

@Composable
private fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
    }
}
