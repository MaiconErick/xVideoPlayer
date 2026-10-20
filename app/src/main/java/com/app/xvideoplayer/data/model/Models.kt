package com.app.xvideoplayer.data.model

data class VideoItem(
    val id: Long = 0,
    val title: String,
    val path: String,
    val uri: String,
    val duration: Long = 0L,
    val size: Long = 0L,
    val resolution: String = "",
    val codec: String = "",
    val source: VideoSource = VideoSource.LOCAL,
    val serverId: Long? = null,
    val thumbnailPath: String? = null,
    val lastPosition: Long = 0L,
    val playedStatus: PlayedStatus = PlayedStatus.UNWATCHED,
    val playCount: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** Display size with the container rotation already applied; 0 when not probed yet */
    val width: Int = 0,
    val height: Int = 0
) {
    val hasDimensions: Boolean get() = width > 0 && height > 0
    val isPortrait: Boolean get() = hasDimensions && height > width
    val progressPercent: Float
        get() = if (duration > 0) (lastPosition.toFloat() / duration) * 100f else 0f
    val folderPath: String get() = path.substringBeforeLast('/')
    val folderName: String get() = folderPath.substringAfterLast('/')
}

enum class VideoSource { LOCAL, SMB, FTP, HTTP }
enum class PlayedStatus { UNWATCHED, IN_PROGRESS, WATCHED }

data class SubtitleTrack(
    val index: Int, val language: String?, val label: String?,
    val mimeType: String?, val isEmbedded: Boolean = true, val externalPath: String? = null
)

data class FolderItem(
    val path: String,
    val name: String,
    val videoCount: Int,
    val totalSize: Long = 0L,
    val subfolderCount: Int = 0,
    val hasInProgress: Boolean = false
)

data class ServerConfig(
    val id: Long = 0, val name: String, val type: ServerType,
    val host: String, val port: Int, val username: String = "", val password: String = "",
    val remotePath: String = "/", val streamMode: StreamMode = StreamMode.STREAM,
    val isActive: Boolean = true, val lastConnected: Long? = null
)

enum class ServerType { SMB, FTP }
enum class StreamMode { STREAM, DOWNLOAD_FIRST }

data class SubtitleStyle(
    val fontSize: Float = 24f, val fontColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000, val outlineColor: Long = 0xFF000000,
    val outlineWidth: Float = 2f, val shadowColor: Long = 0x80000000,
    val shadowRadius: Float = 4f, val shadowOffsetX: Float = 2f, val shadowOffsetY: Float = 2f,
    val bold: Boolean = false, val italic: Boolean = false, val fontFamily: String = "sans-serif",
    val positionX: Float = 0.5f, val positionY: Float = 0.9f, val maxWidth: Float = 0.9f,
    val backgroundPadding: Float = 8f, val backgroundCornerRadius: Float = 4f
)

data class PlayerSettings(
    val defaultDecoderMode: DecoderMode = DecoderMode.AUTO,
    val defaultPlaybackSpeed: Float = 1.0f,
    val resumePlayback: Boolean = true,
    val seekIncrement: Long = 10_000L,
    val longSeekIncrement: Long = 30_000L,
    val doubleTapSeekIncrement: Long = 10_000L,
    val autoRotate: Boolean = true,
    val keepScreenOn: Boolean = true,
    val rememberBrightness: Boolean = true,
    val defaultBrightness: Float = -1f,
    val defaultVolume: Float = 1f,
    val swipeGestureSensitivity: Float = 1f,
    val showRemainingTime: Boolean = false,
    val autoPlayNext: Boolean = true,
    val pauseOnHeadsetDisconnect: Boolean = true,
    val skipSilence: Boolean = false,
    val defaultAudioTrack: String = "auto",
    val defaultSubtitleTrack: String = "off",
    val hardwareDecoderBlacklist: List<String> = emptyList(),
    val pipEnabled: Boolean = true,
    val overlayAutoHideSeconds: Int = 5,
    val replaySeconds: Int = 5,
    /** JSON list of tap zones; empty = the classic thirds */
    val tapZonesJson: String = "",
    /** JSON feature → controller button; empty = defaults */
    val gamepadJson: String = "",
    /** Dual-screen devices (Thor): video on the main screen, controls on the second one */
    val dualScreen: Boolean = true,
    /** Hide hearing-impaired lines such as (door slams), [music] or -(thunder) */
    val hideSdh: Boolean = false,
    /** Pi service that answers word lookups (shared with xFlashcards) */
    val aiServer: String = "http://192.168.1.128:8772",
    val screenshotFormat: ScreenshotFormat = ScreenshotFormat.PNG,
    val screenshotIncludeSubtitle: Boolean = false,
    val abRepeatEnabled: Boolean = false,
    val frameSeekMode: FrameSeekMode = FrameSeekMode.EXACT,
    val resolutionLabel: ResolutionLabel = ResolutionLabel.SHORT,
    val showContinueWatching: Boolean = false,
    val cacheAhead: CacheAhead = CacheAhead.AUTO
)

/** How a video's size is written in the library and player: "1080p", "1920×1080", or both. */
enum class ResolutionLabel(val label: String) { SHORT("Short (1080p)"), FULL("Full (1920×1080)"), BOTH("Both") }

/**
 * How far ahead to buffer network videos (SMB/HTTP). [seconds] = null means mpv's default
 * 64 MB window; [FULL] keeps downloading until the whole file is cached.
 */
enum class CacheAhead(val label: String, val seconds: Int?) {
    AUTO("Auto", null), S30("30 s", 30), M1("1 min", 60), M5("5 min", 300), M15("15 min", 900), FULL("Full video", null)
}

enum class DecoderMode { AUTO, HARDWARE, SOFTWARE }
enum class ScreenshotFormat { PNG, JPEG, WEBP }
enum class FrameSeekMode { EXACT, KEYFRAME }
enum class RepeatMode { OFF, ONE, ALL }

enum class SortField { TITLE, DATE_ADDED, LAST_PLAYED, DURATION, SIZE, PLAY_COUNT, FOLDER }
enum class SortOrder { ASCENDING, DESCENDING }
data class SortConfig(val field: SortField = SortField.FOLDER, val order: SortOrder = SortOrder.ASCENDING)
enum class OrientationFilter { ALL, LANDSCAPE, PORTRAIT }

data class FilterConfig(
    val status: PlayedStatus? = null, val favoritesOnly: Boolean = false,
    val orientation: OrientationFilter = OrientationFilter.ALL,
    val minDuration: Long? = null, val maxDuration: Long? = null, val searchQuery: String = ""
)

data class OverlayGroup(
    val id: String, val name: String, val icon: String,
    val enabled: Boolean = false, val pinned: Boolean = false,
    val offsetX: Float = 0f, val offsetY: Float = 0f,
    val controls: List<OverlayControl> = emptyList()
)

data class OverlayControl(val id: String, val label: String, val icon: String, val type: OverlayControlType = OverlayControlType.BUTTON)
enum class OverlayControlType { BUTTON, TOGGLE, SLIDER }

data class ABRepeatState(
    val pointA: Long? = null, val pointB: Long? = null,
    val isActive: Boolean = false, val loopCount: Int = 0, val currentLoop: Int = 0
)

enum class LibraryViewMode { GRID, LIST, FOLDERS }
