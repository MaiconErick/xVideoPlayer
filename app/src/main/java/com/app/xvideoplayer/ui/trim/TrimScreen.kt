package com.app.xvideoplayer.ui.trim

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.app.xvideoplayer.player.trim.CutResult
import com.app.xvideoplayer.ui.components.AppChip
import com.app.xvideoplayer.ui.player.MpvSurface
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatFileSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private fun fmt(ms: Long): String {
    val t = ms.coerceAtLeast(0); val s = t / 1000
    return if (s >= 3600) "%d:%02d:%02d.%03d".format(s / 3600, (s % 3600) / 60, s % 60, t % 1000)
    else "%02d:%02d.%03d".format(s / 60, s % 60, t % 1000)
}

@Composable
fun TrimScreen(onBack: () -> Unit, onPlay: (android.net.Uri) -> Unit, viewModel: TrimViewModel = hiltViewModel()) {
    val video by viewModel.video.collectAsState()
    val state by viewModel.playerState.collectAsState()
    val position by viewModel.position.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val start by viewModel.start.collectAsState()
    val end by viewModel.end.collectAsState()
    val index by viewModel.index.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val showKeys by viewModel.showKeyframes.collectAsState()
    val estimate by viewModel.estimate.collectAsState()
    val save by viewModel.save.collectAsState()
    val loop by viewModel.loop.collectAsState()
    val stopAtEnd by viewModel.stopAtEnd.collectAsState()

    BackHandler(enabled = save is SaveState.Running) { viewModel.cancelSave() }
    com.app.xvideoplayer.ui.vault.PauseWhenBackgrounded(viewModel.player)

    val result by viewModel.result.collectAsState()
    result?.let { r ->
        CutResultScreen(r, viewModel, onFinished = onBack)
        return
    }

    Column(Modifier.fillMaxSize().background(SurfacePure)) {
        // ── top bar ──
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text("Cut video", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(video?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = viewModel::save, enabled = duration > 0 && end > start && save !is SaveState.Running,
                shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) { Icon(Icons.Rounded.ContentCut, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save cut") }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth > maxHeight * 1.2f
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // ── preview ──
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                        MpvSurface(viewModel.player, Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0x66000000))
                                .border(1.dp, Color(0x33FFFFFF), CircleShape).clickable { viewModel.togglePlay() },
                            contentAlignment = Alignment.Center
                        ) { Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.White, modifier = Modifier.size(36.dp)) }
                        Text(
                            fmt(position), color = Color.White, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (wide) Box(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 12.dp, top = 6.dp)) {
                        InfoPanel(start, end, video?.size ?: 0, estimate, indexing, index?.keyframesMs?.size ?: 0)
                    }
                }

                // ── timeline ──
                Timeline(
                    viewModel = viewModel, duration = duration, start = start, end = end, position = position,
                    keyframes = if (showKeys) index?.keyframesMs else null,
                    aspect = video?.let { if (it.hasDimensions) it.width.toFloat() / it.height else 16f / 9f } ?: (16f / 9f)
                )

                // ── controls ──
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkButton(Icons.Rounded.FirstPage, "Set start", Modifier.weight(1f)) { viewModel.setStartAtPlayhead() }
                    SmallIcon(Icons.Rounded.SkipPrevious, "Previous keyframe", enabled = index != null) { viewModel.stepKeyframe(false) }
                    SmallIcon(Icons.Rounded.KeyboardDoubleArrowLeft, "Back 1 s") { viewModel.nudge(-1000) }
                    SmallIcon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play") { viewModel.togglePlay() }
                    SmallIcon(Icons.Rounded.KeyboardDoubleArrowRight, "Forward 1 s") { viewModel.nudge(1000) }
                    SmallIcon(Icons.Rounded.SkipNext, "Next keyframe", enabled = index != null) { viewModel.stepKeyframe(true) }
                    MarkButton(Icons.Rounded.LastPage, "Set end", Modifier.weight(1f)) { viewModel.setEndAtPlayhead() }
                }
                // ── what happens when playback reaches the end mark ──
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckOption("Loop", checked = loop) { viewModel.setLoop(it) }
                    CheckOption("Stop at end, back to start", checked = stopAtEnd || loop, enabled = !loop) { viewModel.setStopAtEnd(it) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppChip(if (indexing) "Finding keyframes…" else "Keyframes", selected = showKeys && !indexing, icon = Icons.Rounded.Key) { viewModel.toggleKeyframes() }
                    AppChip("Whole video", icon = Icons.Rounded.SettingsBackupRestore) { viewModel.resetSelection() }
                    Spacer(Modifier.weight(1f))
                    if (!wide) Text(
                        "${fmt(end - start).substringBeforeLast('.')}  ·  ≈ ${estimate.formatFileSize()}",
                        color = TextSecondary, style = MaterialTheme.typography.labelLarge
                    )
                }
                if (!wide) Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    InfoPanel(start, end, video?.size ?: 0, estimate, indexing, index?.keyframesMs?.size ?: 0)
                }
            }
        }
    }

    // ── save progress / result ──
    when (val s = save) {
        is SaveState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Cutting…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = SurfaceElevated4)
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).roundToInt()}%  ·  copying streams, no re-encoding", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = viewModel::cancelSave) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceElevated2
        )
        is SaveState.Done -> AlertDialog(
            onDismissRequest = viewModel::dismissResult,
            title = { Text(when (s.result) { is CutResult.Saved -> "Saved"; is CutResult.Failed -> "Cut failed"; CutResult.Cancelled -> "Cancelled" }) },
            text = {
                Text(
                    when (val r = s.result) {
                        is CutResult.Saved -> "${r.displayName}\n${r.bytes.formatFileSize()}  ·  Movies/xVideoPlayer"
                        is CutResult.Failed -> r.message
                        CutResult.Cancelled -> "Nothing was saved."
                    }, color = TextSecondary
                )
            },
            confirmButton = {
                val r = s.result
                if (r is CutResult.Saved) TextButton(onClick = { viewModel.dismissResult(); onPlay(r.uri) }) { Text("Play it", color = Accent) }
                else TextButton(onClick = viewModel::dismissResult) { Text("OK", color = Accent) }
            },
            dismissButton = { if (s.result is CutResult.Saved) TextButton(onClick = viewModel::dismissResult) { Text("Keep editing", color = TextSecondary) } },
            containerColor = SurfaceElevated2
        )
        SaveState.Idle -> Unit
    }
}

