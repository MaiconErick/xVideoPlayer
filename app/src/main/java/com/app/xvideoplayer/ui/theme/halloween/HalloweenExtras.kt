package com.app.xvideoplayer.ui.theme.halloween

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.app.xvideoplayer.ui.theme.AppPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * xVideoPlayer's own Halloween layer (the sprites and bat/ghost/spider animations come from
 * xVideoWatcher). Everything animated here is read inside draw lambdas, so it only re-draws its
 * own layer and never recomposes or slows down the lists on top of it.
 */

private class Wisp(val x: Float, val speed: Float, val phase: Float, val size: Float, val sway: Float, val green: Boolean)

private val wisps = List(16) { i ->
    val r = java.util.Random(i * 7919L + 13)
    Wisp(
        x = r.nextFloat(), speed = 0.35f + r.nextFloat() * 0.55f, phase = r.nextFloat(),
        size = 2.5f + r.nextFloat() * 3.5f, sway = 6f + r.nextFloat() * 18f, green = i % 3 != 0
    )
}

/**
 * Night behind the tab screens: a moon with a breathing halo, will-o'-the-wisps drifting up and
 * fading, and two fog bands crawling along the floor. AMOLED keeps the sky pure black.
 */
@Composable
fun HalloweenBackdrop(modifier: Modifier = Modifier) {
    val pal = AppPalette.current
    val t = rememberInfiniteTransition(label = "night")
    val clock = t.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "clock")
    val moon = Sprites.moon.bitmap()
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val c = clock.value
            if (!pal.amoled) drawRect(Brush.verticalGradient(listOf(Color(0xFF1A0B2E), Color(0xFF0E0718), Color(0xFF0B0612))))

            // moon + halo in the free middle of the header row (title left, actions right)
            val mSize = 26.dp.toPx()
            val mCenter = Offset(size.width * 0.5f, 92.dp.toPx())
            val breathe = 0.75f + 0.25f * sin(c * 2f * PI.toFloat() * 3f)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x40FFD54A).copy(alpha = 0.22f * breathe), Color.Transparent), mCenter, mSize * 2.6f),
                mSize * 2.6f, mCenter
            )
            drawImage(
                moon, srcOffset = IntOffset.Zero, srcSize = IntSize(moon.width, moon.height),
                dstOffset = IntOffset((mCenter.x - mSize / 2).roundToInt(), (mCenter.y - mSize / 2).roundToInt()),
                dstSize = IntSize(mSize.roundToInt(), mSize.roundToInt()), alpha = 0.55f, filterQuality = FilterQuality.None
            )

            // will-o'-the-wisps
            for (w in wisps) {
                val p = (c * w.speed * 6f + w.phase) % 1f            // 0 bottom → 1 top
                val y = size.height * (1.05f - p * 1.1f)
                val x = size.width * w.x + sin((p * 4f + w.phase) * 2f * PI.toFloat()) * w.sway.dp.toPx()
                val fade = sin(p * PI.toFloat())                       // fade in and out
                val col = if (w.green) pal.glow else pal.accent
                val r = w.size.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.2f * fade), Color.Transparent), Offset(x, y), r * 4f), r * 4f, Offset(x, y))
                drawCircle(col.copy(alpha = 0.5f * fade), r * 0.55f, Offset(x, y))
            }

            // ground fog
            fogBand(c, 0.82f, 0.22f, 1f, 0.10f)
            fogBand(c, 0.90f, 0.16f, -1.4f, 0.08f)
        }
        // an occasional bat crossing the top of the screen
        BatFlyBy(Modifier.fillMaxWidth().padding(top = 96.dp).height(40.dp), durationMs = 16_000, size = 20.dp)
    }
}

