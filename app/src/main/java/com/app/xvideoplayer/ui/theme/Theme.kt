package com.app.xvideoplayer.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
private fun colorScheme() = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = TextSecondary,
    onSecondary = SurfacePure,
    secondaryContainer = SurfaceElevated4,
    onSecondaryContainer = TextPrimary,
    background = SurfacePure,
    onBackground = TextPrimary,
    surface = SurfacePure,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated3,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = SurfacePure,
    surfaceContainerLow = SurfaceElevated1,
    surfaceContainer = SurfaceElevated2,
    surfaceContainerHigh = SurfaceElevated3,
    surfaceContainerHighest = SurfaceElevated4,
    surfaceTint = SurfacePure,
    outline = BorderStrong,
    outlineVariant = Border,
    error = ErrorRed,
    onError = SurfacePure,
    scrim = SurfacePure
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun XVideoPlayerTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme(), typography = typographyFor(AppPalette.current.halloween), shapes = AppShapes, content = content)
}
