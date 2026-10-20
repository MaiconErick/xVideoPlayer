package com.app.xvideoplayer.player

import com.app.xvideoplayer.util.formatFileSize
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.app.xvideoplayer.data.model.CacheAhead
import com.app.xvideoplayer.data.model.DecoderMode
import com.app.xvideoplayer.data.model.RepeatMode
import com.app.xvideoplayer.data.model.SubtitleStyle
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.roundToLong

data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isEnded: Boolean = false,
    val isFileLoaded: Boolean = false,
    val duration: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    /** mpv volume as a fraction: 1.0 = 100 %, up to 2.0 boost */
    val volume: Float = 1.0f,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    /** What mpv actually decodes with right now ("mediacodec-copy", "no", …) */
    val activeDecoder: String = "",
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Display size with rotation applied */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val fps: Float = 0f,
    val error: String? = null
)

/**
 * What mpv holds in its demuxer cache. [ranges] are the cached spans of the file in ms (there
 * can be several after seeking around); [speedBytesPerSec] is the current download rate.
 * Only meaningful for network sources — local files are read directly and never cached.
 */
data class CacheState(
    val isNetwork: Boolean = false,
    val ranges: List<LongRange> = emptyList(),
    val aheadMs: Long = 0L,
    val speedBytesPerSec: Long = 0L
)

enum class ScaleMode(val label: String) { FIT("Fit to screen"), CROP("Crop to fill"), STRETCH("Stretch") }

/** One subtitle line seen during playback (start in ms, plain text without ASS tags) */
data class SubLine(val startMs: Long, val text: String)

data class TrackInfo(
    val id: Int, val label: String, val language: String?, val codec: String?,
    val isSelected: Boolean = false, val isExternal: Boolean = false
)

/**
 * One libmpv instance bound to one player screen. mpv delivers property changes on its own
 * thread; everything is funnelled into StateFlows, which are safe to update from any thread.
 */
class MpvPlayer(private val context: Context, decoderMode: DecoderMode) : MPVLib.EventObserver {

