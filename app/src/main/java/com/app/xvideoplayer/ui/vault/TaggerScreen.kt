package com.app.xvideoplayer.ui.vault

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.app.xvideoplayer.data.vault.tagLabel
import com.app.xvideoplayer.data.vault.tagName
import com.app.xvideoplayer.ui.components.rememberFileActions
import com.app.xvideoplayer.ui.player.MpvSurface
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.ui.theme.halloween.PixelSprite
import com.app.xvideoplayer.ui.theme.halloween.Sprite
import com.app.xvideoplayer.ui.theme.halloween.Sprites
import com.app.xvideoplayer.util.formatDuration
import com.app.xvideoplayer.util.formatFileSize
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaggerScreen(onBack: () -> Unit, vm: TaggerViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.finished) { if (vm.finished) onBack() }
    LaunchedEffect(vm.message) { vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.message = null } }
    BackHandler(onBack = onBack)
    PauseWhenBackgrounded(vm.player)
    // back from the cut editor: the file may have been replaced
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.reloadAfterCut() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var showEdits by remember { mutableStateOf(false) }
    if (showEdits) EditsDialog(vm, onDismiss = { showEdits = false })
    if (vm.fullscreen) androidx.compose.ui.window.Dialog(
        onDismissRequest = { vm.fullscreen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        VideoPane(vm, Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(bottom = 6.dp, start = 8.dp, end = 8.dp), inFullscreen = true)
    }

    val cur = vm.current
    // Reserve the keyboard's height all the time (remembered per orientation), so the layout never
    // jumps when the keyboard shows, hides or changes height.
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    val prefs = remember { ctx.getSharedPreferences("tagger", android.content.Context.MODE_PRIVATE) }
    val imeKey = "ime_px_$orientation"
    var reserved by remember(imeKey) { mutableIntStateOf(prefs.getInt(imeKey, 0)) }
    val imeNow = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeNow, imeKey) { if (imeNow > reserved) { reserved = imeNow; prefs.edit().putInt(imeKey, imeNow).apply() } }
    val bottomPx = maxOf(imeNow, reserved, WindowInsets.navigationBars.getBottom(density))

    Box(Modifier.fillMaxSize().background(SurfacePure).statusBarsPadding().padding(bottom = with(density) { bottomPx.toDp() }).padding(8.dp)) {
        if (cur == null) {
            if (vm.loaded) Text("Nothing to tag", color = TextSecondary, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val shape = RoundedCornerShape(22.dp)
        FlashPill(vm, Modifier.align(Alignment.TopCenter).padding(top = 72.dp).zIndex(10f))
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // the typing box keeps focus: whenever anything takes it, it comes straight back
        var focusLost by remember { mutableIntStateOf(0) }
        val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) {
            if (windowFocused && !vm.fullscreen) { delay(150); runCatching { focus.requestFocus() }; keyboard?.show() }
        }
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(focusLost, vm.fullscreen) {
            if (vm.fullscreen) { keyboard?.hide(); return@LaunchedEffect }
            delay(60); runCatching { focus.requestFocus() }
            if (!imeVisible) keyboard?.show()
        }
        val onFocusLost: () -> Unit = { focusLost++ }
        BoxWithConstraints(
            Modifier.fillMaxSize().clip(shape).background(Brush.verticalGradient(listOf(SurfaceElevated2, SurfaceElevated1)))
                .border(1.5.dp, Brush.linearGradient(listOf(Accent, AccentBright, Accent2)), shape)
        ) {
            val wide = maxWidth > maxHeight * 1.2f
            Column(Modifier.fillMaxSize()) {
                Header(vm, onBack, onEdits = { showEdits = true })
                vm.returnTo?.let { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft)
                            .clickable { vm.backToSorting() }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Undo, null, tint = AccentBright, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Editing an old video — back to “${r.title}”", color = AccentBright, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                if (wide) {
                    // landscape: big video + controls + info on the left, tagging on the right;
                    // the type box row sits on the left too (under the info), so the folders get the full right height
                    val inputLeft = vm.current != null
                    Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VideoWithInfo(vm, Modifier.weight(0.58f).fillMaxHeight(), input = if (inputLeft) {{ TagInput(vm, focus, onFocusLost) }} else null, wideInput = true)
                        Column(Modifier.weight(0.42f).fillMaxHeight()) {
                            ChosenTags(vm)
                            if (!inputLeft) TagInput(vm, focus, onFocusLost)
                            FolderPanel(vm, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    // portrait: stacked; the video and the folder list share the space (both shrink with the keyboard)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                        // folder list: its fair share minus two rows; the video takes the rest
                        val folderH = ((maxHeight - 150.dp) / 2.1f - 108.dp).coerceAtLeast(150.dp)
                        val landscapeVideo = vm.current?.isPortrait == false
                        val videoMaxH = maxHeight - 300.dp
                        // portrait video: the video is 15% taller than its old share (the folders give up that height)
                        val panelH = (maxHeight - 36.dp - (maxHeight - 36.dp - (folderH + 66.dp)) * 1.15f).coerceAtLeast(150.dp)
                        Column(Modifier.fillMaxSize()) {
                            if (landscapeVideo) {
                                // landscape video: only as tall as its 16:9 box + one info row, the folders get the rest
                                VideoWithInfo(vm, Modifier.fillMaxWidth().heightIn(max = videoMaxH), compact = true)
                                ChosenTags(vm)
                                TagInput(vm, focus, onFocusLost)
                                FolderPanel(vm, Modifier.weight(1f).fillMaxWidth())
                            } else if (vm.current != null) {
                                // portrait video: the type box + Match/Save go in the info column beside the video,
                                // the height they'd take in the stack goes to the folders
                                VideoWithInfo(vm, Modifier.weight(1f).fillMaxWidth(), input = { TagInput(vm, focus, onFocusLost, stacked = true) })
                                ChosenTags(vm)
                                FolderPanel(vm, Modifier.height(panelH).fillMaxWidth())
                            } else {
                                VideoWithInfo(vm, Modifier.weight(1f).fillMaxWidth())
                                ChosenTags(vm)
                                TagInput(vm, focus, onFocusLost)
                                FolderPanel(vm, Modifier.height(folderH).fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── header ──────────────────────────────────────────────────────────────

@Composable
private fun Header(vm: TaggerViewModel, onClose: () -> Unit, onEdits: () -> Unit) {
    val fileActions = rememberFileActions()
    val openTrim = com.app.xvideoplayer.ui.components.LocalOpenTrim.current
    // phones in portrait: five buttons leave little room, so the title shrinks to the counter
    val compact = LocalConfiguration.current.screenWidthDp < 600
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            Icon(Icons.Rounded.Sell, null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (compact) Text("${vm.pos + 1}/${vm.queue.size}", style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1)
            else {
                Text("Tag videos", style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1)
                Text("${vm.pos + 1} of ${vm.queue.size}  ·  first folder = where it goes", fontSize = 12.sp, color = TextTertiary, maxLines = 1)
            }
        }
        ActionButton(Sprites.moon, "Edits", TextSecondary, onClick = onEdits)
        ActionButton(Sprites.cauldron, "Cut", AccentBright) { vm.current?.let { v -> vm.onCutOpened(); openTrim(v) } }
        ActionButton(Sprites.bat1, "Skip", TextSecondary) { vm.skip() }
        ActionButton(Sprites.skull, if (vm.deleteArmed) "Sure?" else "Delete", ErrorRed, armed = vm.deleteArmed) {
            if (!vm.deleteArmed) vm.deleteArmed = true
            else vm.current?.let { v -> vm.deleteArmed = false; vm.player.pause(); fileActions.delete(listOf(v)) { vm.onDeleted() } }
        }
        ActionButton(Sprites.tomb, "Close", TextSecondary, onClick = onClose)
    }
}

@Composable
private fun ActionButton(sprite: Sprite, label: String, tint: Color, armed: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(start = 6.dp).width(54.dp).clip(shape)
            .background(if (armed) ErrorRed.copy(alpha = 0.3f) else SurfaceElevated3)
            .border(1.dp, tint.copy(alpha = if (armed) 1f else 0.35f), shape)
            .clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PixelSprite(sprite, 18.dp)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ── video, controls, info, chosen tags ─────────────────────────────────

/**
 * Landscape video: info strip under it. Portrait video: info in a column on its left, so the video gets the full height;
 * the video box is cut to the video's own aspect ratio (no black side bars), the info column takes the width it frees.
 * [compact]: landscape video in a stacked (portrait screen) layout: 16:9 box + a single info row, wrapping its height.
 * [input]: the type box, placed under the info; [wideInput]: it's the one-row version (wide screen), so the info column is kept wider.
 */
@Composable
private fun VideoWithInfo(vm: TaggerViewModel, modifier: Modifier, compact: Boolean = false, input: (@Composable () -> Unit)? = null, wideInput: Boolean = false) {
    val cur = vm.current ?: return
    if (cur.isPortrait) BoxWithConstraints(modifier) {
        val gap = 10.dp
        val ratio = cur.width.toFloat() / cur.height
        // with the type box beside the video the left column needs room for its Match + Save buttons
        val videoW = (maxHeight * ratio).coerceAtMost(maxWidth - (if (wideInput) 320.dp else 150.dp) - gap)
        val videoH = (videoW / ratio).coerceAtMost(maxHeight)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // the info box only takes the height it needs (scrolls if it doesn't fit), People + type box right under it
                InfoColumn(vm, Modifier.fillMaxWidth().weight(1f, fill = false))
                PeopleCard(vm, Modifier.fillMaxWidth())
                input?.invoke()
            }
            // if the width cap kicks in, shrink the height too (no black bars above/below)
            VideoPane(vm, Modifier.width(videoW).height(videoH))
        }
    } else if (input != null) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // video shrinks to leave room for the info (two columns) + People + type box
        val ratio = 16f / 9f  // fixed box for every landscape video, so the layout never jumps between files
        VideoPane(vm, Modifier.weight(1f, fill = false).aspectRatio(ratio)) // no fillMaxWidth: forcing the width made the 16:9 box taller than its slot (it spilled under the info)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoColumn(vm, Modifier.weight(1f).fillMaxHeight())
            PeopleCard(vm, Modifier.width(170.dp).fillMaxHeight())
        }
        input()
    } else if (compact) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val ratio = 16f / 9f  // fixed box for every landscape video, so the layout never jumps between files
        VideoPane(vm, Modifier.weight(1f, fill = false).aspectRatio(ratio)) // no fillMaxWidth: forcing the width made the 16:9 box taller than its slot (it spilled under the info)
        InfoStrip(vm)
    } else BoxWithConstraints(modifier) {
        // landscape video: a 16:9 box, the height left over goes to the info + People section
        val videoH = maxWidth * 9f / 16f
        if (maxHeight - videoH < 90.dp) Column(Modifier.fillMaxSize()) {
            VideoPane(vm, Modifier.weight(1f).fillMaxWidth())
            InfoStrip(vm)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoPane(vm, Modifier.fillMaxWidth().height(videoH))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoColumn(vm, Modifier.weight(1f).fillMaxHeight())
                PeopleCard(vm, Modifier.width(170.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoColumn(vm: TaggerViewModel, modifier: Modifier) {
    val cur = vm.current ?: return
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(SurfacePure.copy(alpha = 0.55f)).border(1.dp, Border, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        vm.roleOf(cur)?.let { Text(if (it == "CUT") "✂ THE CUT" else "THE ORIGINAL", fontSize = 11.sp, color = if (it == "CUT") PlayedGreen else AccentBright, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        Text(cur.title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // just the values as chips (no "Size"/"Length" labels), like xVideoWatcher
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChips(cur)
        }
    }
}

@Composable
private fun InfoChips(cur: com.app.xvideoplayer.data.model.VideoItem) {
    InfoChip("💾", cur.size.formatFileSize(), WarningAmber)
    if (cur.hasDimensions) InfoChip("📐", "${cur.width}×${cur.height}", Accent)
    if (cur.duration > 0) InfoChip("⏳", cur.duration.formatDuration(), PlayedGreen)
    InfoChip("🕸", cur.folderName, InfoBlue)
}

@Composable
private fun VideoPane(vm: TaggerViewModel, modifier: Modifier, inFullscreen: Boolean = false) {
    val state by vm.player.state.collectAsState()
    val position by vm.player.position.collectAsState()
    var scrub by remember { mutableStateOf<Long?>(null) }
    if (vm.fullscreen && !inFullscreen) {
        // the surface lives in the fullscreen window meanwhile
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Playing fullscreen", color = TextTertiary, fontSize = 13.sp)
        }
        return
    }
    if (inFullscreen) BackHandler { vm.fullscreen = false }
    // double-taps on a side: 5 s, then 10, then 15 each while the streak continues
    var streak by remember { mutableIntStateOf(0) }
    var streakDir by remember { mutableIntStateOf(0) }
    var streakTotal by remember { mutableIntStateOf(0) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var feedback by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(feedback) { if (feedback != null) { delay(900); feedback = null } }
    fun sideSeek(dir: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (dir != streakDir || now - lastSeekAt > 2000) { streak = 0; streakTotal = 0 }
        val step = when (streak) { 0 -> 5; 1 -> 10; else -> 15 }
        streak++; streakDir = dir; lastSeekAt = now; streakTotal += step
        vm.player.seekRelative(dir * step * 1000L)
        feedback = dir to streakTotal
    }
    Box(
            modifier.clip(RoundedCornerShape(if (inFullscreen) 0.dp else 16.dp)).background(Color.Black)
                .pointerInput(inFullscreen) {
                    detectTapGestures(
                        onTap = { vm.controlsVisible = !vm.controlsVisible },
                        // double tap: sides seek, the middle third enters/leaves fullscreen
                        onDoubleTap = { o ->
                            when {
                                o.x < size.width / 3f -> sideSeek(-1)
                                o.x > size.width * 2f / 3f -> sideSeek(1)
                                else -> vm.fullscreen = !inFullscreen
                            }
                        }
                    )
                }
        ) {
            feedback?.let { (dir, secs) ->
                Text(
                    if (dir < 0) "⏪ −${secs}s" else "+${secs}s ⏩", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = if (inFullscreen) 40.dp else 8.dp)
                        .zIndex(1f).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            MpvSurface(vm.player, Modifier.fillMaxSize())
            if (inFullscreen) LandscapeButton(vm.current, Modifier.align(Alignment.BottomEnd).padding(12.dp).zIndex(3f))
            // which file is open when a cut was kept next to its original
            vm.roleOf(vm.current)?.let { role ->
                val cut = role == "CUT"
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp).zIndex(2f).clip(RoundedCornerShape(10.dp))
                        .background(if (cut) PlayedGreen else Accent).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (cut) Icons.Rounded.ContentCut else Icons.Rounded.Movie, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(role, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black, letterSpacing = 1.sp)
                }
            }
            // tap the video to show/hide; drawn over the picture
            androidx.compose.animation.AnimatedVisibility(
                vm.controlsVisible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize().zIndex(1f)
            ) {
                Box(Modifier.fillMaxSize()) {
                    // center: rewind / play / forward — repeated presses jump 5 s, 10 s, then 15 s each (streak resets after 2 s)
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundOverlayButton(Icons.Rounded.FastRewind, "Rewind", 48) { sideSeek(-1) }
                        RoundOverlayButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", 60) { vm.player.togglePlayPause() }
                        RoundOverlayButton(Icons.Rounded.FastForward, "Forward", 48) { sideSeek(1) }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                            .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((scrub ?: position).formatDuration(), color = Color.White, fontSize = 12.sp)
                            com.app.xvideoplayer.ui.player.SeekBar(
                                position = scrub ?: position, duration = state.duration, cachedRanges = emptyList(), abA = null, abB = null,
                                onScrubStart = { scrub = position }, onScrub = { scrub = it; vm.player.scrubTo(it) },
                                onScrubEnd = { scrub?.let { vm.player.seekTo(it) }; scrub = null },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(state.duration.formatDuration(), color = Color.White, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CtrlButton(if (vm.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Mute", highlighted = vm.muted) { vm.toggleMute() }
                            CtrlButton(if (inFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen") { vm.fullscreen = !inFullscreen }
                        }
                    }
                }
            }
    }
}

/** Fading confirmation (about a second) when a tag is added without the user typing it. */
@Composable
private fun FlashPill(vm: TaggerViewModel, modifier: Modifier) {
    val f = vm.flash
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(f) { if (f != null) { text = f.first; shown = true; delay(1000); shown = false } }
    androidx.compose.animation.AnimatedVisibility(
        shown, modifier = modifier,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)) + androidx.compose.animation.scaleIn(initialScale = 0.9f),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(500))
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(PlayedGreen).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Fullscreen on a portrait-locked phone: one tap turns a landscape video sideways (and back). */
@Composable
private fun LandscapeButton(video: com.app.xvideoplayer.data.model.VideoItem?, modifier: Modifier) {
    val activity = LocalContext.current as? android.app.Activity ?: return
    var forced by remember { mutableStateOf(false) }
    val isLandscapeVideo = video != null && video.hasDimensions && !video.isPortrait
    val screenPortrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    DisposableEffect(Unit) { onDispose { if (forced) activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    if (!isLandscapeVideo || (!screenPortrait && !forced)) return
    Box(
        modifier.size(46.dp).clip(CircleShape).background(Color(0x99000000)).clickable {
            forced = !forced
            activity.requestedOrientation = if (forced) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        },
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.ScreenRotation, "Landscape", tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun RoundOverlayButton(icon: ImageVector, desc: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(0x80000000)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
private fun CtrlButton(icon: ImageVector, desc: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(if (highlighted) AccentSoft else Color.Transparent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (highlighted) AccentBright else Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Under the video: the name on its own line, then size, resolution, length, source folder + People in one row */
@Composable
private fun InfoStrip(vm: TaggerViewModel) {
    val cur = vm.current ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(cur.title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // the chips scroll if they don't fit; the People buttons stay on the right edge
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                InfoChips(cur)
            }
            PeopleButton(vm)
            InputNameButton(vm)
        }
    }
}

/** Shown once a People tag is set (or no name could be read): pick/type the person yourself */
@Composable
private fun InputNameButton(vm: TaggerViewModel, modifier: Modifier = Modifier) {
    val hasPeople = vm.chips.any { TaggerViewModel.isPeople(it) }
    if (!hasPeople && vm.username != null) return
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (vm.peopleMode) Accent else SurfaceElevated3)
            .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable { if (vm.peopleMode) vm.closePeople() else vm.openPeople() }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Keyboard, null, tint = if (vm.peopleMode) OnAccent else AccentBright, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Input name", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (vm.peopleMode) OnAccent else AccentBright, maxLines = 1)
    }
}

/** Replaces the folder panel while choosing a person: People › Stars folders, or a new name */
@Composable
private fun PeoplePanel(vm: TaggerViewModel, q: String, modifier: Modifier) {
    val tree by vm.tree.collectAsState()
    val people = remember(tree, q) { vm.peopleMatches(q) }
    val current = vm.chips.firstOrNull { TaggerViewModel.isPeople(it) }
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PanelTitle(Icons.Rounded.Person, "People › Stars", "${people.size}") }
            Icon(Icons.Rounded.Close, "Back to folders", tint = TextSecondary, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { vm.closePeople() }.padding(5.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            if (q.isNotEmpty() && people.none { tagName(it).equals(q, true) }) item(key = "__new") {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, PlayedGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable { vm.pickPerson(q) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PersonAdd, null, tint = PlayedGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New: $q", fontSize = 15.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            items(people, key = { it }) { t ->
                val chosen = t == current
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (chosen) PlayedGreen.copy(alpha = 0.18f) else SurfaceElevated3)
                        .clickable { vm.pickPerson(t) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (chosen) Icons.Rounded.HowToReg else Icons.Rounded.Person, null, tint = if (chosen) PlayedGreen else TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tagName(t), fontSize = 16.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (people.isEmpty() && q.isEmpty()) item(key = "__none") {
                Text("No people yet — type a name and press Enter.", fontSize = 13.sp, color = TextTertiary, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

/** "Add to People" under the file info (portrait videos) */
@Composable
private fun PeopleCard(vm: TaggerViewModel, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(SurfacePure.copy(alpha = 0.55f)).border(1.dp, Border, RoundedCornerShape(14.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("PEOPLE", fontSize = 10.sp, color = TextTertiary, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        PeopleButton(vm, Modifier.fillMaxWidth())
        InputNameButton(vm, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PeopleButton(vm: TaggerViewModel, modifier: Modifier = Modifier) {
    val name = vm.username
    val tag = name?.let { vm.peopleTagFor(it) }
    val added = tag != null && tag in vm.chips
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (added) PlayedGreen.copy(alpha = 0.18f) else if (name != null) AccentSoft else SurfaceElevated3)
            .border(1.dp, if (added) PlayedGreen.copy(alpha = 0.6f) else Accent.copy(alpha = if (name != null) 0.5f else 0.15f), RoundedCornerShape(10.dp))
            .clickable(enabled = name != null) { if (added) vm.removeTag(tag!!) else vm.addToPeople() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (added) Icons.Rounded.HowToReg else Icons.Rounded.PersonAdd, null, tint = if (added) PlayedGreen else if (name != null) AccentBright else TextTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(if (added) "In People" else "Add to People", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (added) PlayedGreen else if (name != null) AccentBright else TextTertiary, maxLines = 1)
            Text(name ?: "no name before “-”", fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The tags picked so far, on one fixed-height line so the video never shifts when tags change */
@Composable
private fun ChosenTags(vm: TaggerViewModel) {
    val scroll = rememberScrollState()
    LaunchedEffect(vm.chips.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
        if (vm.chips.isEmpty()) Text("No folder yet — type one and press Enter", fontSize = 13.sp, color = TextTertiary, maxLines = 1)
        else Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            vm.chips.forEachIndexed { i, t -> ChosenChip(t, primary = i == 0 && !TaggerViewModel.isPeople(t), onClick = { vm.makePrimary(t) }, onRemove = { vm.removeTag(t) }) }
        }
    }
}

@Composable
private fun InfoChip(icon: String, label: String, tint: Color) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.clip(shape).background(tint.copy(alpha = 0.13f)).border(1.dp, tint.copy(alpha = 0.4f), shape).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tap = make it the primary (the folder it's moved to); × = remove */
@Composable
private fun ChosenChip(tag: String, primary: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape)
            // primary = where the file is moved: green with a move icon; the rest are "also listed in"
            .background(if (primary) PlayedGreen else AccentSoft)
            .border(1.dp, if (primary) PlayedGreen else Accent.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick).padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when { primary -> Icons.Rounded.DriveFileMove; TaggerViewModel.isPeople(tag) -> Icons.Rounded.Person; else -> Icons.Rounded.Sell }, null,
            tint = if (primary) Color.Black else AccentBright, modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(tagLabel(tag), fontSize = 13.sp, color = if (primary) Color.Black else AccentBright, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        Icon(Icons.Rounded.Close, "Remove", tint = if (primary) Color.Black else AccentBright, modifier = Modifier.padding(start = 4.dp).size(18.dp).clip(CircleShape).clickable(onClick = onRemove).padding(2.dp))
    }
}

// ── input ───────────────────────────────────────────────────────────────

@Composable
private fun TagInput(vm: TaggerViewModel, focus: FocusRequester, onFocusLost: () -> Unit, stacked: Boolean = false) {
    val q = vm.text.text
    val tree by vm.tree.collectAsState()
    val best = remember(q, tree) { vm.best(q) }
    val style = TextStyle(fontSize = 20.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    val shape = RoundedCornerShape(16.dp)
    // narrow column (phone, portrait video): field on its own row, the Enter hint under it, Match below (Save is in the folder panel)
    val hint: @Composable (Modifier) -> Unit = { hm ->
        if (q.isNotBlank() && !vm.matchMode && !vm.newFolderMode && !vm.peopleMode) Text(
            vm.existingChild(q)?.let { "⏎ open “${tagName(it)}”" } ?: "⏎ create “${vm.capitalized(q)}”", fontSize = 14.sp, color = PlayedGreen, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = hm
        )
        else if (q.isNotBlank() && !vm.newFolderMode && !vm.peopleMode) Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextTertiary)) { append("⏎ ") }
                if (best != null) withStyle(SpanStyle(color = AccentBright, fontWeight = FontWeight.SemiBold)) { append(tagLabel(best)) }
                else withStyle(SpanStyle(color = PlayedGreen)) { append("new “${q.trim()}”") }
            },
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = hm
        )
    }
    val field: @Composable (Modifier) -> Unit = { fm ->
        Row(
            fm.height(54.dp).clip(shape).background(SurfacePure).border(1.5.dp, Accent.copy(alpha = 0.7f), shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when { vm.peopleMode -> Icons.Rounded.Person; vm.newFolderMode -> Icons.Rounded.CreateNewFolder; else -> Icons.Rounded.Search }, null,
                tint = if (vm.newFolderMode || vm.peopleMode) PlayedGreen else Accent, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (q.isEmpty()) Text(
                    when {
                        vm.peopleMode -> "name…  ⏎ picks it or creates it"
                        !vm.matchMode && !vm.newFolderMode -> if (vm.browse.isEmpty()) "folder…  ⏎ opens/creates it" else "subfolder…  or ⏎ to tag ${tagName(vm.browse)}"
                        vm.newFolderMode -> "new folder in ${if (vm.browse.isEmpty()) "root" else tagName(vm.browse)}…  ⏎ creates"
                        vm.chips.isEmpty() -> "type a folder…  ⏎ adds it"
                        else -> "another folder…  or ⏎ to save"
                    },
                    style = style.copy(color = TextTertiary, fontSize = 15.sp), maxLines = 1
                )
                BasicTextField(
                    value = vm.text, onValueChange = vm::onTextChange, singleLine = true, textStyle = style,
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.onEnter() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused) onFocusLost() }
                        .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { vm.onEnter(); true }
                            Key.Backspace -> if (vm.text.text.isEmpty()) { vm.onBackspaceEmpty(); true } else false
                            else -> false
                        }
                    }
                )
            }
            // what Enter will do
            if (vm.newFolderMode || vm.peopleMode) Icon(
                Icons.Rounded.Close, "Cancel", tint = TextSecondary,
                modifier = Modifier.padding(start = 6.dp).size(30.dp).clip(CircleShape).clickable { if (vm.peopleMode) vm.closePeople() else vm.cancelNewFolder() }.padding(4.dp)
            )
            else if (!stacked) hint(Modifier.widthIn(max = 240.dp).padding(start = 8.dp))
        }
    }
    val match: @Composable (Modifier) -> Unit = { mm ->
        // Match toggle
        Column(
            mm.height(54.dp).clip(shape).background(if (vm.matchMode) AccentSoft else SurfaceElevated3)
                .border(1.dp, if (vm.matchMode) Accent else Border, shape).clickable { vm.toggleMatch() }.padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Icon(if (vm.matchMode) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (vm.matchMode) AccentBright else TextTertiary, modifier = Modifier.size(20.dp))
            Text("Match", fontSize = 11.sp, color = if (vm.matchMode) AccentBright else TextTertiary, fontWeight = FontWeight.SemiBold)
        }
    }
    if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        field(Modifier.fillMaxWidth())
        hint(Modifier.padding(horizontal = 4.dp))
        match(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        field(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        match(Modifier)
    }
}

@Composable
private fun TagRow(tag: String, count: Int, highlighted: Boolean, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val fg = if (highlighted) OnAccent else TextPrimary
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape)
            .background(when { highlighted -> Accent; selected -> AccentSoft; else -> SurfaceElevated3 })
            .border(1.dp, if (selected) Accent else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "✓" else "#", fontSize = 15.sp, color = if (highlighted) OnAccent else if (selected) PlayedGreen else TextTertiary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        val parent = tag.substringBeforeLast('/', "")
        Text(
            buildAnnotatedString {
                if (parent.isNotEmpty()) withStyle(SpanStyle(color = fg.copy(alpha = 0.6f))) { append(tagLabel(parent) + " › ") }
                withStyle(SpanStyle(color = fg, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal)) { append(tagName(tag)) }
            },
            fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (count > 0) Text("$count", fontSize = 12.sp, color = if (highlighted) OnAccent.copy(alpha = 0.8f) else TextTertiary)
    }
}

// ── folders: search results while typing ────────────────────────────────

@Composable
private fun SearchResults(vm: TaggerViewModel, q: String, modifier: Modifier) {
    val tree by vm.tree.collectAsState()
    val usage by vm.usage.collectAsState()
    val visible = remember(tree, q, usage) { vm.matches(q, tree) }
    val listState = rememberLazyListState()
    LaunchedEffect(q) { listState.scrollToItem(0) }
    Panel(modifier) {
        PanelTitle(Icons.Rounded.Search, "Matching folders", "${visible.size}")
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(visible, key = { it }) { t -> TagRow(t, usage[t] ?: 0, highlighted = t == visible.firstOrNull(), selected = t in vm.chips) { vm.toggleTag(t) } }
            if (visible.none { tagName(it).equals(q, true) }) item(key = "__new") {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, PlayedGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable { vm.createFolder(q) { err -> if (err != null) vm.message = err } }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, null, tint = PlayedGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New folder “${vm.capitalized(q)}”", fontSize = 15.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (vm.browse.isEmpty()) "in root" else "in ${tagName(vm.browse)}", fontSize = 12.sp, color = TextTertiary, maxLines = 1)
                }
            }
        }
    }
}

// ── folders: browse the tree ───────────────────────────────────────

@Composable
private fun FolderPanel(vm: TaggerViewModel, modifier: Modifier) {
    val tree by vm.tree.collectAsState()
    val here = vm.browse
    val children = remember(tree, here) { tree.filter { t -> if (here.isEmpty()) '/' !in t else t.startsWith("$here/") && '/' !in t.removePrefix("$here/") } }
    val q = vm.text.text.trim()
    if (vm.peopleMode) { PeoplePanel(vm, q, modifier); return }
    if (q.isNotEmpty() && !vm.newFolderMode && vm.matchMode) { SearchResults(vm, q, modifier); return }
    val listState = rememberLazyGridState()
    LaunchedEffect(here) { listState.scrollToItem(0) }
    Panel(modifier) {
        // one line: breadcrumbs (they say where you are) + New
        val crumbs = listOf("") + here.split('/').filter { it.isNotEmpty() }.runningReduce { a, b -> "$a/$b" }
        val scroll = rememberScrollState()
        LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, null, tint = Accent, modifier = Modifier.padding(start = 2.dp, end = 4.dp).size(16.dp))
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, c ->
                val last = i == crumbs.lastIndex
                if (i > 0) Text(" › ", fontSize = 14.sp, color = TextTertiary)
                Text(
                    if (c.isEmpty()) "⌂ ${(vm.root ?: "").substringAfterLast('/')}" else tagName(c),
                    fontSize = 14.sp, color = if (last) OnAccent else AccentBright, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).then(if (last) Modifier.background(Accent) else Modifier).clickable { vm.openDir(c) }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "+ New", fontSize = 13.sp, color = OnAccent, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (vm.newFolderMode) PlayedGreen else Accent).clickable { if (vm.newFolderMode) vm.cancelNewFolder() else vm.startNewFolder() }.padding(horizontal = 10.dp, vertical = 6.dp)
        )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(92.dp), state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).fillMaxHeight()
        ) {
            if (here.isNotEmpty()) item(key = "__up") { FolderCard(Sprites.tomb, "Go up") { vm.goUp() } }
            items(children, key = { it }) { t ->
                FolderCard(Sprites.folder, tagName(t), selected = t in vm.chips) { vm.openDir(t) }
            }
            if (children.isEmpty()) item(key = "__none", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (here.isEmpty()) "No folders yet — tap “+ New” (e.g. Muscle)." else "No subfolders. Tap Tag on the right (or Enter), or “+ New” for a subfolder.",
                    fontSize = 13.sp, color = TextTertiary, modifier = Modifier.padding(4.dp)
                )
            }
        }
        // card on the right: inside a folder it tags the video with that folder; at the root it's Save
        val sideCard = Modifier.width(80.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp))
        if (here.isNotEmpty()) {
            val chosen = here in vm.chips
            Column(
                sideCard
                    .background(if (chosen) PlayedGreen.copy(alpha = 0.2f) else AccentSoft)
                    .border(1.dp, if (chosen) PlayedGreen else Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .clickable { vm.toggleTag(here) }.padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(if (chosen) Icons.Rounded.Check else Icons.Rounded.Sell, null, tint = if (chosen) PlayedGreen else AccentBright, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(if (chosen) "Tagged" else "Tag", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (chosen) PlayedGreen else AccentBright)
                Text(here, fontSize = 11.sp, color = TextSecondary, maxLines = 6, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp)
            }
        } else {
            val ready = vm.chips.isNotEmpty()
            Column(
                sideCard.background(if (ready) Accent else SurfaceElevated3)
                    .then(if (ready) Modifier else Modifier.border(1.dp, Border, RoundedCornerShape(14.dp)))
                    .clickable(enabled = !vm.busy) { vm.done() }.padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = OnAccent)
                else Icon(Icons.Rounded.Done, null, tint = if (ready) OnAccent else TextTertiary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(4.dp))
                Text("Save", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (ready) OnAccent else TextTertiary)
            }
        }
        }
    }
}

/** A folder in the grid (taller than wide): tap opens it */
@Composable
private fun FolderCard(sprite: Sprite, name: String, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().height(62.dp).clip(shape)
            .background(if (selected) PlayedGreen.copy(alpha = 0.14f) else SurfaceElevated3)
            .border(1.dp, if (selected) PlayedGreen.copy(alpha = 0.6f) else Border, shape)
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // fixed slots (icon, two name lines) so every card lines up
        PixelSprite(if (selected) Sprites.pumpkin else sprite, 20.dp)
        Spacer(Modifier.height(2.dp))
        Text(
            name, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun FolderRow(icon: ImageVector, name: String, trailing: String, color: Color, selected: Boolean? = null, onAdd: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(shape).background(SurfaceElevated3).clickable(onClick = onClick).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 16.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(trailing, fontSize = 13.sp, color = TextTertiary)
        if (onAdd != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (selected == true) PlayedGreen.copy(alpha = 0.2f) else AccentSoft).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) { Icon(if (selected == true) Icons.Rounded.Check else Icons.Rounded.Add, "Tag", tint = if (selected == true) PlayedGreen else AccentBright, modifier = Modifier.size(20.dp)) }
        }
    }
}

// ── shared bits ─────────────────────────────────────────────────────────

@Composable
private fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(SurfacePure.copy(alpha = 0.55f)).border(1.dp, Border, shape).padding(8.dp), content = content)
}

@Composable
private fun PanelTitle(icon: ImageVector, title: String, count: String) {
    Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        if (count.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(count, fontSize = 12.sp, color = TextTertiary) }
    }
}

@Composable
fun NewFolderDialog(parent: String, onCreate: (String, (String) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated2,
        title = { Text("New folder") },
        text = {
            Column {
                Text("inside $parent", fontSize = 13.sp, color = TextTertiary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, singleLine = true,
                    placeholder = { Text("Folder name") }, isError = error != null, supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCreate(name) { error = it } }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) { error = it } }) { Text("Create", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )
}

/** Pauses [player] whenever the app leaves the foreground */
@Composable
fun PauseWhenBackgrounded(player: com.app.xvideoplayer.player.MpvPlayer) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** Files saved by the tagger before, newest first: tap one to reopen and fix its tags */
@Composable
private fun EditsDialog(vm: TaggerViewModel, onDismiss: () -> Unit) {
    val edits by vm.recentEdits.collectAsState()
    val root = vm.root
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f).clip(RoundedCornerShape(24.dp)).background(SurfaceElevated2).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Past edits", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Tap one to edit it again", fontSize = 12.sp, color = TextTertiary)
                }
                Icon(Icons.Rounded.Close, "Close", tint = TextSecondary, modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (edits.isEmpty()) Text("Nothing saved yet.", color = TextTertiary, modifier = Modifier.padding(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(edits, key = { it.id }) { v ->
                    val folder = if (root != null && v.path.startsWith("$root/")) tagLabel(v.path.removePrefix("$root/").substringBeforeLast('/', "")) else v.folderName
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (v.id == vm.current?.id) AccentSoft else SurfaceElevated3)
                            .clickable { vm.editPast(v); onDismiss() }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.app.xvideoplayer.ui.components.VideoThumbnail(v, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.title, fontSize = 13.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(folder, fontSize = 12.sp, color = AccentBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
