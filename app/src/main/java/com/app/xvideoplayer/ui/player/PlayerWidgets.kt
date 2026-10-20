package com.app.xvideoplayer.ui.player

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.app.xvideoplayer.player.MpvPlayer
import com.app.xvideoplayer.ui.components.pressable
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatDuration
import kotlin.math.abs

// ── Video surface ───────────────────────────────────────────────────────

@Composable
fun MpvSurface(player: MpvPlayer, modifier: Modifier = Modifier, onView: (SurfaceView) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also(onView).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = player.attachSurface(h.surface)
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) = player.setSurfaceSize(w, hgt)
                    override fun surfaceDestroyed(h: SurfaceHolder) = player.detachSurface(h.surface)
                })
            }
        }
    )
}

// ── Seek bar ────────────────────────────────────────────────────────────

/**
 * A solid bar rather than a thin line: the track fills (dim white) wherever the file is already
 * cached, the played part is the accent, and a slim playhead marks the position.
 * Touching anywhere jumps there immediately; dragging scrubs with live (keyframe) preview and
 * lands frame-exact on release. Works the same for every file because mpv can seek in anything.
 */
@Composable
fun SeekBar(
    position: Long,
    duration: Long,
    cachedRanges: List<LongRange>,
    abA: Long?,
    abB: Long?,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    val dur by rememberUpdatedState(duration)
    val start by rememberUpdatedState(onScrubStart)
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val grow by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "bar")

    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (dur <= 0) return@awaitEachGesture
                    fun at(x: Float) = ((x / widthPx).coerceIn(0f, 1f) * dur).toLong()
                    dragging = true
                    start()
                    scrub(at(down.position.x))
                    down.consume()
                    drag(down.id) { change -> scrub(at(change.position.x)); change.consume() }
                    dragging = false
                    end()
                }
            }
    ) {
        val spooky = com.app.xvideoplayer.ui.theme.AppPalette.current.halloween
        Canvas(Modifier.fillMaxSize()) {
            val h = (10 + 6 * grow).dp.toPx()
            val y = size.height / 2 - h / 2
            val bar = androidx.compose.ui.geometry.RoundRect(0f, y, size.width, y + h, CornerRadius(h / 2, h / 2))
            fun xOf(ms: Long) = if (duration > 0) size.width * (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val px = xOf(position)

            clipPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(bar) }) {
                drawRect(Color(0x26FFFFFF), Offset(0f, y), Size(size.width, h))
                cachedRanges.forEach { r ->
                    val x0 = xOf(r.first); val x1 = xOf(r.last)
                    if (x1 > x0) drawRect(Color(0x5CFFFFFF), Offset(x0, y), Size(x1 - x0, h))
                }
                if (spooky) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Accent2, Accent, ErrorRed), 0f, size.width), Offset(0f, y), Size(px, h))
                else drawRect(Accent, Offset(0f, y), Size(px, h))
                if (abA != null && abB != null && duration > 0) {
                    val ax = xOf(abA); val bx = xOf(abB)
                    drawRect(PlayedGreen.copy(alpha = 0.45f), Offset(ax, y), Size(bx - ax, h))
                }
            }
            listOfNotNull(abA, abB).forEach { m ->
                if (duration > 0) drawRect(PlayedGreen, Offset(xOf(m) - 1.dp.toPx(), y - 3.dp.toPx()), Size(2.dp.toPx(), h + 6.dp.toPx()))
            }
            // playhead: a slim pill that pokes out of the bar, wider while dragging
            val headW = (3 + 2 * grow).dp.toPx()
            val headH = h + (8 + 6 * grow).dp.toPx()
            if (spooky) with(com.app.xvideoplayer.ui.theme.halloween.HalloweenSpriteDraw) { pumpkin(Offset(px.coerceIn(0f, size.width), size.height / 2), (20 + 8 * grow).dp.toPx()) }
            else drawRoundRect(
                Color.White,
                Offset((px - headW / 2).coerceIn(0f, size.width - headW), size.height / 2 - headH / 2),
                Size(headW, headH),
                CornerRadius(headW / 2, headW / 2)
            )
        }
    }
}

// ── Gesture layer ───────────────────────────────────────────────────────

enum class DragKind { SEEK, BRIGHTNESS, VOLUME, ZOOM, SPEED }

/**
 * One surface for all MX-style gestures. One finger locks to an axis after the touch slop:
 * horizontal = seek, vertical left half = brightness, vertical right half = volume.
 * Two fingers: spreading/pinching = zoom (moving them pans), sliding both up/down = speed.
 */
