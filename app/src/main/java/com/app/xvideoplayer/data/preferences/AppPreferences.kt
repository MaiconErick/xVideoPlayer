package com.app.xvideoplayer.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.app.xvideoplayer.data.model.*
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "xvideoplayer_prefs")

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private val gson = Gson()

    // ── Player Settings Keys ────────────────────────────────────────────
    private object Keys {
        val DECODER_MODE = stringPreferencesKey("decoder_mode")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val RESUME_PLAYBACK = booleanPreferencesKey("resume_playback")
        val SEEK_INCREMENT = longPreferencesKey("seek_increment")
        val LONG_SEEK_INCREMENT = longPreferencesKey("long_seek_increment")
        val DOUBLE_TAP_SEEK = longPreferencesKey("double_tap_seek")
        val AUTO_ROTATE = booleanPreferencesKey("auto_rotate")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val REMEMBER_BRIGHTNESS = booleanPreferencesKey("remember_brightness")
        val DEFAULT_BRIGHTNESS = floatPreferencesKey("default_brightness")
        val DEFAULT_VOLUME = floatPreferencesKey("default_volume")
        val SWIPE_SENSITIVITY = floatPreferencesKey("swipe_sensitivity")
        val SHOW_REMAINING_TIME = booleanPreferencesKey("show_remaining_time")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val PAUSE_HEADSET_DISCONNECT = booleanPreferencesKey("pause_headset_disconnect")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val DEFAULT_AUDIO_TRACK = stringPreferencesKey("default_audio_track")
        val DEFAULT_SUBTITLE_TRACK = stringPreferencesKey("default_subtitle_track")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val OVERLAY_AUTO_HIDE = intPreferencesKey("overlay_auto_hide")
        val REPLAY_SECONDS = intPreferencesKey("replay_seconds")
        val HIDE_SDH = booleanPreferencesKey("hide_sdh")
        val TAP_ZONES = stringPreferencesKey("tap_zones")
        val GAMEPAD_MAP = stringPreferencesKey("gamepad_map")
        val DUAL_SCREEN = booleanPreferencesKey("dual_screen")
        val AI_SERVER = stringPreferencesKey("ai_server")
        val SCREENSHOT_FORMAT = stringPreferencesKey("screenshot_format")
        val SCREENSHOT_INCLUDE_SUB = booleanPreferencesKey("screenshot_include_subtitle")
        val FRAME_SEEK_MODE = stringPreferencesKey("frame_seek_mode")
        val RESOLUTION_LABEL = stringPreferencesKey("resolution_label")
        val SHOW_CONTINUE_WATCHING = booleanPreferencesKey("show_continue_watching")
        val THEME_STYLE = stringPreferencesKey("theme_style")
        val THEME_AMOLED = booleanPreferencesKey("theme_amoled")
        val CACHE_AHEAD = stringPreferencesKey("cache_ahead")
        val TRIM_LOOP = booleanPreferencesKey("trim_loop")
        val TRIM_STOP_AT_END = booleanPreferencesKey("trim_stop_at_end")

        // Subtitle style
        val SUB_FONT_SIZE = floatPreferencesKey("sub_font_size")
        val SUB_FONT_COLOR = longPreferencesKey("sub_font_color")
        val SUB_BG_COLOR = longPreferencesKey("sub_bg_color")
        val SUB_OUTLINE_COLOR = longPreferencesKey("sub_outline_color")
        val SUB_OUTLINE_WIDTH = floatPreferencesKey("sub_outline_width")
        val SUB_SHADOW_COLOR = longPreferencesKey("sub_shadow_color")
        val SUB_SHADOW_RADIUS = floatPreferencesKey("sub_shadow_radius")
        val SUB_SHADOW_OFFSET_X = floatPreferencesKey("sub_shadow_offset_x")
        val SUB_SHADOW_OFFSET_Y = floatPreferencesKey("sub_shadow_offset_y")
        val SUB_BOLD = booleanPreferencesKey("sub_bold")
        val SUB_ITALIC = booleanPreferencesKey("sub_italic")
        val SUB_FONT_FAMILY = stringPreferencesKey("sub_font_family")
        val SUB_POS_X = floatPreferencesKey("sub_pos_x")
        val SUB_POS_Y = floatPreferencesKey("sub_pos_y")
        val SUB_MAX_WIDTH = floatPreferencesKey("sub_max_width")
        val SUB_BG_PADDING = floatPreferencesKey("sub_bg_padding")
        val SUB_BG_CORNER = floatPreferencesKey("sub_bg_corner")

        // Library view
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val VIEW_MODE = stringPreferencesKey("view_mode") // GRID, LIST
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val ORIENTATION_FILTER = stringPreferencesKey("orientation_filter")
        val FOLDER_MODE = stringPreferencesKey("folder_mode")
        val FOLDER_VIEW = stringPreferencesKey("folder_view")
        val CONTINUE_PANEL_HIDDEN = booleanPreferencesKey("continue_panel_hidden")

        // Overlay groups enabled
        val OVERLAY_GROUPS = stringPreferencesKey("overlay_groups_json")

        // Cloud default stream mode
        val DEFAULT_STREAM_MODE = stringPreferencesKey("default_stream_mode")
    }

    // ── Player Settings ─────────────────────────────────────────────────
    val playerSettings: Flow<PlayerSettings> = context.dataStore.data.map { prefs ->
        PlayerSettings(
            defaultDecoderMode = DecoderMode.valueOf(prefs[Keys.DECODER_MODE] ?: "AUTO"),
            defaultPlaybackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            resumePlayback = prefs[Keys.RESUME_PLAYBACK] ?: true,
            seekIncrement = prefs[Keys.SEEK_INCREMENT] ?: 10_000L,
            longSeekIncrement = prefs[Keys.LONG_SEEK_INCREMENT] ?: 30_000L,
            doubleTapSeekIncrement = prefs[Keys.DOUBLE_TAP_SEEK] ?: 10_000L,
            autoRotate = prefs[Keys.AUTO_ROTATE] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            rememberBrightness = prefs[Keys.REMEMBER_BRIGHTNESS] ?: true,
            defaultBrightness = prefs[Keys.DEFAULT_BRIGHTNESS] ?: -1f,
            defaultVolume = prefs[Keys.DEFAULT_VOLUME] ?: 1f,
            swipeGestureSensitivity = prefs[Keys.SWIPE_SENSITIVITY] ?: 1f,
            showRemainingTime = prefs[Keys.SHOW_REMAINING_TIME] ?: false,
            autoPlayNext = prefs[Keys.AUTO_PLAY_NEXT] ?: true,
            pauseOnHeadsetDisconnect = prefs[Keys.PAUSE_HEADSET_DISCONNECT] ?: true,
            skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
            defaultAudioTrack = prefs[Keys.DEFAULT_AUDIO_TRACK] ?: "auto",
            defaultSubtitleTrack = prefs[Keys.DEFAULT_SUBTITLE_TRACK] ?: "off",
            pipEnabled = prefs[Keys.PIP_ENABLED] ?: true,
            overlayAutoHideSeconds = prefs[Keys.OVERLAY_AUTO_HIDE] ?: 5,
            replaySeconds = prefs[Keys.REPLAY_SECONDS] ?: 5,
            hideSdh = prefs[Keys.HIDE_SDH] ?: false,
            tapZonesJson = prefs[Keys.TAP_ZONES] ?: "",
            gamepadJson = prefs[Keys.GAMEPAD_MAP] ?: "",
            dualScreen = prefs[Keys.DUAL_SCREEN] ?: true,
            aiServer = prefs[Keys.AI_SERVER] ?: "http://192.168.1.128:8772",
            screenshotFormat = ScreenshotFormat.valueOf(prefs[Keys.SCREENSHOT_FORMAT] ?: "PNG"),
            screenshotIncludeSubtitle = prefs[Keys.SCREENSHOT_INCLUDE_SUB] ?: false,
            frameSeekMode = FrameSeekMode.valueOf(prefs[Keys.FRAME_SEEK_MODE] ?: "EXACT"),
            resolutionLabel = runCatching { ResolutionLabel.valueOf(prefs[Keys.RESOLUTION_LABEL] ?: "SHORT") }.getOrDefault(ResolutionLabel.SHORT),
            showContinueWatching = prefs[Keys.SHOW_CONTINUE_WATCHING] ?: false,
            cacheAhead = runCatching { CacheAhead.valueOf(prefs[Keys.CACHE_AHEAD] ?: "AUTO") }.getOrDefault(CacheAhead.AUTO)
        )
    }

    suspend fun updatePlayerSetting(block: suspend (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setDecoderMode(mode: DecoderMode) = context.dataStore.edit { it[Keys.DECODER_MODE] = mode.name }
    suspend fun setPlaybackSpeed(speed: Float) = context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }
    suspend fun setResumePlayback(v: Boolean) = context.dataStore.edit { it[Keys.RESUME_PLAYBACK] = v }
    suspend fun setSeekIncrement(ms: Long) = context.dataStore.edit { it[Keys.SEEK_INCREMENT] = ms }
    suspend fun setLongSeekIncrement(ms: Long) = context.dataStore.edit { it[Keys.LONG_SEEK_INCREMENT] = ms }
    suspend fun setDoubleTapSeek(ms: Long) = context.dataStore.edit { it[Keys.DOUBLE_TAP_SEEK] = ms }
    suspend fun setAutoRotate(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_ROTATE] = v }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = v }
    suspend fun setRememberBrightness(v: Boolean) = context.dataStore.edit { it[Keys.REMEMBER_BRIGHTNESS] = v }
    suspend fun setDefaultBrightness(v: Float) = context.dataStore.edit { it[Keys.DEFAULT_BRIGHTNESS] = v }
    suspend fun setDefaultVolume(v: Float) = context.dataStore.edit { it[Keys.DEFAULT_VOLUME] = v }
    suspend fun setSwipeSensitivity(v: Float) = context.dataStore.edit { it[Keys.SWIPE_SENSITIVITY] = v }
    suspend fun setShowRemainingTime(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_REMAINING_TIME] = v }
    suspend fun setAutoPlayNext(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_PLAY_NEXT] = v }
    suspend fun setPauseHeadset(v: Boolean) = context.dataStore.edit { it[Keys.PAUSE_HEADSET_DISCONNECT] = v }
    suspend fun setSkipSilence(v: Boolean) = context.dataStore.edit { it[Keys.SKIP_SILENCE] = v }
    suspend fun setPipEnabled(v: Boolean) = context.dataStore.edit { it[Keys.PIP_ENABLED] = v }
    suspend fun setTapZones(json: String) = context.dataStore.edit { it[Keys.TAP_ZONES] = json }
    suspend fun setGamepadMap(json: String) = context.dataStore.edit { it[Keys.GAMEPAD_MAP] = json }
    suspend fun setDualScreen(v: Boolean) = context.dataStore.edit { it[Keys.DUAL_SCREEN] = v }

    /** Picture filters are stored per scope: "global", "f:<folder>" or "v:<videoId>". */
    suspend fun filterJson(scope: String): String? = context.dataStore.data.map { it[stringPreferencesKey("vf_$scope")] }.first()
    suspend fun setFilterJson(scope: String, json: String) = context.dataStore.edit { it[stringPreferencesKey("vf_$scope")] = json }
    suspend fun setHideSdh(v: Boolean) = context.dataStore.edit { it[Keys.HIDE_SDH] = v }

    /** Per-video choices (subtitle / audio track) so reopening a video restores them. */
    suspend fun trackMemory(key: String): String? =
        context.dataStore.data.map { it[stringPreferencesKey("trk_$key")] }.first()
    suspend fun setTrackMemory(key: String, json: String) = context.dataStore.edit { it[stringPreferencesKey("trk_$key")] = json }

    suspend fun setReplaySeconds(sec: Int) = context.dataStore.edit { it[Keys.REPLAY_SECONDS] = sec }
    suspend fun setOverlayAutoHide(sec: Int) = context.dataStore.edit { it[Keys.OVERLAY_AUTO_HIDE] = sec }
    suspend fun setScreenshotFormat(f: ScreenshotFormat) = context.dataStore.edit { it[Keys.SCREENSHOT_FORMAT] = f.name }
    suspend fun setScreenshotIncludeSub(v: Boolean) = context.dataStore.edit { it[Keys.SCREENSHOT_INCLUDE_SUB] = v }
    suspend fun setFrameSeekMode(m: FrameSeekMode) = context.dataStore.edit { it[Keys.FRAME_SEEK_MODE] = m.name }
    suspend fun setResolutionLabel(l: ResolutionLabel) = context.dataStore.edit { it[Keys.RESOLUTION_LABEL] = l.name }
    suspend fun setShowContinueWatching(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_CONTINUE_WATCHING] = v }
    suspend fun setCacheAhead(c: CacheAhead) = context.dataStore.edit { it[Keys.CACHE_AHEAD] = c.name }

    // ── Subtitle Style ──────────────────────────────────────────────────
    val subtitleStyle: Flow<SubtitleStyle> = context.dataStore.data.map { prefs ->
        SubtitleStyle(
            fontSize = prefs[Keys.SUB_FONT_SIZE] ?: 24f,
            fontColor = prefs[Keys.SUB_FONT_COLOR] ?: 0xFFFFFFFF,
            backgroundColor = prefs[Keys.SUB_BG_COLOR] ?: 0x00000000,
            outlineColor = prefs[Keys.SUB_OUTLINE_COLOR] ?: 0xFF000000,
            outlineWidth = prefs[Keys.SUB_OUTLINE_WIDTH] ?: 2f,
            shadowColor = prefs[Keys.SUB_SHADOW_COLOR] ?: 0x80000000,
            shadowRadius = prefs[Keys.SUB_SHADOW_RADIUS] ?: 4f,
            shadowOffsetX = prefs[Keys.SUB_SHADOW_OFFSET_X] ?: 2f,
            shadowOffsetY = prefs[Keys.SUB_SHADOW_OFFSET_Y] ?: 2f,
            bold = prefs[Keys.SUB_BOLD] ?: false,
            italic = prefs[Keys.SUB_ITALIC] ?: false,
            fontFamily = prefs[Keys.SUB_FONT_FAMILY] ?: "sans-serif",
            positionX = prefs[Keys.SUB_POS_X] ?: 0.5f,
            positionY = prefs[Keys.SUB_POS_Y] ?: 0.9f,
            maxWidth = prefs[Keys.SUB_MAX_WIDTH] ?: 0.9f,
            backgroundPadding = prefs[Keys.SUB_BG_PADDING] ?: 8f,
            backgroundCornerRadius = prefs[Keys.SUB_BG_CORNER] ?: 4f
        )
    }

    suspend fun setSubtitleStyle(style: SubtitleStyle) = context.dataStore.edit { prefs ->
        prefs[Keys.SUB_FONT_SIZE] = style.fontSize
        prefs[Keys.SUB_FONT_COLOR] = style.fontColor
        prefs[Keys.SUB_BG_COLOR] = style.backgroundColor
        prefs[Keys.SUB_OUTLINE_COLOR] = style.outlineColor
        prefs[Keys.SUB_OUTLINE_WIDTH] = style.outlineWidth
        prefs[Keys.SUB_SHADOW_COLOR] = style.shadowColor
        prefs[Keys.SUB_SHADOW_RADIUS] = style.shadowRadius
        prefs[Keys.SUB_SHADOW_OFFSET_X] = style.shadowOffsetX
        prefs[Keys.SUB_SHADOW_OFFSET_Y] = style.shadowOffsetY
        prefs[Keys.SUB_BOLD] = style.bold
        prefs[Keys.SUB_ITALIC] = style.italic
        prefs[Keys.SUB_FONT_FAMILY] = style.fontFamily
        prefs[Keys.SUB_POS_X] = style.positionX
        prefs[Keys.SUB_POS_Y] = style.positionY
        prefs[Keys.SUB_MAX_WIDTH] = style.maxWidth
        prefs[Keys.SUB_BG_PADDING] = style.backgroundPadding
        prefs[Keys.SUB_BG_CORNER] = style.backgroundCornerRadius
    }

    // ── Library View Settings ───────────────────────────────────────────
    val sortConfig: Flow<SortConfig> = context.dataStore.data.map { prefs ->
        SortConfig(
            field = SortField.valueOf(prefs[Keys.SORT_FIELD] ?: "FOLDER"),
            order = SortOrder.valueOf(prefs[Keys.SORT_ORDER] ?: "ASCENDING")
        )
    }

    suspend fun setSortConfig(config: SortConfig) = context.dataStore.edit {
        it[Keys.SORT_FIELD] = config.field.name
        it[Keys.SORT_ORDER] = config.order.name
    }

    /** GRID or LIST (older builds also stored FOLDERS, which is now its own tab) */
    /** GRID, LIST (adaptive columns on wide screens) or LIST1 (always one entry per row) */
    val viewMode: Flow<String> = context.dataStore.data.map { when (it[Keys.VIEW_MODE]) { "GRID" -> "GRID"; "LIST1" -> "LIST1"; else -> "LIST" } }
    val folderView: Flow<String> = context.dataStore.data.map { if (it[Keys.FOLDER_VIEW] == "GRID") "GRID" else "LIST" }
    suspend fun setFolderView(v: String) = context.dataStore.edit { it[Keys.FOLDER_VIEW] = v }
    val continuePanelHidden: Flow<Boolean> = context.dataStore.data.map { it[Keys.CONTINUE_PANEL_HIDDEN] ?: false }
    suspend fun setContinuePanelHidden(v: Boolean) = context.dataStore.edit { it[Keys.CONTINUE_PANEL_HIDDEN] = v }
    suspend fun setViewMode(mode: String) = context.dataStore.edit { it[Keys.VIEW_MODE] = mode }

    val gridColumns: Flow<Int> = context.dataStore.data.map { it[Keys.GRID_COLUMNS] ?: 3 }
    suspend fun setGridColumns(cols: Int) = context.dataStore.edit { it[Keys.GRID_COLUMNS] = cols }

    val orientationFilter: Flow<OrientationFilter> = context.dataStore.data.map {
        runCatching { OrientationFilter.valueOf(it[Keys.ORIENTATION_FILTER] ?: "ALL") }.getOrDefault(OrientationFilter.ALL)
    }
    suspend fun setOrientationFilter(f: OrientationFilter) = context.dataStore.edit { it[Keys.ORIENTATION_FILTER] = f.name }

    /** Folders tab: "ALL" = flat list of every folder with videos, "NESTED" = browse the folder tree */
    /** Look of the app: DEFAULT or HALLOWEEN, each AMOLED-black or not */
    val themePalette: Flow<com.app.xvideoplayer.ui.theme.Palette> = context.dataStore.data.map {
        val style = runCatching { com.app.xvideoplayer.ui.theme.ThemeStyle.valueOf(it[Keys.THEME_STYLE] ?: "DEFAULT") }
            .getOrDefault(com.app.xvideoplayer.ui.theme.ThemeStyle.DEFAULT)
        com.app.xvideoplayer.ui.theme.Palette.of(style, it[Keys.THEME_AMOLED] ?: true)
    }
    suspend fun setThemeStyle(s: com.app.xvideoplayer.ui.theme.ThemeStyle) = context.dataStore.edit { it[Keys.THEME_STYLE] = s.name }
    suspend fun setThemeAmoled(v: Boolean) = context.dataStore.edit { it[Keys.THEME_AMOLED] = v }

    val folderMode: Flow<String> = context.dataStore.data.map { if (it[Keys.FOLDER_MODE] == "NESTED") "NESTED" else "ALL" }
    suspend fun setFolderMode(mode: String) = context.dataStore.edit { it[Keys.FOLDER_MODE] = mode }

    // ── Overlay Groups ──────────────────────────────────────────────────
    val overlayGroupsJson: Flow<String> = context.dataStore.data.map {
        it[Keys.OVERLAY_GROUPS] ?: getDefaultOverlayGroupsJson()
    }

    suspend fun setOverlayGroupsJson(json: String) = context.dataStore.edit {
        it[Keys.OVERLAY_GROUPS] = json
    }

    // ── Cut editor preview ──────────────────────────────────────────────
    /** Loop playback inside the selection (start → end → start …) */
    val trimLoop: Flow<Boolean> = context.dataStore.data.map { it[Keys.TRIM_LOOP] ?: false }
    /** Without loop: pause at the end mark and rewind to the start mark */
    val trimStopAtEnd: Flow<Boolean> = context.dataStore.data.map { it[Keys.TRIM_STOP_AT_END] ?: true }

    suspend fun setTrimLoop(v: Boolean) = context.dataStore.edit { it[Keys.TRIM_LOOP] = v }
    suspend fun setTrimStopAtEnd(v: Boolean) = context.dataStore.edit { it[Keys.TRIM_STOP_AT_END] = v }

    // ── Cloud default ───────────────────────────────────────────────────
    val defaultStreamMode: Flow<StreamMode> = context.dataStore.data.map {
        StreamMode.valueOf(it[Keys.DEFAULT_STREAM_MODE] ?: "STREAM")
    }

    suspend fun setDefaultStreamMode(mode: StreamMode) = context.dataStore.edit {
        it[Keys.DEFAULT_STREAM_MODE] = mode.name
    }

    private fun getDefaultOverlayGroupsJson(): String {
        return gson.toJson(
            listOf(
                mapOf("id" to "screenshot", "name" to "Screenshot", "icon" to "camera", "enabled" to false),
                mapOf("id" to "frame_control", "name" to "Frame Control", "icon" to "skip", "enabled" to false),
                mapOf("id" to "ab_repeat", "name" to "AB Repeat", "icon" to "repeat", "enabled" to false)
            )
        )
    }
}
