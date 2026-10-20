package com.app.xvideoplayer.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeStyle { DEFAULT, HALLOWEEN }

/** Every color the UI uses. The active one lives in [AppPalette] and can change at runtime. */
data class Palette(
    val style: ThemeStyle,
    val amoled: Boolean,
    val accent: Color, val accentBright: Color, val accentSoft: Color, val onAccent: Color,
    val surface0: Color, val surface1: Color, val surface2: Color, val surface3: Color, val surface4: Color,
    val border: Color, val borderStrong: Color,
    val text1: Color, val text2: Color, val text3: Color,
    val good: Color, val bad: Color, val warn: Color, val info: Color,
    val overlayActive: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    /** Halloween: second accent (witch purple) and the glow color of wisps */
    val accent2: Color = accent, val glow: Color = accent
) {
    val halloween get() = style == ThemeStyle.HALLOWEEN

    companion object {
        fun of(style: ThemeStyle, amoled: Boolean): Palette = when (style) {
            ThemeStyle.DEFAULT -> if (amoled) DefaultAmoled else DefaultDark
            ThemeStyle.HALLOWEEN -> if (amoled) HalloweenAmoled else HalloweenNight
        }

        private val DefaultAmoled = Palette(
            ThemeStyle.DEFAULT, true,
            Color(0xFFFF7A45), Color(0xFFFF9466), Color(0x29FF7A45), Color(0xFF1A0A03),
            Color(0xFF000000), Color(0xFF0B0B0D), Color(0xFF131316), Color(0xFF1B1B20), Color(0xFF25252B),
            Color(0xFF26262C), Color(0xFF35353D),
            Color(0xFFF4F4F6), Color(0xFFA2A2AD), Color(0xFF6B6B77),
            Color(0xFF4ADE80), Color(0xFFF87171), Color(0xFFFBBF24), Color(0xFF60A5FA),
            Color(0x4DFF7A45), Color(0xFF3B1D10), Color(0xFFFFD3C2)
        )
        private val DefaultDark = DefaultAmoled.copy(
            amoled = false,
            surface0 = Color(0xFF141417), surface1 = Color(0xFF1B1B1F), surface2 = Color(0xFF222227),
            surface3 = Color(0xFF2A2A30), surface4 = Color(0xFF34343B),
            border = Color(0xFF2E2E35), borderStrong = Color(0xFF3E3E47)
        )
        /** Black like AMOLED, but every surface carries a faint crypt-purple tint */
        private val HalloweenAmoled = Palette(
            ThemeStyle.HALLOWEEN, true,
            Color(0xFFFF7A18), Color(0xFFFFA14D), Color(0x2EFF7A18), Color(0xFF1A0A00),
            Color(0xFF000000), Color(0xFF0C0712), Color(0xFF140C1E), Color(0xFF1E1230), Color(0xFF2A1A40),
            Color(0xFF2A1B3D), Color(0xFF3D2858),
            Color(0xFFF2E8D5), Color(0xFFB4A6C9), Color(0xFF7A6C92),
            Color(0xFF7CFF4F), Color(0xFFFF2E4D), Color(0xFFFFD54A), Color(0xFF8B3DFF),
            Color(0x55FF7A18), Color(0xFF3D1A05), Color(0xFFFFD8B0),
            accent2 = Color(0xFF8B3DFF), glow = Color(0xFF7CFF4F)
        )
        /** Moonlit night sky: deep purple instead of black */
        private val HalloweenNight = HalloweenAmoled.copy(
            amoled = false,
            surface0 = Color(0xFF0E0718), surface1 = Color(0xFF170C25), surface2 = Color(0xFF1F1232),
            surface3 = Color(0xFF29183F), surface4 = Color(0xFF34204F),
            border = Color(0xFF34224B), borderStrong = Color(0xFF4A3268)
        )
    }
}

object AppPalette {
    var current: Palette by mutableStateOf(Palette.of(ThemeStyle.DEFAULT, true))
}

private inline val P get() = AppPalette.current

// The names the whole app already uses — now backed by the live palette
val Accent: Color get() = P.accent
val AccentBright: Color get() = P.accentBright
val AccentSoft: Color get() = P.accentSoft
val OnAccent: Color get() = P.onAccent
val Accent2: Color get() = P.accent2

val SurfacePure: Color get() = P.surface0
val SurfaceElevated1: Color get() = P.surface1
val SurfaceElevated2: Color get() = P.surface2
val SurfaceElevated3: Color get() = P.surface3
val SurfaceElevated4: Color get() = P.surface4
val Border: Color get() = P.border
val BorderStrong: Color get() = P.borderStrong

val TextPrimary: Color get() = P.text1
val TextSecondary: Color get() = P.text2
val TextTertiary: Color get() = P.text3

val PlayedGreen: Color get() = P.good
val ErrorRed: Color get() = P.bad
val WarningAmber: Color get() = P.warn
val InfoBlue: Color get() = P.info

// Player overlay (sits on video, stays neutral)
val OverlayBackground = Color(0xB3000000)
val OverlayButton = Color(0x33FFFFFF)
val OverlayButtonActive: Color get() = P.overlayActive
val ScrimTop = Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))

// Older names still referenced by a few screens
val PrimaryDark: Color get() = P.accent
val PrimaryContainer: Color get() = P.primaryContainer
val OnPrimaryContainer: Color get() = P.onPrimaryContainer
val SecondaryDark = Color(0xFF9CA3AF)