@Composable
private fun InfoPanel(start: Long, end: Long, original: Long, estimate: Long, indexing: Boolean, keyCount: Int) {
    val saved = (original - estimate).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceElevated1).border(1.dp, Border, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row { InfoCell("Start", fmt(start), Modifier.weight(1f)); InfoCell("End", fmt(end), Modifier.weight(1f)) }
        InfoCell("Length", fmt(end - start))
        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 4.dp))
        Row {
            InfoCell("Original", original.formatFileSize(), Modifier.weight(1f))
            InfoCell(if (indexing) "Cut (approx.)" else "Cut", "≈ ${estimate.formatFileSize()}", Modifier.weight(1f), AccentBright)
        }
        InfoCell("Saves", "${saved.formatFileSize()}  (${if (original > 0) (saved * 100 / original) else 0}%)", color = PlayedGreen)
        Text(
            if (indexing) "Reading keyframes… the size gets exact once done."
            else "Lossless: streams are copied, not re-encoded. The start snaps to the keyframe at or before your mark ($keyCount keyframes).",
            color = TextTertiary, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier, color: Color = TextPrimary) {
    Column(modifier) {
        Text(label.uppercase(), color = TextTertiary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = color, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun CheckOption(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(enabled = enabled) { onChange(!checked) }.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.padding(10.dp).size(20.dp),
            colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent, uncheckedColor = TextTertiary, disabledCheckedColor = Accent.copy(alpha = 0.4f))
        )
        Text(label, color = if (enabled) TextPrimary else TextTertiary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun MarkButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(CircleShape).background(AccentSoft).border(1.dp, Accent.copy(alpha = 0.5f), CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = AccentBright, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = AccentBright, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(SurfaceElevated2).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = if (enabled) TextPrimary else TextTertiary, modifier = Modifier.size(22.dp)) }
}

// ── Timeline: filmstrip + selection + keyframes + playhead, pinch to zoom ──

@Composable
private fun Timeline(
    viewModel: TrimViewModel, duration: Long, start: Long, end: Long, position: Long,
    keyframes: LongArray?, aspect: Float
) {
    if (duration <= 0) { Box(Modifier.fillMaxWidth().height(130.dp)); return }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // visible window
    var span by remember(duration) { mutableLongStateOf(duration) }
    var viewStart by remember(duration) { mutableLongStateOf(0L) }
    val minSpan = minOf(duration, 1_500L)
    fun clampView() { span = span.coerceIn(minSpan, duration); viewStart = viewStart.coerceIn(0, (duration - span).coerceAtLeast(0)) }

    // handle drafts while dragging
    var startDraft by remember { mutableStateOf<Long?>(null) }
    var endDraft by remember { mutableStateOf<Long?>(null) }
    val s = startDraft ?: start
    val e = endDraft ?: end

    val density = LocalDensity.current
    val stripH = 92.dp
    val stripHPx = with(density) { stripH.toPx() }
    val w = size.width.coerceAtLeast(1).toFloat()
    fun msToX(ms: Long) = (ms - viewStart).toFloat() / span * w
    fun xToMs(x: Float) = (viewStart + (x / w * span)).toLong().coerceIn(0, duration)

    // thumbnails: tiles aligned to multiples of tileSpan so panning doesn't reshuffle them
    val tileW = stripHPx * aspect.coerceIn(0.4f, 2.4f)
    val tileSpan = (span * tileW / w).toLong().coerceAtLeast(40)
    val bucket = remember(tileSpan) { var b = 250L; while (b * 2 <= tileSpan) b *= 2; b }
    val firstTile = floor(viewStart.toDouble() / tileSpan).toLong()
    val lastTile = ceil((viewStart + span).toDouble() / tileSpan).toLong()
    val tileKeys = (firstTile..lastTile).map { k -> ((k * tileSpan + tileSpan / 2) / bucket) * bucket }
    LaunchedEffect(tileKeys) { viewModel.requestThumbs(tileKeys.map { it.coerceIn(0, duration) }) }
    val thumbVersion by viewModel.thumbVersion.collectAsState()

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // ruler
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(viewStart).substringBeforeLast('.'), color = TextTertiary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            val zoom = duration.toFloat() / span
            AnimatedVisibility(zoom > 1.05f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("×${"%.1f".format(zoom)}", color = AccentBright, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("Fit", color = TextSecondary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(CircleShape).background(SurfaceElevated3).clickable { span = duration; viewStart = 0 }.padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
            if (zoom <= 1.05f) Text("Pinch to zoom", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(viewStart + span).substringBeforeLast('.'), color = TextTertiary, style = MaterialTheme.typography.labelSmall)
        }

        OverviewBar(
            duration = duration, start = s, end = e, position = position,
            viewStart = viewStart, span = span,
            onJump = { ms ->
                viewModel.jumpTo(ms)
                if (span < duration && (ms < viewStart || ms > viewStart + span)) { viewStart = ms - span / 2; clampView() }
            },
            onSeek = { ms ->
                viewModel.seek(ms)
                // zoomed in: bring the tapped spot into view
                if (span < duration && (ms < viewStart || ms > viewStart + span)) { viewStart = ms - span / 2; clampView() }
            }
        )
        Spacer(Modifier.height(6.dp))
        // time bubble that rides on the playhead
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            val px = msToX(position)
            if (size.width > 0 && px in 0f..w) {
                val bubbleW = with(density) { 86.dp.toPx() }
                Text(
                    fmt(position), color = SurfacePure, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.offset { IntOffset((px - bubbleW / 2).coerceIn(0f, w - bubbleW).roundToInt(), 0) }
                        .width(86.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(stripH).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated2)
                .onSizeChanged { size = it }
                .pointerInput(duration) {
                    detectTapGestures(
                        onTap = { o -> viewModel.seek(xToMs(o.x)) },
                        // double tap: zoom in 4× around the tap, or back to fit
                        onDoubleTap = { o ->
                            if (span < duration) { span = duration; viewStart = 0 }
                            else { val at = xToMs(o.x); span = (duration / 4).coerceAtLeast(minSpan); viewStart = at - span / 2; clampView() }
                        }
                    )
                }
                .pointerInput(duration) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f) {
                            val anchor = viewStart + (centroid.x / size.width * span).toLong()
                            span = (span / zoom).toLong()
                            span = span.coerceIn(minSpan, duration)
                            viewStart = anchor - (centroid.x / size.width * span).toLong()
                        }
                        if (span < duration) viewStart -= (pan.x / size.width * span).toLong()   // zoomed: drag pans
                        else if (zoom == 1f) viewModel.scrub(xToMs(centroid.x))                 // fit: drag scrubs
                        clampView()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                thumbVersion // re-draw when a new thumbnail lands
                for (k in firstTile..lastTile) {
                    val x0 = msToX(k * tileSpan)
                    val key = (((k * tileSpan + tileSpan / 2) / bucket) * bucket).coerceIn(0, duration)
                    val img = viewModel.thumb(key)
                        ?: generateSequence(bucket * 2) { it * 2 }.take(6).firstNotNullOfOrNull { b -> viewModel.thumb((key / b) * b) }
                    if (img != null) {
                        // center-crop into the tile
                        val srcAspect = img.width.toFloat() / img.height
                        val dstAspect = tileW / this.size.height
                        val (sw, sh) = if (srcAspect > dstAspect) (img.height * dstAspect).toInt() to img.height else img.width to (img.width / dstAspect).toInt()
                        drawImage(
                            img, srcOffset = androidx.compose.ui.unit.IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                            srcSize = IntSize(sw, sh),
                            dstOffset = androidx.compose.ui.unit.IntOffset(x0.roundToInt(), 0),
                            dstSize = IntSize(tileW.roundToInt() + 1, this.size.height.roundToInt())
                        )
                    } else drawRect(Color(0xFF1E1E24), Offset(x0, 0f), Size(tileW - 2f, this.size.height))
                }
                // outside the selection: dimmed
                val sx = msToX(s); val ex = msToX(e)
                drawRect(Color(0xB3000000), Offset.Zero, Size(sx.coerceIn(0f, this.size.width), this.size.height))
                drawRect(Color(0xB3000000), Offset(ex.coerceIn(0f, this.size.width), 0f), Size((this.size.width - ex).coerceAtLeast(0f), this.size.height))
                // selection frame
                drawRoundRect(Accent, Offset(sx, 1.5.dp.toPx()), Size(ex - sx, this.size.height - 3.dp.toPx()), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
                // keyframe ticks
                keyframes?.let { keys ->
                    val lo = keys.indexOfFirst { it >= viewStart }.takeIf { it >= 0 } ?: return@let
                    var prevX = -10f
                    val tickH = 14.dp.toPx()
                    for (i in lo until keys.size) {
                        val t = keys[i]; if (t > viewStart + span) break
                        val x = msToX(t)
                        if (x - prevX < 3f) continue // too dense to tell apart: thin out
                        prevX = x
                        drawLine(Color(0xFFFFD54F), Offset(x, this.size.height - tickH), Offset(x, this.size.height), strokeWidth = 2.dp.toPx())
                    }
                }
                // playhead
                val px = msToX(position)
                if (px in 0f..this.size.width) {
                    drawLine(Color(0x99000000), Offset(px, 0f), Offset(px, this.size.height), strokeWidth = 5.dp.toPx())
                    drawLine(Color.White, Offset(px, 0f), Offset(px, this.size.height), strokeWidth = 2.5.dp.toPx())
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(px - 7.dp.toPx(), 0f); lineTo(px + 7.dp.toPx(), 0f); lineTo(px, 9.dp.toPx()); close()
                    }
                    drawPath(tri, Color.White)
                }
            }

            // drag handles
            val handleW = 26.dp
            val handleWPx = with(density) { handleW.toPx() }
            Handle(
                x = msToX(s) - handleWPx / 2, width = handleW, left = true,
                onDrag = { dx -> val v = ((startDraft ?: start) + (dx / w * span).toLong()).coerceIn(0, e - 100); startDraft = v; viewModel.scrub(v) },
                onEnd = { startDraft?.let { viewModel.setStart(it) }; startDraft = null }
            )
            Handle(
                x = msToX(e) - handleWPx / 2, width = handleW, left = false,
                onDrag = { dx -> val v = ((endDraft ?: end) + (dx / w * span).toLong()).coerceIn(s + 100, duration); endDraft = v; viewModel.scrub(v) },
                onEnd = { endDraft?.let { viewModel.setEnd(it) }; endDraft = null }
            )
            // marks scrolled out of view get a tab on the side they're on; tap to jump there
            val marks = listOf("Start" to s, "End" to e)
            val leftOut = marks.filter { it.second < viewStart }
            val rightOut = marks.filter { it.second > viewStart + span }
            if (leftOut.isNotEmpty()) Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                leftOut.forEach { (name, ms) -> EdgeTab("$name ${fmt(ms).substringBeforeLast('.')}", left = true) { viewStart = ms - span / 10; clampView() } }
            }
            if (rightOut.isNotEmpty()) Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                rightOut.forEach { (name, ms) -> EdgeTab("$name ${fmt(ms).substringBeforeLast('.')}", left = false) { viewStart = ms - span * 9 / 10; clampView() } }
            }
        }
        // selection readout under the strip
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("⟦ ${fmt(s)}", color = AccentBright, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text("${fmt(e)} ⟧", color = AccentBright, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Handle(x: Float, width: androidx.compose.ui.unit.Dp, left: Boolean, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), 0) }.width(width).fillMaxHeight()
            .pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { c, dx -> c.consume(); drag(dx) } },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(14.dp).fillMaxHeight(0.72f)
                .clip(if (left) RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp) else RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                .background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { Box(Modifier.size(width = 4.dp, height = 2.dp).background(OnAccent.copy(alpha = 0.7f))) }
            }
        }
    }
}


