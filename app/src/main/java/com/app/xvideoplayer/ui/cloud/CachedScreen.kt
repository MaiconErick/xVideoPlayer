package com.app.xvideoplayer.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.app.xvideoplayer.remote.SmbDownloads
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatFileSize

/** Finished offline copies grouped by the server folder they came from */
data class CachedGroup(val key: String, val name: String, val path: String, val items: List<SmbDownloads.Item>) {
    val size get() = items.sumOf { it.size }
}

fun cachedGroups(items: List<SmbDownloads.Item>): List<CachedGroup> =
    items.filter { it.state == SmbDownloads.State.DONE }
        .groupBy { it.serverId to it.path.substringBeforeLast('/', "") }
        .map { (k, list) ->
            CachedGroup(
                "${k.first}|${k.second}", k.second.substringAfterLast('/').ifEmpty { "Server root" }, k.second.ifEmpty { "/" },
                list.sortedBy { it.name.lowercase() }
            )
        }.sortedBy { it.name.lowercase() }

@Composable
fun CachedScreen(onBack: () -> Unit, onPlay: (uri: String) -> Unit, vm: OfflineViewModel = hiltViewModel()) {
    val all by vm.items.collectAsState()
    val groups = remember(all) { cachedGroups(all) }
    var openKey by remember { mutableStateOf<String?>(null) }
    val open = groups.firstOrNull { it.key == openKey }
    var removing by remember { mutableStateOf<List<SmbDownloads.Item>?>(null) }
    val goBack = { if (open != null) openKey = null else onBack() }
    BackHandler(onBack = goBack)

    Column(Modifier.fillMaxSize().background(SurfacePure).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            Column(Modifier.weight(1f)) {
                Text(open?.name ?: "Cached", style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (open != null) "${open.items.size} video${if (open.items.size == 1) "" else "s"}  ·  ${open.size.formatFileSize()}  ·  ${open.path}"
                else "${groups.size} folder${if (groups.size == 1) "" else "s"}  ·  ${groups.sumOf { it.size }.formatFileSize()} on this device"
                Text(sub, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (open != null) IconButton(onClick = { removing = open.items }) { Icon(Icons.Rounded.DeleteOutline, "Remove this folder's copies", tint = TextSecondary) }
        }
        when {
            groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing cached yet.\nLong-press files in a server and tap Cache.", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
            }
            open == null -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { it.key }) { g ->
                    Row(Modifier.fillMaxWidth().clickable { openKey = g.key }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(PlayedGreen.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.FolderSpecial, null, tint = PlayedGreen)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${g.items.size} video${if (g.items.size == 1) "" else "s"}  ·  ${g.size.formatFileSize()}", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                            Text(g.path, style = MaterialTheme.typography.labelSmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = TextTertiary)
                    }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(open.items, key = { it.uri }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { onPlay(item.uri) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.size.formatFileSize(), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                        }
                        IconButton(onClick = { removing = listOf(item) }) { Icon(Icons.Rounded.DeleteOutline, "Remove copy", tint = TextTertiary) }
                    }
                }
            }
        }
    }
    removing?.let { list ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(if (list.size == 1) "Remove this copy?" else "Remove ${list.size} copies?") },
            text = { Text("Frees ${list.sumOf { it.size }.formatFileSize()} on this device. Nothing on the server is touched.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { vm.remove(list.map { it.uri }); removing = null }) { Text("Remove", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceElevated2
        )
    }
}
