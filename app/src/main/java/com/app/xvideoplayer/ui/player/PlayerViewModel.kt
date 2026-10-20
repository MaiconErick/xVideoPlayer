package com.app.xvideoplayer.ui.player

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.data.repository.ServerRepository
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.player.CacheState
import com.app.xvideoplayer.player.MpvPlayer
import com.app.xvideoplayer.player.PlayerState
import com.app.xvideoplayer.player.ScaleMode
import com.app.xvideoplayer.util.formatDuration
import com.app.xvideoplayer.player.ScreenshotManager
import com.app.xvideoplayer.player.SmbHttpBridge
import com.app.xvideoplayer.player.ScreenshotSession
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject

enum class RotationOverride { AUTO, LANDSCAPE, PORTRAIT }

@HiltViewModel
class PlayerViewModel @Inject constructor(
    application: Application,
    private val videoRepository: VideoRepository,
    private val serverRepository: ServerRepository,
    private val appPreferences: AppPreferences,
    private val screenshotManager: ScreenshotManager,
    private val smbBridge: SmbHttpBridge,
    private val offline: com.app.xvideoplayer.remote.SmbDownloads,
    val collage: ScreenshotSession,
    private val vault: com.app.xvideoplayer.data.vault.PrivateVault
) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()
    private val gson = Gson()
    /** Outlives viewModelScope so the final resume point is still written after the screen closes */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val player = MpvPlayer(application, DecoderMode.AUTO)
    val playerState: StateFlow<PlayerState> = player.state
    val cache: StateFlow<CacheState> = player.cache

    val playerSettings = appPreferences.playerSettings.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerSettings())
    val subtitleStyle = appPreferences.subtitleStyle.stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleStyle())

    private val _currentVideo = MutableStateFlow<VideoItem?>(null)
    val currentVideo: StateFlow<VideoItem?> = _currentVideo.asStateFlow()

    /**
     * Star: private videos go to the private Favorites, everything else to the library's favorites.
     * Private favorites are keyed by path, so they survive the file being moved by tagging.
     */
    private val _starred = MutableStateFlow(false); val starred = _starred.asStateFlow()

    fun toggleStar() {
        val v = _currentVideo.value ?: return
        if (v.source != VideoSource.LOCAL) return
        val now = !_starred.value
        _starred.value = now
        viewModelScope.launch {
            if (vault.isPrivate(v.path)) vault.setFavorite(v.path, now)
            else { videoRepository.setFavorite(v.id, now); _currentVideo.value = v.copy(isFavorite = now) }
            _messages.tryEmit(if (now) "Added to Favorites" else "Removed from Favorites")
        }
    }

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private var playlist: List<VideoItem> = emptyList()
    private val _hasPrev = MutableStateFlow(false); val hasPrev = _hasPrev.asStateFlow()
    private val _hasNext = MutableStateFlow(false); val hasNext = _hasNext.asStateFlow()

    // ── Scrubbing: the bar/gesture shows where you're dragging, not where mpv currently is ──
    private val _scrubPosition = MutableStateFlow<Long?>(null)
    val scrubPosition: StateFlow<Long?> = _scrubPosition.asStateFlow()
    val displayPosition: StateFlow<Long> = combine(player.position, _scrubPosition) { pos, scrub -> scrub ?: pos }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    private var lastFastSeek = 0L

    // hidden until the file is open, so opening a video shows the loading spinner first
    private val _showControls = MutableStateFlow(false); val showControls = _showControls.asStateFlow()

    /** Pinch zoom (1 = normal) and pan as fractions of the video size */
    private val _zoom = MutableStateFlow(1f); val zoom = _zoom.asStateFlow()
    private var panX = 0f; private var panY = 0f
    private val _aspect = MutableStateFlow("auto"); val aspect = _aspect.asStateFlow()
    private val _locked = MutableStateFlow(false); val locked = _locked.asStateFlow()
    private val _scaleMode = MutableStateFlow(ScaleMode.FIT); val scaleMode = _scaleMode.asStateFlow()
    private val _rotation = MutableStateFlow(RotationOverride.AUTO); val rotation = _rotation.asStateFlow()

    private val _abState = MutableStateFlow(ABRepeatState()); val abState = _abState.asStateFlow()
    private val _overlayGroups = MutableStateFlow<List<OverlayGroup>>(emptyList()); val overlayGroups = _overlayGroups.asStateFlow()
    private val _screenshotIncludeSub = MutableStateFlow(false); val screenshotIncludeSub = _screenshotIncludeSub.asStateFlow()

    private val _brightness = MutableStateFlow(-1f); val brightness = _brightness.asStateFlow()
    /** 0..1 = system volume, 1..2 = mpv software boost on top of max system volume */
    private val _volumeLevel = MutableStateFlow(1f); val volumeLevel = _volumeLevel.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4); val messages = _messages.asSharedFlow()

    private val _mediaInfo = MutableStateFlow(""); val mediaInfo = _mediaInfo.asStateFlow()
    private var scrubVideoId = 0L

    // ── Tap zones ────────────────────────────────────────────────────────
    val tapZones: StateFlow<List<TapZone>> = playerSettings.map { TapZone.parse(it.tapZonesJson, (it.doubleTapSeekIncrement / 1000).toInt()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TapZone.defaults())
    fun saveTapZones(list: List<TapZone>) { viewModelScope.launch { appPreferences.setTapZones(TapZone.encode(list)) } }

    // ── Picture filters (brightness, contrast, …) ────────────────────────
    private val _filters = MutableStateFlow(VideoFilters()); val filters = _filters.asStateFlow()
    private val _compareSplit = MutableStateFlow(-1f); val compareSplit = _compareSplit.asStateFlow()
    private var filtersSaved = VideoFilters()
    val filtersDirty: Boolean get() = _filters.value != filtersSaved

    fun setFilters(f: VideoFilters) { _filters.value = f; player.applyFilters(f, _compareSplit.value) }
    fun setCompareSplit(x: Float) { _compareSplit.value = x; player.applyFilters(_filters.value, x) }
    /** Videos of the folder being played, for "apply to some of them" */
    fun folderVideos(): List<VideoItem> = playlist

    /** video → its folder → everywhere → nothing */
    private suspend fun resolveFilters(video: VideoItem): VideoFilters =
        VideoFilters.parse(appPreferences.filterJson("v:${video.id}"))
            ?: VideoFilters.parse(appPreferences.filterJson("f:${video.folderPath}"))
            ?: VideoFilters.parse(appPreferences.filterJson("global"))
            ?: VideoFilters()

    fun saveFilters(scope: FilterScope, videos: List<VideoItem> = emptyList()) {
        val f = _filters.value
        val v = _currentVideo.value
        viewModelScope.launch {
            when (scope) {
                FilterScope.VIDEO -> if (v != null && v.id > 0) appPreferences.setFilterJson("v:${v.id}", f.toJson())
                FilterScope.FOLDER -> if (v != null) appPreferences.setFilterJson("f:${v.folderPath}", f.toJson())
                FilterScope.GLOBAL -> appPreferences.setFilterJson("global", f.toJson())
                FilterScope.SELECTED -> videos.forEach { appPreferences.setFilterJson("v:${it.id}", f.toJson()) }
            }
            filtersSaved = f
            _messages.tryEmit("Picture settings saved")
        }
    }
    fun discardFilters() { setFilters(filtersSaved) }

    // ── Small actions shared by tap zones, gamepad and the second screen ─
    private var volumeBeforeMute = 1f
    fun toggleMute() {
        val v = _volumeLevel.value
        if (v > 0.01f) { volumeBeforeMute = v; setVolumeLevel(0f); _messages.tryEmit("Muted") } else { setVolumeLevel(volumeBeforeMute.coerceAtLeast(0.2f)); _messages.tryEmit("Sound on") }
    }
    fun toggleSubtitles() {
        val st = playerState.value
        if (st.subtitleTracks.isEmpty()) { _messages.tryEmit("This video has no subtitles"); return }
        if (st.subtitleTracks.any { it.isSelected }) { disableSubtitles(); _messages.tryEmit("Subtitles off") }
        else { selectSubtitleTrack(st.subtitleTracks.first().id); _messages.tryEmit("Subtitles on") }
    }
    fun cycleSubtitleTrack() {
        val tracks = playerState.value.subtitleTracks
        if (tracks.isEmpty()) { _messages.tryEmit("This video has no subtitles"); return }
        val i = tracks.indexOfFirst { it.isSelected }
        if (i == tracks.lastIndex) { disableSubtitles(); _messages.tryEmit("Subtitles off") }
        else { val t = tracks[i + 1]; selectSubtitleTrack(t.id); _messages.tryEmit(t.label) }
    }
    fun nudgeSpeed(delta: Float) {
        val s = ((playerState.value.playbackSpeed + delta) * 20).toInt() / 20f
        setPlaybackSpeed(s.coerceIn(0.25f, 4f)); _messages.tryEmit("%.2f×".format(playerState.value.playbackSpeed.let { s.coerceIn(0.25f, 4f) }))
    }

    private var replayJob: Job? = null
    private var controlsHideJob: Job? = null
    private var saveJob: Job? = null
    private var started = false
    private var speedBeforeBoost: Float? = null
    private val audio = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        viewModelScope.launch {
            appPreferences.overlayGroupsJson.collect { json ->
                runCatching {
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val parsed: List<Map<String, Any>> = gson.fromJson(json, type)
                    _overlayGroups.value = parsed.map { m ->
                        OverlayGroup(
                            id = m["id"] as? String ?: "", name = m["name"] as? String ?: "", icon = m["icon"] as? String ?: "",
                            enabled = m["enabled"] as? Boolean ?: false, pinned = m["pinned"] as? Boolean ?: false,
                            offsetX = (m["offsetX"] as? Number)?.toFloat() ?: 0f, offsetY = (m["offsetY"] as? Number)?.toFloat() ?: 0f
                        )
                    }
                }
            }
        }
        viewModelScope.launch { playerSettings.collect { _screenshotIncludeSub.value = it.screenshotIncludeSubtitle } }
        viewModelScope.launch { subtitleStyle.collect { player.applySubtitleStyle(it) } }
        // auto-hide only while playing; paused controls stay up
        viewModelScope.launch { playerState.map { it.isPlaying }.distinctUntilChanged().collect { if (it) scheduleHide() else controlsHideJob?.cancel() } }
        player.onFileLoaded = ::onFileLoaded
        player.onEndReached = ::onEndReached
        refreshVolumeLevel()
    }

    // ── Start / playlist ────────────────────────────────────────────────

    fun start(videoId: Long, uri: String, source: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            val settings = appPreferences.playerSettings.first()
            if (settings.defaultDecoderMode != DecoderMode.AUTO) player.setDecoderMode(settings.defaultDecoderMode)
            player.setPlaybackSpeed(settings.defaultPlaybackSpeed)
            player.setCacheAhead(settings.cacheAhead)

            val video = when {
                videoId > 0 -> videoRepository.getVideoById(videoId)
                uri.isNotBlank() -> videoRepository.getVideoByUri(uri)
                else -> null
            }
            if (video != null) {
                // opened from the private section: previous/next follow that list (a tag can span folders)
                val privateList = vault.playlist.also { vault.playlist = emptyList() }
                if (video.source == VideoSource.LOCAL && video.id in privateList) {
                    val byId = videoRepository.getVideosBySource(VideoSource.LOCAL).first().associateBy { it.id }
                    playlist = privateList.mapNotNull { byId[it] }
                } else if (video.source == VideoSource.LOCAL) {
                    playlist = videoRepository.getVideosBySource(VideoSource.LOCAL).first()
                        .filter { it.folderPath == video.folderPath }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                }
                open(video)
            } else {
                _title.value = displayNameFor(uri)
                val url = resolveUrl(uri, source) ?: return@launch
                player.load(url, 0L)
            }
        }
    }

    private suspend fun open(video: VideoItem) {
        _scrubPosition.value = null          // a swipe still in progress must not carry over to the new video
        hideControls()                       // starting a video never pops the overlay
        replayJob?.cancel()
        _mediaInfo.value = ""
        _currentVideo.value = video
        _starred.value = if (vault.isPrivate(video.path)) vault.isFavorite(video.path) else video.isFavorite
        _title.value = video.title
        val idx = playlist.indexOfFirst { it.id == video.id }
        _hasPrev.value = idx > 0
        _hasNext.value = idx >= 0 && idx < playlist.lastIndex
        clearAbLoop()
        val settings = playerSettings.value
        val resumeAt = video.lastPosition.takeIf {
            settings.resumePlayback && it > 3_000 && (video.duration <= 0 || it < video.duration - 5_000)
        } ?: 0L
        val url = resolveUrl(video.uri, video.source.name, video.path) ?: return
        player.load(url, resumeAt)
        if (resumeAt > 0) _messages.tryEmit("Resumed from ${resumeAt.formatDuration()}")
        startAutoSave()
    }

    /** [removeCurrent]: the current file was just deleted — drop it from the playlist, don't save it */
    fun playNext(removeCurrent: Boolean = false) = skip(+1, removeCurrent)
    fun playPrevious() = skip(-1)

    private fun skip(delta: Int, removeCurrent: Boolean = false) {
        val cur = _currentVideo.value ?: return
        val idx = playlist.indexOfFirst { it.id == cur.id }
        val target = playlist.getOrNull(idx + delta) ?: return
        if (removeCurrent) playlist = playlist.filterNot { it.id == cur.id }
        viewModelScope.launch {
            if (!removeCurrent) saveNow(final = true)
            open(videoRepository.getVideoById(target.id) ?: target)
        }
    }

    /** After a rename: pick up the new title/path from the library */
    fun reloadCurrentInfo() {
        val cur = _currentVideo.value ?: return
        viewModelScope.launch {
            videoRepository.getVideoById(cur.id)?.let { v ->
                _currentVideo.value = v; _title.value = v.title
                playlist = playlist.map { if (it.id == v.id) v else it }
            }
        }
    }

    private fun onFileLoaded() {
        val s = playerState.value
        val v = _currentVideo.value
        if (v != null && v.id > 0) {
            viewModelScope.launch {
                videoRepository.incrementPlayCount(v.id)
                videoRepository.recordPlaybackInfo(v.id, s.videoWidth, s.videoHeight, s.duration)
                if (!v.hasDimensions && s.videoWidth > 0) _currentVideo.value = v.copy(width = s.videoWidth, height = s.videoHeight)
            }
        }
        player.applySubtitleStyle(subtitleStyle.value)
        applyVolumeLevel(_volumeLevel.value)
        // re-apply view settings to the new file and reveal the controls once there's a picture
        player.setScale(_scaleMode.value)
        if (_zoom.value != 1f) { player.setZoom(_zoom.value); player.setPan(panX, panY) }
        if (_aspect.value != "auto") player.setAspectOverride(_aspect.value)
        player.setHideSdh(playerSettings.value.hideSdh)
        restoreTracks()
        _currentVideo.value?.let { cv -> viewModelScope.launch { val f = resolveFilters(cv); filtersSaved = f; _filters.value = f; _compareSplit.value = -1f; player.applyFilters(f) } }
        viewModelScope.launch {
            _mediaInfo.value = player.mediaInfoLine()
            delay(2500); _mediaInfo.value = player.mediaInfoLine()   // bitrates settle after a few packets
        }
    }

    private fun onEndReached() {
        if (_scrubPosition.value != null) return   // the user is dragging the seek bar to the end: don't jump to the next video
        viewModelScope.launch { saveNow(final = true) }
        val repeat = playerState.value.repeatMode
        when {
            repeat == RepeatMode.ONE -> Unit // mpv loops the file itself
            _hasNext.value && (repeat == RepeatMode.ALL || playerSettings.value.autoPlayNext) -> playNext()
            repeat == RepeatMode.ALL && playlist.size > 1 -> viewModelScope.launch { open(playlist.first()) }
            else -> showControls()
        }
    }

    /** Turns whatever we were given into something libmpv can open. */
    private suspend fun resolveUrl(uri: String, source: String, path: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            when {
                // a real path lets mpv find "movie.srt" next to "movie.mkv" and seek without the fd layer
                path != null && path.startsWith("/") && File(path).canRead() -> path
                // a finished offline copy plays from the device (works with the server off, seeks instantly)
                uri.startsWith("smb://") && offline.completeFileFor(uri) != null -> offline.completeFileFor(uri)!!.absolutePath
                uri.startsWith("smb://") -> {
                    val u = Uri.parse(uri)
                    val server = serverRepository.findServer(ServerType.SMB, u.host ?: "", if (u.port > 0) u.port else 445)
                        ?: return@withContext null.also { _messages.tryEmit("No saved SMB server for ${u.host}") }
                    smbBridge.urlFor(server, uri)
                }
                uri.startsWith("content://") -> {
                    val fd = app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.detachFd() ?: return@withContext null
                    "fdclose://$fd"
                }
                uri.startsWith("file://") -> Uri.parse(uri).path
                else -> uri
            }
        } catch (e: Exception) {
            _messages.tryEmit("Can't open: ${e.message}")
            null
        }
    }

    private fun displayNameFor(uri: String): String {
        if (uri.startsWith("content://")) {
            runCatching {
                app.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
                }
            }
        }
        return Uri.decode(uri.substringAfterLast('/')).substringBeforeLast('.').ifBlank { "Video" }
    }

    // ── Position saving ─────────────────────────────────────────────────

    private fun startAutoSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { while (isActive) { delay(5_000); if (playerState.value.isPlaying) saveNow(false) } }
    }

    private suspend fun saveNow(final: Boolean) {
        val v = _currentVideo.value ?: return
        if (v.id <= 0 || !playerState.value.isFileLoaded) return
        val pos = if (playerState.value.isEnded) playerState.value.duration else player.position.value
        withContext(Dispatchers.IO) { videoRepository.updatePlaybackPosition(v.id, pos, final) }
    }

    fun onStopped() {
        player.pause()
        saveScope.launch { saveNow(final = false) }
    }

    // ── Transport ───────────────────────────────────────────────────────

    fun togglePlayPause() { player.togglePlayPause(); poke() }

    fun seekRelative(offsetMs: Long) {
        player.seekRelative(offsetMs, exact = playerSettings.value.frameSeekMode == FrameSeekMode.EXACT)
    }

    fun beginScrub() { _scrubPosition.value = player.position.value; scrubVideoId = _currentVideo.value?.id ?: 0L; controlsHideJob?.cancel() }

    /**
     * Live preview while dragging. Default shows the real frame under the finger (exact seeks,
     * one at a time); "Fast (keyframe)" in settings jumps between keyframes instead.
     */
    fun scrubTo(ms: Long) {
        if (_scrubPosition.value == null || (_currentVideo.value?.id ?: 0L) != scrubVideoId) return   // stale drag from a previous video
        val dur = playerState.value.duration
        val target = if (dur > 0) ms.coerceIn(0, dur) else ms.coerceAtLeast(0)
        _scrubPosition.value = target
        if (playerSettings.value.frameSeekMode == FrameSeekMode.EXACT) { player.scrubTo(target); return }
        val now = System.currentTimeMillis()
        if (now - lastFastSeek > 90) { lastFastSeek = now; player.seekTo(target, exact = false) }
    }

    fun endScrub() {
        _scrubPosition.value?.let { player.seekTo(it, exact = true) }
        _scrubPosition.value = null
        scheduleHide()
    }

    fun seekTo(ms: Long) { player.seekTo(ms, exact = true); poke() }

    /** Jump back a few seconds and show subtitles just for that stretch (to catch a line you didn't understand). */
    fun replayWithSubs() {
        val st = playerState.value
        val secs = playerSettings.value.replaySeconds.coerceAtLeast(1)
        if (st.subtitleTracks.isEmpty()) { _messages.tryEmit("This video has no subtitles"); return }
        replayJob?.cancel()
        val wasOff = st.subtitleTracks.none { it.isSelected }
        if (wasOff) player.selectSubtitleTrack(st.subtitleTracks.first().id)
        player.setSubtitlesVisible(true)
        player.seekRelative(-secs * 1000L, exact = true)
        if (!st.isPlaying) player.togglePlayPause()
        replayJob = viewModelScope.launch {
            delay(secs * 1000L + 600)
            if (wasOff) player.disableSubtitles()
        }
    }
    fun seekByFrames(count: Int) = player.seekFrames(count)
    fun setPlaybackSpeed(speed: Float) = player.setPlaybackSpeed(speed)
    fun cycleRepeatMode() {
        val next = when (playerState.value.repeatMode) { RepeatMode.OFF -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.OFF }
        player.setRepeatMode(next)
        _messages.tryEmit(when (next) { RepeatMode.OFF -> "Repeat off"; RepeatMode.ONE -> "Repeat this video"; RepeatMode.ALL -> "Repeat folder" })
    }
    fun switchDecoder(mode: DecoderMode) = player.setDecoderMode(mode)

    /** Applies to the playing video right away and becomes the default for the next one. */
    fun setCacheAhead(mode: CacheAhead) {
        player.setCacheAhead(mode)
        viewModelScope.launch { appPreferences.setCacheAhead(mode) }
    }
    fun selectAudioTrack(id: Int) { player.selectAudioTrack(id); rememberTracks() }
    fun selectSubtitleTrack(id: Int) { player.selectSubtitleTrack(id); rememberTracks() }
    fun disableSubtitles() { player.disableSubtitles(); rememberTracks(off = true) }

    // ── Subtitle style from inside the player (live) ────────────────────
    fun previewSubtitleStyle(s: SubtitleStyle) = player.applySubtitleStyle(s)
    fun saveSubtitleStyle(s: SubtitleStyle) { player.applySubtitleStyle(s); viewModelScope.launch { appPreferences.setSubtitleStyle(s) } }
    fun subtitleSeek(dir: Int) = player.subSeek(dir)
    fun setDualScreen(on: Boolean) { viewModelScope.launch { appPreferences.setDualScreen(on) } }
    fun setHideSdh(on: Boolean) { player.setHideSdh(on); viewModelScope.launch { appPreferences.setHideSdh(on) } }

    /** Quick language chips in the subtitles panel: picks the first matching track. */
    fun pickSubtitleLanguage(code: String) {
        val t = subtitleTrackFor(code, playerState.value.subtitleTracks) ?: run { _messages.tryEmit("No ${code.uppercase()} subtitles in this video"); return }
        selectSubtitleTrack(t.id)
    }

    // ── Remember the audio/subtitle choice per video ────────────────────
    private fun memoryKey(): String? = _currentVideo.value?.let { if (it.id > 0) "v${it.id}" else it.path.hashCode().toString() }

    private fun rememberTracks(off: Boolean = false, external: String? = null) {
        val key = memoryKey() ?: return
        viewModelScope.launch {
            delay(150) // let mpv report the new selection
            val st = playerState.value
            val o = org.json.JSONObject()
            val sub = st.subtitleTracks.firstOrNull { it.isSelected }
            if (external != null) o.put("sub", org.json.JSONObject().put("ext", external))
            else if (off || sub == null) o.put("sub", "off")
            else o.put("sub", org.json.JSONObject().put("lang", sub.language ?: "").put("label", sub.label).put("ext", false))
            st.audioTracks.firstOrNull { it.isSelected }?.let { o.put("audio", org.json.JSONObject().put("lang", it.language ?: "").put("label", it.label)) }
            appPreferences.setTrackMemory(key, o.toString())
        }
    }

    private fun restoreTracks() {
        val key = memoryKey() ?: return
        viewModelScope.launch {
            val json = appPreferences.trackMemory(key) ?: return@launch
            val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@launch
            val st = playerState.value
            o.optJSONObject("audio")?.let { a ->
                st.audioTracks.firstOrNull { it.language == a.optString("lang") && it.label == a.optString("label") }
                    ?: st.audioTracks.firstOrNull { it.language == a.optString("lang") && a.optString("lang").isNotBlank() }
            }?.let { if (!it.isSelected) player.selectAudioTrack(it.id) }
            val sub = o.opt("sub")
            when {
                sub == "off" -> player.disableSubtitles()
                sub is org.json.JSONObject && sub.has("ext") && sub.opt("ext") is String -> {
                    val f = File(sub.getString("ext")); if (f.exists()) player.addSubtitleFile(f.absolutePath)
                }
                sub is org.json.JSONObject -> {
                    val t = st.subtitleTracks.firstOrNull { it.language == sub.optString("lang") && it.label == sub.optString("label") }
                        ?: st.subtitleTracks.firstOrNull { sub.optString("lang").isNotBlank() && it.language == sub.optString("lang") }
                    if (t != null && !t.isSelected) player.selectSubtitleTrack(t.id)
                }
            }
        }
    }

    // ── Word lookup (tap a word of the current subtitle line) ───────────
    fun lookupLine(): String? = player.currentSubText.value.ifBlank { null } ?: player.subLineAt(player.position.value)

    suspend fun lookupWord(sentence: String, index: Int, word: String): Result<org.json.JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val c = java.net.URL(playerSettings.value.aiServer.trimEnd('/') + "/api/ai/lookup").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 60000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(org.json.JSONObject().put("sentence", sentence).put("index", index).put("word", word).toString().toByteArray()) }
            try { org.json.JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        }
    }

    fun addSubtitleFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "external.srt"
                val dst = File(File(app.cacheDir, "subs").apply { mkdirs() }, name)
                app.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
                player.addSubtitleFile(dst.absolutePath)
                rememberTracks(external = dst.absolutePath)
                _messages.tryEmit("Loaded $name")
            }.onFailure { _messages.tryEmit("Couldn't load subtitle") }
        }
    }

    /** Hold-to-fast-forward */
    fun startSpeedBoost() { if (speedBeforeBoost == null) { speedBeforeBoost = playerState.value.playbackSpeed; player.setPlaybackSpeed(2f) } }
    fun endSpeedBoost() { speedBeforeBoost?.let { player.setPlaybackSpeed(it) }; speedBeforeBoost = null }

    fun cycleScaleMode() {
        val next = ScaleMode.entries[(_scaleMode.value.ordinal + 1) % ScaleMode.entries.size]
        _scaleMode.value = next
        player.setScale(next)
        _messages.tryEmit(next.label)
    }

    fun setScaleMode(mode: ScaleMode) { _scaleMode.value = mode; player.setScale(mode) }

    /** "auto" or a ratio like "16:9" */
    fun setAspect(ratio: String) { _aspect.value = ratio; player.setAspectOverride(ratio) }

    /** Two-finger pinch: [factor] multiplies the zoom, [dx]/[dy] pan by a fraction of the screen. */
    fun pinch(factor: Float, dx: Float, dy: Float) {
        val z = (_zoom.value * factor).coerceIn(0.5f, 6f)
        _zoom.value = z
        panX = (panX + dx / z).coerceIn(-1f, 1f)
        panY = (panY + dy / z).coerceIn(-1f, 1f)
        player.setZoom(z); player.setPan(panX, panY)
    }

    fun resetZoom() { _zoom.value = 1f; panX = 0f; panY = 0f; player.setZoom(1f); player.setPan(0f, 0f) }

    fun cycleRotation() {
        _rotation.value = RotationOverride.entries[(_rotation.value.ordinal + 1) % RotationOverride.entries.size]
        _messages.tryEmit(when (_rotation.value) { RotationOverride.AUTO -> "Rotate to fit video"; RotationOverride.LANDSCAPE -> "Landscape"; RotationOverride.PORTRAIT -> "Portrait" })
    }

    // ── Brightness / volume ─────────────────────────────────────────────

    fun currentBrightness(): Float = _brightness.value.takeIf { it >= 0 }
        ?: runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)

    fun setBrightness(value: Float) { _brightness.value = value.coerceIn(0.01f, 1f) }

    fun refreshVolumeLevel() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        _volumeLevel.value = if (sys >= 0.999f) maxOf(1f, playerState.value.volume) else sys
    }

    fun setVolumeLevel(level: Float) {
        val l = level.coerceIn(0f, 2f)
        _volumeLevel.value = l
        applyVolumeLevel(l)
    }

    private fun applyVolumeLevel(l: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (minOf(l, 1f) * max).toInt()
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != target) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        player.setVolume(if (l > 1f) l else 1f)
    }

    // ── Controls visibility ─────────────────────────────────────────────

    fun toggleControls() { if (_showControls.value) hideControls() else showControls() }
    fun showControls() { _showControls.value = true; scheduleHide() }
    fun hideControls() { controlsHideJob?.cancel(); _showControls.value = false }
    /** Any interaction restarts the auto-hide countdown */
    fun poke() { if (_showControls.value) scheduleHide() }

    private fun scheduleHide() {
        controlsHideJob?.cancel()
        val sec = playerSettings.value.overlayAutoHideSeconds
        if (sec > 0 && playerState.value.isPlaying) controlsHideJob = viewModelScope.launch { delay(sec * 1000L); _showControls.value = false }
    }

    fun toggleLock() {
        _locked.value = !_locked.value
        if (_locked.value) hideControls() else showControls()
    }

    // ── Overlay groups ──────────────────────────────────────────────────

    fun toggleOverlayGroup(id: String) { _overlayGroups.update { l -> l.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }; saveOverlayGroups() }
    fun togglePinOverlay(id: String) { _overlayGroups.update { l -> l.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } }; saveOverlayGroups() }
    fun dragOverlay(id: String, dx: Float, dy: Float) { _overlayGroups.update { l -> l.map { if (it.id == id) it.copy(offsetX = it.offsetX + dx, offsetY = it.offsetY + dy) else it } } }
    fun commitOverlayDrag() = saveOverlayGroups()

    private fun saveOverlayGroups() {
        viewModelScope.launch {
            appPreferences.setOverlayGroupsJson(gson.toJson(_overlayGroups.value.map {
                mapOf("id" to it.id, "name" to it.name, "icon" to it.icon, "enabled" to it.enabled, "pinned" to it.pinned, "offsetX" to it.offsetX, "offsetY" to it.offsetY)
            }))
        }
    }

    // ── Screenshot ──────────────────────────────────────────────────────

    /** Set by the surface composable; weak so the ViewModel never holds a dead view. */
    var surfaceView: java.lang.ref.WeakReference<android.view.SurfaceView>? = null

    /**
     * Without subtitles: saved straight to the gallery. With subtitles: the clean frame and the
     * subtitle lines go into the collage session, where the text is drawn (and can be changed).
     */
    fun takeScreenshot(includeSub: Boolean) {
        if (includeSub) { addToCollage(); return }
        viewModelScope.launch {
            val view = surfaceView?.get() ?: return@launch
            val wasPlaying = playerState.value.isPlaying
            player.pause()
            val pos = player.position.value
            _messages.tryEmit("Saving frame…")

            // mpv re-renders the exact paused frame at the video's own resolution. (Android's
            // MediaMetadataRetriever was tried first, but on MKVs it returned frames seconds off.)
            val bmp = captureFullSize(view, hideSubs = !includeSub)

            if (wasPlaying) player.play()
            val size = bmp?.let { "${it.width}×${it.height}" }
            val saved = bmp?.let { screenshotManager.saveToGallery(it, playerSettings.value.screenshotFormat, _title.value, pos) }
            _messages.tryEmit(if (saved != null) "Saved $size frame to Pictures/xVideoPlayer" else "Screenshot failed")
        }
    }

    fun addToCollage() {
        viewModelScope.launch {
            val view = surfaceView?.get() ?: return@launch
            player.pause()
            val pos = player.position.value
            val text = player.currentSubText.value
            val lines = player.subtitleLines.value
            val bmp = captureFullSize(view, hideSubs = true)
            if (bmp == null) { _messages.tryEmit("Screenshot failed"); return@launch }
            collage.add(bmp, pos, lines, text, _title.value)
            _messages.tryEmit("Added to collage (${collage.shots.value.size})")
        }
    }

    /**
     * Temporarily makes the surface buffer exactly the video's size so mpv renders the paused frame
     * (and subtitles, if shown) 1:1, copies it, then gives the surface back to the layout.
     */
    private suspend fun captureFullSize(view: android.view.SurfaceView, hideSubs: Boolean): android.graphics.Bitmap? {
        val s = playerState.value
        val subsShown = s.subtitleTracks.any { it.isSelected }
        if (hideSubs && subsShown) player.setSubtitlesVisible(false)
        try {
            if (s.videoWidth <= 0 || s.videoHeight <= 0) {
                if (hideSubs && subsShown) delay(160)
                return screenshotManager.capture(view, videoRectIn(view.width, view.height))
            }
            val w = s.videoWidth; val h = s.videoHeight
            view.holder.setFixedSize(w, h)
            if (_scaleMode.value != ScaleMode.FIT) player.setScale(ScaleMode.FIT)
            delay(250) // surfaceChanged → mpv picks up the new buffer size
            // right after pausing, the resize redraw can show a stale queued frame; an exact seek to
            // the paused timestamp makes mpv decode and draw precisely the frame that was on screen
            player.seekTo(player.position.value, exact = true)
            delay(450)
            return screenshotManager.capture(view, android.graphics.Rect(0, 0, w, h), w, h)
        } finally {
            view.holder.setSizeFromLayout()
            if (_scaleMode.value != ScaleMode.FIT) player.setScale(_scaleMode.value)
            if (hideSubs && subsShown) player.setSubtitlesVisible(true)
        }
    }

    /** Where the picture sits inside the surface (letterboxing removed) for the current scale mode. */
    private fun videoRectIn(w: Int, h: Int): android.graphics.Rect {
        val s = playerState.value
        if (s.videoWidth <= 0 || s.videoHeight <= 0 || _scaleMode.value != ScaleMode.FIT) return android.graphics.Rect(0, 0, w, h)
        val scale = minOf(w.toFloat() / s.videoWidth, h.toFloat() / s.videoHeight)
        val vw = (s.videoWidth * scale).toInt(); val vh = (s.videoHeight * scale).toInt()
        val l = (w - vw) / 2; val t = (h - vh) / 2
        return android.graphics.Rect(l, t, l + vw, t + vh)
    }

    fun setScreenshotIncludeSub(v: Boolean) { _screenshotIncludeSub.value = v; viewModelScope.launch { appPreferences.setScreenshotIncludeSub(v) } }

    // ── A-B repeat ──────────────────────────────────────────────────────

    fun setPointA() {
        _abState.value = ABRepeatState(pointA = player.position.value, loopCount = _abState.value.loopCount)
        player.setAbLoop(null, null, 0)
    }

    fun setPointB() {
        val a = _abState.value.pointA ?: return
        val b = player.position.value
        if (b <= a + 100) { _messages.tryEmit("B must be after A"); return }
        _abState.update { it.copy(pointB = b, isActive = true) }
        player.setAbLoop(a, b, _abState.value.loopCount)
        player.seekTo(a)
    }

    fun toggleABRepeat() {
        val s = _abState.value
        if (s.pointA == null || s.pointB == null) return
        val active = !s.isActive
        _abState.value = s.copy(isActive = active)
        if (active) player.setAbLoop(s.pointA, s.pointB, s.loopCount) else player.setAbLoop(null, null, 0)
    }

    fun clearAbLoop() { _abState.value = ABRepeatState(loopCount = _abState.value.loopCount); player.setAbLoop(null, null, 0) }

    fun setABLoopCount(count: Int) {
        _abState.update { it.copy(loopCount = count) }
        val s = _abState.value
        if (s.isActive && s.pointA != null && s.pointB != null) player.setAbLoop(s.pointA, s.pointB, count)
    }

    override fun onCleared() {
        val v = _currentVideo.value
        val state = playerState.value
        val pos = if (state.isEnded) state.duration else player.position.value
        if (v != null && v.id > 0 && state.isFileLoaded) saveScope.launch { videoRepository.updatePlaybackPosition(v.id, pos, final = true) }
        controlsHideJob?.cancel(); saveJob?.cancel()
        player.release()
        super.onCleared()
    }
}

enum class FilterScope { VIDEO, FOLDER, GLOBAL, SELECTED }