@Composable
fun PlayerGestureLayer(
    enabled: Boolean,
    onTap: () -> Unit,
    /** true while repeated taps are accumulating a seek: taps then act at once instead of waiting for a double-tap */
    instantTap: Boolean,
    onDoubleTap: (xFraction: Float, yFraction: Float) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDragStart: (DragKind) -> Unit,
    onDrag: (DragKind, fraction: Float) -> Unit,
    onDragEnd: (DragKind) -> Unit,
    onPinch: (zoomFactor: Float, panX: Float, panY: Float) -> Unit,
    /** top of the subtitle zone as a fraction of the height; negative = no subtitle on screen, gestures off */
    subZoneTop: Float = -1f,
    onSubSwipe: (dir: Int) -> Unit = {},
    onSubHeight: (deltaFraction: Float) -> Unit = {},
    onSubPinch: (factor: Float) -> Unit = {},
    onSubEnd: () -> Unit = {},
    /** live horizontal finger offset (px) while swiping on a subtitle; 0 when released */
    onSubDrag: (dx: Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val subTop by rememberUpdatedState(subZoneTop)
    val subSwipe by rememberUpdatedState(onSubSwipe)
    val subHeight by rememberUpdatedState(onSubHeight)
    val subPinch by rememberUpdatedState(onSubPinch)
    val subEnd by rememberUpdatedState(onSubEnd)
    val subDrag by rememberUpdatedState(onSubDrag)
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val lpStart by rememberUpdatedState(onLongPressStart)
    val lpEnd by rememberUpdatedState(onLongPressEnd)
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDragEnd)
    val pinch by rememberUpdatedState(onPinch)
    val isEnabled by rememberUpdatedState(enabled)
    val instant by rememberUpdatedState(instantTap)

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Own tap detector: the stock one dropped the second tap of a double tap when the taps were a
                // little slow or far apart, and the lone first tap then toggled the overlay instead of pausing.
                coroutineScope {
                    val scope = this
                    var pending: Job? = null
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero
                    val windowMs = 320L
                    val maxGap = 140.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val res = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation() ?: "cancel"
                        }
                        if (res == null) { // held: long press
                            if (isEnabled) { lpStart(); waitForUpOrCancellation(); lpEnd() } else waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        val up = res as? androidx.compose.ui.input.pointer.PointerInputChange ?: return@awaitEachGesture
                        if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        val now = up.uptimeMillis
                        if (!isEnabled || instant) { pending?.cancel(); lastTapAt = 0L; tap(); return@awaitEachGesture }
                        if (pending?.isActive == true && now - lastTapAt <= windowMs && (up.position - lastTapPos).getDistance() <= maxGap) {
                            pending?.cancel(); lastTapAt = 0L
                            doubleTap(up.position.x / size.width, up.position.y / size.height)
                        } else {
                            lastTapAt = now; lastTapPos = up.position
                            pending?.cancel()
                            pending = scope.launch { delay(windowMs); tap() }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!isEnabled) return@awaitEachGesture
                    // leave the very top/bottom strips to the system's own swipe gestures
                    val edge = 32.dp.toPx()
                    if (down.position.y < edge || down.position.y > size.height - edge) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    // On a subtitle: vertical drag = height, horizontal swipe = previous/next line, two fingers = size.
                    if (subTop >= 0f && down.position.y / size.height >= subTop) {
                        var mode = 0 // 1 swipe, 2 height, 3 pinch
                        var tx = 0f; var ty = 0f
                        var lastSpan = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val span = (pressed[0].position - pressed[1].position).getDistance()
                                if (mode != 3) { mode = 3; lastSpan = span } else { if (lastSpan > 0f) subPinch(span / lastSpan); lastSpan = span }
                                ev.changes.forEach { it.consume() }; continue
                            }
                            if (mode == 3) { ev.changes.forEach { it.consume() }; continue }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            val d = ch.positionChange()
                            if (mode == 0) {
                                tx += d.x; ty += d.y
                                if (abs(tx) > slop || abs(ty) > slop) mode = if (abs(tx) > abs(ty) * 0.7f) 1 else 2
                                if (mode == 1) subDrag(tx)
                            } else if (mode == 1) { tx += d.x; subDrag(tx) }
                            else subHeight(d.y / size.height)
                            if (mode != 0) ch.consume()
                        }
                        if (mode == 1) subDrag(0f)
                        if (mode == 1 && abs(tx) > 40.dp.toPx()) subSwipe(if (tx < 0) 1 else -1)
                        if (mode == 2 || mode == 3) subEnd()
                        return@awaitEachGesture
                    }
                    var kind: DragKind? = null
                    var totalX = 0f; var totalY = 0f
                    // two-finger state
                    var multi = false
                    var startSpan = 0f; var lastSpan = 0f
                    var startCentroid = Offset.Zero; var lastCentroid = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2 && (kind == null || multi)) {
                            val a = pressed[0].position; val b = pressed[1].position
                            val span = (a - b).getDistance()
                            val centroid = (a + b) / 2f
                            if (!multi) {
                                multi = true
                                startSpan = span; lastSpan = span
                                startCentroid = centroid; lastCentroid = centroid
                            } else if (kind == null) {
                                val spanDelta = abs(span - startSpan)
                                val move = centroid - startCentroid
                                if (spanDelta > slop * 2) { kind = DragKind.ZOOM; dStart(kind) }
                                else if (abs(move.y) > slop * 2 && abs(move.y) > abs(move.x)) { kind = DragKind.SPEED; dStart(kind) }
                                lastSpan = span; lastCentroid = centroid
                            } else {
                                if (kind == DragKind.ZOOM) {
                                    val factor = if (lastSpan > 0f) span / lastSpan else 1f
                                    val pan = centroid - lastCentroid
                                    pinch(factor, pan.x / size.width, pan.y / size.height)
                                } else if (kind == DragKind.SPEED) {
                                    dMove(DragKind.SPEED, -(centroid.y - lastCentroid.y) / size.height)
                                }
                                lastSpan = span; lastCentroid = centroid
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) { event.changes.forEach { it.consume() }; continue } // one finger lifted mid-pinch: wait for release

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.positionChange()
                        if (kind == null) {
                            totalX += d.x; totalY += d.y
                            if (abs(totalX) > slop || abs(totalY) > slop) {
                                kind = when {
                                    abs(totalX) > abs(totalY) -> DragKind.SEEK
                                    down.position.x < size.width / 2f -> DragKind.BRIGHTNESS
                                    else -> DragKind.VOLUME
                                }
                                dStart(kind)
                            }
                        } else {
                            dMove(kind, if (kind == DragKind.SEEK) d.x / size.width else -d.y / size.height)
                        }
                        if (kind != null) change.consume()
                    }
                    kind?.let { dEnd(it) }
                }
            }
    )
}

