package com.app.xvideoplayer.ui.player

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.app.xvideoplayer.ui.components.AppChip
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.SPEED_PRESETS
import com.app.xvideoplayer.util.formatDuration

/** The second screen of a dual-screen handheld (the Thor's bottom display), or null on single-screen phones. */
fun findSecondaryDisplay(ctx: Context): Display? {
    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    return dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
        .firstOrNull { it.name.contains("Screen-2", true) || it.displayId == 4 } ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRESENTATION) != 0 }
}

/** Hosts Compose on another display: a Presentation has no lifecycle of its own, so it brings one. */
class SecondScreen(outer: Context, display: Display, private val content: @Composable () -> Unit) :
    Presentation(outer, display), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        val decor = window!!.decorView
        decor.setViewTreeLifecycleOwner(this); decor.setViewTreeViewModelStoreOwner(this); decor.setViewTreeSavedStateRegistryOwner(this)
        setContentView(ComposeView(context).apply { setContent(content) })
    }
    override fun onStart() { super.onStart(); registry.currentState = Lifecycle.State.RESUMED }
    override fun onStop() { registry.currentState = Lifecycle.State.DESTROYED; super.onStop() }
}

/** Things only the main window can do (dialogs, file pickers, deleting files); the second screen asks it to. */
class RemoteActions(
    val onClose: () -> Unit, val onMainMenus: () -> Unit, val onStopDual: () -> Unit,
    val onLoadSub: () -> Unit, val onLookup: () -> Unit, val onRename: () -> Unit, val onDelete: () -> Unit, val onCut: () -> Unit,
    val onSavePicture: () -> Unit, val onTapZones: () -> Unit, val canEditFile: Boolean
)

private enum class RemoteTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SUBS("Subtitles", Icons.Rounded.Subtitles), AUDIO("Audio", Icons.Rounded.Audiotrack), PICTURE("Picture", Icons.Rounded.Tune),
    TOOLS("Tools", Icons.Rounded.Build), FILE("File", Icons.Rounded.Folder), MORE("More", Icons.Rounded.MoreHoriz)
}

