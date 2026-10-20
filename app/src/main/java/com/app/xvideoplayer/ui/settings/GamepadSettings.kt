package com.app.xvideoplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.*
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GamepadSettingsViewModel @Inject constructor(private val prefs: AppPreferences) : ViewModel() {
    val map = prefs.playerSettings.map { GamepadMap.parse(it.gamepadJson) }.stateIn(viewModelScope, SharingStarted.Eagerly, GamepadMap.DEFAULT)
    fun save(m: Map<PadFeature, Int>) { viewModelScope.launch { prefs.setGamepadMap(GamepadMap.encode(m)) } }
}

/**
 * Pick a feature (tabs group them), press a button on the controller, done. A button that already
 * does something else is called out, in the list and again when you try to reuse it.
 */
@Composable
fun GamepadSettingsDialog(onDismiss: () -> Unit, vm: GamepadSettingsViewModel = hiltViewModel()) {
    val map by vm.map.collectAsState()
    var tab by remember { mutableStateOf(PadTab.PLAYBACK) }
    var listening by remember { mutableStateOf<PadFeature?>(null) }
    var conflict by remember { mutableStateOf<Triple<PadFeature, Int, PadFeature>?>(null) } // feature wanting code, code, current owner
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // while "Press a button…" is showing, the next controller button goes here instead of to the player
    DisposableEffect(listening) {
        val f = listening
        if (f != null) com.app.xvideoplayer.data.model.GamepadRouter.capture = { code ->
            val owner = map.entries.firstOrNull { it.value == code && it.key != f }?.key
            if (owner != null) conflict = Triple(f, code, owner)
            else vm.save(map + (f to code))
            listening = null
        }
        onDispose { com.app.xvideoplayer.data.model.GamepadRouter.capture = null }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(SurfacePure).statusBarsPadding().navigationBarsPadding()
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { com.app.xvideoplayer.data.model.GamepadRouter.onKey(it.nativeKeyEvent) }
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
                Text("Gamepad controls", style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.save(GamepadMap.DEFAULT) }) { Text("Defaults", color = Accent) }
            }
            ScrollableTabRow(selectedTabIndex = PadTab.entries.indexOf(tab), containerColor = Color.Transparent, edgePadding = 12.dp, divider = {}) {
                PadTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label, color = if (tab == t) Accent else TextSecondary) }) }
            }
            Text(
                "Tap a feature, then press the button you want for it.", color = TextTertiary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PadFeature.entries.filter { it.tab == tab }, key = { it.name }) { f ->
                    val code = map[f]
                    val sharedWith = if (code != null) map.entries.filter { it.value == code && it.key != f }.map { it.key } else emptyList()
                    val isListening = listening == f
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (isListening) AccentSoft else SurfaceElevated2)
                            .border(1.dp, if (isListening) Accent else Border, RoundedCornerShape(14.dp))
                            .clickable { conflict = null; listening = if (isListening) null else f }.padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                when { isListening -> "Press a button…"; code != null -> GamepadMap.keyName(code); else -> "Not set" },
                                color = when { isListening -> Accent; code != null -> AccentBright; else -> TextTertiary }, style = MaterialTheme.typography.titleSmall
                            )
                            if (code != null && !isListening) IconButton(onClick = { vm.save(map - f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Clear", tint = TextTertiary, modifier = Modifier.size(18.dp)) }
                        }
                        if (sharedWith.isNotEmpty()) Text(
                            "⚠ ${GamepadMap.keyName(code!!)} also does: ${sharedWith.joinToString { it.label }}", color = WarningAmber,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                        )
                        conflict?.takeIf { it.first == f }?.let { (_, c, owner) ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${GamepadMap.keyName(c)} is already used by “${owner.label}”.", color = ErrorRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.save(map - owner + (f to c)); conflict = null }) { Text("Replace", color = Accent) }
                                TextButton(onClick = { conflict = null }) { Text("Keep", color = TextSecondary) }
                            }
                        }
                    }
                }
            }
        }
    }
}
