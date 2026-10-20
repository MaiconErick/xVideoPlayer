package com.app.xvideoplayer.ui.cloud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.app.xvideoplayer.remote.SmbDownloads
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatFileSize
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OfflineViewModel @Inject constructor(val downloads: SmbDownloads) : ViewModel() {
    val items = downloads.items
    val overall = downloads.overall
    fun remove(uris: Collection<String>) = downloads.remove(uris)
    fun retry(uri: String? = null) = downloads.retry(uri)
    fun cancelAll() = downloads.cancelAll()
}

/** Asks once for the notification permission (Android 13+) so the overall progress shows in the shade too. */
@Composable
fun rememberNotificationAsk(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember {
        {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

fun etaText(seconds: Long): String = when {
    seconds < 0 -> ""
    seconds < 60 -> "${seconds}s left"
    seconds < 3600 -> "${seconds / 60} min left"
    else -> "${seconds / 3600} h ${seconds % 3600 / 60} min left"
}

/**
 * The overall bar: "Caching 2 of 5 · 1.2 GB / 4.5 GB · 11 MB/s" over a thick progress bar.
 * Shown above the bottom tabs (any tab) and at the bottom of the server browser.
 */
@Composable
fun OverallDownloadBar(modifier: Modifier = Modifier, vm: OfflineViewModel = androidx.hilt.navigation.compose.hiltViewModel(), onClick: (() -> Unit)? = null) {
    val o by vm.overall.collectAsState()
    AnimatedVisibility(o.active, modifier = modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val shown by animateFloatAsState(o.fraction, tween(400), label = "overall")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceElevated2)
                .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DownloadForOffline, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Caching ${minOf(o.finished + 1, o.count)} of ${o.count}  ·  ${(o.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1
                    )
                    Text(
                        listOf(
                            "${o.done.formatFileSize()} / ${o.total.formatFileSize()}",
                            if (o.speed > 0) "${o.speed.formatFileSize()}/s" else "", etaText(o.etaSeconds)
                        ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1
                    )
                }
                IconButton(onClick = vm::cancelAll) { Icon(Icons.Rounded.Close, "Cancel all downloads", tint = TextSecondary) }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.padding(end = 10.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(SurfaceElevated4)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(Accent))
            }
            o.current?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 10.dp))
            }
        }
    }
}

/** Per-file progress line used in the browser rows and the Offline copies list */
@Composable
fun ItemProgress(item: SmbDownloads.Item, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(item.fraction, tween(350), label = "item")
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(SurfaceElevated4)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(if (item.state == SmbDownloads.State.FAILED) ErrorRed else Accent))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            when (item.state) {
                SmbDownloads.State.QUEUED -> "Queued" + if (item.done > 0) "  ·  ${(item.fraction * 100).toInt()}% kept" else ""
                SmbDownloads.State.RUNNING -> "${(item.fraction * 100).toInt()}%  ·  ${item.done.formatFileSize()} / ${item.size.formatFileSize()}" +
                    if (item.speed > 0) "  ·  ${item.speed.formatFileSize()}/s" else ""
                SmbDownloads.State.FAILED -> "Failed: ${item.error ?: "?"}  ·  ${(item.fraction * 100).toInt()}% kept"
                SmbDownloads.State.DONE -> "Offline copy"
            },
            style = MaterialTheme.typography.labelSmall, color = if (item.state == SmbDownloads.State.FAILED) ErrorRed else TextTertiary, maxLines = 1
        )
    }
}

/** Network tab: every offline copy with its progress, total size and remove/retry buttons */
@Composable
fun OfflineCopiesSection(vm: OfflineViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val items by vm.items.collectAsState()
    if (items.isEmpty()) return
    var confirmAll by remember { mutableStateOf(false) }
    val doneSize = items.filter { it.state == SmbDownloads.State.DONE }.sumOf { it.size }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offline copies", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text("${items.count { it.state == SmbDownloads.State.DONE }} ready  ·  ${doneSize.formatFileSize()} on this device", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
            if (items.any { it.state == SmbDownloads.State.FAILED }) TextButton(onClick = { vm.retry() }) { Text("Retry failed", color = Accent) }
            TextButton(onClick = { confirmAll = true }) { Text("Remove all", color = ErrorRed) }
        }
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (item.state == SmbDownloads.State.DONE) PlayedGreen.copy(alpha = 0.18f) else AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(
                        when (item.state) { SmbDownloads.State.DONE -> Icons.Rounded.OfflinePin; SmbDownloads.State.FAILED -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.Downloading },
                        null, tint = when (item.state) { SmbDownloads.State.DONE -> PlayedGreen; SmbDownloads.State.FAILED -> ErrorRed; else -> Accent }
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.state == SmbDownloads.State.DONE) Text("${item.size.formatFileSize()}  ·  ${item.path.substringBeforeLast('/')}", style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else ItemProgress(item, Modifier.padding(top = 4.dp))
                }
                if (item.state == SmbDownloads.State.FAILED) IconButton(onClick = { vm.retry(item.uri) }) { Icon(Icons.Rounded.Refresh, "Retry", tint = Accent) }
                IconButton(onClick = { vm.remove(listOf(item.uri)) }) {
                    Icon(if (item.state == SmbDownloads.State.DONE) Icons.Rounded.DeleteOutline else Icons.Rounded.Close, "Remove", tint = TextTertiary)
                }
            }
        }
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Remove all offline copies?") },
        text = { Text("Frees ${doneSize.formatFileSize()} and stops any running download. Nothing on the server is touched.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { vm.remove(items.map { it.uri }); confirmAll = false }) { Text("Remove", color = ErrorRed) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2
    )
}