// ── Feedback visuals ────────────────────────────────────────────────────

/** Feedback for subtitle swipes: a chevron bubble that follows the finger, then a pill that flies off after a commit. */
@Composable
fun SubSwipeFeedback(dragX: Float, flashDir: Int, flashStamp: Long, yFraction: Float, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 40.dp.toPx() }
    var shownStamp by remember { mutableStateOf(0L) }
    var flashing by remember { mutableStateOf(false) }
    val fly = remember { Animatable(0f) }
    LaunchedEffect(flashStamp) {
        if (flashStamp == 0L) return@LaunchedEffect
        shownStamp = flashStamp; flashing = true
        fly.snapTo(0f); fly.animateTo(1f, tween(520, easing = FastOutSlowInEasing)); flashing = false
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val y = maxHeight * yFraction.coerceIn(0.3f, 0.95f)
        if (dragX != 0f && !flashing) {
            val p = (abs(dragX) / threshold).coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.TopCenter).offset(y = y - 24.dp)
                    .graphicsLayer { translationX = dragX * 0.6f; alpha = 0.25f + 0.75f * p; scaleX = 0.8f + 0.3f * p; scaleY = 0.8f + 0.3f * p }
                    .clip(CircleShape).background(if (p >= 1f) AccentBright.copy(alpha = 0.9f) else Color(0xCC111114))
                    .border(1.dp, Color(0x33FFFFFF), CircleShape).size(48.dp),
                contentAlignment = Alignment.Center
            ) { Icon(if (dragX < 0) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
        }
        if (flashing && flashDir != 0) {
            val t = fly.value
            Row(
                Modifier.align(Alignment.TopCenter).offset(y = y - 22.dp)
                    .graphicsLayer {
                        translationX = flashDir * -1f * (t * 160.dp.toPx()) + flashDir * 40.dp.toPx() * (1f - t)
                        alpha = if (t < 0.3f) t / 0.3f else 1f - (t - 0.3f) / 0.7f
                    }
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xCC111114)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (flashDir < 0) Icon(Icons.Rounded.SkipPrevious, null, tint = AccentBright, modifier = Modifier.size(20.dp))
                Text(if (flashDir > 0) "  Next line  " else "  Previous line  ", color = Color.White, style = MaterialTheme.typography.labelLarge)
                if (flashDir > 0) Icon(Icons.Rounded.SkipNext, null, tint = AccentBright, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The curved "« 20s" panel MX/YouTube show on double-tap seeks. */
@Composable
fun DoubleTapSeekFeedback(side: Int, seconds: Int, visible: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth * 0.36f
        AnimatedVisibility(
            visible = visible && side != 0,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(350)),
            modifier = Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            val shape = remember(side) {
                GenericShape { size, _ ->
                    val bulge = size.width * 0.18f
                    if (side < 0) {
                        moveTo(0f, 0f); lineTo(size.width - bulge, 0f)
                        quadraticBezierTo(size.width + bulge, size.height / 2, size.width - bulge, size.height)
                        lineTo(0f, size.height); close()
                    } else {
                        moveTo(size.width, 0f); lineTo(bulge, 0f)
                        quadraticBezierTo(-bulge, size.height / 2, bulge, size.height)
                        lineTo(size.width, size.height); close()
                    }
                }
            }
            Box(Modifier.width(w).fillMaxHeight().clip(shape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                val t = rememberInfiniteTransition(label = "chev")
                val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "phase")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row {
                        repeat(3) { i ->
                            val idx = if (side < 0) 2 - i else i
                            val a = (1f - abs(((phase * 3f) - idx).let { if (it < 0) it + 3 else it } - 0.5f)).coerceIn(0.25f, 1f)
                            Icon(if (side < 0) Icons.Rounded.ArrowLeft else Icons.Rounded.ArrowRight, null, tint = Color.White.copy(alpha = a), modifier = Modifier.size(26.dp))
                        }
                    }
                    AnimatedContent(seconds, transitionSpec = { (scaleIn(initialScale = 0.7f) + fadeIn()) togetherWith fadeOut() }, label = "secs") { s ->
                        Text(
                            "$s seconds", color = Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), Offset(0f, 2f), 8f))
                        )
                    }
                }
            }
        }
    }
}

