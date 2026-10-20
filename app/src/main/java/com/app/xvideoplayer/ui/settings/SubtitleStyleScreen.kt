package com.app.xvideoplayer.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.xvideoplayer.data.model.SubtitleStyle
import com.app.xvideoplayer.data.preferences.AppPreferences
import com.app.xvideoplayer.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubtitleStyleViewModel @Inject constructor(private val appPreferences: AppPreferences) : ViewModel() {
    val style = appPreferences.subtitleStyle.stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleStyle())
    fun updateStyle(style: SubtitleStyle) { viewModelScope.launch { appPreferences.setSubtitleStyle(style) } }
    fun resetToDefault() { viewModelScope.launch { appPreferences.setSubtitleStyle(SubtitleStyle()) } }
}

private fun Long.argb() = Color(this.toInt())

@Composable
fun SubtitleStyleScreen(onBack: () -> Unit, viewModel: SubtitleStyleViewModel = hiltViewModel()) {
    val style by viewModel.style.collectAsState()
    val update = viewModel::updateStyle

    Column(Modifier.fillMaxSize().background(SurfacePure)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = TextPrimary) }
            Text("Subtitle style", style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::resetToDefault) { Text("Reset", color = Accent) }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                // preview stays visible on the left, the options scroll on the right
                Row(Modifier.fillMaxSize()) {
                    SubtitlePreview(style, Modifier.weight(0.45f).padding(start = 16.dp, end = 8.dp, top = 8.dp).align(Alignment.Top))
                    LazyColumn(Modifier.weight(0.55f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 32.dp)) {
                        item { SubtitleStyleGroups(style, update) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    SubtitlePreview(style, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 520.dp).padding(horizontal = 16.dp, vertical = 8.dp))
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        item { SubtitleStyleGroups(style, update) }
                    }
                }
            }
        }
    }
}

/** All the style controls; used by this screen and by the live style panel inside the player. */
@Composable
fun SubtitleStyleGroups(style: SubtitleStyle, update: (SubtitleStyle) -> Unit) {
    Column {
        SettingsGroup("Text") {
            SliderRow(Icons.Rounded.FormatSize, "Size", style.fontSize, 12f..56f, "${style.fontSize.toInt()}") { update(style.copy(fontSize = it)) }
            SwitchRow(Icons.Rounded.FormatBold, "Bold", null, style.bold) { update(style.copy(bold = it)) }
            SwitchRow(Icons.Rounded.FormatItalic, "Italic", null, style.italic) { update(style.copy(italic = it)) }
            ColorRow("Color", style.fontColor, TEXT_COLORS) { update(style.copy(fontColor = it)) }
        }
        SettingsGroup("Position") {
            SliderRow(Icons.Rounded.VerticalAlignBottom, "Height on screen", style.positionY, 0.3f..1f, "${(style.positionY * 100).toInt()}%") { update(style.copy(positionY = it)) }
            SliderRow(Icons.Rounded.WidthNormal, "Max width", style.maxWidth, 0.5f..1f, "${(style.maxWidth * 100).toInt()}%") { update(style.copy(maxWidth = it)) }
        }
        SettingsGroup("Outline & shadow") {
            SliderRow(Icons.Rounded.BorderOuter, "Outline", style.outlineWidth, 0f..6f, "%.1f".format(style.outlineWidth)) { update(style.copy(outlineWidth = it)) }
            ColorRow("Outline color", style.outlineColor, TEXT_COLORS) { update(style.copy(outlineColor = it)) }
            SliderRow(Icons.Rounded.Gradient, "Shadow", style.shadowOffsetY, 0f..6f, "%.1f".format(style.shadowOffsetY)) { update(style.copy(shadowOffsetX = it, shadowOffsetY = it)) }
        }
        SettingsGroup("Background box") {
            ColorRow("Box color", style.backgroundColor, BOX_COLORS) { update(style.copy(backgroundColor = it)) }
        }
        SettingsGroup("Font") {
            SubFontList(style.fontFamily) { update(style.copy(fontFamily = it)) }
        }
    }
}

/** Every bundled font, each row drawn in its own font. */
@Composable
private fun SubFontList(current: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        com.app.xvideoplayer.player.SubtitleFonts.ALL.forEach { f ->
            val selected = f.key == current
            val family = rememberSubFontFamily(f.key)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) AccentSoft else Color.Transparent)
                    .clickable { onPick(f.key) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("The quick brown fox 字幕 자막", fontFamily = family, fontSize = 18.sp, color = if (selected) AccentBright else TextPrimary, maxLines = 1)
                    Text(f.label, fontSize = 11.sp, color = TextTertiary)
                }
                if (selected) Icon(Icons.Rounded.Check, null, tint = Accent)
            }
        }
    }
}

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFFFFF59D, 0xFFFFEB3B, 0xFF80DEEA, 0xFFA5D6A7, 0xFFFF8A80, 0xFFE0E0E0, 0xFF000000)
private val BOX_COLORS = listOf(0x00000000, 0x66000000, 0x99000000, 0xCC000000, 0xFF000000, 0x99FFFFFF)

@Composable
private fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    val family = rememberSubFontFamily(style.fontFamily)
    val base = TextStyle(
        fontFamily = family, fontSize = (style.fontSize * 0.75f).sp, textAlign = TextAlign.Center,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal
    )
    val bottomPad by animateDpAsState(((1f - style.positionY) * 140).dp + 8.dp, label = "pos")
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2C3E50), Color(0xFF4CA1AF), Color(0xFFC4E0E5))))
            .border(1.dp, Border, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            Modifier.padding(bottom = bottomPad).fillMaxWidth(style.maxWidth)
                .wrapContentWidth()
                .clip(RoundedCornerShape(6.dp)).background(style.backgroundColor.argb()).padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            val text = "The quick brown fox\njumps over the lazy dog"
            if (style.outlineWidth > 0f) Text(text, style = base.copy(color = style.outlineColor.argb(), drawStyle = Stroke(width = style.outlineWidth * 3f)))
            Text(
                text,
                style = base.copy(
                    color = style.fontColor.argb(),
                    shadow = if (style.shadowOffsetY > 0f) Shadow(style.shadowColor.argb(), Offset(style.shadowOffsetX * 2, style.shadowOffsetY * 2), 4f) else null
                )
            )
        }
    }
}

@Composable
private fun ColorRow(title: String, current: Long, colors: List<Long>, onPick: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(colors) { c ->
                val selected = c == current
                val ring by animateDpAsState(if (selected) 3.dp else 1.dp, label = "ring")
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF444444), Color(0xFF888888))))
                        .background(c.argb())
                        .border(ring, if (selected) Accent else BorderStrong, CircleShape)
                        .clickable { onPick(c) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Rounded.Check, null, tint = if ((c and 0xFFFFFF) > 0x888888 && (c ushr 24) > 0x80) Color.Black else Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Compose-side font for a subtitle font key (bundled files are loaded straight from assets). */
@Composable
fun rememberSubFontFamily(key: String): FontFamily {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(key) {
        val f = com.app.xvideoplayer.player.SubtitleFonts.ALL.firstOrNull { it.key == key }
        when {
            f == null || key == "sans-serif" -> FontFamily.SansSerif
            key == "monospace" -> FontFamily.Monospace
            f.asset != null -> runCatching { FontFamily(androidx.compose.ui.text.font.Font("subfonts/${f.asset}", ctx.assets)) }.getOrDefault(FontFamily.SansSerif)
            else -> FontFamily.SansSerif
        }
    }
}