/**
 * Whole-video bar: selection, start/end flags, the zoomed window and the playhead, always visible.
 * Above it, a tappable tag per mark ([onJump] moves the playhead there).
 */
@Composable
private fun OverviewBar(duration: Long, start: Long, end: Long, position: Long, viewStart: Long, span: Long, onJump: (Long) -> Unit, onSeek: (Long) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val seek by rememberUpdatedState(onSeek)
    fun at(x: Float) = (x / width * duration).toLong().coerceIn(0, duration)
    // start tag hangs left of its flag, end tag right of its flag, so they don't cover each other
    Box(Modifier.fillMaxWidth().height(26.dp)) {
        MarkTag("Start", start, duration, width, flagOnRight = true) { onJump(start) }
        MarkTag("End", end, duration, width, flagOnRight = false) { onJump(end) }
    }
    Box(
        Modifier.fillMaxWidth().height(30.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) { detectTapGestures { o -> seek(at(o.x)) } }
            .pointerInput(duration) { detectHorizontalDragGestures { c, _ -> seek(at(c.position.x)) } }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            fun x(ms: Long) = ms.toFloat() / duration * size.width
            val barTop = 9.dp.toPx(); val barH = 12.dp.toPx(); val r = CornerRadius(barH / 2)
            drawRoundRect(Color(0xFF26262C), Offset(0f, barTop), Size(size.width, barH), r)
            // selection
            drawRoundRect(Accent.copy(alpha = 0.55f), Offset(x(start), barTop), Size((x(end) - x(start)).coerceAtLeast(2f), barH), r)
            // start / end flags (always visible, even when the strip is zoomed elsewhere)
            for ((ms, isStart) in listOf(start to true, end to false)) {
                val fx = x(ms)
                drawLine(AccentBright, Offset(fx, 2.dp.toPx()), Offset(fx, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                val flag = androidx.compose.ui.graphics.Path().apply {
                    val dir = if (isStart) 1f else -1f
                    moveTo(fx, 2.dp.toPx()); lineTo(fx + dir * 9.dp.toPx(), 5.5.dp.toPx()); lineTo(fx, 9.dp.toPx()); close()
                }
                drawPath(flag, AccentBright)
            }
            // zoomed window
            if (span < duration) drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x(viewStart), barTop - 3.dp.toPx()), Size(x(viewStart + span) - x(viewStart), barH + 6.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            // playhead
            val px = x(position)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3.dp.toPx())
        }
    }
}