/** Fog as a row of soft overlapping puffs (radial fades on every side, so no hard edges). */
private fun DrawScope.fogBand(c: Float, yFrac: Float, hFrac: Float, dir: Float, alpha: Float) {
    val r = size.height * hFrac
    val step = r * 0.9f
    val shift = (((c * dir * 2f) % 1f + 1f) % 1f) * step * 4f
    var x = -r * 2f + shift % step - step * 2f
    var i = 0
    while (x < size.width + r * 2f) {
        val wobble = sin((i * 1.7f + c * 6f) * PI.toFloat())
        val center = Offset(x, size.height * yFrac + wobble * r * 0.12f)
        val rr = r * (0.85f + 0.2f * wobble)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB9A8E0).copy(alpha = alpha), Color.Transparent), center, rr), rr, center)
        x += step; i++
    }
}

/** Candle-like glow under the selected bottom-bar tab: warm, uneven flicker. */
@Composable
fun CandleGlow(modifier: Modifier = Modifier) {
    val pal = AppPalette.current
    val t = rememberInfiniteTransition(label = "candle")
    val a = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "a")
    Canvas(modifier) {
        val v = a.value * 2f * PI.toFloat()
        // three incommensurate waves read as a natural flicker
        val flick = 0.72f + 0.14f * sin(v * 3f) + 0.09f * sin(v * 7f + 1.3f) + 0.05f * sin(v * 13f + 0.4f)
        val r = size.minDimension * 0.75f * (0.92f + 0.08f * flick)
        drawCircle(Brush.radialGradient(listOf(pal.accent.copy(alpha = 0.45f * flick), pal.warn.copy(alpha = 0.12f * flick), Color.Transparent), center, r), r, center)
    }
}

/** Loading indicator: a cauldron with slime bubbles rising, popping and re-forming. */
@Composable
fun CauldronLoader(height: Dp = 56.dp, modifier: Modifier = Modifier) {
    val pal = AppPalette.current
    val t = rememberInfiniteTransition(label = "brew")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "p")
    val bob = t.animateFloat(-1f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bob")
    Box(modifier.size(height * 1.3f, height * 1.6f), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val top = size.height - height.toPx() * 0.92f
            for (i in 0 until 5) {
                val q = (p.value + i / 5f) % 1f
                val x = size.width * (0.3f + 0.4f * ((i * 37) % 10) / 10f) + sin((q * 3f + i) * PI.toFloat()) * 4.dp.toPx()
                val y = top - q * height.toPx() * 0.55f
                val r = (2.5f + 3.5f * (1f - abs(q - 0.5f) * 2f)).dp.toPx()
                drawCircle(pal.glow.copy(alpha = 0.85f * (1f - q)), r, Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.35f * (1f - q)), r * 0.35f, Offset(x - r * 0.3f, y - r * 0.3f))
            }
        }
        PixelSprite(Sprites.cauldron, height, Modifier.graphicsLayer { translationY = bob.value * 1.5.dp.toPx() })
    }
}

/** A ghost that drifts side to side as well as bobbing — for empty screens. */
@Composable
fun WanderingGhost(height: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "wander")
    val x = t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x")
    val fade = t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2300), RepeatMode.Reverse), label = "fade")
    FloatingGhost(height, modifier.graphicsLayer { translationX = x.value * 14.dp.toPx(); alpha = fade.value; rotationZ = x.value * 4f })
}

/** Draws a sprite (e.g. the seek-bar pumpkin) centred at [center] with crisp pixels. */
fun DrawScope.drawSprite(sprite: Sprite, center: Offset, heightPx: Float) {
    val img = sprite.bitmap()
    val w = heightPx * img.width / img.height
    drawImage(
        img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset((center.x - w / 2).roundToInt(), (center.y - heightPx / 2).roundToInt()),
        dstSize = IntSize(w.roundToInt(), heightPx.roundToInt()), filterQuality = FilterQuality.None
    )
}

/** Sprite helpers usable from any DrawScope */
object HalloweenSpriteDraw {
    fun DrawScope.pumpkin(center: Offset, heightPx: Float) = drawSprite(Sprites.pumpkin, center, heightPx)
}