/** Vertical level pill for brightness (left) / volume (right). Volume above 100 % turns accent. */
@Composable
fun LevelIndicator(icon: ImageVector, level: Float, max: Float, label: String, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(level, spring(stiffness = 900f), label = "lvl")
    Column(
        modifier.width(54.dp).clip(RoundedCornerShape(27.dp)).background(Color(0xCC111114)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(27.dp)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = if (level > 1f) AccentBright else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.width(6.dp).height(140.dp)) {
            val r = CornerRadius(size.width / 2, size.width / 2)
            drawRoundRect(Color(0x33FFFFFF), cornerRadius = r)
            val fill = (animated / max).coerceIn(0f, 1f) * size.height
            val normal = (minOf(animated, 1f) / max).coerceIn(0f, 1f) * size.height
            if (fill > normal) drawRoundRect(Accent, Offset(0f, size.height - fill), Size(size.width, fill), r)
            drawRoundRect(Color.White, Offset(0f, size.height - normal), Size(size.width, normal), r)
        }
        Spacer(Modifier.height(10.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Big centered readout while seeking by swipe or seek bar. */
@Composable
fun SeekReadout(target: Long, origin: Long, duration: Long, modifier: Modifier = Modifier) {
    val delta = target - origin
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC111114)).padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            (if (delta >= 0) "+" else "−") + abs(delta).formatDuration(),
            color = if (delta >= 0) AccentBright else Color.White, style = MaterialTheme.typography.headlineMedium
        )
        Text("${target.formatDuration()} / ${duration.formatDuration()}", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PillMessage(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color(0xE6151518)).border(1.dp, Color(0x1FFFFFFF), CircleShape).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = AccentBright, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 44, iconSize: Int = 24, tint: Color = Color.White, highlighted: Boolean = false) {
    val bg by animateColorAsState(if (highlighted) OverlayButtonActive else Color.Transparent, label = "btnBg")
    Box(
        modifier.size(size.dp).clip(CircleShape).background(bg)
            .pressable(onClick, pressedScale = 0.86f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = if (highlighted) AccentBright else tint, modifier = Modifier.size(iconSize.dp))
    }
}

/**
 * In-player sheet. A Material ModalBottomSheet opens its own window, which brings the system
 * bars back and shifts the video; this stays inside the player. Landscape slides in from the
 * right like MX's side panel, portrait from the bottom.
 */
@Composable
fun PlayerPanel(
    visible: Boolean, onDismiss: () -> Unit,
    /** false = the video stays visible and touchable behind the panel (live previews) */
    dim: Boolean = true,
    width: androidx.compose.ui.unit.Dp = 330.dp,
    portraitMaxFraction: Float = 0.7f,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.activity.compose.BackHandler(enabled = visible, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (dim) AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }
        AnimatedVisibility(
            visible,
            enter = if (landscape) slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn()
                    else slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = if (landscape) slideOutHorizontally(tween(220)) { it } + fadeOut() else slideOutVertically(tween(220)) { it } + fadeOut(),
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
        ) {
            val shape = if (landscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            Column(
                (if (landscape) Modifier.width(width).fillMaxHeight() else Modifier.fillMaxWidth().heightIn(max = maxHeight * portraitMaxFraction))
                    .clip(shape).background(Color(0xF2131316)).border(1.dp, Color(0x14FFFFFF), shape)
                    .pointerInput(Unit) { detectTapGestures { } } // swallow taps so they don't close the panel
                    .padding(vertical = 14.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(if (landscape) WindowInsetsSides.End + WindowInsetsSides.Vertical else WindowInsetsSides.Bottom)),
                content = content
            )
        }
    }
}
