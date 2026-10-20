package com.app.xvideoplayer.ui.settings

import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.data.repository.VideoRepository
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.SPEED_PRESETS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val videoRepository: VideoRepository
) : ViewModel() {
    val settings = appPreferences.playerSettings.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerSettings())
    val viewMode = appPreferences.viewMode.stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")
    val syncState = videoRepository.syncState

    fun setDecoderMode(m: DecoderMode) = launch { appPreferences.setDecoderMode(m) }
    fun setPlaybackSpeed(s: Float) = launch { appPreferences.setPlaybackSpeed(s) }
    fun setResumePlayback(v: Boolean) = launch { appPreferences.setResumePlayback(v) }
    fun setDoubleTapSeek(ms: Long) = launch { appPreferences.setDoubleTapSeek(ms) }
    fun setAutoRotate(v: Boolean) = launch { appPreferences.setAutoRotate(v) }
    fun setKeepScreenOn(v: Boolean) = launch { appPreferences.setKeepScreenOn(v) }
    fun setShowRemainingTime(v: Boolean) = launch { appPreferences.setShowRemainingTime(v) }
    fun setAutoPlayNext(v: Boolean) = launch { appPreferences.setAutoPlayNext(v) }
    fun setPauseHeadset(v: Boolean) = launch { appPreferences.setPauseHeadset(v) }
    fun setPipEnabled(v: Boolean) = launch { appPreferences.setPipEnabled(v) }
    fun setDualScreen(v: Boolean) = launch { appPreferences.setDualScreen(v) }
    fun setOverlayAutoHide(sec: Int) = launch { appPreferences.setOverlayAutoHide(sec) }
    fun setReplaySeconds(sec: Int) = launch { appPreferences.setReplaySeconds(sec) }
    fun setHideSdh(v: Boolean) = launch { appPreferences.setHideSdh(v) }
    fun setScreenshotFormat(f: ScreenshotFormat) = launch { appPreferences.setScreenshotFormat(f) }
    fun setScreenshotIncludeSub(v: Boolean) = launch { appPreferences.setScreenshotIncludeSub(v) }
    fun setFrameSeekMode(m: FrameSeekMode) = launch { appPreferences.setFrameSeekMode(m) }
    fun setSwipeSensitivity(v: Float) = launch { appPreferences.setSwipeSensitivity(v) }
    fun setViewMode(m: String) = launch { appPreferences.setViewMode(m) }
    fun setResolutionLabel(l: ResolutionLabel) = launch { appPreferences.setResolutionLabel(l) }
    fun setShowContinueWatching(v: Boolean) = launch { appPreferences.setShowContinueWatching(v) }
    fun setCacheAhead(c: CacheAhead) = launch { appPreferences.setCacheAhead(c) }
    val palette = appPreferences.themePalette.stateIn(viewModelScope, SharingStarted.Eagerly, com.app.xvideoplayer.ui.theme.AppPalette.current)
    fun setThemeStyle(t: com.app.xvideoplayer.ui.theme.ThemeStyle) = launch { appPreferences.setThemeStyle(t) }
    fun setThemeAmoled(v: Boolean) = launch { appPreferences.setThemeAmoled(v) }
    fun rescan() = videoRepository.requestSync()
    fun clearHistory() = launch { videoRepository.clearHistory() }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
}

