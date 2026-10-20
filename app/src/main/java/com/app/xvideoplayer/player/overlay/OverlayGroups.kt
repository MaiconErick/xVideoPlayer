package com.app.xvideoplayer.player.overlay

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.app.xvideoplayer.data.model.ABRepeatState
import com.app.xvideoplayer.ui.theme.*
import kotlin.math.roundToInt

/** Draggable, pin-able tool panel. Pinned panels stay visible when the controls hide. */
@Composable
fun OverlayGroupContainer(
    title: String, isPinned: Boolean, onPinToggle: () -> Unit,
    isVisible: Boolean, offsetX: Float, offsetY: Float,
    onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    AnimatedVisibility(
        visible = isVisible || isPinned,
        enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut() + scaleOut(targetScale = 0.92f),
        modifier = modifier
            .padding(start = 16.dp, top = 96.dp)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { dragEnd() }) { change, amount -> change.consume(); drag(amount.x, amount.y) }
            }
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xD9111114)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
                .padding(10.dp).width(IntrinsicSize.Max).widthIn(min = 170.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DragIndicator, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onPinToggle), contentAlignment = Alignment.Center) {
                    Icon(if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, "Pin", tint = if (isPinned) Accent else TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, isActive: Boolean = false, tint: Color = if (isActive) AccentBright else TextPrimary) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(if (isActive) OverlayButtonActive else OverlayButton)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
fun ScreenshotOverlay(
    isPinned: Boolean, onPinToggle: () -> Unit, isVisible: Boolean,
    includeSubtitle: Boolean, onIncludeSubtitleChange: (Boolean) -> Unit,
    onTakeScreenshot: (Boolean) -> Unit,
    offsetX: Float, offsetY: Float, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    OverlayGroupContainer("Screenshot", isPinned, onPinToggle, isVisible, offsetX, offsetY, onDrag, onDragEnd, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayButton(Icons.Rounded.CameraAlt, "Capture", onClick = { onTakeScreenshot(includeSubtitle) })
            OverlayButton(Icons.Rounded.Subtitles, if (includeSubtitle) "With subs" else "No subs", onClick = { onIncludeSubtitleChange(!includeSubtitle) }, isActive = includeSubtitle)
        }
    }
}

@Composable
fun FrameControlOverlay(
    isPinned: Boolean, onPinToggle: () -> Unit, isVisible: Boolean,
    onSeekFrames: (Int) -> Unit, currentPosition: Long, duration: Long,
    offsetX: Float, offsetY: Float, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit,
    modifier: Modifier = Modifier, fps: Float = 30f
) {
    val currentFrame = (currentPosition * fps / 1000).toLong()
    val maxFrame = (duration * fps / 1000).toLong()
    OverlayGroupContainer("Frames", isPinned, onPinToggle, isVisible, offsetX, offsetY, onDrag, onDragEnd, modifier) {
        Text("$currentFrame / $maxFrame  ·  ${formatMs(currentPosition)}", color = AccentBright, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayButton(Icons.Rounded.FastRewind, "−10", onClick = { onSeekFrames(-10) })
            OverlayButton(Icons.Rounded.ChevronLeft, "−1", onClick = { onSeekFrames(-1) })
            OverlayButton(Icons.Rounded.ChevronRight, "+1", onClick = { onSeekFrames(1) })
            OverlayButton(Icons.Rounded.FastForward, "+10", onClick = { onSeekFrames(10) })
        }
    }
}

@Composable
fun ABRepeatOverlay(
    isPinned: Boolean, onPinToggle: () -> Unit, isVisible: Boolean,
    state: ABRepeatState, onSetA: () -> Unit, onSetB: () -> Unit,
    onClear: () -> Unit, onToggle: () -> Unit, onSetLoopCount: (Int) -> Unit,
    offsetX: Float, offsetY: Float, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    OverlayGroupContainer("A-B repeat", isPinned, onPinToggle, isVisible, offsetX, offsetY, onDrag, onDragEnd, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayButton(Icons.Rounded.Start, state.pointA?.let { "A ${formatMs(it)}" } ?: "Set A", onClick = onSetA, isActive = state.pointA != null)
            OverlayButton(Icons.Rounded.LastPage, state.pointB?.let { "B ${formatMs(it)}" } ?: "Set B", onClick = onSetB, isActive = state.pointB != null)
            OverlayButton(if (state.isActive) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, if (state.isActive) "Looping" else "Loop", onClick = onToggle, isActive = state.isActive)
            OverlayButton(Icons.Rounded.Clear, "Clear", onClick = onClear, tint = ErrorRed)
        }
        if (state.pointA != null && state.pointB != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Times", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                listOf(0, 3, 5, 10).forEach { c ->
                    val sel = state.loopCount == c
                    Box(
                        Modifier.clip(CircleShape).background(if (sel) OverlayButtonActive else OverlayButton).clickable { onSetLoopCount(c) }.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text(if (c == 0) "∞" else "$c", color = if (sel) AccentBright else TextPrimary, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60; val frac = (ms % 1000) / 10
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, sec, frac) else "%02d:%02d.%02d".format(m, sec, frac)
}