@Composable
private fun MarkTag(label: String, ms: Long, duration: Long, barWidth: Int, flagOnRight: Boolean, onClick: () -> Unit) {
    var tagW by remember { mutableIntStateOf(0) }
    val fx = ms.toFloat() / duration.coerceAtLeast(1) * barWidth
    val x = (if (flagOnRight) fx - tagW else fx).coerceIn(0f, (barWidth - tagW).coerceAtLeast(0).toFloat())
    Row(
        Modifier.offset { IntOffset(x.roundToInt(), 0) }.onSizeChanged { tagW = it.width }
            .fillMaxHeight().padding(bottom = 2.dp)
            .clip(if (flagOnRight) RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 8.dp) else RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp, topStart = 8.dp))
            .background(Accent).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!flagOnRight) Icon(Icons.Rounded.LastPage, null, tint = OnAccent, modifier = Modifier.size(14.dp))
        Text("$label ${fmt(ms).substringBeforeLast('.')}", color = OnAccent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        if (flagOnRight) Icon(Icons.Rounded.FirstPage, null, tint = OnAccent, modifier = Modifier.size(14.dp))
    }
}

/** Shown at the strip's edge when the start/end mark is scrolled out of view; tap to jump to it. */
@Composable
private fun EdgeTab(label: String, left: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.padding(horizontal = 4.dp).clip(CircleShape).background(Accent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (left) Icon(Icons.Rounded.ChevronLeft, null, tint = OnAccent, modifier = Modifier.size(16.dp))
        Text(label, color = OnAccent, style = MaterialTheme.typography.labelMedium)
        if (!left) Icon(Icons.Rounded.ChevronRight, null, tint = OnAccent, modifier = Modifier.size(16.dp))
    }
}


