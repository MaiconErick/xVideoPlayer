package com.app.xvideoplayer.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.model.RepeatMode
import com.app.xvideoplayer.ui.components.SheetAction
import com.app.xvideoplayer.ui.components.qualityLabel
import com.app.xvideoplayer.ui.theme.*
import androidx.compose.ui.draw.alpha
import com.app.xvideoplayer.util.OrientationHandoff
import com.app.xvideoplayer.util.PipState
import com.app.xvideoplayer.util.SPEED_PRESETS
import com.app.xvideoplayer.util.formatDuration
import com.app.xvideoplayer.util.formatSpeed
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    videoId: Long, videoUri: String, source: String,
    onBack: () -> Unit, onNavigateToSubtitleStyle: () -> Unit,
    onOpenCollage: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as Activity
    val haptics = LocalHapticFeedback.current
    val mediaInfo by viewModel.mediaInfo.collectAsState()
    val subStyle by viewModel.subtitleStyle.collectAsState()
    val subText by viewModel.player.currentSubText.collectAsState()
    var subLinger by remember { mutableStateOf(false) }
    var subDragX by remember { mutableStateOf(0f) }
    var subFlashDir by remember { mutableStateOf(0) }
    var subFlashStamp by remember { mutableStateOf(0L) }
    var gestureStyle by remember { mutableStateOf<SubtitleStyle?>(null) }
    var lookupLine by remember { mutableStateOf<String?>(null) }
    var lookupResume by remember { mutableStateOf(false) }
    LaunchedEffect(subText) { if (subText.isNotBlank()) subLinger = true else { delay(4000); subLinger = false } }
    val leave = { OrientationHandoff.prepareExit(activity); onBack() }

    val state by viewModel.playerState.collectAsState()
    val position by viewModel.displayPosition.collectAsState()
    val cache by viewModel.cache.collectAsState()
    val scrubPos by viewModel.scrubPosition.collectAsState()
    val settings by viewModel.playerSettings.collectAsState()
    val video by viewModel.currentVideo.collectAsState()
    val title by viewModel.title.collectAsState()
    val starred by viewModel.starred.collectAsState()
    val showControls by viewModel.showControls.collectAsState()
    val locked by viewModel.locked.collectAsState()
    val ab by viewModel.abState.collectAsState()
    val overlayGroups by viewModel.overlayGroups.collectAsState()
    val includeSub by viewModel.screenshotIncludeSub.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val volumeLevel by viewModel.volumeLevel.collectAsState()
    val hasPrev by viewModel.hasPrev.collectAsState()
    val hasNext by viewModel.hasNext.collectAsState()
    val rotation by viewModel.rotation.collectAsState()
    val inPip by PipState.inPip.collectAsState()
    val zoom by viewModel.zoom.collectAsState()
    val scaleMode by viewModel.scaleMode.collectAsState()
    val aspect by viewModel.aspect.collectAsState()
    val collageShots by viewModel.collage.shots.collectAsState()
    val fileActions = com.app.xvideoplayer.ui.components.rememberFileActions()
    val openTrim = com.app.xvideoplayer.ui.components.LocalOpenTrim.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var speedAtGestureStart by remember { mutableFloatStateOf(1f) }
    var speedGestureAccum by remember { mutableFloatStateOf(0f) }

    var sheet by remember { mutableStateOf<String?>(null) }
    var showRemaining by remember(settings.showRemainingTime) { mutableStateOf(settings.showRemainingTime) }

    // gesture feedback state
    var dragKind by remember { mutableStateOf<DragKind?>(null) }
    var seekOrigin by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableLongStateOf(0L) }
    var tapSide by remember { mutableIntStateOf(0) }
    var tapSeconds by remember { mutableIntStateOf(0) }
    var tapStamp by remember { mutableLongStateOf(0L) }
    var speedBoosting by remember { mutableStateOf(false) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val zones by viewModel.tapZones.collectAsState()
    var editingZones by remember { mutableStateOf(false) }
    var lastSeekMs by remember { mutableLongStateOf(10_000L) }
    val filters by viewModel.filters.collectAsState()
    val compareSplit by viewModel.compareSplit.collectAsState()
    var keepAsk by remember { mutableStateOf(false) }

    fun runZone(z: com.app.xvideoplayer.data.model.TapZone) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (z.action) {
            com.app.xvideoplayer.data.model.ZoneAction.SEEK_BACK, com.app.xvideoplayer.data.model.ZoneAction.SEEK_FORWARD -> {
                val dir = if (z.action == com.app.xvideoplayer.data.model.ZoneAction.SEEK_BACK) -1 else 1
                lastSeekMs = z.seconds * 1000L
                viewModel.seekRelative(dir * lastSeekMs)
                tapSeconds = if (tapSide == dir) tapSeconds + z.seconds else z.seconds
                tapSide = dir; tapStamp = System.nanoTime()
            }
            com.app.xvideoplayer.data.model.ZoneAction.PLAY_PAUSE -> viewModel.togglePlayPause()
            com.app.xvideoplayer.data.model.ZoneAction.NEXT -> viewModel.playNext()
            com.app.xvideoplayer.data.model.ZoneAction.PREVIOUS -> viewModel.playPrevious()
            com.app.xvideoplayer.data.model.ZoneAction.CONTROLS -> viewModel.toggleControls()
            com.app.xvideoplayer.data.model.ZoneAction.MUTE -> viewModel.toggleMute()
            com.app.xvideoplayer.data.model.ZoneAction.SCREENSHOT -> viewModel.takeScreenshot(includeSub)
            com.app.xvideoplayer.data.model.ZoneAction.REPLAY_SUBS -> viewModel.replayWithSubs()
            com.app.xvideoplayer.data.model.ZoneAction.SUBTITLES -> viewModel.toggleSubtitles()
            com.app.xvideoplayer.data.model.ZoneAction.SPEED_UP -> viewModel.nudgeSpeed(0.25f)
            com.app.xvideoplayer.data.model.ZoneAction.SPEED_DOWN -> viewModel.nudgeSpeed(-0.25f)
            com.app.xvideoplayer.data.model.ZoneAction.LOCK -> { viewModel.toggleLock(); lockHintVisible = true }
            com.app.xvideoplayer.data.model.ZoneAction.NONE -> Unit
        }
    }

    LaunchedEffect(Unit) { viewModel.start(videoId, videoUri, source) }
    LaunchedEffect(Unit) { viewModel.messages.collect { message = it } }
    LaunchedEffect(message) { if (message != null) { delay(1800); message = null } }
    LaunchedEffect(tapStamp) { if (tapStamp != 0L) { delay(750); tapSide = 0; tapSeconds = 0 } }
    LaunchedEffect(lockHintVisible) { if (lockHintVisible) { delay(2200); lockHintVisible = false } }

    // ── Orientation: follow the video's shape (portrait videos stay portrait) ──
    val w = if (state.videoWidth > 0) state.videoWidth else video?.width ?: 0
    val h = if (state.videoHeight > 0) state.videoHeight else video?.height ?: 0
    val videoIsPortrait: Boolean? = if (w > 0 && h > 0) h > w else null
    val desiredOrientation = when (rotation) {
        RotationOverride.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        RotationOverride.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        RotationOverride.AUTO -> if (!settings.autoRotate) null else videoIsPortrait?.let {
            if (it) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    LaunchedEffect(desiredOrientation) { desiredOrientation?.let { activity.requestedOrientation = it } }

    // ── Window: immersive, cutout, keep-awake; undone on exit ──
    DisposableEffect(Unit) {
        OrientationHandoff.cancel()
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        val oldCutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = oldCutout
            }
            PipState.allowed = false
            if (Build.VERSION.SDK_INT >= 31) runCatching { activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            // keep the current orientation until the user physically turns the device (no snap-back)
            OrientationHandoff.release(activity)
        }
    }
    LaunchedEffect(settings.keepScreenOn, state.isPlaying) {
        if (settings.keepScreenOn && state.isPlaying) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(brightness) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
        }
    }

    // ── Picture-in-picture ──
    LaunchedEffect(settings.pipEnabled, state.isPlaying, w, h) {
        val allowed = settings.pipEnabled && state.isPlaying
        PipState.allowed = allowed
        val (rw, rh) = if (w > 0 && h > 0) w to h else 16 to 9
        val ratio = (rw.toFloat() / rh).coerceIn(1f / 2.39f, 2.39f)
        PipState.aspectWidth = (ratio * 1000).toInt(); PipState.aspectHeight = 1000
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder().setAspectRatio(Rational(PipState.aspectWidth, PipState.aspectHeight))
                    .setAutoEnterEnabled(allowed).setSeamlessResizeEnabled(true).build()
            )
        }
    }

    // ── Lifecycle: pause when really hidden (not when shrinking to PiP) ──
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> viewModel.onStopped()
                Lifecycle.Event.ON_RESUME -> viewModel.refreshVolumeLevel()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(settings.pauseOnHeadsetDisconnect) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { if (settings.pauseOnHeadsetDisconnect) viewModel.player.pause() }
        }
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    BackHandler {
        when {
            locked -> { lockHintVisible = true }
            else -> leave()
        }
    }

    // ── Gamepad: the mapped feature runs here (Activity.dispatchKeyEvent → GamepadRouter) ──
    val doLookup: () -> Unit = {
        val l = viewModel.lookupLine()
        if (l == null) message = "No subtitle text yet"
        else { lookupResume = state.isPlaying; viewModel.player.pause(); lookupLine = l }
    }
    val perform by rememberUpdatedState<(com.app.xvideoplayer.data.model.PadFeature) -> Unit> { f ->
        val step = settings.doubleTapSeekIncrement
        when (f) {
            com.app.xvideoplayer.data.model.PadFeature.PLAY_PAUSE -> viewModel.togglePlayPause()
            com.app.xvideoplayer.data.model.PadFeature.SEEK_BACK -> viewModel.seekRelative(-step)
            com.app.xvideoplayer.data.model.PadFeature.SEEK_FORWARD -> viewModel.seekRelative(step)
            com.app.xvideoplayer.data.model.PadFeature.PREVIOUS -> viewModel.playPrevious()
            com.app.xvideoplayer.data.model.PadFeature.NEXT -> viewModel.playNext()
            com.app.xvideoplayer.data.model.PadFeature.SPEED_UP -> viewModel.nudgeSpeed(0.25f)
            com.app.xvideoplayer.data.model.PadFeature.SPEED_DOWN -> viewModel.nudgeSpeed(-0.25f)
            com.app.xvideoplayer.data.model.PadFeature.FRAME_BACK -> viewModel.seekByFrames(-1)
            com.app.xvideoplayer.data.model.PadFeature.FRAME_FORWARD -> viewModel.seekByFrames(1)
            com.app.xvideoplayer.data.model.PadFeature.VOLUME_UP -> { viewModel.setVolumeLevel(volumeLevel + 0.05f); message = "Volume ${((volumeLevel + 0.05f).coerceAtMost(2f) * 100).toInt()}%" }
            com.app.xvideoplayer.data.model.PadFeature.VOLUME_DOWN -> { viewModel.setVolumeLevel(volumeLevel - 0.05f); message = "Volume ${((volumeLevel - 0.05f).coerceAtLeast(0f) * 100).toInt()}%" }
            com.app.xvideoplayer.data.model.PadFeature.MUTE -> viewModel.toggleMute()
            com.app.xvideoplayer.data.model.PadFeature.BRIGHTNESS_UP -> viewModel.setBrightness(viewModel.currentBrightness() + 0.05f)
            com.app.xvideoplayer.data.model.PadFeature.BRIGHTNESS_DOWN -> viewModel.setBrightness(viewModel.currentBrightness() - 0.05f)
            com.app.xvideoplayer.data.model.PadFeature.ZOOM_MODE -> viewModel.cycleScaleMode()
            com.app.xvideoplayer.data.model.PadFeature.ROTATE -> viewModel.cycleRotation()
            com.app.xvideoplayer.data.model.PadFeature.SUB_TOGGLE -> viewModel.toggleSubtitles()
            com.app.xvideoplayer.data.model.PadFeature.SUB_TRACK -> viewModel.cycleSubtitleTrack()
            com.app.xvideoplayer.data.model.PadFeature.SUB_NEXT_LINE -> viewModel.subtitleSeek(1)
            com.app.xvideoplayer.data.model.PadFeature.SUB_PREV_LINE -> viewModel.subtitleSeek(-1)
            com.app.xvideoplayer.data.model.PadFeature.SUB_UP -> viewModel.saveSubtitleStyle(subStyle.copy(positionY = (subStyle.positionY - 0.02f).coerceIn(0.3f, 1f)))
            com.app.xvideoplayer.data.model.PadFeature.SUB_DOWN -> viewModel.saveSubtitleStyle(subStyle.copy(positionY = (subStyle.positionY + 0.02f).coerceIn(0.3f, 1f)))
            com.app.xvideoplayer.data.model.PadFeature.REPLAY_SUBS -> viewModel.replayWithSubs()
            com.app.xvideoplayer.data.model.PadFeature.LOOKUP -> doLookup()
            com.app.xvideoplayer.data.model.PadFeature.CONTROLS -> viewModel.toggleControls()
            com.app.xvideoplayer.data.model.PadFeature.SCREENSHOT -> viewModel.takeScreenshot(includeSub)
            com.app.xvideoplayer.data.model.PadFeature.LOCK -> { viewModel.toggleLock(); lockHintVisible = true }
            com.app.xvideoplayer.data.model.PadFeature.BACK -> leave()
        }
    }
    DisposableEffect(Unit) {
        com.app.xvideoplayer.data.model.GamepadRouter.handler = { f -> perform(f) }
        onDispose { com.app.xvideoplayer.data.model.GamepadRouter.handler = null }
    }

    // ── Dual-screen handhelds: video here, controls on the second screen ──
    var onSecondScreen by remember { mutableStateOf(false) }
    var mainMenus by remember { mutableStateOf(false) }   // "Menus on main screen": the regular overlay shows once, then hides again
    LaunchedEffect(showControls) { if (!showControls) mainMenus = false }
    val doLookupLatest by rememberUpdatedState<() -> Unit>({ doLookup() })
    val subtitlePickerLaunchRef = remember { mutableStateOf<() -> Unit>({}) }
    val subtitlePickerLaunch: () -> Unit = { subtitlePickerLaunchRef.value() }
    DisposableEffect(settings.dualScreen) {
        val display = if (settings.dualScreen) findSecondaryDisplay(context) else null
        var panel: SecondScreen? = null
        if (display != null) {
            panel = SecondScreen(activity, display) {
                // not XVideoPlayerTheme: it casts its context to an Activity, which a Presentation window is not
                MaterialTheme(colorScheme = darkColorScheme()) {
                    RemotePanel(
                        viewModel,
                        RemoteActions(
                            onClose = { leave() },
                            onMainMenus = { mainMenus = true; viewModel.showControls() },
                            onStopDual = { viewModel.setDualScreen(false) },
                            onLoadSub = { subtitlePickerLaunch() },
                            onLookup = { doLookupLatest() },
                            onRename = { renaming = true },
                            onDelete = { if (Build.VERSION.SDK_INT >= 30) deleteCurrent(viewModel, fileActions, hasNext, leave) else confirmDelete = true },
                            onCut = { video?.let { v -> viewModel.player.pause(); openTrim(v) } },
                            onSavePicture = { keepAsk = true },
                            onTapZones = { viewModel.player.pause(); editingZones = true },
                            canEditFile = video != null
                        )
                    )
                }
            }
            onSecondScreen = runCatching { panel.show() }.isSuccess
        }
        onDispose { runCatching { panel?.dismiss() }; onSecondScreen = false }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::addSubtitleFromUri) }
    subtitlePickerLaunchRef.value = { subtitlePicker.launch(arrayOf("*/*")) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MpvSurface(viewModel.player, Modifier.fillMaxSize(), onView = { viewModel.surfaceView = java.lang.ref.WeakReference(it) })

        if (!inPip) {
            PlayerGestureLayer(
                enabled = !locked,
                instantTap = tapSide != 0,
                onTap = {
                    if (locked) lockHintVisible = !lockHintVisible
                    else if (tapSide != 0) { // keep tapping the same side to add more
                        viewModel.seekRelative(tapSide * lastSeekMs)
                        tapSeconds += (lastSeekMs / 1000).toInt(); tapStamp = System.nanoTime()
                    } else viewModel.toggleControls()
                },
                onDoubleTap = { x, y -> zones.lastOrNull { it.contains(x, y) }?.let { runZone(it) } },
                onLongPressStart = { if (state.isPlaying) { speedBoosting = true; viewModel.startSpeedBoost(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) } },
                onLongPressEnd = { if (speedBoosting) { speedBoosting = false; viewModel.endSpeedBoost() } },
                onDragStart = { kind ->
                    dragKind = kind
                    when (kind) {
                        DragKind.SEEK -> { seekOrigin = viewModel.player.position.value; seekTarget = seekOrigin; viewModel.beginScrub() }
                        DragKind.BRIGHTNESS -> viewModel.setBrightness(viewModel.currentBrightness())
                        DragKind.VOLUME -> viewModel.refreshVolumeLevel()
                        DragKind.SPEED -> { speedAtGestureStart = state.playbackSpeed; speedGestureAccum = 0f }
                        DragKind.ZOOM -> Unit
                    }
                },
                onPinch = { f, dx, dy -> viewModel.pinch(f, dx, dy) },
                subZoneTop = if (subLinger && state.subtitleTracks.any { it.isSelected }) maxOf(0.3f, subStyle.positionY - 0.2f) else -1f,
                onSubSwipe = { dir -> viewModel.subtitleSeek(dir); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); subFlashDir = dir; subFlashStamp = System.nanoTime() },
                onSubDrag = { subDragX = it },
                onSubHeight = { dy ->
                    val ns = (gestureStyle ?: subStyle).let { it.copy(positionY = (it.positionY + dy).coerceIn(0.3f, 1f)) }
                    gestureStyle = ns; viewModel.previewSubtitleStyle(ns); message = "Subtitle height ${(ns.positionY * 100).toInt()}%"
                },
                onSubPinch = { f ->
                    val ns = (gestureStyle ?: subStyle).let { it.copy(fontSize = (it.fontSize * f).coerceIn(12f, 56f)) }
                    gestureStyle = ns; viewModel.previewSubtitleStyle(ns); message = "Subtitle size ${ns.fontSize.toInt()}"
                },
                onSubEnd = { gestureStyle?.let { viewModel.saveSubtitleStyle(it) }; gestureStyle = null },
                onDrag = { kind, f ->
                    val sens = settings.swipeGestureSensitivity
                    when (kind) {
                        DragKind.SEEK -> {
                            // a full-width swipe covers ~2 minutes (or the whole video if shorter)
                            val span = state.duration.coerceIn(30_000L, 120_000L)
                            seekTarget = (seekTarget + (f * span * sens).toLong()).coerceIn(0L, state.duration.coerceAtLeast(0L))
                            viewModel.scrubTo(seekTarget)
                        }
                        DragKind.BRIGHTNESS -> viewModel.setBrightness(viewModel.currentBrightness() + f * 1.3f * sens)
                        DragKind.VOLUME -> viewModel.setVolumeLevel(volumeLevel + f * 1.6f * sens)
                        DragKind.SPEED -> {
                            // a full-height two-finger swipe spans about 3x of speed, in 0.05 steps
                            speedGestureAccum += f * 3f * sens
                            val sp = (((speedAtGestureStart + speedGestureAccum) * 20).toInt() / 20f).coerceIn(0.25f, 4f)
                            if (sp != state.playbackSpeed) viewModel.setPlaybackSpeed(sp)
                        }
                        DragKind.ZOOM -> Unit
                    }
                },
                onDragEnd = { kind ->
                    if (kind == DragKind.SEEK) viewModel.endScrub()
                    dragKind = null
                }
            )

            SubSwipeFeedback(subDragX, subFlashDir, subFlashStamp, subStyle.positionY)
            DoubleTapSeekFeedback(side = tapSide, seconds = tapSeconds, visible = tapSide != 0)

            // ── Controls ──
            ControlsOverlay(
                visible = showControls && !locked && (!onSecondScreen || mainMenus),
                title = title,
                subtitle = listOfNotNull(
                    video?.copy(width = w, height = h)?.qualityLabel(settings.resolutionLabel),
                    when {
                        state.activeDecoder.isBlank() -> null
                        state.activeDecoder == "no" -> "SW"
                        else -> "HW"
                    },
                    if (state.playbackSpeed != 1f) "${state.playbackSpeed}×" else null
                ).joinToString("  ·  ") + if (mediaInfo.isNotBlank()) "\n$mediaInfo" else "",
                state = state, position = position, cache = cache, ab = ab,
                showRemaining = showRemaining, onToggleRemaining = { showRemaining = !showRemaining },
                hasPrev = hasPrev, hasNext = hasNext, rotation = rotation,
                onBack = leave,
                onPlayPause = viewModel::togglePlayPause,
                onPrev = viewModel::playPrevious, onNext = viewModel::playNext,
                onScrubStart = { seekOrigin = viewModel.player.position.value; viewModel.beginScrub(); dragKind = DragKind.SEEK },
                onScrub = { seekTarget = it; viewModel.scrubTo(it) },
                onScrubEnd = { viewModel.endScrub(); dragKind = null },
                onLock = { viewModel.toggleLock(); lockHintVisible = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onRotate = viewModel::cycleRotation,
                onReplay = viewModel::replayWithSubs,
                onLookup = doLookup,
                onScale = { sheet = "zoom" },
                onRename = { renaming = true },
                starred = starred, onStar = if (video?.source == com.app.xvideoplayer.data.model.VideoSource.LOCAL) viewModel::toggleStar else null,
                onCut = { video?.let { v -> viewModel.player.pause(); openTrim(v) } },
                onDelete = { if (Build.VERSION.SDK_INT >= 30) deleteCurrent(viewModel, fileActions, hasNext, leave) else confirmDelete = true },
                canEditFile = video != null,
                tools = ToolStripState(
                    capture = overlayGroups.any { (it.id == "screenshot" || it.id == "frame_control") && it.enabled },
                    abRepeat = overlayGroups.any { it.id == "ab_repeat" && it.enabled },
                    includeSub = includeSub, ab = ab, collageCount = collageShots.size
                ),
                onScreenshot = { viewModel.takeScreenshot(includeSub) },
                onCollage = viewModel::addToCollage,
                onToggleSub = { viewModel.setScreenshotIncludeSub(!includeSub) },
                onFrames = viewModel::seekByFrames,
                onSetA = viewModel::setPointA, onSetB = viewModel::setPointB,
                onToggleAb = viewModel::toggleABRepeat, onClearAb = viewModel::clearAbLoop,
                onRepeat = viewModel::cycleRepeatMode,
                onSpeed = { sheet = "speed" },
                onTracks = { sheet = "tracks" },
                onMore = { sheet = "more" },
                onPip = if (Build.VERSION.SDK_INT >= 26) { {
                    runCatching {
                        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(PipState.aspectWidth, PipState.aspectHeight)).build())
                    }
                } } else null,
                onInteract = viewModel::poke
            )

            // ── Lock button (only thing that works while locked) ──
            AnimatedVisibility(
                visible = locked && lockHintVisible,
                enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircleIconButton(Icons.Rounded.Lock, "Unlock", onClick = { viewModel.toggleLock(); lockHintVisible = false }, size = 60, iconSize = 28, highlighted = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap to unlock", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── Center feedback ──
            Box(Modifier.align(Alignment.Center)) {
                AnimatedContent(
                    targetState = when {
                        dragKind == DragKind.SEEK || scrubPos != null -> "seek"
                        dragKind == DragKind.ZOOM -> "zoom"
                        dragKind == DragKind.SPEED -> "speed"
                        speedBoosting -> "boost"
                        else -> ""
                    },
                    transitionSpec = { (fadeIn(tween(120)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(160)) },
                    label = "center"
                ) { which ->
                    when (which) {
                        "seek" -> SeekReadout(scrubPos ?: seekTarget, seekOrigin, state.duration)
                        "boost" -> PillMessage("2× speed", Icons.Rounded.FastForward)
                        "zoom" -> PillMessage("${(zoom * 100).toInt()}%", Icons.Rounded.ZoomIn)
                        "speed" -> PillMessage("%.2f×".format(state.playbackSpeed), Icons.Rounded.Speed)
                        else -> Spacer(Modifier.size(1.dp))
                    }
                }
            }
            AnimatedVisibility(
                visible = dragKind == DragKind.BRIGHTNESS, enter = fadeIn() + slideInHorizontally { -it / 2 }, exit = fadeOut() + slideOutHorizontally { -it / 2 },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
            ) {
                val b = if (brightness < 0) 0.5f else brightness
                LevelIndicator(if (b > 0.5f) Icons.Rounded.BrightnessHigh else Icons.Rounded.BrightnessLow, b, 1f, "${(b * 100).toInt()}")
            }
            AnimatedVisibility(
                visible = dragKind == DragKind.VOLUME, enter = fadeIn() + slideInHorizontally { it / 2 }, exit = fadeOut() + slideOutHorizontally { it / 2 },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
            ) {
                LevelIndicator(
                    when { volumeLevel <= 0.01f -> Icons.Rounded.VolumeOff; volumeLevel < 0.5f -> Icons.Rounded.VolumeDown; else -> Icons.Rounded.VolumeUp },
                    volumeLevel, 2f, "${(volumeLevel * 100).toInt()}"
                )
            }
        }

        // buffering spinner (delayed so quick seeks don't flash it)
        var showSpinner by remember { mutableStateOf(false) }
        LaunchedEffect(state.isBuffering, state.isFileLoaded, state.error) {
            showSpinner = false
            if (state.error != null) return@LaunchedEffect
            if (!state.isFileLoaded) showSpinner = true            // opening: show right away
            else if (state.isBuffering) { delay(350); showSpinner = true } // mid-play stalls: skip quick blips
        }
        AnimatedVisibility(showSpinner, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (com.app.xvideoplayer.ui.theme.AppPalette.current.halloween) com.app.xvideoplayer.ui.theme.halloween.CauldronLoader(52.dp)
                else CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(46.dp))
                if (cache.isNetwork && cache.speedBytesPerSec > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("↓ ${cache.speedBytesPerSec.formatSpeed()}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        state.error?.let { err ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(22.dp)).background(SurfaceElevated2).border(1.dp, Border, RoundedCornerShape(22.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text(err, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = leave, colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated4, contentColor = TextPrimary)) { Text("Go back") }
            }
        }

        AnimatedVisibility(
            visible = message != null && !inPip,
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
        ) { PillMessage(message ?: "") }

        // collected screenshots waiting to become a collage
        AnimatedVisibility(
            visible = collageShots.isNotEmpty() && !inPip,
            enter = fadeIn() + slideInHorizontally { it }, exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (showControls) 128.dp else 24.dp)
        ) {
            Row(
                Modifier.clip(CircleShape).background(Color(0xE6151518)).border(1.dp, Color(0x33FFFFFF), CircleShape).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Collections, null, tint = AccentBright, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${collageShots.size} screenshot${if (collageShots.size == 1) "" else "s"}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(CircleShape).background(Accent).clickable { viewModel.player.pause(); onOpenCollage() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text("Done", color = OnAccent, style = MaterialTheme.typography.labelLarge)
                }
                CircleIconButton(Icons.Rounded.Close, "Discard screenshots", { viewModel.collage.clear() }, size = 34, iconSize = 18)
            }
        }

        PlayerPanel(visible = sheet == "zoom", onDismiss = { sheet = null }) {
            ZoomPanel(
                scaleMode = scaleMode, onScale = viewModel::setScaleMode,
                aspect = aspect, onAspect = viewModel::setAspect,
                zoom = zoom, onResetZoom = viewModel::resetZoom
            )
        }
        PlayerPanel(visible = sheet == "speed", onDismiss = { sheet = null }) {
            SpeedPanel(state.playbackSpeed, onSelect = viewModel::setPlaybackSpeed)
        }
        PlayerPanel(visible = sheet == "tracks", onDismiss = { sheet = null }, width = 300.dp) {
            TracksPanel(
                state = state,
                onAudio = { viewModel.selectAudioTrack(it) }, onSub = { viewModel.selectSubtitleTrack(it) }, onSubOff = viewModel::disableSubtitles,
                onLoadSub = { sheet = null; subtitlePicker.launch(arrayOf("*/*")) },
                onStyle = { sheet = "substyle" },
                hideSdh = settings.hideSdh, onHideSdh = viewModel::setHideSdh, onLang = viewModel::pickSubtitleLanguage
            )
        }
        // live subtitle style: the video stays visible and sliders apply while you drag
        PlayerPanel(visible = sheet == "substyle", onDismiss = { sheet = null }, dim = false, width = 340.dp, portraitMaxFraction = 0.5f) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitle style", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.saveSubtitleStyle(SubtitleStyle()) }) { Text("Reset", color = Accent) }
                TextButton(onClick = { sheet = null }) { Text("Done", color = Accent) }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                com.app.xvideoplayer.ui.settings.SubtitleStyleGroups(subStyle) { viewModel.saveSubtitleStyle(it) }
            }
        }
        PlayerPanel(visible = sheet == "more", onDismiss = { sheet = null }) {
            MorePanel(
                state = state, overlayGroups = overlayGroups,
                cache = cache, cacheAhead = settings.cacheAhead, onCacheAhead = viewModel::setCacheAhead,
                onDecoder = viewModel::switchDecoder, onToggleTool = viewModel::toggleOverlayGroup,
                onScreenshot = { sheet = null; viewModel.takeScreenshot(includeSub) },
                onPicture = { sheet = "adjust" },
                onTapZones = { sheet = null; viewModel.player.pause(); editingZones = true },
                hasSecondDisplay = remember { findSecondaryDisplay(context) != null }, dualOn = settings.dualScreen,
                onToggleDual = { sheet = null; viewModel.setDualScreen(!settings.dualScreen) }
            )
        }
        // picture adjustments: the video stays visible, sliders apply while dragging
        PlayerPanel(visible = sheet == "adjust", onDismiss = {
            sheet = null; viewModel.setCompareSplit(-1f); if (viewModel.filtersDirty) keepAsk = true
        }, dim = false, width = 340.dp, portraitMaxFraction = 0.55f) {
            PictureAdjustPanelContent(
                f = filters, compare = compareSplit >= 0f, onChange = viewModel::setFilters,
                onCompare = { viewModel.setCompareSplit(if (it) 0.5f else -1f) },
                onReset = { viewModel.setFilters(com.app.xvideoplayer.data.model.VideoFilters()) },
                onSave = { keepAsk = true },
                onClose = { sheet = null; viewModel.setCompareSplit(-1f); if (viewModel.filtersDirty) keepAsk = true }
            )
        }
        if (compareSplit >= 0f) CompareDivider(compareSplit, viewModel::setCompareSplit)
    }

    if (editingZones) TapZoneEditor(
        zones = zones, defaultSeconds = (settings.doubleTapSeekIncrement / 1000).toInt(),
        onChange = viewModel::saveTapZones, onClose = { editingZones = false }
    )
    if (keepAsk) KeepPictureDialog(
        folderVideos = viewModel.folderVideos(),
        onSave = { scope, vids -> viewModel.saveFilters(scope, vids); keepAsk = false },
        onDiscard = { viewModel.discardFilters(); keepAsk = false },
        onDismiss = { keepAsk = false }
    )
    lookupLine?.let { line ->
        androidx.activity.compose.BackHandler { lookupLine = null; if (lookupResume) viewModel.player.play() }
        WordLookupOverlay(line, onClose = { lookupLine = null; if (lookupResume) viewModel.player.play() }, lookup = viewModel::lookupWord)
    }

    if (renaming) video?.let { v ->
        com.app.xvideoplayer.ui.components.RenameDialog(
            currentName = v.title,
            onConfirm = { name -> renaming = false; fileActions.rename(v, name) { viewModel.reloadCurrentInfo() } },
            onDismiss = { renaming = false }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this video?") },
            text = { Text("\"$title\" will be deleted from the device.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; deleteCurrent(viewModel, fileActions, hasNext, leave) }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceElevated2
        )
    }
}

/** Deletes the playing file, then moves on to the next video in the folder (or leaves). */
private fun deleteCurrent(viewModel: PlayerViewModel, actions: com.app.xvideoplayer.ui.components.FileActions, hasNext: Boolean, onBack: () -> Unit) {
    val v = viewModel.currentVideo.value ?: return
    viewModel.player.pause()
    actions.delete(listOf(v)) { if (hasNext) viewModel.playNext(removeCurrent = true) else onBack() }
}

data class ToolStripState(val capture: Boolean, val abRepeat: Boolean, val includeSub: Boolean, val ab: ABRepeatState, val collageCount: Int)

// ── Controls overlay ────────────────────────────────────────────────────

@Composable
private fun ControlsOverlay(
    visible: Boolean, title: String, subtitle: String,
    state: com.app.xvideoplayer.player.PlayerState, position: Long, cache: com.app.xvideoplayer.player.CacheState, ab: ABRepeatState,
    showRemaining: Boolean, onToggleRemaining: () -> Unit,
    hasPrev: Boolean, hasNext: Boolean, rotation: RotationOverride,
    onBack: () -> Unit, onPlayPause: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit,
    onScrubStart: () -> Unit, onScrub: (Long) -> Unit, onScrubEnd: () -> Unit,
    onLock: () -> Unit, onRotate: () -> Unit, onReplay: () -> Unit, onLookup: () -> Unit, onScale: () -> Unit, onRepeat: () -> Unit,
    onSpeed: () -> Unit, onTracks: () -> Unit, onMore: () -> Unit, onPip: (() -> Unit)?,
    onInteract: () -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit, canEditFile: Boolean, onCut: () -> Unit,
    starred: Boolean, onStar: (() -> Unit)?,
    tools: ToolStripState,
    onScreenshot: () -> Unit, onCollage: () -> Unit, onToggleSub: () -> Unit, onFrames: (Int) -> Unit,
    onSetA: () -> Unit, onSetB: () -> Unit, onToggleAb: () -> Unit, onClearAb: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        // top
        AnimatedVisibility(
            visible, enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(Modifier.fillMaxWidth().background(ScrimTop)) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 8.dp, vertical = 8.dp).padding(bottom = if (tools.capture || tools.abRepeat) 64.dp else 28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(Icons.Rounded.ArrowBack, "Back", onBack)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (onStar != null) CircleIconButton(
                        if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (starred) "Remove from Favorites" else "Add to Favorites",
                        { onInteract(); onStar() }, tint = if (starred) com.app.xvideoplayer.ui.vault.StarGold else Color.White
                    )
                    CircleIconButton(Icons.Rounded.Subtitles, "Audio & subtitles", { onInteract(); onTracks() })
                    if (onPip != null) CircleIconButton(Icons.Rounded.PictureInPictureAlt, "Picture in picture", onPip)
                    CircleIconButton(Icons.Rounded.MoreVert, "More", { onInteract(); onMore() })
                }
                if (tools.capture || tools.abRepeat) {
                    ToolStrip(
                        tools, onInteract, onScreenshot, onCollage, onToggleSub, onFrames, onSetA, onSetB, onToggleAb, onClearAb,
                        Modifier.align(Alignment.BottomStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(start = 12.dp, end = 12.dp)
                    )
                }
            }
        }

        // center transport
        AnimatedVisibility(
            visible, enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.85f), exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                CircleIconButton(Icons.Rounded.SkipPrevious, "Previous video", onPrev, size = 52, iconSize = 30, tint = if (hasPrev) Color.White else Color(0x55FFFFFF))
                val playIcon = when { state.isEnded -> Icons.Rounded.Replay; state.isPlaying -> Icons.Rounded.Pause; else -> Icons.Rounded.PlayArrow }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color(0x66000000)).border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        playIcon,
                        transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
                        label = "play"
                    ) { icon -> Icon(icon, "Play/pause", tint = Color.White, modifier = Modifier.size(44.dp)) }
                }
                CircleIconButton(Icons.Rounded.SkipNext, "Next video", onNext, size = 52, iconSize = 30, tint = if (hasNext) Color.White else Color(0x55FFFFFF))
            }
        }

        // bottom
        AnimatedVisibility(
            visible, enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(Modifier.fillMaxWidth().background(ScrimBottom)) {
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(top = 36.dp, start = 16.dp, end = 16.dp, bottom = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(position.formatDuration(), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 48.dp))
                        SeekBar(
                            position = position, duration = state.duration, cachedRanges = cache.ranges, abA = ab.pointA, abB = ab.pointB,
                            onScrubStart = onScrubStart, onScrub = onScrub, onScrubEnd = onScrubEnd,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        Text(
                            if (showRemaining) "−${(state.duration - position).coerceAtLeast(0).formatDuration()}" else state.duration.formatDuration(),
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggleRemaining)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircleIconButton(Icons.Rounded.LockOpen, "Lock controls", onLock)
                        CircleIconButton(
                            when (rotation) { RotationOverride.AUTO -> Icons.Rounded.ScreenRotation; RotationOverride.LANDSCAPE -> Icons.Rounded.StayCurrentLandscape; RotationOverride.PORTRAIT -> Icons.Rounded.StayCurrentPortrait },
                            "Rotation", { onInteract(); onRotate() }, highlighted = rotation != RotationOverride.AUTO
                        )
                        CircleIconButton(Icons.Rounded.AspectRatio, "Zoom & aspect", { onInteract(); onScale() })
                        CircleIconButton(Icons.Rounded.Replay5, "Replay with subtitles", { onInteract(); onReplay() })
                        CircleIconButton(Icons.Rounded.Translate, "Look up words in the subtitle", { onInteract(); onLookup() })
                        CircleIconButton(
                            if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                            { onInteract(); onRepeat() }, highlighted = state.repeatMode != RepeatMode.OFF
                        )
                        if (canEditFile) {
                            CircleIconButton(Icons.Rounded.ContentCut, "Cut (lossless)", { onInteract(); onCut() })
                            CircleIconButton(Icons.Rounded.DriveFileRenameOutline, "Rename", { onInteract(); onRename() })
                            CircleIconButton(Icons.Rounded.DeleteOutline, "Delete", { onInteract(); onDelete() })
                        }
                        Spacer(Modifier.weight(1f))
                        if (cache.isNetwork) {
                            NetworkStats(cache)
                            Spacer(Modifier.width(10.dp))
                        }
                        Box(
                            Modifier.clip(CircleShape).background(if (state.playbackSpeed != 1f) OverlayButtonActive else OverlayButton)
                                .clickable { onInteract(); onSpeed() }.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text("${state.playbackSpeed}×".replace(".0×", "×"), color = if (state.playbackSpeed != 1f) AccentBright else Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

// ── Sheets ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPanel(current: Float, onSelect: (Float) -> Unit) {
    run {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playback speed", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                Text("%.2f×".format(current), style = MaterialTheme.typography.titleMedium, color = Accent)
            }
            Spacer(Modifier.height(10.dp))
            var fine by remember { mutableFloatStateOf(current) }
            Slider(
                value = fine, onValueChange = { fine = (it * 20).toInt() / 20f; onSelect(fine) }, valueRange = 0.25f..4f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = SurfaceElevated4)
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEED_PRESETS.forEach { s ->
                    com.app.xvideoplayer.ui.components.AppChip("${s}×".replace(".0×", "×"), selected = current == s) { fine = s; onSelect(s) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TracksPanel(
    state: com.app.xvideoplayer.player.PlayerState,
    onAudio: (Int) -> Unit, onSub: (Int) -> Unit, onSubOff: () -> Unit,
    onLoadSub: () -> Unit, onStyle: () -> Unit,
    hideSdh: Boolean, onHideSdh: (Boolean) -> Unit, onLang: (String) -> Unit
) {
    run {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetSection("Audio")
            if (state.audioTracks.isEmpty()) Text("No audio tracks", color = TextTertiary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            state.audioTracks.forEach { t -> TrackRow(t.label, listOfNotNull(t.language, t.codec).joinToString(" · "), t.isSelected) { onAudio(t.id) } }
            Spacer(Modifier.height(8.dp))
            SheetSection("Subtitles")
            // quick picks: only the languages this video actually has are lit
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUB_LANG_CHIPS.forEach { (key, label) ->
                    val track = subtitleTrackFor(key, state.subtitleTracks)
                    Box(Modifier.alpha(if (track != null) 1f else 0.35f)) {
                        com.app.xvideoplayer.ui.components.AppChip(label, selected = track?.isSelected == true) { if (track != null) onLang(key) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TrackRow("Off", null, state.subtitleTracks.none { it.isSelected }) { onSubOff() }
            state.subtitleTracks.forEach { t ->
                TrackRow(t.label, listOfNotNull(t.language, t.codec, if (t.isExternal) "external" else null).joinToString(" · "), t.isSelected) { onSub(t.id) }
            }
            HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 6.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onHideSdh(!hideSdh) }.padding(horizontal = 22.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hide sound lines", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text("Skips (door slams), [music], -(thunder)", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = hideSdh, onCheckedChange = onHideSdh,
                    colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, uncheckedTrackColor = SurfaceElevated4, uncheckedBorderColor = BorderStrong)
                )
            }
            SheetAction(Icons.Rounded.FileOpen, "Load subtitle file…", onClick = onLoadSub)
            SheetAction(Icons.Rounded.FormatColorText, "Subtitle style", onClick = onStyle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MorePanel(
    state: com.app.xvideoplayer.player.PlayerState, overlayGroups: List<OverlayGroup>,
    cache: com.app.xvideoplayer.player.CacheState, cacheAhead: CacheAhead, onCacheAhead: (CacheAhead) -> Unit,
    onDecoder: (DecoderMode) -> Unit, onToggleTool: (String) -> Unit, onScreenshot: () -> Unit,
    onPicture: () -> Unit, onTapZones: () -> Unit, hasSecondDisplay: Boolean, dualOn: Boolean, onToggleDual: () -> Unit
) {
    run {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetAction(Icons.Rounded.CameraAlt, "Take screenshot", onClick = onScreenshot)
            SheetAction(Icons.Rounded.Tune, "Picture: brightness, contrast, vignette…", onClick = onPicture)
            SheetAction(Icons.Rounded.TouchApp, "Edit tap zones", onClick = onTapZones)
            if (hasSecondDisplay) SheetAction(
                Icons.Rounded.Devices, if (dualOn) "Stop using the second screen" else "Use the second screen for controls", onClick = onToggleDual
            )
            SheetSection("Decoder")
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecoderMode.entries.forEach { m ->
                    com.app.xvideoplayer.ui.components.AppChip(
                        when (m) { DecoderMode.AUTO -> "Auto"; DecoderMode.HARDWARE -> "Hardware"; DecoderMode.SOFTWARE -> "Software" },
                        selected = state.decoderMode == m
                    ) { onDecoder(m) }
                }
            }
            Text(
                "Now using: " + when { state.activeDecoder.isBlank() -> "—"; state.activeDecoder == "no" -> "software (CPU)"; else -> "hardware (${state.activeDecoder})" },
                color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
            )
            SheetSection("Cache ahead")
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CacheAhead.entries.forEach { m ->
                    com.app.xvideoplayer.ui.components.AppChip(m.label, selected = cacheAhead == m) { onCacheAhead(m) }
                }
            }
            Text(
                if (!cache.isNetwork) "Local file — read straight from storage, nothing to cache"
                else "Cached ahead: ${cache.aheadMs.formatDuration()}  ·  ↓ ${cache.speedBytesPerSec.formatSpeed()}",
                color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
            )
            SheetSection("On-screen tools")
            overlayGroups.filter { it.id != "frame_control" }.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { onToggleTool(g.id) }.padding(horizontal = 22.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (g.id) { "screenshot" -> Icons.Rounded.CameraAlt; "frame_control" -> Icons.Rounded.Animation; else -> Icons.Rounded.Repeat },
                        null, tint = TextSecondary, modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(18.dp))
                    Text(if (g.id == "screenshot") "Capture & frames" else g.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = g.enabled, onCheckedChange = { onToggleTool(g.id) },
                        colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, uncheckedTrackColor = SurfaceElevated4, uncheckedBorderColor = BorderStrong)
                    )
                }
            }
        }
    }
}

/** Download rate + how much is buffered past the playhead, shown for network videos only. */
@Composable
private fun NetworkStats(cache: com.app.xvideoplayer.player.CacheState) {
    Column(horizontalAlignment = Alignment.End) {
        Text("↓ ${cache.speedBytesPerSec.formatSpeed()}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text("${cache.aheadMs.formatDuration()} cached", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(title.uppercase(), color = TextTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun TrackRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) AccentSoft else Color.Transparent, label = "trackBg")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (selected) AccentBright else TextPrimary, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) Text(detail, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
        AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Rounded.Check, null, tint = Accent)
        }
    }
}

// ── Tool strip (under the top bar) ──────────────────────────────────────

@Composable
private fun ToolStrip(
    tools: ToolStripState, onInteract: () -> Unit,
    onScreenshot: () -> Unit, onCollage: () -> Unit, onToggleSub: () -> Unit, onFrames: (Int) -> Unit,
    onSetA: () -> Unit, onSetB: () -> Unit, onToggleAb: () -> Unit, onClearAb: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.padding(bottom = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        if (tools.capture) {
            ToolButton(Icons.Rounded.CameraAlt, "Screenshot") { onInteract(); onScreenshot() }
            ToolButton(Icons.Rounded.Collections, "Collage") { onInteract(); onCollage() }
            ToolButton(Icons.Rounded.Subtitles, if (tools.includeSub) "With subs" else "No subs", active = tools.includeSub) { onInteract(); onToggleSub() }
            ToolDivider()
            ToolButton(Icons.Rounded.FastRewind, "−10") { onInteract(); onFrames(-10) }
            ToolButton(Icons.Rounded.ChevronLeft, "−1") { onInteract(); onFrames(-1) }
            ToolButton(Icons.Rounded.ChevronRight, "+1") { onInteract(); onFrames(1) }
            ToolButton(Icons.Rounded.FastForward, "+10") { onInteract(); onFrames(10) }
        }
        if (tools.capture && tools.abRepeat) ToolDivider()
        if (tools.abRepeat) {
            val ab = tools.ab
            ToolButton(Icons.Rounded.Start, ab.pointA?.formatDuration()?.let { "A $it" } ?: "Set A", active = ab.pointA != null) { onInteract(); onSetA() }
            ToolButton(Icons.Rounded.LastPage, ab.pointB?.formatDuration()?.let { "B $it" } ?: "Set B", active = ab.pointB != null) { onInteract(); onSetB() }
            if (ab.pointA != null && ab.pointB != null) {
                ToolButton(if (ab.isActive) Icons.Rounded.PauseCircle else Icons.Rounded.Repeat, if (ab.isActive) "Looping" else "Loop", active = ab.isActive) { onInteract(); onToggleAb() }
            }
            if (ab.pointA != null) ToolButton(Icons.Rounded.Clear, "Clear") { onInteract(); onClearAb() }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        // dark base in both states so the label stays readable over bright video
        Modifier.height(36.dp).clip(CircleShape).background(if (active) Color(0xD91A0F0A) else Color(0x8C000000))
            .border(1.dp, if (active) Accent.copy(alpha = 0.6f) else Color(0x26FFFFFF), CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (active) AccentBright else Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (active) AccentBright else Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun ToolDivider() { Box(Modifier.padding(horizontal = 2.dp).size(width = 1.dp, height = 22.dp).background(Color(0x40FFFFFF))) }

// ── Zoom & aspect panel ─────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoomPanel(
    scaleMode: com.app.xvideoplayer.player.ScaleMode, onScale: (com.app.xvideoplayer.player.ScaleMode) -> Unit,
    aspect: String, onAspect: (String) -> Unit,
    zoom: Float, onResetZoom: () -> Unit
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SheetSection("Fit")
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.app.xvideoplayer.player.ScaleMode.entries.forEach { m ->
                com.app.xvideoplayer.ui.components.AppChip(m.label, selected = scaleMode == m) { onScale(m) }
            }
        }
        SheetSection("Aspect ratio")
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Original", "16:9" to "16:9", "4:3" to "4:3", "21:9" to "21:9", "1:1" to "1:1", "9:16" to "9:16").forEach { (v, l) ->
                com.app.xvideoplayer.ui.components.AppChip(l, selected = aspect == v) { onAspect(v) }
            }
        }
        SheetSection("Zoom")
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${(zoom * 100).toInt()}%  ·  pinch the video to zoom, drag with two fingers to move", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            com.app.xvideoplayer.ui.components.AppChip("Reset", selected = false, icon = Icons.Rounded.ZoomOutMap) { onResetZoom() }
        }
        Spacer(Modifier.height(8.dp))
    }
}
