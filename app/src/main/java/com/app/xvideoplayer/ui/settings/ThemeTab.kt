package com.app.xvideoplayer.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.xvideoplayer.ui.components.pressable
import com.app.xvideoplayer.ui.theme.*
import com.app.xvideoplayer.ui.theme.halloween.FlappingBat
import com.app.xvideoplayer.ui.theme.halloween.HangingSpider
import com.app.xvideoplayer.ui.theme.halloween.PixelSprite
import com.app.xvideoplayer.ui.theme.halloween.Sprites

/** Settings → Theme: pick the look, then AMOLED black or not. */
fun LazyListScope.themeTab(current: Palette, onStyle: (ThemeStyle) -> Unit, onAmoled: (Boolean) -> Unit) {
    item(key = "theme_cards") {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
            Text("LOOK", style = MaterialTheme.typography.labelSmall, color = TextTertiary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            Row(Modifier.widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeCard(Palette.of(ThemeStyle.DEFAULT, current.amoled), "Default", "Clean, ember accent", current.style == ThemeStyle.DEFAULT, Modifier.weight(1f)) { onStyle(ThemeStyle.DEFAULT) }
                ThemeCard(Palette.of(ThemeStyle.HALLOWEEN, current.amoled), "Halloween", "Pumpkins, wisps & bats", current.style == ThemeStyle.HALLOWEEN, Modifier.weight(1f)) { onStyle(ThemeStyle.HALLOWEEN) }
            }
        }
    }
    item(key = "theme_amoled") {
        SettingsGroup("Background") {
            SwitchRow(
                Icons.Rounded.Contrast, "AMOLED black",
                if (current.amoled) "Pure black — saves battery on OLED screens"
                else if (current.halloween) "Moonlit purple night sky" else "Soft dark grey",
                current.amoled, onAmoled
            )
        }
    }
    if (current.halloween) item(key = "theme_note") {
        Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().height(150.dp)) {
            HangingSpider(46.dp, Modifier.align(Alignment.TopEnd).padding(end = 48.dp))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 120.dp)) {
                Text("What changes", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "Wisps and fog drift behind the lists, the selected tab glows like a candle, the seek bar gets a pumpkin, " +
                        "loading brews in a cauldron and empty screens are haunted. Video playback itself stays untouched.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary
                )
            }
        }
    }
}

/** A tiny mock of the app painted in [p]'s own colors, so both looks can be compared side by side. */
@Composable
private fun ThemeCard(p: Palette, title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ring by animateDpAsState(if (selected) 2.5.dp else 1.dp, label = "ring")
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(SurfaceElevated1)
            .border(ring, if (selected) Accent else Border, RoundedCornerShape(20.dp))
            .pressable(onClick).padding(10.dp)
    ) {
        // mini screen
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(14.dp))
                .background(if (p.halloween && !p.amoled) Brush.verticalGradient(listOf(Color(0xFF1A0B2E), p.surface0)) else Brush.verticalGradient(listOf(p.surface0, p.surface0)))
        ) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Videos", color = p.text1, fontSize = 17.sp,
                        fontFamily = if (p.halloween) Creepy else FontFamily.SansSerif,
                        modifier = Modifier.weight(1f)
                    )
                    if (p.halloween) PixelSprite(Sprites.moon, 16.dp)
                }
                Spacer(Modifier.height(8.dp))
                repeat(2) { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp, 20.dp).clip(RoundedCornerShape(5.dp)).background(p.surface3)) {
                            if (p.halloween && i == 0) PixelSprite(Sprites.pumpkin, 14.dp, Modifier.align(Alignment.Center))
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(if (i == 0) 0.8f else 0.6f).height(5.dp).clip(CircleShape).background(p.text2.copy(alpha = 0.6f)))
                            Spacer(Modifier.height(3.dp))
                            Box(Modifier.fillMaxWidth(0.4f).height(4.dp).clip(CircleShape).background(p.text3.copy(alpha = 0.6f)))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // mini seek bar
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(p.surface4)) {
                    Box(Modifier.fillMaxWidth(0.55f).fillMaxHeight().background(
                        if (p.halloween) Brush.horizontalGradient(listOf(p.accent2, p.accent, p.bad)) else Brush.horizontalGradient(listOf(p.accent, p.accent))
                    ))
                }
            }
            if (p.halloween) FlappingBat(12.dp, Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
            if (p.halloween) Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 22.dp).size(5.dp).clip(CircleShape).background(p.glow.copy(alpha = 0.8f)))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
            if (selected) Box(Modifier.size(22.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = OnAccent, modifier = Modifier.size(15.dp))
            }
        }
    }
}