    private val mpv: MPVLib = MPVLib.create(context) ?: error("libmpv failed to initialise")
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlayerState(decoderMode = decoderMode))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _cache = MutableStateFlow(CacheState())
    val cache: StateFlow<CacheState> = _cache.asStateFlow()
    @Volatile private var lastCacheRead = 0L
    @Volatile private var cacheReadPending = false

    /** Called on the main thread once a file is open and its duration/tracks are known */
    var onFileLoaded: (() -> Unit)? = null
    /** Called on the main thread when playback reaches the end of the file */
    var onEndReached: (() -> Unit)? = null

    private var surfaceAttached = false
    private var pendingLoad: Pair<String, Long>? = null
    private var loadedOk = false
    /** END_FILE for the *previous* file arrives after a new loadfile; only errors after our START_FILE count */
    @Volatile private var startFileSeen = false
    private var released = false

    // Scrubbing: one exact seek at a time; the newest target is sent when mpv finishes the last one
    @Volatile private var scrubInFlight = false
    @Volatile private var pendingScrub: Long? = null
    @Volatile private var scrubIssuedAt = 0L

    private val _subtitleLines = MutableStateFlow<List<SubLine>>(emptyList())
    /** Every subtitle line shown so far in this file, in time order (feeds the screenshot collage) */
    val subtitleLines: StateFlow<List<SubLine>> = _subtitleLines.asStateFlow()
    private val _currentSubText = MutableStateFlow("")
    val currentSubText: StateFlow<String> = _currentSubText.asStateFlow()

    init {
        val fontsDir = SubtitleFonts.ensureInstalled(context)
        mpv.apply {
            setOptionString("vo", "gpu")
            setOptionString("gpu-context", "android")
            setOptionString("opengl-es", "yes")
            setOptionString("hwdec", hwdecFor(decoderMode))
            setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            setOptionString("ao", "audiotrack,opensles")
            setOptionString("tls-verify", "no")
            setOptionString("input-default-bindings", "no")
            setOptionString("input-vo-keyboard", "no")
            setOptionString("demuxer-max-bytes", "$DEFAULT_MAX_BYTES")
            setOptionString("demuxer-max-back-bytes", "$DEFAULT_BACK_BYTES")
            // Back the cache with a file so long read-aheads ("Full video") never eat RAM
            File(context.cacheDir, "mpv-cache").apply { mkdirs() }.let {
                setOptionString("demuxer-cache-dir", it.absolutePath)
                setOptionString("cache-on-disk", "yes")
            }
            // Stay on the last frame at EOF instead of unloading, so "replay" and seeking back still work
            setOptionString("keep-open", "always")
            setOptionString("idle", "yes")
            setOptionString("force-window", "no")
            setOptionString("osd-level", "0")
            setOptionString("osd-bar", "no")
            setOptionString("volume-max", "200")
            setOptionString("hr-seek-framedrop", "yes")
            setOptionString("sub-auto", "fuzzy")
            setOptionString("embeddedfonts", "yes")
            if (fontsDir != null) {
                setOptionString("sub-fonts-dir", fontsDir.absolutePath)
                setOptionString("osd-fonts-dir", fontsDir.absolutePath)
                setOptionString("sub-font", SubtitleFonts.DEFAULT_FAMILY)
            }
            init()
        }
        mpv.addObserver(this)
        OBSERVED.forEach { (name, fmt) -> mpv.observeProperty(name, fmt) }
    }

    // ── Surface ─────────────────────────────────────────────────────────

    /** The surface mpv draws on now; a view can move to another surface (e.g. fullscreen) before the old one is destroyed */
    private var currentSurface: Surface? = null

    fun attachSurface(surface: Surface) {
        if (released) return
        if (surfaceAttached) mpv.detachSurface()
        currentSurface = surface
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        surfaceAttached = true
        pendingLoad?.let { (url, start) -> pendingLoad = null; startLoad(url, start) }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (!released && surfaceAttached) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    /** [surface] = the one being destroyed; ignored if mpv already moved on to another surface */
    fun detachSurface(surface: Surface? = null) {
        if (released || !surfaceAttached) return
        if (surface != null && surface != currentSurface) return
        surfaceAttached = false
        currentSurface = null
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
    }

    // ── Loading / transport ─────────────────────────────────────────────

    /** [url] may be a path, http(s)/ftp URL or fdclose://N. Waits for the surface if needed. */
    @Volatile private var startPaused = false

    /** [paused] = open on the first frame without starting playback (cut editor preview) */
    fun load(url: String, startMs: Long = 0L, paused: Boolean = false) {
        startPaused = paused
        if (released) return
        _state.update { it.copy(isFileLoaded = false, isEnded = false, isBuffering = true, error = null, duration = 0L) }
        _position.value = startMs
        if (surfaceAttached) startLoad(url, startMs) else pendingLoad = url to startMs
    }

    private fun startLoad(url: String, startMs: Long) {
        loadedOk = false
        startFileSeen = false
        scrubInFlight = false; pendingScrub = null
        _subtitleLines.value = emptyList(); _currentSubText.value = ""
        _cache.value = CacheState(isNetwork = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ftp://"))
        mpv.setPropertyString("start", if (startMs > 0) "%.3f".format(java.util.Locale.US, startMs / 1000.0) else "none")
        mpv.setPropertyBoolean("pause", startPaused)
        mpv.command(arrayOf("loadfile", url, "replace"))
    }

    fun play() {
        if (released) return
        if (_state.value.isEnded) seekTo(0, exact = true)
        mpv.setPropertyBoolean("pause", false)
    }

    fun pause() { if (!released) mpv.setPropertyBoolean("pause", true) }

    fun togglePlayPause() { if (_state.value.isPlaying) pause() else play() }

    /** [exact] = frame-accurate (slower); otherwise jumps to the nearest keyframe for instant scrubbing. */
    fun seekTo(positionMs: Long, exact: Boolean = true) {
        if (released) return
        val dur = _state.value.duration
        val target = if (dur > 0) positionMs.coerceIn(0, dur) else positionMs.coerceAtLeast(0)
        _position.value = target
        if (_state.value.isEnded && target < dur - 500) _state.update { it.copy(isEnded = false) }
        mpv.command(arrayOf("seek", "%.3f".format(java.util.Locale.US, target / 1000.0), if (exact) "absolute+exact" else "absolute+keyframes"))
    }

    fun seekRelative(offsetMs: Long, exact: Boolean = true) = seekTo(_position.value + offsetMs, exact)

    /**
     * Live scrubbing that shows real frames (not just keyframes): every seek is exact, but a new
     * one is only issued once mpv has drawn the previous target, so it never falls behind a drag.
     */
    fun scrubTo(positionMs: Long) {
        if (released) return
        val now = android.os.SystemClock.uptimeMillis()
        if (scrubInFlight && now - scrubIssuedAt < 600) { pendingScrub = positionMs; return }
        scrubInFlight = true
        scrubIssuedAt = now
        pendingScrub = null
        seekTo(positionMs, exact = true)
    }

    // ── Zoom / pan (pinch) ──────────────────────────────────────────────

    /** [scale] 1.0 = normal size; mpv takes it as log2 */
    fun setZoom(scale: Float) {
        if (!released) mpv.setPropertyDouble("video-zoom", kotlin.math.ln(scale.coerceIn(0.25f, 8f).toDouble()) / kotlin.math.ln(2.0))
    }

    /** Pan as a fraction of the video size (0 = centered) */
    fun setPan(x: Float, y: Float) {
        if (released) return
        mpv.setPropertyDouble("video-pan-x", x.toDouble())
        mpv.setPropertyDouble("video-pan-y", y.toDouble())
    }

    fun frameStep(forward: Boolean) {
        if (released) return
        mpv.command(arrayOf(if (forward) "frame-step" else "frame-back-step"))
    }

    fun seekFrames(count: Int) {
        if (count == 1 || count == -1) { frameStep(count > 0); return }
        pause()
        val fps = _state.value.fps.takeIf { it > 1f } ?: 30f
        seekRelative((count * 1000f / fps).roundToLong(), exact = true)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (released) return
        mpv.setPropertyDouble("speed", speed.toDouble())
        _state.update { it.copy(playbackSpeed = speed) }
    }

    /** 0.0 – 2.0 (values above 1 are software boost) */
    fun setVolume(volume: Float) {
        if (released) return
        val v = volume.coerceIn(0f, 2f)
        mpv.setPropertyDouble("volume", v * 100.0)
        _state.update { it.copy(volume = v) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        if (released) return
        mpv.setPropertyString("loop-file", if (mode == RepeatMode.ONE) "inf" else "no")
        _state.update { it.copy(repeatMode = mode) }
    }

    fun setDecoderMode(mode: DecoderMode) {
        if (released) return
        mpv.setPropertyString("hwdec", hwdecFor(mode))
        _state.update { it.copy(decoderMode = mode) }
    }

    /**
     * Read-ahead for network files. Timed modes stop once that much is buffered past the
     * playhead; FULL keeps going and also keeps everything already played, so the whole
     * file ends up seekable without re-downloading. Byte limits are capped by free disk.
     */
    fun setCacheAhead(mode: CacheAhead) {
        if (released) return
        val free = runCatching { android.os.StatFs(context.cacheDir.path).availableBytes }.getOrDefault(0L)
        fun cap(bytes: Long) = minOf(bytes, (free - DISK_RESERVE_BYTES).coerceAtLeast(DEFAULT_MAX_BYTES))
        val (secs, maxBytes, backBytes) = when (mode) {
            CacheAhead.AUTO -> Triple(DEFAULT_CACHE_SECS, DEFAULT_MAX_BYTES, DEFAULT_BACK_BYTES)
            CacheAhead.FULL -> Triple(DEFAULT_CACHE_SECS, cap(FULL_MAX_BYTES), cap(FULL_MAX_BYTES))
            else -> Triple(mode.seconds!!.toLong(), cap(TIMED_MAX_BYTES), cap(TIMED_BACK_BYTES))
        }
        mpv.setPropertyString("cache", if (mode == CacheAhead.AUTO) "auto" else "yes")
        mpv.setPropertyString("cache-secs", secs.toString())
        mpv.setPropertyString("demuxer-max-bytes", maxBytes.toString())
        mpv.setPropertyString("demuxer-max-back-bytes", backBytes.toString())
    }

    /** "auto" = the file's own aspect ratio, otherwise e.g. "16:9" */
    fun setAspectOverride(ratio: String) {
        if (!released) mpv.setPropertyString("video-aspect-override", if (ratio == "auto") "-1" else ratio)
    }

    fun setScale(mode: ScaleMode) {
        if (released) return
        mpv.setPropertyString("keepaspect", if (mode == ScaleMode.STRETCH) "no" else "yes")
        mpv.setPropertyDouble("panscan", if (mode == ScaleMode.CROP) 1.0 else 0.0)
    }

    fun selectAudioTrack(id: Int) { if (!released) mpv.setPropertyInt("aid", id) }
    fun selectSubtitleTrack(id: Int) { if (!released) mpv.setPropertyInt("sid", id) }
    fun disableSubtitles() { if (!released) mpv.setPropertyString("sid", "no") }
    // ── Picture adjustments: one user shader with the values baked in ──
    // vo=gpu has no //!PARAM / glsl-shader-opts (that is gpu-next only), and it caches shader files by path,
    // so every change writes the values as constants into a new file name and swaps glsl-shaders to it.
    private var shaderTemplate: String? = null
    private var shaderSerial = 0
    private var shaderFile: File? = null

    /** [split] ≥ 0 shows the original on the left of that x position (0..1) for before/after comparison. */
    fun applyFilters(f: com.app.xvideoplayer.data.model.VideoFilters, split: Float = -1f) {
        if (released) return
        val active = !f.isDefault || split >= 0f
        val old = shaderFile
        if (!active) {
            if (old != null) { mpv.setPropertyString("glsl-shaders", ""); old.delete(); shaderFile = null }
            return
        }
        val template = shaderTemplate ?: runCatching { context.assets.open("shaders/xvp_adjust.glsl").bufferedReader().use { it.readText() } }.getOrNull()?.also { shaderTemplate = it } ?: return
        fun n(v: Float) = "%.4f".format(java.util.Locale.US, v)
        val consts = "const float brightness = ${n(f.brightness)};\nconst float contrast = ${n(f.contrast)};\nconst float saturation = ${n(f.saturation)};\n" +
            "const float highlights = ${n(f.highlights)};\nconst float blacks = ${n(f.blacks)};\nconst float whites = ${n(f.whites)};\n" +
            "const float vignette = ${n(f.vignette)};\nconst float vig_soft = ${n(f.vignetteSoft)};\nconst float split = ${n(split)};\n"
        val text = template.replace("// @VALUES@", consts)
        val dir = File(context.cacheDir, "shaders").apply { mkdirs() }
        if (old == null) dir.listFiles()?.forEach { it.delete() } // leftovers from an earlier run
        val file = File(dir, "xvp_adjust_${++shaderSerial}.glsl")
        runCatching { file.writeText(text) }.onFailure { return }
        mpv.setPropertyString("glsl-shaders", file.absolutePath)
        old?.delete()
        shaderFile = file
    }

    /** Jump to the next (+1) or previous (-1) subtitle line. */
    fun subSeek(dir: Int) { if (!released) mpv.command(arrayOf("sub-seek", dir.toString())) }

    /** mpv's own filter that drops (sound effect), [music] and ♪ lines meant for the hearing impaired. */
    fun setHideSdh(on: Boolean) {
        if (released) return
        mpv.setPropertyString("sub-filter-sdh", if (on) "yes" else "no")
        mpv.setPropertyString("sub-filter-sdh-harder", if (on) "yes" else "no")
    }

    /** Last subtitle line that started at or before [posMs] (for word lookup when nothing is on screen right now). */
    fun subLineAt(posMs: Long): String? = _subtitleLines.value.lastOrNull { it.startMs <= posMs + 400 }?.text

    fun addSubtitleFile(path: String) { if (!released) mpv.command(arrayOf("sub-add", path, "select")) }

    // ── A-B loop (native mpv, frame accurate) ───────────────────────────

    fun setAbLoop(aMs: Long?, bMs: Long?, count: Int) {
        if (released) return
        mpv.setPropertyString("ab-loop-a", aMs?.let { "%.3f".format(java.util.Locale.US, it / 1000.0) } ?: "no")
        mpv.setPropertyString("ab-loop-b", bMs?.let { "%.3f".format(java.util.Locale.US, it / 1000.0) } ?: "no")
        mpv.setPropertyString("ab-loop-count", if (count <= 0) "inf" else count.toString())
    }

    /** "H.264 · 23.976 fps · 1.4 GB · 8.2 Mb/s" for the player title; VFR clips show the measured rate too. */
    fun mediaInfoLine(): String {
        if (released) return ""
        val parts = mutableListOf<String>()
        mpv.getPropertyString("video-format")?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
        val cf = mpv.getPropertyDouble("container-fps") ?: 0.0
        val ef = mpv.getPropertyDouble("estimated-vf-fps") ?: 0.0
        fun fmt(d: Double) = if (d % 1.0 < 0.005 || d % 1.0 > 0.995) "%.0f".format(d) else "%.3f".format(d).trimEnd('0')
        when {
            cf > 0 && ef > 0 && kotlin.math.abs(ef - cf) / cf > 0.04 -> parts += "~${fmt(ef)} fps VFR"
            cf > 0 -> parts += "${fmt(cf)} fps"
        }
        val size = mpv.getPropertyString("file-size")?.toLongOrNull() ?: 0L
        if (size > 0) parts += size.formatFileSize()
        // overall = file size / length; mpv's video-bitrate is only the last few packets and swings a lot (18 Mb/s on an 8 Mb/s file)
        val dur = _state.value.duration / 1000.0
        val total = if (size > 0 && dur > 0) size * 8 / dur else (mpv.getPropertyDouble("video-bitrate") ?: 0.0) + (mpv.getPropertyDouble("audio-bitrate") ?: 0.0)
        if (total > 0) parts += "%.1f Mb/s".format(java.util.Locale.US, total / 1_000_000)
        return parts.joinToString("  ·  ")
    }

    /** Used around screenshots: this libmpv build has no image encoders, so frames are grabbed off the surface. */
    fun setSubtitlesVisible(visible: Boolean) { if (!released) mpv.setPropertyString("sub-visibility", if (visible) "yes" else "no") }

    // ── Subtitle style → mpv sub-* options ──────────────────────────────

    fun applySubtitleStyle(s: SubtitleStyle) {
        if (released) return
        fun color(argb: Long) = "#%08X".format(argb and 0xFFFFFFFFL)
        mpv.apply {
            // mpv's font size is in "scaled pixels" of a 720-line virtual screen; 24sp ≈ mpv's default 55
            setPropertyString("sub-font-size", (s.fontSize * 2.3f).toInt().toString())
            setPropertyString("sub-color", color(s.fontColor))
            setPropertyString("sub-border-color", color(s.outlineColor))
            setPropertyString("sub-border-size", "%.1f".format(java.util.Locale.US, s.outlineWidth * 1.5f))
            setPropertyString("sub-shadow-color", color(s.shadowColor))
            setPropertyString("sub-shadow-offset", "%.1f".format(java.util.Locale.US, maxOf(kotlin.math.abs(s.shadowOffsetX), kotlin.math.abs(s.shadowOffsetY))))
            val boxed = (s.backgroundColor ushr 24) and 0xFF > 0
            setPropertyString("sub-back-color", color(s.backgroundColor))
            setPropertyString("sub-border-style", if (boxed) "background-box" else "outline-and-shadow")
            setPropertyString("sub-bold", if (s.bold) "yes" else "no")
            setPropertyString("sub-italic", if (s.italic) "yes" else "no")
            setPropertyString("sub-pos", (s.positionY * 100).toInt().coerceIn(0, 150).toString())
            setPropertyString("sub-margin-y", "2")   // default is 22: that gap kept subtitles away from the screen edge
            setPropertyString("sub-margin-x", (((1f - s.maxWidth) / 2f) * 1280).toInt().coerceAtLeast(0).toString())
            setPropertyString("sub-font", SubtitleFonts.familyOf(s.fontFamily))
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun release() {
        if (released) return
        released = true
        onFileLoaded = null; onEndReached = null
        mpv.removeObserver(this)
        runCatching { if (surfaceAttached) mpv.detachSurface() }
        runCatching { mpv.destroy() }
    }

    // ── mpv → state ─────────────────────────────────────────────────────

    override fun eventProperty(property: String) {
        when (property) {
            "track-list" -> if (_state.value.isFileLoaded) readTracks()
            "video-params" -> if (_state.value.isFileLoaded) readVideoParams()
            "demuxer-cache-state" -> readCacheState()
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "cache-speed" -> _cache.update { it.copy(speedBytesPerSec = value) }
        }
    }

    /** demuxer-cache-state is a node; libmpv hands it over as JSON when read as a string. */
    private fun readCacheState() {
        if (released || !_cache.value.isNetwork) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCacheRead < 250) {
            // throttled: make sure the latest state still lands, e.g. the final "fully cached" one
            if (!cacheReadPending) {
                cacheReadPending = true
                main.postDelayed({ cacheReadPending = false; readCacheState() }, 300)
            }
            return
        }
        lastCacheRead = now
        val json = mpv.getPropertyString("demuxer-cache-state") ?: return
        runCatching {
            val o = org.json.JSONObject(json)
            val arr = o.optJSONArray("seekable-ranges")
            val ranges = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val s = (r.optDouble("start", 0.0) * 1000).roundToLong()
                    val e = (r.optDouble("end", 0.0) * 1000).roundToLong()
                    if (e > s) add(s..e)
                }
            }
            val ahead = (o.optDouble("cache-duration", 0.0) * 1000).roundToLong()
            _cache.update { it.copy(ranges = ranges, aheadMs = ahead) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _position.value = (value * 1000).roundToLong()
            "duration" -> _state.update { it.copy(duration = (value * 1000).roundToLong()) }
            "speed" -> _state.update { it.copy(playbackSpeed = value.toFloat()) }
            "volume" -> _state.update { it.copy(volume = (value / 100.0).toFloat()) }
            "container-fps" -> _state.update { it.copy(fps = value.toFloat()) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _state.update { it.copy(isPlaying = !value && it.isFileLoaded && !it.isEnded) }
            "paused-for-cache" -> _state.update { it.copy(isBuffering = value) }
            "eof-reached" -> if (value) {
                _state.update { it.copy(isEnded = true, isPlaying = false) }
                main.post { onEndReached?.invoke() }
            } else _state.update { it.copy(isEnded = false) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "hwdec-current" -> _state.update { it.copy(activeDecoder = value) }
            "sub-text" -> onSubText(value)
        }
    }

    /**
     * mpv joins every subtitle event on screen with newlines. Karaoke (song) tracks are dozens of
     * one-syllable events, each repeated per layer ("te", "te", "te", "wo", …): drop consecutive
     * repeats and glue syllable runs back into one line so the collage gets readable text.
     */
    private fun normalizeSubText(raw: String): String {
        val lines = raw.split('\n').fold(mutableListOf<String>()) { acc, l ->
            if (acc.lastOrNull()?.trim() != l.trim()) acc += l
            acc
        }.filter { it.isNotBlank() }
        if (lines.size > 3 && lines.map { it.trim().length }.average() <= 4.0)
            return lines.joinToString("").replace(Regex("\\s+"), " ").trim()
        return lines.joinToString("\n") { it.trim() }
    }

    private fun onSubText(raw: String) {
        val text = normalizeSubText(raw)
        _currentSubText.value = text
        if (text.isEmpty()) return
        val start = mpv.getPropertyDouble("sub-start")?.let { (it * 1000).roundToLong() } ?: _position.value
        _subtitleLines.update { lines ->
            if (lines.any { it.startMs == start && it.text == text }) lines
            else (lines + SubLine(start, text)).sortedBy { it.startMs }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_START_FILE -> startFileSeen = true
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                loadedOk = true
                mpv.setPropertyString("start", "none")
                val paused = mpv.getPropertyBoolean("pause") ?: false
                _state.update { it.copy(isFileLoaded = true, isBuffering = false, isEnded = false, isPlaying = !paused, error = null) }
                readTracks(); readVideoParams()
                main.post { onFileLoaded?.invoke() }
            }
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val paused = mpv.getPropertyBoolean("pause") ?: false
                _state.update { it.copy(isBuffering = false, isPlaying = !paused && it.isFileLoaded && !it.isEnded) }
                scrubInFlight = false
                pendingScrub?.let { next -> main.post { scrubTo(next) } }
            }
            MpvEvent.MPV_EVENT_END_FILE -> if (startFileSeen && !loadedOk) {
                _state.update { it.copy(isBuffering = false, isPlaying = false, error = "Couldn't open this video") }
            }
        }
    }

    private fun readVideoParams() {
        var w = mpv.getPropertyInt("video-params/dw") ?: return
        var h = mpv.getPropertyInt("video-params/dh") ?: return
        val rot = mpv.getPropertyInt("video-params/rotate") ?: 0
        if (rot % 180 == 90) { val t = w; w = h; h = t }
        if (w > 0 && h > 0) _state.update { it.copy(videoWidth = w, videoHeight = h) }
    }

    private fun readTracks() {
        val count = mpv.getPropertyInt("track-list/count") ?: return
        val audio = mutableListOf<TrackInfo>(); val subs = mutableListOf<TrackInfo>()
        for (i in 0 until count) {
            val type = mpv.getPropertyString("track-list/$i/type") ?: continue
            if (type != "audio" && type != "sub") continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val title = mpv.getPropertyString("track-list/$i/title")
            val lang = mpv.getPropertyString("track-list/$i/lang")
            val codec = mpv.getPropertyString("track-list/$i/codec")
            val selected = mpv.getPropertyBoolean("track-list/$i/selected") ?: false
            val external = mpv.getPropertyBoolean("track-list/$i/external") ?: false
            val n = if (type == "audio") audio.size + 1 else subs.size + 1
            val label = title?.takeIf { it.isNotBlank() }
                ?: lang?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != it } ?: it.uppercase() }
                ?: (if (type == "audio") "Audio $n" else "Subtitle $n")
            val t = TrackInfo(id, label, lang, codec, selected, external)
            if (type == "audio") audio += t else subs += t
        }
        _state.update { it.copy(audioTracks = audio, subtitleTracks = subs) }
    }

    companion object {
        private val OBSERVED = listOf(
            "time-pos" to MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-state" to MpvFormat.MPV_FORMAT_NONE,
            "cache-speed" to MpvFormat.MPV_FORMAT_INT64,
            "speed" to MpvFormat.MPV_FORMAT_DOUBLE,
            "volume" to MpvFormat.MPV_FORMAT_DOUBLE,
            "container-fps" to MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MpvFormat.MPV_FORMAT_FLAG,
            "hwdec-current" to MpvFormat.MPV_FORMAT_STRING,
            "sub-text" to MpvFormat.MPV_FORMAT_STRING,
            "track-list" to MpvFormat.MPV_FORMAT_NONE,
            "video-params" to MpvFormat.MPV_FORMAT_NONE,
        )

        private const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        private const val DEFAULT_BACK_BYTES = 32L * 1024 * 1024
        private const val DEFAULT_CACHE_SECS = 3_600_000L // mpv's own default (1000 h)
        private const val TIMED_MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val TIMED_BACK_BYTES = 256L * 1024 * 1024
        private const val FULL_MAX_BYTES = 16L * 1024 * 1024 * 1024
        private const val DISK_RESERVE_BYTES = 1L * 1024 * 1024 * 1024

        fun hwdecFor(mode: DecoderMode) = when (mode) {
            DecoderMode.AUTO -> "auto"
            DecoderMode.HARDWARE -> "mediacodec-copy"
            DecoderMode.SOFTWARE -> "no"
        }
    }
}

