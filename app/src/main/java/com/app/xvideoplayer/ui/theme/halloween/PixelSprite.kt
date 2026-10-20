package com.app.xvideoplayer.ui.theme.halloween

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.size
import android.graphics.Bitmap

private val cache = HashMap<String, ImageBitmap>()

/** Builds the sprite once at 1px-per-pixel; drawing scales it with nearest-neighbour filtering. */
fun Sprite.bitmap(): ImageBitmap = cache.getOrPut(id) {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            Sprites.palette[ch]?.let { bmp.setPixel(x, y, it.toInt()) }
        }
    }
    bmp.asImageBitmap()
}

@Composable
fun PixelSprite(sprite: Sprite, height: Dp, modifier: Modifier = Modifier) {
    val img = sprite.bitmap()
    val width = height * (sprite.width.toFloat() / sprite.height)
    Canvas(modifier.size(width, height)) {
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
