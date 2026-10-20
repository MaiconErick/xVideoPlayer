package com.app.xvideoplayer.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil model for a local video's poster frame. [backdrop] asks for a tiny version: drawn stretched
 * behind portrait videos it looks blurred without the cost of a live blur while scrolling.
 */
data class VideoThumb(val uri: String, val path: String, val backdrop: Boolean = false)

/**
 * Uses MediaStore's own thumbnail cache (instant after the first time, shared with the
 * gallery) instead of decoding a frame for every list item; falls back to a frame grab.
 */
class VideoThumbFetcher(
    private val data: VideoThumb,
    private val options: Options,
    private val context: Context
) : Fetcher {

    /** Decode only as big as the slot it's drawn in (Coil passes the layout size) */
    private val target: Int = run {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 480
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 270
        maxOf(w, h).coerceIn(160, 720)
    }

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        var bitmap = systemThumbnail() ?: grabFrame() ?: return@withContext null
        if (data.backdrop) {
            val small = Bitmap.createScaledBitmap(bitmap, 24, maxOf(1, 24 * bitmap.height / bitmap.width), true)
            if (small != bitmap) bitmap.recycle()
            bitmap = small
        }
        DrawableResult(bitmap.toDrawable(context.resources), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun systemThumbnail(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !data.uri.startsWith("content://")) return null
        return try {
            context.contentResolver.loadThumbnail(Uri.parse(data.uri), Size(target, target), null)
        } catch (e: Exception) { null }
    }

    private fun grabFrame(): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            if (data.uri.startsWith("content://")) r.setDataSource(context, Uri.parse(data.uri)) else r.setDataSource(data.path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                r.getScaledFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target, target)
            else r.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    class Factory(private val context: Context) : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoThumbFetcher(data, options, context.applicationContext)
    }
}

class VideoThumbKeyer : Keyer<VideoThumb> {
    override fun key(data: VideoThumb, options: Options) = "thumb:${data.uri}:${data.backdrop}"
}
