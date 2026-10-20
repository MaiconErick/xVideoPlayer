package com.app.xvideoplayer.ui.collage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.app.xvideoplayer.player.CollageShot
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class CollageLayout(val label: String) {
    STRIPS("Subtitle strips"),
    STACK("Full frames"),
    GRID("Grid")
}

data class CollageOptions(
    val layout: CollageLayout = CollageLayout.STRIPS,
    /** text height as a fraction of a full frame's height */
    val textSize: Float = 0.075f,
    /** STRIPS: height of each extra strip as a fraction of the frame height (grows to fit the text) */
    val stripHeight: Float = 0.2f,
    val textColor: Int = Color.WHITE,
    val bold: Boolean = false,
    val outline: Boolean = true
)

/**
 * Draws the collage with the Android canvas: frames are the clean captures, subtitles are typeset
 * here so the user can pick any line and size. The same code renders the preview and the export.
 */
object CollageRenderer {

    fun render(shots: List<CollageShot>, opts: CollageOptions, targetWidth: Int): Bitmap? {
        if (shots.isEmpty()) return null
        val cols = if (opts.layout == CollageLayout.GRID) 2 else 1
        val tileW = targetWidth / cols

        data class Tile(val shot: CollageShot, val frameH: Int, val drawH: Int, val layout: StaticLayout?)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, if (opts.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val tiles = shots.mapIndexed { i, shot ->
            val frameH = (tileW.toFloat() * shot.height / shot.width.coerceAtLeast(1)).roundToInt()
            textPaint.textSize = frameH * opts.textSize
            val layout = shot.text.takeIf { it.isNotBlank() }?.let {
                StaticLayout.Builder.obtain(it, 0, it.length, textPaint, (tileW * 0.9f).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
            }
            val margin = frameH * 0.045f
            val drawH = if (opts.layout == CollageLayout.STRIPS && i > 0) {
                val textH = (layout?.height ?: 0) + margin * 2
                maxOf(frameH * opts.stripHeight, textH).roundToInt().coerceAtMost(frameH)
            } else frameH
            Tile(shot, frameH, drawH, layout)
        }

        val rows = tiles.chunked(cols)
        val totalH = rows.sumOf { r -> r.maxOf { it.drawH } }
        val out = Bitmap.createBitmap(targetWidth, totalH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        var y = 0
        for (row in rows) {
            row.forEachIndexed { c, t ->
                val x = c * tileW
                val frame = decode(t.shot, tileW) ?: return@forEachIndexed
                // a strip shows the bottom band of the frame, where subtitles live
                val srcTop = ((1f - t.drawH.toFloat() / t.frameH) * frame.height).roundToInt()
                canvas.drawBitmap(frame, Rect(0, srcTop, frame.width, frame.height), RectF(x.toFloat(), y.toFloat(), (x + tileW).toFloat(), (y + t.drawH).toFloat()), bmpPaint)
                frame.recycle()
                t.layout?.let { drawText(canvas, it, x, y, tileW, t.drawH, t.frameH, opts) }
            }
            y += row.maxOf { it.drawH }
        }
        return out
    }

    private fun drawText(canvas: Canvas, layout: StaticLayout, x: Int, y: Int, w: Int, h: Int, frameH: Int, opts: CollageOptions) {
        val margin = frameH * 0.045f
        val left = x + (w - layout.width) / 2f
        val top = y + h - margin - layout.height
        canvas.save()
        canvas.translate(left, top)
        val p = layout.paint
        if (opts.outline) {
            p.style = Paint.Style.STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = p.textSize * 0.14f
            p.color = Color.BLACK
            layout.draw(canvas)
        }
        p.style = Paint.Style.FILL
        p.color = opts.textColor
        if (!opts.outline) p.setShadowLayer(p.textSize * 0.08f, 0f, p.textSize * 0.04f, Color.BLACK)
        layout.draw(canvas)
        p.clearShadowLayer()
        canvas.restore()
    }

    /** Decodes a capture no bigger than needed for [width] (keeps previews fast). */
    private fun decode(shot: CollageShot, width: Int): Bitmap? {
        val sample = Integer.highestOneBit((shot.width / width.coerceAtLeast(1)).coerceAtLeast(1))
        return BitmapFactory.decodeFile(shot.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** One shot with its text, at the shot's own resolution (for "save each"). */
    fun renderSingle(shot: CollageShot, opts: CollageOptions): Bitmap? =
        render(listOf(shot), opts.copy(layout = CollageLayout.STACK), shot.width)

    fun exportWidth(shots: List<CollageShot>, layout: CollageLayout): Int {
        val w = shots.maxOfOrNull { it.width } ?: 1920
        return if (layout == CollageLayout.GRID) ceil(minOf(w, 1920) * 2.0).toInt().coerceAtMost(3840) else minOf(w, 1920)
    }
}
