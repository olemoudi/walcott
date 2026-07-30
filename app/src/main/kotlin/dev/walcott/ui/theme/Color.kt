package dev.walcott.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF5B49E0)
private val VioletLight = Color(0xFF9E90FF)

// The background sits a clear step below the white cards, and outlineVariant draws the
// hairline card borders — together they are what makes surfaces read as raised, since
// cards carry no shadow or tonal elevation.
val WalcottLightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1B1240),
    secondary = Color(0xFF2FB37A),
    onSecondary = Color.White,
    background = Color(0xFFEEF1F8),
    onBackground = Color(0xFF1A1D26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D26),
    surfaceVariant = Color(0xFFECEEF6),
    onSurfaceVariant = Color(0xFF515667),
    outline = Color(0xFFC7CBD9),
    outlineVariant = Color(0xFFE2E5EF),
    surfaceDim = Color(0xFFD9DCE7),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7FB),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFEAEDF5),
    surfaceContainerHighest = Color(0xFFE4E8F2),
    error = Color(0xFFD3403B),
    onError = Color.White,
)

val WalcottDarkColors = darkColorScheme(
    primary = VioletLight,
    onPrimary = Color(0xFF1B1240),
    primaryContainer = Color(0xFF3B2E9E),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFF57D3A0),
    onSecondary = Color(0xFF08301F),
    background = Color(0xFF0F1218),
    onBackground = Color(0xFFE7E9F0),
    surface = Color(0xFF1A202B),
    onSurface = Color(0xFFE7E9F0),
    surfaceVariant = Color(0xFF232937),
    onSurfaceVariant = Color(0xFFAAB1C4),
    outline = Color(0xFF3A4152),
    outlineVariant = Color(0xFF2A303F),
    surfaceDim = Color(0xFF0F1218),
    surfaceBright = Color(0xFF353D4E),
    surfaceContainerLowest = Color(0xFF0A0D13),
    surfaceContainerLow = Color(0xFF161B25),
    surfaceContainer = Color(0xFF1A202B),
    surfaceContainerHigh = Color(0xFF222836),
    surfaceContainerHighest = Color(0xFF2A3242),
    error = Color(0xFFFF6B66),
    onError = Color(0xFF3A0907),
)
