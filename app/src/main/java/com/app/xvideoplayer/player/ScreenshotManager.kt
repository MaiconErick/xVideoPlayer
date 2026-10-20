package com.app.xvideoplayer.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.app.xvideoplayer.data.model.ScreenshotFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves video frames at source resolution into Pictures/xVideoPlayer. The libmpv build has no
 * image encoders, so the frame is copied off mpv's render surface (sized to the video while capturing).
 */
@Singleton
class ScreenshotManager @Inject constructor(@ApplicationContext private val context: Context) {

    fun extensionFor(format: ScreenshotFormat) = when (format) {
        ScreenshotFormat.PNG -> "png"
        ScreenshotFormat.JPEG -> "jpg"
        ScreenshotFormat.WEBP -> "webp"
    }

    /**
     * Copies the surface's pixels into a [outWidth]×[outHeight] bitmap, cropped to [videoRect].
     * Pass the surface's buffer size to get it 1:1 (the player temporarily sizes the buffer to the video).
     */
    suspend fun capture(view: SurfaceView, videoRect: Rect, outWidth: Int = view.width, outHeight: Int = view.height): Bitmap? = withContext(Dispatchers.Main) {
        if (outWidth <= 0 || outHeight <= 0 || !view.holder.surface.isValid) return@withContext null
        val full = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            PixelCopy.request(view, full, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        if (!ok) return@withContext null
        val r = Rect(videoRect).apply { intersect(0, 0, full.width, full.height) }
        if (r.width() <= 0 || r.height() <= 0 || (r.width() == full.width && r.height() == full.height)) full
        else Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()).also { full.recycle() }
    }

    suspend fun saveToGallery(bitmap: Bitmap, format: ScreenshotFormat, videoTitle: String, positionMs: Long, suffix: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            val ext = extensionFor(format)
            val mime = when (ext) { "jpg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
            val compress = when (format) {
                ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
                ScreenshotFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ScreenshotFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            val safeTitle = videoTitle.replace(Regex("[^\\w\\- ]"), "_").take(60)
            val name = "${safeTitle}_${formatTimestamp(positionMs)}$suffix.$ext"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/xVideoPlayer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                context.contentResolver.openOutputStream(uri)?.use { out -> bitmap.compress(compress, 95, out) }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "xVideoPlayer").apply { mkdirs() }
                val dst = File(dir, name)
                dst.outputStream().use { bitmap.compress(compress, 95, it) }
                dst.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace(); null
        } finally {
            bitmap.recycle()
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val s = ms / 1000
        return "%02d-%02d-%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
