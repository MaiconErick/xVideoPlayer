package com.app.xvideoplayer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.DecimalFormat

/** Format milliseconds to HH:MM:SS or MM:SS */
fun Long.formatDuration(): String {
    if (this <= 0) return "00:00"
    val totalSec = this / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

/** Format milliseconds to HH:MM:SS.ff with frame precision */
fun Long.formatDurationWithFrames(): String {
    if (this <= 0) return "00:00.00"
    val totalSec = this / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val ms = (this % 1000) / 10
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, ms)
    else "%02d:%02d.%02d".format(m, s, ms)
}

/** Format file size to human readable */
private val SIZE_FORMAT = ThreadLocal.withInitial { DecimalFormat("#.##") }

fun Long.formatFileSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val df = SIZE_FORMAT.get()!!
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "${df.format(size)} ${units[unitIndex]}"
}

/** Bytes per second → "3.4 MB/s" */
fun Long.formatSpeed(): String = if (this <= 0) "0 KB/s" else "${formatFileSize()}/s"

/** Format Unix timestamp to relative time string */
private val DATE_FORMAT = ThreadLocal.withInitial { java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US) }

fun Long.formatRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> {
            val sdf = DATE_FORMAT.get()!!
            sdf.format(java.util.Date(this))
        }
    }
}

/** Get required permissions based on API level */
fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

fun Context.hasVideoPermission(): Boolean {
    return getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

/** Speed presets for playback */
val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f)

/** Video file extensions */
val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
    "ts", "mts", "m2ts", "3gp", "3g2", "ogv", "mpg", "mpeg", "vob"
)
