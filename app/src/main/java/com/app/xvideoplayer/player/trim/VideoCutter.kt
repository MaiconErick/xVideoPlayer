package com.app.xvideoplayer.player.trim

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * Packet index of a file: every packet's time and size (all streams), plus video keyframe times.
 * [sizePrefix] lets the size of any time window be summed in O(log n).
 */
class PacketIndex(
    val keyframesMs: LongArray,
    private val timesMs: LongArray,
    private val sizePrefix: LongArray
) {
    val totalBytes: Long get() = sizePrefix.lastOrNull() ?: 0L

    /** Bytes of all packets with start ≤ t < end — what a stream-copy cut will contain (minus a little container overhead). */
    fun bytesBetween(startMs: Long, endMs: Long): Long {
        if (timesMs.isEmpty() || endMs <= startMs) return 0L
        val a = lowerBound(timesMs, startMs)
        val b = lowerBound(timesMs, endMs)
        return sizePrefix[b] - sizePrefix[a]
    }

    /** The keyframe a copy-cut starting at [ms] really starts from (largest keyframe ≤ ms). */
    fun keyframeAtOrBefore(ms: Long): Long {
        if (keyframesMs.isEmpty()) return ms
        val i = lowerBound(keyframesMs, ms + 1) - 1
        return if (i >= 0) keyframesMs[i] else keyframesMs[0]
    }

    fun nextKeyframe(ms: Long): Long? = keyframesMs.getOrNull(lowerBound(keyframesMs, ms + 1))
    fun previousKeyframe(ms: Long): Long? = keyframesMs.getOrNull(lowerBound(keyframesMs, ms) - 1)

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] < v) lo = mid + 1 else hi = mid }
        return lo
    }
}

sealed interface CutResult {
    data class Saved(val uri: Uri, val displayName: String, val bytes: Long) : CutResult
    data class Failed(val message: String) : CutResult
    data object Cancelled : CutResult
}

/**
 * Lossless cutting with FFmpegKit: `-c copy` remux, no re-encoding, so it runs at disk speed.
 * Copy cuts can only begin on a keyframe; the UI snaps the start there so the preview is exact.
 */
@Singleton
class VideoCutter @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile private var runningSession: Long? = null

    /** One ffprobe pass over the packet headers (no decoding). */
    suspend fun index(path: String): PacketIndex? = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "probe_${System.nanoTime()}.txt")
        try {
            val session = FFprobeKit.executeWithArguments(arrayOf(
                "-v", "error",
                "-show_entries", "stream=index,codec_type:packet=stream_index,pts_time,dts_time,size,flags",
                "-of", "csv=nokey=0",
                "-o", out.absolutePath,
                path
            ))
            if (!ReturnCode.isSuccess(session.returnCode) || !out.exists()) return@withContext null
            parse(out)
        } catch (e: Exception) {
            e.printStackTrace(); null
        } finally {
            out.delete()
        }
    }

    private fun parse(file: File): PacketIndex {
        val videoStreams = HashSet<Int>()
        val times = ArrayList<Long>(200_000)
        val sizes = ArrayList<Int>(200_000)
        val keyCandidates = ArrayList<Pair<Int, Long>>(8_000) // (stream, ms) — streams are listed after packets
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("stream,")) {
                    var idx = -1; var type = ""
                    for (kv in line.split(',')) {
                        val i = kv.indexOf('='); if (i < 0) continue
                        when (kv.substring(0, i)) { "index" -> idx = kv.substring(i + 1).toIntOrNull() ?: -1; "codec_type" -> type = kv.substring(i + 1) }
                    }
                    if (type == "video") videoStreams += idx
                    continue
                }
                if (!line.startsWith("packet,")) continue
                var stream = -1; var pts = Double.NaN; var dts = Double.NaN; var size = 0; var flags = ""
                for (kv in line.split(',')) {
                    val i = kv.indexOf('='); if (i < 0) continue
                    val v = kv.substring(i + 1)
                    when (kv.substring(0, i)) {
                        "stream_index" -> stream = v.toIntOrNull() ?: -1
                        "pts_time" -> pts = v.toDoubleOrNull() ?: Double.NaN
                        "dts_time" -> dts = v.toDoubleOrNull() ?: Double.NaN
                        "size" -> size = v.toIntOrNull() ?: 0
                        "flags" -> flags = v
                    }
                }
                val t = if (!pts.isNaN()) pts else dts
                if (t.isNaN()) continue
                val ms = (t * 1000).roundToLong()
                times += ms; sizes += size
                if (flags.startsWith("K")) keyCandidates += stream to ms
            }
        }
        // the first video stream's keyframes are what a copy cut can start on
        val video = videoStreams.minOrNull()
        val keys = keyCandidates.filter { it.first == video }.map { it.second }
        // packets interleave by dts; sort by time for range sums
        val order = times.indices.sortedBy { times[it] }
        val t = LongArray(order.size) { times[order[it]] }
        val prefix = LongArray(order.size + 1)
        for (i in order.indices) prefix[i + 1] = prefix[i] + sizes[order[i]]
        return PacketIndex(keys.sorted().distinct().toLongArray(), t, prefix)
    }

    /**
     * Copies [startMs, endMs) of [inputPath] (start must be a keyframe) into a new file in
     * Movies/xVideoPlayer, same container as the source. [onProgress] gets 0..1.
     */
    suspend fun cut(
        inputPath: String, title: String, startMs: Long, endMs: Long,
        onProgress: (Float) -> Unit
    ): CutResult = withContext(Dispatchers.IO) {
        val ext = inputPath.substringAfterLast('.', "mp4").lowercase()
        val name = "${title.take(80).replace(Regex("[\\\\/:*?\"<>|]"), "_")} (cut ${stamp(startMs)}-${stamp(endMs)}).$ext"
        val mime = when (ext) { "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"; "ts", "m2ts" -> "video/mp2t"; else -> "video/mp4" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/xVideoPlayer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext CutResult.Failed("Couldn't create the output file")
        val output = FFmpegKitConfig.getSafParameterForWrite(context, uri)
        val lengthMs = (endMs - startMs).coerceAtLeast(1)
        val args = arrayOf(
            "-hide_banner", "-y",
            // input seeking on a keyframe + stream copy = exact, instant, lossless
            "-ss", secs(startMs + 1), "-to", secs(endMs),
            "-i", inputPath,
            "-map", "0", "-map", "-0:d?", "-ignore_unknown",
            "-c", "copy",
            "-map_metadata", "0", "-map_chapters", "-1",
            "-avoid_negative_ts", "make_zero",
            output
        )
        val rc = suspendCancellableCoroutine<ReturnCode?> { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(args, { s ->
                runningSession = null
                if (cont.isActive) cont.resume(s.returnCode)
            }, null) { stats ->
                onProgress((stats.time.toFloat() / lengthMs).coerceIn(0f, 1f))
            }
            runningSession = session.sessionId
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        when {
            ReturnCode.isSuccess(rc) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                }
                val bytes = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
                CutResult.Saved(uri, name, bytes)
            }
            else -> {
                runCatching { context.contentResolver.delete(uri, null, null) }
                if (ReturnCode.isCancel(rc)) CutResult.Cancelled else CutResult.Failed("FFmpeg couldn't cut this file")
            }
        }
    }

    fun cancel() { runningSession?.let { FFmpegKit.cancel(it) } }

    private fun secs(ms: Long) = "%.3f".format(java.util.Locale.US, ms / 1000.0)
    private fun stamp(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d-%02d-%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d-%02d".format(s / 60, s % 60)
    }
}
