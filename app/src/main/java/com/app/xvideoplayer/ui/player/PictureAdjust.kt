package com.app.xvideoplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.app.xvideoplayer.data.model.VideoFilters
import com.app.xvideoplayer.data.model.VideoItem
import com.app.xvideoplayer.ui.settings.SliderRow
import com.app.xvideoplayer.ui.theme.*
import kotlin.math.roundToInt

/** Sliders for the picture; they change the video while the finger is still down. */
@Composable
fun PictureAdjustPanelContent(
    f: VideoFilters, compare: Boolean, onChange: (VideoFilters) -> Unit, onCompare: (Boolean) -> Unit,
    onReset: () -> Unit, onSave: () -> Unit, onClose: (() -> Unit)?, scrollable: Boolean = true
) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Picture", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
        TextButton(onClick = onReset) { Text("Reset", color = Accent) }
        TextButton(onClick = onSave) { Text("Save…", color = Accent) }
        if (onClose != null) TextButton(onClick = onClose) { Text("Done", color = Accent) }
    }
    // The second screen's tab already scrolls; a nested verticalScroll there crashes on infinite height.
    Column(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
        Row(
            Modifier.fillMaxWidth().clickable { onCompare(!compare) }.padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Compare with the original", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Text("Drag the line across the video", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = compare, onCheckedChange = onCompare,
                colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent, uncheckedTrackColor = SurfaceElevated4, uncheckedBorderColor = BorderStrong)
            )
        }
        fun pct(v: Float) = "${(v * 100).roundToInt()}"
        SliderRow(Icons.Rounded.LightMode, "Brightness", f.brightness, -1f..1f, pct(f.brightness)) { onChange(f.copy(brightness = it)) }
        SliderRow(Icons.Rounded.Contrast, "Contrast", f.contrast, -1f..1f, pct(f.contrast)) { onChange(f.copy(contrast = it)) }
        SliderRow(Icons.Rounded.Palette, "Saturation", f.saturation, -1f..1f, pct(f.saturation)) { onChange(f.copy(saturation = it)) }
        SliderRow(Icons.Rounded.WbSunny, "Highlights", f.highlights, -1f..1f, pct(f.highlights)) { onChange(f.copy(highlights = it)) }
        SliderRow(Icons.Rounded.Nightlight, "Blacks", f.blacks, -1f..1f, pct(f.blacks)) { onChange(f.copy(blacks = it)) }
        SliderRow(Icons.Rounded.Brightness7, "Whites", f.whites, -1f..1f, pct(f.whites)) { onChange(f.copy(whites = it)) }
        SliderRow(Icons.Rounded.Vignette, "Vignette", f.vignette, 0f..1f, pct(f.vignette)) { onChange(f.copy(vignette = it)) }
        SliderRow(Icons.Rounded.BlurOn, "Vignette softness", f.vignetteSoft, 0f..1f, pct(f.vignetteSoft)) { onChange(f.copy(vignetteSoft = it)) }
        Spacer(Modifier.height(8.dp))
    }
}

/** The line you drag to compare: original on its left, adjusted picture on its right. */
@Composable
fun CompareDivider(split: Float, onSplit: (Float) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val x = (split.coerceIn(0f, 1f) * wPx).roundToInt()
        Box(Modifier.offset { IntOffset(x - 1, 0) }.width(2.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
        Box(
            Modifier.offset { IntOffset(x - with(density) { 24.dp.roundToPx() }, with(density) { (maxHeight / 2 - 24.dp).roundToPx() }) }
                .size(48.dp).clip(CircleShape).background(Color(0xCC000000)).border(2.dp, Color.White, CircleShape)
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onSplit(((split * wPx) + drag.x).div(wPx).coerceIn(0.02f, 0.98f)) } },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.SwapHoriz, "Drag to compare", tint = Color.White) }
        Text("ORIGINAL", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
        Text("EDITED", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Asked after you adjusted a video: keep it for this video, the folder, everything, or some videos. */
@Composable
fun KeepPictureDialog(folderVideos: List<VideoItem>, onSave: (FilterScope, List<VideoItem>) -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (!picking) AlertDialog(
        onDismissRequest = onDismiss, containerColor = SurfaceElevated2,
        title = { Text("Keep these picture settings?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeButton("Only this video") { onSave(FilterScope.VIDEO, emptyList()) }
                ScopeButton("All videos in this folder") { onSave(FilterScope.FOLDER, emptyList()) }
                ScopeButton("Everything (global)") { onSave(FilterScope.GLOBAL, emptyList()) }
                ScopeButton("Choose videos from this folder…") { picking = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDiscard) { Text("Don't keep", color = ErrorRed) } }
    ) else {
        val chosen = remember { mutableStateListOf<Long>() }
        AlertDialog(
            onDismissRequest = { picking = false }, containerColor = SurfaceElevated2,
            title = { Text("Apply to which videos?") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(folderVideos, key = { it.id }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (v.id in chosen) chosen.remove(v.id) else chosen.add(v.id) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = v.id in chosen, onCheckedChange = { if (it) chosen.add(v.id) else chosen.remove(v.id) })
                            Text(v.title, color = TextPrimary, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(FilterScope.SELECTED, folderVideos.filter { it.id in chosen }) }, enabled = chosen.isNotEmpty()) { Text("Apply to ${chosen.size}", color = Accent) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { chosen.clear(); chosen.addAll(folderVideos.map { it.id }) }) { Text("All", color = TextSecondary) }
                    TextButton(onClick = { picking = false }) { Text("Back", color = TextSecondary) }
                }
            }
        )
    }
}

@Composable
private fun ScopeButton(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceElevated3).clickable(onClick = onClick).padding(14.dp)) {
        Text(text, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
    }
}
