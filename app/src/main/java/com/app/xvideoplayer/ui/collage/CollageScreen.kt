package com.app.xvideoplayer.ui.collage

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.app.xvideoplayer.data.model.PlayerSettings
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.player.CollageShot
import com.app.xvideoplayer.player.ScreenshotManager
import com.app.xvideoplayer.player.ScreenshotSession
import com.app.xvideoplayer.ui.components.AppChip
import com.app.xvideoplayer.ui.components.AppTextField
import com.app.xvideoplayer.ui.components.EmptyState
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.util.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class CollageViewModel @Inject constructor(
    val session: ScreenshotSession,
    private val screenshotManager: ScreenshotManager,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val shots = session.shots
    private val _options = MutableStateFlow(CollageOptions()); val options = _options.asStateFlow()
    private val _saving = MutableStateFlow(false); val saving = _saving.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2); val messages = _messages.asSharedFlow()

    /** Small live preview, re-rendered off the main thread whenever shots or options change */
    val preview: StateFlow<Bitmap?> = combine(shots, _options) { s, o -> s to o }
        .debounce(120)
        .mapLatest { (s, o) -> withContext(Dispatchers.Default) { CollageRenderer.render(s, o, if (o.layout == CollageLayout.GRID) 1200 else 900) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setOptions(o: CollageOptions) { _options.value = o }

    fun save(alsoEach: Boolean, onSaved: () -> Unit) {
        val list = shots.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            _saving.value = true
            val format = appPreferences.playerSettings.first().screenshotFormat
            val o = _options.value
            val title = session.videoTitle.ifBlank { "Collage" }
            val collage = withContext(Dispatchers.Default) { CollageRenderer.render(list, o, CollageRenderer.exportWidth(list, o.layout)) }
            val ok = collage?.let { screenshotManager.saveToGallery(it, format, title, list.first().positionMs, suffix = "_collage") } != null
            var each = 0
            if (alsoEach) for (shot in list) {
                val b = withContext(Dispatchers.Default) { CollageRenderer.renderSingle(shot, o) } ?: continue
                if (screenshotManager.saveToGallery(b, format, title, shot.positionMs) != null) each++
            }
            _saving.value = false
            _messages.tryEmit(if (ok) "Saved to Pictures/xVideoPlayer" + (if (alsoEach) " (+$each single)" else "") else "Couldn't save the collage")
            if (ok) onSaved()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollageScreen(onBack: () -> Unit, viewModel: CollageViewModel = hiltViewModel()) {
    val shots by viewModel.shots.collectAsState()
    val opts by viewModel.options.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val saving by viewModel.saving.collectAsState()
    var picking by remember { mutableStateOf<CollageShot?>(null) }
    var editing by remember { mutableStateOf<CollageShot?>(null) }
    var askSave by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = SurfacePure,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
                Column(Modifier.weight(1f)) {
                    Text("Collage", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("${shots.size} screenshot${if (shots.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                }
                Button(
                    onClick = { askSave = true }, enabled = shots.isNotEmpty() && !saving, shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), color = OnAccent, strokeWidth = 2.dp)
                    else { Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
                }
            }
        }
    ) { pad ->
        if (shots.isEmpty()) {
            EmptyState(Icons.Rounded.Collections, "No screenshots", "Take screenshots with \"With subs\" on in the player.", modifier = Modifier.padding(pad))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp), state = rememberLazyListState(), horizontalAlignment = Alignment.CenterHorizontally) {
            item(key = "preview") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val bmp = preview
                    if (bmp != null) Image(
                        bmp.asImageBitmap(), "Collage preview", contentScale = ContentScale.Fit,
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp))
                    ) else Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceElevated2))
                }
            }
            item(key = "options") {
                Column(Modifier.widthIn(max = 760.dp).padding(horizontal = 16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CollageLayout.entries.forEach { l -> AppChip(l.label, selected = opts.layout == l) { viewModel.setOptions(opts.copy(layout = l)) } }
                    }
                    LabeledSlider("Subtitle size", "${(opts.textSize * 1000).toInt() / 10f}%", opts.textSize, 0.03f..0.14f) { viewModel.setOptions(opts.copy(textSize = it)) }
                    if (opts.layout == CollageLayout.STRIPS)
                        LabeledSlider("Strip height", "${(opts.stripHeight * 100).toInt()}%", opts.stripHeight, 0.1f..0.6f) { viewModel.setOptions(opts.copy(stripHeight = it)) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(0xFFFFFFFF.toInt() to "White", 0xFFFFEB3B.toInt() to "Yellow", 0xFF80DEEA.toInt() to "Cyan").forEach { (c, n) ->
                            AppChip(n, selected = opts.textColor == c) { viewModel.setOptions(opts.copy(textColor = c)) }
                        }
                        AppChip("Bold", selected = opts.bold, icon = Icons.Rounded.FormatBold) { viewModel.setOptions(opts.copy(bold = !opts.bold)) }
                        AppChip("Outline", selected = opts.outline, icon = Icons.Rounded.BorderOuter) { viewModel.setOptions(opts.copy(outline = !opts.outline)) }
                    }
                }
            }
            item(key = "hint") {
                Text("Swipe a subtitle sideways (or tap it) to use a different line", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
            }
            itemsIndexed(shots, key = { _, s -> s.id }) { i, shot ->
                ShotRow(
                    shot = shot, index = i, count = shots.size, opts = opts,
                    onPrevLine = { viewModel.session.setLine(shot.id, (shot.lineIndex - 1).coerceAtLeast(-1)) },
                    onNextLine = { viewModel.session.setLine(shot.id, (shot.lineIndex + 1).coerceAtMost(shot.lines.lastIndex)) },
                    onPick = { picking = shot },
                    onMove = { viewModel.session.move(shot.id, it) },
                    onRemove = { viewModel.session.remove(shot.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    picking?.let { shot ->
        LinePickerSheet(
            shot = shot,
            onPick = { idx -> viewModel.session.setLine(shot.id, idx); picking = null },
            onCustom = { picking = null; editing = shot },
            onDismiss = { picking = null }
        )
    }
    editing?.let { shot ->
        var text by remember(shot.id) { mutableStateOf(shot.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Subtitle text") },
            text = { AppTextField(text, { text = it }, "Text") },
            confirmButton = { TextButton(onClick = { viewModel.session.setCustomText(shot.id, text); editing = null }) { Text("Use", color = Accent) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = TextSecondary) } },
            containerColor = SurfaceElevated2
        )
    }
    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text("Save collage") },
            text = { Text("Save just the collage, or the collage plus each screenshot with its subtitle?", color = TextSecondary) },
            confirmButton = {
                Row {
                    TextButton(onClick = { askSave = false; viewModel.save(alsoEach = true) {} }) { Text("Collage + each", color = TextSecondary) }
                    TextButton(onClick = { askSave = false; viewModel.save(alsoEach = false) {} }) { Text("Collage", color = Accent) }
                }
            },
            dismissButton = {},
            containerColor = SurfaceElevated2
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row { Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(value, color = Accent, style = MaterialTheme.typography.bodyMedium) }
        Slider(v, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = SurfaceElevated4))
    }
}

@Composable
private fun ShotRow(
    shot: CollageShot, index: Int, count: Int, opts: CollageOptions,
    onPrevLine: () -> Unit, onNextLine: () -> Unit, onPick: () -> Unit,
    onMove: (Int) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.widthIn(max = 560.dp).padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceElevated1).border(1.dp, Border, RoundedCornerShape(18.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(shot.width.toFloat() / shot.height.coerceAtLeast(1))
            .pointerInput(shot.id) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -60) onNextLine() else if (total > 60) onPrevLine() }
                ) { _, d -> total += d }
            }
            .clickable(onClick = onPick)
        ) {
            AsyncImage(model = shot.file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            AnimatedContent(
                shot.text, transitionSpec = { (fadeIn(tween(160)) + slideInHorizontally { it / 6 }) togetherWith fadeOut(tween(100)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp), label = "line"
            ) { t ->
                Text(
                    t.ifBlank { "(no subtitle)" }, textAlign = TextAlign.Center,
                    color = if (t.isBlank()) Color(0x99FFFFFF) else Color(opts.textColor),
                    style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black, Offset(0f, 2f), 6f))
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevLine, enabled = shot.lineIndex > -1) { Icon(Icons.Rounded.ChevronLeft, "Previous line", tint = TextSecondary) }
            Text(
                "${shot.positionMs.formatDuration()}  ·  line ${if (shot.lineIndex < 0) "—" else "${shot.lineIndex + 1}/${shot.lines.size}"}",
                color = TextTertiary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onNextLine, enabled = shot.lineIndex < shot.lines.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Next line", tint = TextSecondary) }
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move up", tint = TextSecondary) }
            IconButton(onClick = { onMove(1) }, enabled = index < count - 1) { Icon(Icons.Rounded.ArrowDownward, "Move down", tint = TextSecondary) }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Remove", tint = ErrorRed) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinePickerSheet(shot: CollageShot, onPick: (Int) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated2) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (shot.lineIndex - 2).coerceAtLeast(0))
        Text("Pick a subtitle line", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 460.dp)) {
            item {
                LineRow("No subtitle", null, shot.lineIndex == -1 && shot.customText == null) { onPick(-1) }
            }
            itemsIndexed(shot.lines) { i, line ->
                LineRow(line.text, line.startMs.formatDuration(), i == shot.lineIndex && shot.customText == null) { onPick(i) }
            }
            item {
                LineRow("Write my own…", null, shot.customText != null, icon = Icons.Rounded.Edit) { onCustom() }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun LineRow(text: String, time: String?, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentSoft else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        if (time != null) { Text(time, color = TextTertiary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp)) }
        Text(text, color = if (selected) AccentBright else TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
