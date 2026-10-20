package com.app.xvideoplayer.ui.cloud

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.repository.ServerRepository
import com.app.xvideoplayer.ui.components.*
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatRelativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CloudViewModel @Inject constructor(private val serverRepository: ServerRepository) : ViewModel() {
    val servers: StateFlow<List<ServerConfig>?> = serverRepository.getAllServers()
        .map<List<ServerConfig>, List<ServerConfig>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun addServer(config: ServerConfig) { viewModelScope.launch { serverRepository.insertServer(config) } }
    fun updateServer(config: ServerConfig) { viewModelScope.launch { serverRepository.updateServer(config) } }
    fun deleteServer(config: ServerConfig) { viewModelScope.launch { serverRepository.deleteServer(config) } }
}

@Composable
fun CloudScreen(onBrowseServer: (Long) -> Unit, onPlayUrl: (String) -> Unit, viewModel: CloudViewModel = hiltViewModel()) {
    val servers by viewModel.servers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var deleting by remember { mutableStateOf<ServerConfig?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Network", style = MaterialTheme.typography.headlineLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                }
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(Icons.Rounded.Dns, "Add server", "SMB or FTP share", Modifier.weight(1f)) { showAdd = true }
                ActionTile(Icons.Rounded.Link, "Play a link", "http, https, ftp", Modifier.weight(1f)) { showUrl = true }
            }
        }
        val list = servers
        if (list != null) {
            if (list.isEmpty()) item(key = "empty") {
                EmptyState(Icons.Rounded.CloudOff, "No servers yet", "Add a Windows/Samba share or an FTP server to stream from it.", modifier = Modifier.height(340.dp))
            } else {
                item(key = "title") { SectionTitle("Saved servers") }
                items(list, key = { it.id }) { s ->
                    ServerRow(s, onClick = { onBrowseServer(s.id) }, onEdit = { editing = s }, onDelete = { deleting = s }, modifier = Modifier.animateItem())
                }
            }
        }
        item(key = "offline") { OfflineCopiesSection() }
    }

    if (showAdd || editing != null) {
        ServerDialog(
            server = editing,
            onConfirm = { cfg -> if (editing != null) viewModel.updateServer(cfg) else viewModel.addServer(cfg); showAdd = false; editing = null },
            onDismiss = { showAdd = false; editing = null }
        )
    }
    if (showUrl) UrlDialog(onConfirm = { showUrl = false; onPlayUrl(it) }, onDismiss = { showUrl = false })
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove server?") },
            text = { Text("\"${s.name}\" will be removed from the app. Nothing on the server is touched.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { viewModel.deleteServer(s); deleting = null }) { Text("Remove", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(SurfaceElevated2).border(1.dp, Border, RoundedCornerShape(20.dp))
            .pressable(onClick).padding(16.dp)
    ) {
        Box(Modifier.size(40.dp).background(AccentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

@Composable
private fun ServerRow(server: ServerConfig, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().pressable(onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceElevated3), contentAlignment = Alignment.Center) {
            Icon(if (server.type == ServerType.SMB) Icons.Rounded.Dns else Icons.Rounded.Storage, null, tint = TextSecondary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("${server.type.name}  ·  ${server.host}", server.lastConnected?.let { "opened ${it.formatRelativeTime()}" }).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options", tint = TextTertiary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = SurfaceElevated3, shape = RoundedCornerShape(16.dp)) {
                DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Remove", color = ErrorRed) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = ErrorRed) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ServerDialog(server: ServerConfig?, onConfirm: (ServerConfig) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var type by remember { mutableStateOf(server?.type ?: ServerType.SMB) }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "445") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var remotePath by remember { mutableStateOf(server?.remotePath ?: "/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (server != null) "Edit server" else "Add server") },
        text = {
            // scrolls, so in landscape the fields can be reached and the Save/Cancel buttons stay on screen
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerType.entries.forEach { t ->
                        AppChip(t.name, selected = type == t) { type = t; port = if (t == ServerType.SMB) "445" else "21" }
                    }
                }
                AppTextField(host, { host = it }, "Host", placeholder = "192.168.1.10")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(remotePath, { remotePath = it }, "Path", modifier = Modifier.weight(1f), placeholder = "/share/videos")
                    AppTextField(port, { port = it.filter(Char::isDigit) }, "Port", modifier = Modifier.width(92.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                AppTextField(username, { username = it }, "Username (optional)")
                AppTextField(password, { password = it }, "Password", visualTransformation = PasswordVisualTransformation())
                AppTextField(name, { name = it }, "Display name (optional)")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(ServerConfig(
                        id = server?.id ?: 0, name = name.ifBlank { host }, type = type, host = host.trim(),
                        port = port.toIntOrNull() ?: if (type == ServerType.SMB) 445 else 21,
                        username = username, password = password,
                        remotePath = remotePath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" },
                        streamMode = server?.streamMode ?: StreamMode.STREAM
                    ))
                },
                enabled = host.isNotBlank()
            ) { Text("Save", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun UrlDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play a link") },
        text = { AppTextField(url, { url = it }, "Video URL", placeholder = "https://…", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) },
        confirmButton = { TextButton(onClick = { onConfirm(url.trim()) }, enabled = url.isNotBlank()) { Text("Play", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceElevated2, shape = RoundedCornerShape(24.dp)
    )
}