/**
 * The whole player on the second screen: transport on the left, and tabs on the right for subtitles (tracks, language
 * shortcuts, style), audio, picture, tools (screenshots, frames, A-B, repeat, lock), file actions and decoder/cache.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemotePanel(vm: PlayerViewModel, actions: RemoteActions) {
    var tab by remember { mutableStateOf(RemoteTab.SUBS) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > maxHeight * 1.05f
        if (wide) Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { TransportColumn(vm, actions) }
            Column(Modifier.weight(0.54f).fillMaxHeight()) { TabsColumn(vm, actions, tab) { tab = it } }
        } else Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(0.45f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { TransportColumn(vm, actions) }
            Column(Modifier.weight(0.55f)) { TabsColumn(vm, actions, tab) { tab = it } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransportColumn(vm: PlayerViewModel, actions: RemoteActions) {
    val state by vm.playerState.collectAsState()
    val position by vm.displayPosition.collectAsState()
    val title by vm.title.collectAsState()
    val volume by vm.volumeLevel.collectAsState()
    val hasPrev by vm.hasPrev.collectAsState()
    val hasNext by vm.hasNext.collectAsState()
    val locked by vm.locked.collectAsState()
    val msg by vm.messages.collectAsState(initial = "")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        AppChip("Close", onClick = actions.onClose)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(position.formatDuration(), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 52.dp))
        SeekBar(
            position = position, duration = state.duration, cachedRanges = emptyList(), abA = null, abB = null,
            onScrubStart = vm::beginScrub, onScrub = vm::scrubTo, onScrubEnd = vm::endScrub, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(state.duration.formatDuration(), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
    // the Thor's bottom screen leaves ~230 dp for this column: buttons shrink to fit all five
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val small = (maxWidth.value / 6.2f).toInt().coerceIn(34, 54)
        val big = (small * 1.5f).toInt()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RemoteButton(Icons.Rounded.SkipPrevious, "Previous", small, enabled = hasPrev) { vm.playPrevious() }
            RemoteButton(Icons.Rounded.Replay10, "Back 10 s", small) { vm.seekRelative(-10_000) }
            RemoteButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play / pause", big, filled = true) { vm.togglePlayPause() }
            RemoteButton(Icons.Rounded.Forward10, "Forward 10 s", small) { vm.seekRelative(10_000) }
            RemoteButton(Icons.Rounded.SkipNext, "Next", small, enabled = hasNext) { vm.playNext() }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (volume <= 0.01f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Mute", tint = Color.White, modifier = Modifier.size(28.dp).clickable { vm.toggleMute() })
        Slider(
            value = volume.coerceIn(0f, 2f), onValueChange = vm::setVolumeLevel, valueRange = 0f..2f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = SurfaceElevated4)
        )
        Text("${(volume * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 44.dp))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SPEED_PRESETS.forEach { s -> AppChip("${s}×".replace(".0×", "×"), selected = state.playbackSpeed == s) { vm.setPlaybackSpeed(s) } }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip(if (locked) "Unlock controls" else "Lock controls", selected = locked) { vm.toggleLock() }
        AppChip("Menus on main screen", icon = Icons.Rounded.OpenInNew, onClick = actions.onMainMenus)
        AppChip("Stop dual screen", icon = Icons.Rounded.PhoneAndroid, onClick = actions.onStopDual)
    }
    if (msg.isNotBlank()) Text(msg, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ColumnScope.TabsColumn(vm: PlayerViewModel, actions: RemoteActions, tab: RemoteTab, onTab: (RemoteTab) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteTab.entries.forEach { t -> AppChip(t.label, selected = tab == t, icon = t.icon) { onTab(t) } }
    }
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF111114)).border(1.dp, Border, RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
    ) {
        when (tab) {
            RemoteTab.SUBS -> SubsTab(vm, actions)
            RemoteTab.AUDIO -> AudioTab(vm)
            RemoteTab.PICTURE -> PictureTab(vm, actions)
            RemoteTab.TOOLS -> ToolsTab(vm)
            RemoteTab.FILE -> FileTab(vm, actions)
            RemoteTab.MORE -> MoreTab(vm, actions)
        }
    }
}

@Composable
private fun TrackItem(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) AccentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (selected) AccentBright else Color.White, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) Text(detail, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = Accent)
    }
}

@Composable
private fun TabTitle(text: String) = Text(text.uppercase(), color = TextTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubsTab(vm: PlayerViewModel, actions: RemoteActions) {
    val state by vm.playerState.collectAsState()
    val settings by vm.playerSettings.collectAsState()
    val style by vm.subtitleStyle.collectAsState()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SUB_LANG_CHIPS.forEach { (key, label) ->
            val t = subtitleTrackFor(key, state.subtitleTracks)
            Box(Modifier.alpha(if (t != null) 1f else 0.35f)) { AppChip(label, selected = t?.isSelected == true) { if (t != null) vm.selectSubtitleTrack(t.id) } }
        }
    }
    TrackItem("Off", null, state.subtitleTracks.none { it.isSelected }) { vm.disableSubtitles() }
    state.subtitleTracks.forEach { t ->
        TrackItem(t.label, listOfNotNull(t.language, t.codec, if (t.isExternal) "external" else null).joinToString(" · "), t.isSelected) { vm.selectSubtitleTrack(t.id) }
    }
    FlowRow(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip("Load subtitle file…", icon = Icons.Rounded.FileOpen, onClick = actions.onLoadSub)
        AppChip("Replay with subs", icon = Icons.Rounded.Replay5) { vm.replayWithSubs() }
        AppChip("Look up words", icon = Icons.Rounded.Translate, onClick = actions.onLookup)
        AppChip("Next line", icon = Icons.Rounded.SkipNext) { vm.subtitleSeek(1) }
        AppChip("Previous line", icon = Icons.Rounded.SkipPrevious) { vm.subtitleSeek(-1) }
    }
    Row(Modifier.fillMaxWidth().clickable { vm.setHideSdh(!settings.hideSdh) }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Hide sound lines  (door slams), [music]", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = settings.hideSdh, onCheckedChange = vm::setHideSdh,
            colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, uncheckedTrackColor = SurfaceElevated4, uncheckedBorderColor = BorderStrong)
        )
    }
    TabTitle("Style")
    com.app.xvideoplayer.ui.settings.SubtitleStyleGroups(style) { vm.saveSubtitleStyle(it) }
}

@Composable
private fun AudioTab(vm: PlayerViewModel) {
    val state by vm.playerState.collectAsState()
    TabTitle("Audio track")
    if (state.audioTracks.isEmpty()) Text("No audio tracks", color = TextTertiary, modifier = Modifier.padding(20.dp))
    state.audioTracks.forEach { t -> TrackItem(t.label, listOfNotNull(t.language, t.codec).joinToString(" · "), t.isSelected) { vm.selectAudioTrack(t.id) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureTab(vm: PlayerViewModel, actions: RemoteActions) {
    val scale by vm.scaleMode.collectAsState()
    val aspect by vm.aspect.collectAsState()
    val zoom by vm.zoom.collectAsState()
    val rotation by vm.rotation.collectAsState()
    val filters by vm.filters.collectAsState()
    val split by vm.compareSplit.collectAsState()
    TabTitle("Fit")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.app.xvideoplayer.player.ScaleMode.entries.forEach { m -> AppChip(m.label, selected = scale == m) { vm.setScaleMode(m) } }
    }
    TabTitle("Aspect ratio")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "Original", "16:9" to "16:9", "4:3" to "4:3", "21:9" to "21:9", "1:1" to "1:1", "9:16" to "9:16").forEach { (v, l) -> AppChip(l, selected = aspect == v) { vm.setAspect(v) } }
    }
    FlowRow(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip("Zoom ${(zoom * 100).toInt()}% · reset", icon = Icons.Rounded.ZoomOutMap) { vm.resetZoom() }
        AppChip("Rotation: ${rotation.name.lowercase()}", icon = Icons.Rounded.ScreenRotation) { vm.cycleRotation() }
    }
    PictureAdjustPanelContent(
        f = filters, compare = split >= 0f, onChange = vm::setFilters, onCompare = { vm.setCompareSplit(if (it) 0.5f else -1f) },
        onReset = { vm.setFilters(com.app.xvideoplayer.data.model.VideoFilters()) }, onSave = actions.onSavePicture, onClose = null, scrollable = false
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsTab(vm: PlayerViewModel) {
    val state by vm.playerState.collectAsState()
    val ab by vm.abState.collectAsState()
    val includeSub by vm.screenshotIncludeSub.collectAsState()
    val shots by vm.collage.shots.collectAsState()
    TabTitle("Capture")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip("Screenshot", icon = Icons.Rounded.CameraAlt) { vm.takeScreenshot(includeSub) }
        AppChip("Add to collage (${shots.size})", icon = Icons.Rounded.Collections) { vm.addToCollage() }
        AppChip(if (includeSub) "With subtitles" else "No subtitles", selected = includeSub, icon = Icons.Rounded.Subtitles) { vm.setScreenshotIncludeSub(!includeSub) }
    }
    TabTitle("Frames")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip("−10", icon = Icons.Rounded.FastRewind) { vm.seekByFrames(-10) }
        AppChip("−1", icon = Icons.Rounded.ChevronLeft) { vm.seekByFrames(-1) }
        AppChip("+1", icon = Icons.Rounded.ChevronRight) { vm.seekByFrames(1) }
        AppChip("+10", icon = Icons.Rounded.FastForward) { vm.seekByFrames(10) }
    }
    TabTitle("A-B repeat")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip(ab.pointA?.formatDuration()?.let { "A $it" } ?: "Set A", selected = ab.pointA != null) { vm.setPointA() }
        AppChip(ab.pointB?.formatDuration()?.let { "B $it" } ?: "Set B", selected = ab.pointB != null) { vm.setPointB() }
        if (ab.pointA != null && ab.pointB != null) AppChip(if (ab.isActive) "Looping" else "Loop", selected = ab.isActive) { vm.toggleABRepeat() }
        if (ab.pointA != null) AppChip("Clear") { vm.clearAbLoop() }
    }
    TabTitle("Repeat")
    FlowRow(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip(
            when (state.repeatMode) { com.app.xvideoplayer.data.model.RepeatMode.OFF -> "Repeat: off"; com.app.xvideoplayer.data.model.RepeatMode.ONE -> "Repeat: this video"; else -> "Repeat: folder" },
            selected = state.repeatMode != com.app.xvideoplayer.data.model.RepeatMode.OFF, icon = Icons.Rounded.Repeat
        ) { vm.cycleRepeatMode() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileTab(vm: PlayerViewModel, actions: RemoteActions) {
    val starred by vm.starred.collectAsState()
    val video by vm.currentVideo.collectAsState()
    TabTitle("This video")
    Text(video?.path.orEmpty(), color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
    FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip(if (starred) "Favorited" else "Add to favorites", selected = starred, icon = if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder) { vm.toggleStar() }
        if (actions.canEditFile) {
            AppChip("Cut…", icon = Icons.Rounded.ContentCut, onClick = actions.onCut)
            AppChip("Rename…", icon = Icons.Rounded.DriveFileRenameOutline, onClick = actions.onRename)
            AppChip("Delete…", icon = Icons.Rounded.DeleteOutline, onClick = actions.onDelete)
        }
    }
    Text("Rename, cut and delete ask for confirmation on the main screen.", color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreTab(vm: PlayerViewModel, actions: RemoteActions) {
    val state by vm.playerState.collectAsState()
    val settings by vm.playerSettings.collectAsState()
    val cache by vm.cache.collectAsState()
    TabTitle("Decoder")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.app.xvideoplayer.data.model.DecoderMode.entries.forEach { m ->
            AppChip(when (m) { com.app.xvideoplayer.data.model.DecoderMode.AUTO -> "Auto"; com.app.xvideoplayer.data.model.DecoderMode.HARDWARE -> "Hardware"; else -> "Software" }, selected = state.decoderMode == m) { vm.switchDecoder(m) }
        }
    }
    TabTitle("Cache ahead")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.app.xvideoplayer.data.model.CacheAhead.entries.forEach { m -> AppChip(m.label, selected = settings.cacheAhead == m) { vm.setCacheAhead(m) } }
    }
    if (cache.isNetwork) Text("Cached ahead: ${cache.aheadMs.formatDuration()}", color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    TabTitle("Gestures")
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip("Edit tap zones…", icon = Icons.Rounded.TouchApp, onClick = actions.onTapZones)
    }
}

@Composable
private fun RemoteButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Int, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (filled) Accent else SurfaceElevated3).border(1.dp, if (filled) Accent else Border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = if (filled) OnAccent else if (enabled) Color.White else Color(0x55FFFFFF), modifier = Modifier.size((size * 0.55f).dp)) }
}
