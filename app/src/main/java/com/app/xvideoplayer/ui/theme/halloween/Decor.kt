package com.app.xvideoplayer.ui.theme.halloween

// Sprites and animations borrowed from xVideoWatcher (DEV/Kotlin/Projects/xVideoWatcher)

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Ghost that bobs up and down. */
@Composable
fun FloatingGhost(height: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ghost")
    val dy by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "dy")
    PixelSprite(Sprites.ghost, height, modifier.graphicsLayer { translationY = -dy * 6.dp.toPx() })
}

/** Bat that flaps its wings in place. */
@Composable
fun FlappingBat(height: Dp, modifier: Modifier = Modifier, periodMs: Int = 260) {
    val t = rememberInfiniteTransition(label = "bat")
    val phase by t.animateFloat(0f, 2f, infiniteRepeatable(tween(periodMs * 2, easing = LinearEasing)), label = "flap")
    PixelSprite(if (phase < 1f) Sprites.bat1 else Sprites.bat2, height, modifier)
}

/** Bat flying across the width of its parent on a wavy path. */
@Composable
fun BatFlyBy(modifier: Modifier = Modifier, durationMs: Int = 9000, size: Dp = 22.dp, delayFraction: Float = 0f) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val t = rememberInfiniteTransition(label = "flyby")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "p")
        val q = (p + delayFraction) % 1f
        FlappingBat(
            size,
            Modifier.graphicsLayer {
                translationX = (-0.15f + q * 1.3f) * w.toPx()
                translationY = sin(q * 12f) * 8.dp.toPx()
            },
        )
    }
}

/** Spider hanging from a thread, swinging gently. Anchor is the top-center of this composable. */
@Composable
fun HangingSpider(thread: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spider")
    val a by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "swing")
    val drop by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3100), RepeatMode.Reverse), label = "drop")
    Box(modifier.graphicsLayer {
        rotationZ = a * 6f
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }) {
        val len = thread + 10.dp * drop
        Canvas(Modifier.offset(x = 9.dp).size(1.dp, len)) {
            drawLine(Color(0x88A0A0B8), Offset(0f, 0f), Offset(0f, len.toPx()), strokeWidth = 1.dp.toPx())
        }
        PixelSprite(Sprites.spider, 18.dp, Modifier.offset(y = len - 4.dp))
    }
}

/** Cobweb drawn into the top-left or top-right corner of the parent. */
@Composable
fun Cobweb(size: Dp, right: Boolean, modifier: Modifier = Modifier, color: Color = Color(0x40F2E8D5)) {
    Canvas(modifier.size(size).graphicsLayer { if (right) scaleX = -1f }) {
        val r = size.toPx()
        val spokes = 6
        val rings = 4
        val stroke = 1.dp.toPx()
        val angles = (0..spokes).map { (Math.PI / 2) * it / spokes }
        for (ang in angles) {
            drawLine(color, Offset.Zero, Offset((cos(ang) * r).toFloat(), (sin(ang) * r).toFloat()), stroke)
        }
        for (ring in 1..rings) {
            val rr = r * ring / (rings + 0.3f)
            val path = Path()
            angles.forEachIndexed { i, ang ->
                val x = (cos(ang) * rr).toFloat()
                val y = (sin(ang) * rr).toFloat()
                if (i == 0) path.moveTo(x, y) else {
                    val prev = angles[i - 1]
                    val mid = (prev + ang) / 2
                    val sag = rr * 0.82f
                    path.quadraticBezierTo((cos(mid) * sag).toFloat(), (sin(mid) * sag).toFloat(), x, y)
                }
            }
            drawPath(path, color, style = Stroke(stroke))
        }
    }
}
