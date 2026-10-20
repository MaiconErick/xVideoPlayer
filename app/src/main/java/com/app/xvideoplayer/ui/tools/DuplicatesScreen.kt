package com.app.xvideoplayer.ui.tools

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.model.VideoSource
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.ui.components.*
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatFileSize
import com.app.xvideoplayer.util.formatRelativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject

data class DuplicateGroup(val key: String, val videos: List<VideoItem>) {
    val size: Long get() = videos.first().size
    val wasted: Long get() = size * (videos.size - 1)
}

sealed interface DupState {
    data object Idle : DupState
    data class Scanning(val done: Int, val total: Int) : DupState
    data class Done(val groups: List<DuplicateGroup>) : DupState
}

/**
 * Exact duplicates: same byte size, then a fingerprint of three 256 KB slices (start/middle/end)
 * plus the size. Only files that share a size are ever read, so even huge libraries finish fast.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(private val repo: VideoRepository) : ViewModel() {
    private val _state = MutableStateFlow<DupState>(DupState.Idle); val state = _state.asStateFlow()
    private val _selected = MutableStateFlow<Set<Long>>(emptySet()); val selected = _selected.asStateFlow()
    private var job: Job? = null

    init { scan() }

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val all = repo.getPublicLocalVideos().first().filter { it.size > 0 }
            val candidates = all.groupBy { it.size }.values.filter { it.size > 1 }
            val total = candidates.sumOf { it.size }
            var done = 0
            _state.value = DupState.Scanning(0, total)
            val groups = mutableListOf<DuplicateGroup>()
            for (bucket in candidates) {
                if (!isActive) return@launch
                val byPrint = bucket.groupBy { v -> fingerprint(v.path).also { done++; _state.value = DupState.Scanning(done, total) } }
                byPrint.forEach { (k, vs) -> if (k != null && vs.size > 1) groups += DuplicateGroup(k, vs.sortedBy { it.dateAdded }) }
            }
            groups.sortByDescending { it.wasted }
            _state.value = DupState.Done(groups)
            _selected.value = emptySet()
        }
    }

    private fun fingerprint(path: String): String? = try {
        RandomAccessFile(path, "r").use { f ->
            val md = MessageDigest.getInstance("MD5")
            val len = f.length()
            val buf = ByteArray(256 * 1024)
            for (pos in listOf(0L, (len / 2 - buf.size / 2).coerceAtLeast(0), (len - buf.size).coerceAtLeast(0))) {
                f.seek(pos)
                val n = f.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
            md.update(len.toString().toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }

    fun toggle(id: Long) = _selected.update { if (id in it) it - id else it + id }

    /** Select every copy except the oldest one in each group */
    fun selectExtras() {
        val groups = (_state.value as? DupState.Done)?.groups ?: return
        _selected.value = groups.flatMap { g -> g.videos.drop(1).map { it.id } }.toSet()
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun selectedVideos(): List<VideoItem> {
        val groups = (_state.value as? DupState.Done)?.groups ?: return emptyList()
        val ids = _selected.value
        return groups.flatMap { it.videos }.filter { it.id in ids }
    }

    /** After a delete, drop the removed files from the groups (and groups left with one file). */
    fun removeDeleted(ids: Set<Long>) {
        val groups = (_state.value as? DupState.Done)?.groups ?: return
        _state.value = DupState.Done(groups.map { g -> g.copy(videos = g.videos.filterNot { it.id in ids }) }.filter { it.videos.size > 1 })
        _selected.update { it - ids }
    }
}

@Composable
fun DuplicatesScreen(onBack: () -> Unit, onPlay: (VideoItem) -> Unit, viewModel: DuplicatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val fileActions = rememberFileActions()

    Column(Modifier.fillMaxSize().background(SurfacePure)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("Duplicate videos", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                val sub = when (val s = state) {
                    is DupState.Done -> if (s.groups.isEmpty()) "No duplicates found" else "${s.groups.size} sets  ·  ${s.groups.sumOf { it.wasted }.formatFileSize()} reclaimable"
                    is DupState.Scanning -> "Comparing ${s.done} / ${s.total} files…"
                    DupState.Idle -> ""
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
            IconButton(onClick = viewModel::scan) { Icon(Icons.Rounded.Refresh, "Scan again", tint = TextPrimary) }
        }

        when (val s = state) {
            DupState.Idle -> Unit
            is DupState.Scanning -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = SurfaceElevated3
                )
                Spacer(Modifier.height(12.dp))
                Text("Only files with the same size are compared", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
            is DupState.Done -> if (s.groups.isEmpty()) {
                EmptyState(Icons.Rounded.DoneAll, "No duplicates", "Every video in the library is unique.")
            } else Box(Modifier.weight(1f)) {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "actions") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppChip("Select extra copies", icon = Icons.Rounded.SelectAll, onClick = viewModel::selectExtras)
                            if (selected.isNotEmpty()) AppChip("Clear", icon = Icons.Rounded.Close, onClick = viewModel::clearSelection)
                        }
                    }
                    items(s.groups, key = { it.key }) { g ->
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceElevated1)
                                .border(1.dp, Border, RoundedCornerShape(18.dp)).animateContentSize()
                        ) {
                            Text(
                                "${g.videos.size} copies  ·  ${g.size.formatFileSize()} each", color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp)
                            )
                            g.videos.forEachIndexed { i, v ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { viewModel.toggle(v.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(v.id in selected, { viewModel.toggle(v.id) }, colors = CheckboxDefaults.colors(checkedColor = ErrorRed))
                                    Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).clickable { onPlay(v) }) {
                                        VideoThumbnail(v, Modifier.fillMaxSize())
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(v.title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(v.folderPath.removePrefix("/storage/emulated/0/"), color = TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            (if (i == 0) "Oldest  ·  " else "") + "added ${v.dateAdded.formatRelativeTime()}",
                                            color = if (i == 0) PlayedGreen else TextTertiary, style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    selected.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val vids = viewModel.selectedVideos()
                    Button(
                        onClick = { fileActions.delete(vids) { viewModel.removeDeleted(vids.map { it.id }.toSet()) } },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = SurfacePure), shape = CircleShape,
                        modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(8.dp))
                        Text("Delete ${vids.size}  ·  ${vids.sumOf { it.size }.formatFileSize()}")
                    }
                }
            }
        }
    }
}