// ── After cutting: watch the result, then Go back or Done ──────────────

@Composable
private fun CutResultScreen(r: CutResult.Saved, viewModel: TrimViewModel, onFinished: () -> Unit) {
    val state by viewModel.playerState.collectAsState()
    val position by viewModel.position.collectAsState()
    val original by viewModel.video.collectAsState()
    val fileActions = com.app.xvideoplayer.ui.components.rememberFileActions()
    var askReplace by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }

    BackHandler { viewModel.discardResult() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(PlayedGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = PlayedGreen, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Cut saved — check it", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text("${r.displayName}  ·  ${r.bytes.formatFileSize()}", style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MpvSurface(viewModel.player, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { viewModel.player.togglePlayPause() } })
            androidx.compose.animation.AnimatedVisibility(!state.isPlaying, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.Center)) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                    Icon(if (state.isEnded) Icons.Rounded.Replay else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(42.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(scrub ?: position).substringBeforeLast('.'), color = Color.White, style = MaterialTheme.typography.labelMedium)
            com.app.xvideoplayer.ui.player.SeekBar(
                position = scrub ?: position, duration = state.duration, cachedRanges = emptyList(), abA = null, abB = null,
                onScrubStart = { scrub = position }, onScrub = { scrub = it; viewModel.player.scrubTo(it) },
                onScrubEnd = { scrub?.let { viewModel.player.seekTo(it) }; scrub = null },
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            Text(fmt(state.duration).substringBeforeLast('.'), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::discardResult, modifier = Modifier.weight(1f).height(52.dp), shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong)
            ) {
                Icon(Icons.Rounded.ArrowBack, null, tint = TextPrimary); Spacer(Modifier.width(8.dp))
                Column {
                    Text("Go back", color = TextPrimary)
                    Text("discard this cut", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = { viewModel.player.pause(); askReplace = true }, modifier = Modifier.weight(1f).height(52.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) { Icon(Icons.Rounded.Done, null); Spacer(Modifier.width(8.dp)); Text("Done") }
        }
    }

    if (askReplace) {
        AlertDialog(
            onDismissRequest = { askReplace = false },
            title = { Text("Replace the original?") },
            text = {
                Text(
                    "Replace overwrites \"${original?.title.orEmpty()}\" with this cut (same name and folder, " +
                        "${(original?.size ?: 0).formatFileSize()} → ${r.bytes.formatFileSize()}). This can't be undone.\n\n" +
                        "Keep both leaves the original and saves the cut as a new video.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askReplace = false
                    original?.let { fileActions.replace(it, r.uri, viewModel.resultLength()) { viewModel.keepBoth(); onFinished() } }
                }) { Text("Replace original", color = ErrorRed) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askReplace = false }) { Text("Cancel", color = TextSecondary) }
                    TextButton(onClick = { askReplace = false; viewModel.keepBoth(kept = true); onFinished() }) { Text("Keep both", color = Accent) }
                }
            },
            containerColor = SurfaceElevated2
        )
    }
}
