package com.app.xvideoplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.xvideoplayer.data.model.TapZone
import com.app.xvideoplayer.data.model.ZoneAction
import com.app.xvideoplayer.ui.components.AppChip
import com.app.xvideoplayer.ui.settings.SliderRow
import com.app.xvideoplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Full-screen editor drawn over the paused video. Drag a zone to move it, drag its edges or corners to resize,
 * tap it to choose what a double tap there does. "Add zone" puts a new rectangle in the middle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TapZoneEditor(zones: List<TapZone>, defaultSeconds: Int, onChange: (List<TapZone>) -> Unit, onClose: () -> Unit) {
    var list by remember { mutableStateOf(zones) }
    var selected by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(list)
    LaunchedEffect(list) { delay(300); onChange(list) }
    androidx.activity.compose.BackHandler(onBack = onClose)

    fun update(id: String, f: (TapZone) -> TapZone) { list = latest.map { if (it.id == id) f(it) else it } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xB3000000)).pointerInput(Unit) { detectTapGestures { selected = null } }) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        list.forEach { z ->
            val color = Color(z.color)
            val isSel = z.id == selected
            Box(
                Modifier
                    .offset { IntOffset((z.x0 * wPx).roundToInt(), (z.y0 * hPx).roundToInt()) }
                    .size(with(density) { ((z.x1 - z.x0) * wPx).toDp() }, with(density) { ((z.y1 - z.y0) * hPx).toDp() })
                    .background(color.copy(alpha = if (isSel) 0.42f else 0.28f))
                    .border(if (isSel) 3.dp else 1.5.dp, color)
                    .pointerInput(z.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // consumed, or the backdrop's tap detector sees the same tap and clears the selection again
                            down.consume()
                            selected = z.id
                            val edge = 30.dp.toPx()
                            val cur = latest.first { it.id == z.id }
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val l = down.position.x < edge; val r = down.position.x > w - edge
                            val t = down.position.y < edge; val b = down.position.y > h - edge
                            val min = 0.08f
                            drag(down.id) { ch ->
                                val d = ch.positionChange()
                                val dx = d.x / wPx; val dy = d.y / hPx
                                update(z.id) { c ->
                                    if (l || r || t || b) {
                                        var x0 = c.x0; var x1 = c.x1; var y0 = c.y0; var y1 = c.y1
                                        if (l) x0 = (x0 + dx).coerceIn(0f, x1 - min)
                                        if (r) x1 = (x1 + dx).coerceIn(x0 + min, 1f)
                                        if (t) y0 = (y0 + dy).coerceIn(0f, y1 - min)
                                        if (b) y1 = (y1 + dy).coerceIn(y0 + min, 1f)
                                        c.copy(x0 = x0, x1 = x1, y0 = y0, y1 = y1)
                                    } else {
                                        val ww = c.x1 - c.x0; val hh = c.y1 - c.y0
                                        val nx = (c.x0 + dx).coerceIn(0f, 1f - ww); val ny = (c.y0 + dy).coerceIn(0f, 1f - hh)
                                        c.copy(x0 = nx, x1 = nx + ww, y0 = ny, y1 = ny + hh)
                                    }
                                }
                                ch.consume()
                            }
                            @Suppress("UNUSED_VARIABLE") val unused = cur
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    z.action.label + if (z.action.usesSeconds) "\n${z.seconds} s" else "", color = Color.White, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp)
                )
            }
        }

        // top bar
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tap zones", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AppChip("Add zone") {
                val color = TapZone.PALETTE[list.size % TapZone.PALETTE.size]
                val z = TapZone("z" + System.currentTimeMillis().toString(36), 0.35f, 0.35f, 0.65f, 0.65f, ZoneAction.PLAY_PAUSE, defaultSeconds, color)
                list = list + z; selected = z.id
            }
            AppChip("Reset") { list = TapZone.defaults(defaultSeconds); selected = null }
            AppChip("Done", selected = true, onClick = onClose)
        }
        if (selected == null) Text(
            "Double tapping a zone does its action. Drag to move, drag the edges to resize, tap to change.",
            color = Color(0xCCFFFFFF), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        )

        // settings of the selected zone
        list.firstOrNull { it.id == selected }?.let { z ->
            Column(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(10.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF2131316)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                    .pointerInput(Unit) { detectTapGestures { } }.padding(vertical = 10.dp)
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Double tap here:", color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { list = list.filterNot { it.id == z.id }; selected = null }) { Text("Delete zone", color = ErrorRed) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZoneAction.entries.forEach { a -> AppChip(a.label, selected = z.action == a) { update(z.id) { it.copy(action = a) } } }
                }
                if (z.action.usesSeconds) SliderRow(null, "Seconds", z.seconds.toFloat(), 1f..60f, "${z.seconds} s") { v -> update(z.id) { it.copy(seconds = v.roundToInt().coerceIn(1, 60)) } }
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TapZone.PALETTE.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (z.color == c) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { update(z.id) { it.copy(color = c) } }
                        )
                    }
                }
            }
        }
    }
}