@Composable
fun SettingsScreen(onNavigateToSubtitleStyle: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val palette by viewModel.palette.collectAsState()
    var showGamepad by remember { mutableStateOf(false) }
    if (showGamepad) GamepadSettingsDialog(onDismiss = { showGamepad = false })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().statusBarsPadding().padding(start = 20.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            }
        }
        item {
            Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.app.xvideoplayer.ui.components.AppChip("General", selected = tab == 0, icon = Icons.Rounded.Tune) { tab = 0 }
                com.app.xvideoplayer.ui.components.AppChip("Theme", selected = tab == 1, icon = Icons.Rounded.Palette) { tab = 1 }
            }
        }
        if (tab == 1) themeTab(palette, viewModel::setThemeStyle, viewModel::setThemeAmoled)
        else {
        item {
            SettingsGroup("Playback") {
                ChoiceRow(Icons.Rounded.Memory, "Decoder", listOf("Auto", "Hardware", "Software"), s.defaultDecoderMode.ordinal) { viewModel.setDecoderMode(DecoderMode.entries[it]) }
                ChoiceRow(Icons.Rounded.Speed, "Default speed", SPEED_PRESETS.map { "${it}×".replace(".0×", "×") }, SPEED_PRESETS.indexOf(s.defaultPlaybackSpeed).coerceAtLeast(0)) { viewModel.setPlaybackSpeed(SPEED_PRESETS[it]) }
                SwitchRow(Icons.Rounded.History, "Resume where I left off", null, s.resumePlayback, viewModel::setResumePlayback)
                SwitchRow(Icons.Rounded.SkipNext, "Play next video in folder", null, s.autoPlayNext, viewModel::setAutoPlayNext)
                SwitchRow(Icons.Rounded.HeadsetOff, "Pause when headphones unplug", null, s.pauseOnHeadsetDisconnect, viewModel::setPauseHeadset)
                SwitchRow(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture", "Keep playing in a small window when you leave", s.pipEnabled, viewModel::setPipEnabled)
                SwitchRow(Icons.Rounded.Devices, "Controls on the second screen", "Dual-screen handhelds: video on one screen, controls on the other", s.dualScreen, viewModel::setDualScreen)
                ChoiceRow(Icons.Rounded.CloudDownload, "Cache network videos", CacheAhead.entries.map { it.label }, s.cacheAhead.ordinal) { viewModel.setCacheAhead(CacheAhead.entries[it]) }
            }
        }
        item {
            SettingsGroup("Gestures") {
                val skips = listOf(5_000L, 10_000L, 15_000L, 30_000L)
                ChoiceRow(Icons.Rounded.TouchApp, "Double-tap skips", skips.map { "${it / 1000} s" }, skips.indexOf(s.doubleTapSeekIncrement).coerceAtLeast(1)) { viewModel.setDoubleTapSeek(skips[it]) }
                ChoiceRow(Icons.Rounded.CenterFocusStrong, "Seek precision", listOf("Exact frame", "Fast (keyframe)"), s.frameSeekMode.ordinal) { viewModel.setFrameSeekMode(FrameSeekMode.entries[it]) }
                LinkRow(Icons.Rounded.SportsEsports, "Gamepad controls", "Assign controller buttons to player features", onClick = { showGamepad = true })
                SliderRow(Icons.Rounded.Swipe, "Swipe sensitivity", s.swipeGestureSensitivity, 0.25f..3f, "%.1f×".format(s.swipeGestureSensitivity), viewModel::setSwipeSensitivity)
                SliderRow(Icons.Rounded.Replay5, "Replay with subtitles", s.replaySeconds.toFloat(), 2f..20f, "${s.replaySeconds} s") { viewModel.setReplaySeconds(it.toInt()) }
            }
        }
        item {
            SettingsGroup("Display") {
                SwitchRow(Icons.Rounded.ScreenRotation, "Rotate to match the video", "Portrait videos open in portrait, landscape in landscape", s.autoRotate, viewModel::setAutoRotate)
                SwitchRow(Icons.Rounded.LightMode, "Keep screen on while playing", null, s.keepScreenOn, viewModel::setKeepScreenOn)
                SwitchRow(Icons.Rounded.Timelapse, "Show time remaining", null, s.showRemainingTime, viewModel::setShowRemainingTime)
                ChoiceRow(Icons.Rounded.HighQuality, "Resolution label", ResolutionLabel.entries.map { it.label }, s.resolutionLabel.ordinal) { viewModel.setResolutionLabel(ResolutionLabel.entries[it]) }
                SliderRow(Icons.Rounded.VisibilityOff, "Hide controls after", s.overlayAutoHideSeconds.toFloat(), 0f..15f,
                    if (s.overlayAutoHideSeconds == 0) "Never" else "${s.overlayAutoHideSeconds} s") { viewModel.setOverlayAutoHide(it.toInt()) }
            }
        }
        item {
            SettingsGroup("Screenshots") {
                ChoiceRow(Icons.Rounded.Image, "Format", listOf("PNG", "JPEG", "WebP"), s.screenshotFormat.ordinal) { viewModel.setScreenshotFormat(ScreenshotFormat.entries[it]) }
                SwitchRow(Icons.Rounded.Subtitles, "Include subtitles", null, s.screenshotIncludeSubtitle, viewModel::setScreenshotIncludeSub)
            }
        }
        item {
            SettingsGroup("Subtitles") {
                LinkRow(Icons.Rounded.FormatColorText, "Subtitle style", "Size, colors, outline, position, fonts", onNavigateToSubtitleStyle)
                SwitchRow(Icons.Rounded.Subtitles, "Hide sound lines", "Skips (door slams), [music] and -(thunder) lines", s.hideSdh, viewModel::setHideSdh)
            }
        }
        item {
            SettingsGroup("Library") {
                ChoiceRow(Icons.Rounded.GridView, "Layout", listOf("List", "Grid"), if (viewMode == "GRID") 1 else 0) { viewModel.setViewMode(if (it == 1) "GRID" else "LIST") }
                SwitchRow(Icons.Rounded.History, "Show \"Continue watching\"", "Videos you've started, in a row above the list", s.showContinueWatching, viewModel::setShowContinueWatching)
                LinkRow(Icons.Rounded.Sync, "Rescan device now", if (sync.isSyncing) "Scanning…" else "The library also updates by itself when files change", viewModel::rescan)
                LinkRow(Icons.Rounded.DeleteSweep, "Clear watch history", null, { confirmClear = true }, tint = ErrorRed)
            }
        }
        item {
            Text(
                "xVideoPlayer 2.2  ·  powered by mpv",
                style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("Resume points stay; only the history log is removed.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { viewModel.clearHistory(); confirmClear = false }) { Text("Clear", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
        )
    }
}

// ── Building blocks (also used by the subtitle style screen) ────────────

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    // capped width so the cards don't stretch edge to edge on landscape/tablet screens
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SurfaceElevated1).border(1.dp, Border, RoundedCornerShape(20.dp)).animateContentSize(),
            content = content
        )
    }
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color = TextSecondary) {
    Box(Modifier.size(34.dp).background(SurfaceElevated3, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SwitchRow(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { RowIcon(icon); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, uncheckedThumbColor = TextTertiary, uncheckedTrackColor = SurfaceElevated3, uncheckedBorderColor = BorderStrong)
        )
    }
}

@Composable
fun ChoiceRow(icon: ImageVector?, title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) { RowIcon(icon); Spacer(Modifier.width(14.dp)) }
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(options.getOrElse(selected) { "" }, style = MaterialTheme.typography.bodyMedium, color = Accent)
            Icon(Icons.Rounded.UnfoldMore, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SurfaceElevated3, shape = RoundedCornerShape(16.dp), modifier = Modifier.align(Alignment.CenterEnd)) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o, color = if (i == selected) Accent else TextPrimary) },
                    trailingIcon = { if (i == selected) Icon(Icons.Rounded.Check, null, tint = Accent) },
                    onClick = { onSelect(i); open = false }
                )
            }
        }
    }
}

@Composable
fun SliderRow(icon: ImageVector?, title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onDone: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    var lastEmit by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value) { if (!dragging) v = value }   // external changes only; ignore stale echoes mid-drag
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { RowIcon(icon); Spacer(Modifier.width(14.dp)) }
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
        // applies while the finger is still down (throttled), and once more on release
        Slider(
            value = v,
            onValueChange = {
                v = it; dragging = true
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastEmit > 60) { lastEmit = now; onDone(v) }
            },
            onValueChangeFinished = { dragging = false; onDone(v) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = SurfaceElevated4),
            modifier = Modifier.padding(start = if (icon != null) 48.dp else 0.dp)
        )
    }
}

@Composable
fun LinkRow(icon: ImageVector?, title: String, subtitle: String?, onClick: () -> Unit, tint: Color = TextPrimary) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { RowIcon(icon, if (tint == TextPrimary) TextSecondary else tint); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
    }
}