/** libass on Android has no system font lookup, so copy a couple of system fonts where mpv can see them. */
/** A subtitle font: [key] is what gets stored in the style, [family] the name libass looks up, [asset] the bundled file (null = system). */
data class SubFont(val key: String, val label: String, val family: String, val asset: String? = null)

object SubtitleFonts {
    const val DEFAULT_FAMILY = "Roboto"
    val ALL = listOf(
        SubFont("sans-serif", "Roboto (default)", "Roboto"),
        SubFont("monospace", "Droid Sans Mono", "Droid Sans Mono"),
        SubFont("Lato", "Lato", "Lato", "Lato-Regular.ttf"),
        SubFont("Fira Sans", "Fira Sans", "Fira Sans", "FiraSans-Regular.ttf"),
        SubFont("PT Sans", "PT Sans", "PT Sans", "PT_Sans-Web-Regular.ttf"),
        SubFont("Ubuntu", "Ubuntu", "Ubuntu", "Ubuntu-Regular.ttf"),
        SubFont("Atkinson", "Atkinson Hyperlegible", "Atkinson Hyperlegible", "AtkinsonHyperlegible-Regular.ttf"),
        SubFont("PT Serif", "PT Serif", "PT Serif", "PT_Serif-Web-Regular.ttf"),
        SubFont("Fira Mono", "Fira Mono", "Fira Mono", "FiraMono-Regular.ttf"),
        SubFont("Comic Neue", "Comic Neue", "Comic Neue", "ComicNeue-Regular.ttf"),
        SubFont("Patrick Hand", "Patrick Hand", "Patrick Hand", "PatrickHand-Regular.ttf"),
        SubFont("Kalam", "Kalam", "Kalam", "Kalam-Regular.ttf"),
        SubFont("Bebas Neue", "Bebas Neue", "Bebas Neue", "BebasNeue-Regular.ttf"),
        SubFont("Anton", "Anton", "Anton", "Anton-Regular.ttf"),
        SubFont("Bangers", "Bangers", "Bangers", "Bangers-Regular.ttf"),
        SubFont("Pacifico", "Pacifico", "Pacifico", "Pacifico-Regular.ttf"),
        SubFont("Permanent Marker", "Permanent Marker", "Permanent Marker", "PermanentMarker-Regular.ttf"),
        SubFont("Special Elite", "Special Elite (typewriter)", "Special Elite", "SpecialElite-Regular.ttf"),
        SubFont("Creepster", "Creepster", "Creepster", "Creepster-Regular.ttf"),
        SubFont("Press Start 2P", "Press Start 2P (pixel)", "Press Start 2P", "PressStart2P-Regular.ttf"),
    )
    fun familyOf(key: String) = ALL.firstOrNull { it.key == key }?.family ?: DEFAULT_FAMILY

    private val SOURCES = listOf(
        listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/DroidSans.ttf"),
        listOf("/system/fonts/NotoSansCJK-Regular.ttc"),
        listOf("/system/fonts/DroidSansMono.ttf")
    )

    @Synchronized
    fun ensureInstalled(context: Context): File? = try {
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        for (f in ALL) {
            val name = f.asset ?: continue
            val dst = File(dir, name)
            context.assets.open("subfonts/$name").use { input ->
                if (!dst.exists() || dst.length() != input.available().toLong()) dst.outputStream().use { input.copyTo(it) }
            }
        }
        for (candidates in SOURCES) {
            val src = candidates.map(::File).firstOrNull { it.canRead() } ?: continue
            val dst = File(dir, src.name)
            if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
        }
        dir
    } catch (e: Exception) { null }
}
