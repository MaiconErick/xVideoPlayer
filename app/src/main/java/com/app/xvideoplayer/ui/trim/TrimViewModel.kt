package com.app.xvideoplayer.ui.trim

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.DecoderMode
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.player.MpvPlayer
import com.app.xvideoplayer.player.trim.CutResult
import com.app.xvideoplayer.player.trim.PacketIndex
import com.app.xvideoplayer.player.trim.VideoCutter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

sealed interface SaveState {
    data object Idle : SaveState
    data class Running(val progress: Float) : SaveState
    data class Done(val result: CutResult) : SaveState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrimViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val repo: VideoRepository,
    private val cutter: VideoCutter,
    private val prefs: AppPreferences,
    private val vault: com.app.xvideoplayer.data.vault.PrivateVault
) : AndroidViewModel(application) {

    private val videoId: Long = savedStateHandle["videoId"] ?: 0L

    val player = MpvPlayer(application, DecoderMode.AUTO)
    val playerState = player.state
    val position = player.position

    private val _video = MutableStateFlow<VideoItem?>(null); val video = _video.asStateFlow()
    private val _index = MutableStateFlow<PacketIndex?>(null); val index = _index.asStateFlow()
    private val _indexing = MutableStateFlow(true); val indexing = _indexing.asStateFlow()

    private val _start = MutableStateFlow(0L); val start = _start.asStateFlow()
    private val _end = MutableStateFlow(0L); val end = _end.asStateFlow()
    private val _showKeyframes = MutableStateFlow(true); val showKeyframes = _showKeyframes.asStateFlow()
    private val _save = MutableStateFlow<SaveState>(SaveState.Idle); val save = _save.asStateFlow()

    /** Preview behaviour at the end mark: loop back to start, or stop and rewind to start */
    val loop: StateFlow<Boolean> = prefs.trimLoop.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val stopAtEnd: StateFlow<Boolean> = prefs.trimStopAtEnd.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private var lastWrapAt = 0L

    /** Set while the result of a cut is being reviewed (the preview plays the new file) */
    private val _result = MutableStateFlow<CutResult.Saved?>(null); val result = _result.asStateFlow()
    private var resultLengthMs = 0L

    val duration: StateFlow<Long> = playerState.map { it.duration }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Estimated size of the cut: exact packet sum once indexed, proportional guess before. */
    val estimate: StateFlow<Long> = combine(_index, _start, _end, _video, duration) { idx, s, e, v, d ->
        when {
            idx != null && idx.totalBytes > 0 -> {
                val packets = idx.bytesBetween(s, e)
                // packet payloads + a little container overhead, scaled to the real file size
                (packets.toDouble() / idx.totalBytes * (v?.size ?: idx.totalBytes)).toLong()
            }
            v != null && d > 0 -> (v.size.toDouble() * (e - s) / d).toLong()
            else -> 0L
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // ── filmstrip thumbnails ────────────────────────────────────────────
    private val thumbCache = LruCache<Long, ImageBitmap>(260)
    private val _thumbVersion = MutableStateFlow(0); val thumbVersion = _thumbVersion.asStateFlow()
    private val wantedThumbs = MutableStateFlow<List<Long>>(emptyList())
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var retriever: MediaMetadataRetriever? = null

    init {
        viewModelScope.launch {
            val v = repo.getVideoById(videoId) ?: return@launch
            _video.value = v
            player.load(v.path, 0L, paused = true)
            launch(Dispatchers.IO) {
                _index.value = cutter.index(v.path)
                _indexing.value = false
            }
        }
        // the end handle starts at the end of the video
        viewModelScope.launch { duration.first { it > 0 }.let { d -> if (_end.value == 0L) _end.value = d } }
        // reaching the end mark while previewing: loop back to start, or stop and rewind
        viewModelScope.launch {
            position.collect { p ->
                if (_result.value != null || !playerState.value.isPlaying || _end.value <= 0 || p < _end.value) return@collect
                // the position can still read past the end for a tick after the seek back
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastWrapAt < 400) return@collect
                when {
                    loop.value -> { lastWrapAt = now; player.seekTo(_start.value) }
                    stopAtEnd.value -> { lastWrapAt = now; player.pause(); player.seekTo(_start.value) }
                }
            }
        }
        // decode requested thumbnails one at a time, newest request first
        viewModelScope.launch(thumbDispatcher) {
            wantedThumbs.collectLatest { keys ->
                val path = _video.filterNotNull().first().path
                val r = retriever ?: MediaMetadataRetriever().also { runCatching { it.setDataSource(path) }; retriever = it }
                for (k in keys) {
                    ensureActive()
                    if (thumbCache.get(k) != null) continue
                    val bmp: Bitmap? = runCatching { r.getScaledFrameAtTime(k * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256) }.getOrNull()
                    if (bmp != null) { thumbCache.put(k, bmp.asImageBitmap()); _thumbVersion.update { it + 1 } }
                }
            }
        }
    }

    fun thumb(key: Long): ImageBitmap? = thumbCache.get(key)

    /** Visible tile times, in the order they should load (center outwards feels fastest). */
    fun requestThumbs(keys: List<Long>) {
        val missing = keys.filter { thumbCache.get(it) == null }
        if (missing.isEmpty()) return
        val mid = missing.size / 2
        wantedThumbs.value = missing.sortedBy { kotlin.math.abs(missing.indexOf(it) - mid) }
    }

    // ── transport ───────────────────────────────────────────────────────
    fun seek(ms: Long) = player.seekTo(ms.coerceIn(0, duration.value), exact = true)
    fun scrub(ms: Long) = player.scrubTo(ms.coerceIn(0, duration.value))
    fun togglePlay() {
        val bounded = loop.value || stopAtEnd.value
        if (bounded && !playerState.value.isPlaying && (position.value >= _end.value - 50 || position.value < _start.value)) player.seekTo(_start.value)
        player.togglePlayPause()
    }
    fun nudge(deltaMs: Long) { player.pause(); seek(position.value + deltaMs) }
    fun stepKeyframe(forward: Boolean) {
        val idx = _index.value ?: return
        val p = position.value
        (if (forward) idx.nextKeyframe(p + 5) else idx.previousKeyframe(p - 5))?.let { player.pause(); seek(it) }
    }

    // ── selection ───────────────────────────────────────────────────────
    /** Copy cuts can only begin on a keyframe: snap so the preview matches the result exactly. */
    fun setStart(ms: Long) {
        val snapped = _index.value?.keyframeAtOrBefore(ms) ?: ms
        _start.value = snapped.coerceIn(0, (_end.value - 100).coerceAtLeast(0))
        player.pause(); seek(_start.value)
    }

    fun setEnd(ms: Long) {
        _end.value = ms.coerceIn(_start.value + 100, duration.value)
        player.pause(); seek(_end.value)
    }

    fun setStartAtPlayhead() = setStart(position.value)
    fun setEndAtPlayhead() = setEnd(position.value)
    fun resetSelection() { _start.value = 0; _end.value = duration.value }
    fun toggleKeyframes() { _showKeyframes.update { !it } }
    fun setLoop(v: Boolean) { viewModelScope.launch { prefs.setTrimLoop(v) } }
    fun setStopAtEnd(v: Boolean) { viewModelScope.launch { prefs.setTrimStopAtEnd(v) } }

    /** Tap on a start/end marker: move the playhead there without changing the selection */
    fun jumpTo(ms: Long) { player.pause(); seek(ms) }

    fun save() {
        val v = _video.value ?: return
        if (_save.value is SaveState.Running) return
        player.pause()
        viewModelScope.launch {
            _save.value = SaveState.Running(0f)
            val result = cutter.cut(v.path, v.title, _start.value, _end.value) { p -> _save.value = SaveState.Running(p) }
            if (result is CutResult.Saved) {
                resultLengthMs = _end.value - _start.value
                _save.value = SaveState.Idle
                openResult(result)
            } else _save.value = SaveState.Done(result)
        }
    }

    private fun openResult(r: CutResult.Saved) {
        _result.value = r
        val app = getApplication<Application>()
        // a real path lets mpv seek freely; fall back to a file descriptor
        val path = runCatching {
            app.contentResolver.query(r.uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val url = path?.takeIf { java.io.File(it).canRead() }
            ?: app.contentResolver.openFileDescriptor(r.uri, "r")?.detachFd()?.let { "fdclose://$it" }
            ?: return
        player.load(url, 0L, paused = false)
    }

    /** "Go back": throw the cut away and return to the editor on the original */
    fun discardResult() {
        val r = _result.value ?: return
        _result.value = null
        viewModelScope.launch(Dispatchers.IO) { runCatching { getApplication<Application>().contentResolver.delete(r.uri, null, null) } }
        val v = _video.value ?: return
        player.load(v.path, _start.value, paused = true)
    }

    /** "Done" → keep both files; [kept] = the user chose Keep both (the tagger picks the cut up) */
    fun keepBoth(kept: Boolean = false) {
        player.pause()
        val r = _result.value
        if (kept && r != null) {
            val path = runCatching {
                getApplication<Application>().contentResolver.query(r.uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (path != null) vault.keptCuts += path to r.uri.toString()
        }
        repo.requestSync(debounceMs = 500)
    }

    fun resultLength() = resultLengthMs

    fun cancelSave() = cutter.cancel()
    fun dismissResult() { _save.value = SaveState.Idle }

    override fun onCleared() {
        if (_save.value is SaveState.Running) cutter.cancel()
        player.release()
        repo.requestSync(debounceMs = 1_000)
        runCatching { retriever?.release() }
        super.onCleared()
    }
}
