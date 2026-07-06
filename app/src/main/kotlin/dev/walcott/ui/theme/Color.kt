package dev.walcott.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF5B49E0)
private val VioletLight = Color(0xFF9E90FF)

val WalcottLightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1B1240),
    secondary = Color(0xFF2FB37A),
    onSecondary = Color.White,
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF1A1D26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D26),
    surfaceVariant = Color(0xFFECEEF6),
    onSurfaceVariant = Color(0xFF515667),
    outline = Color(0xFFC7CBD9),
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
    surface = Color(0xFF171B24),
    onSurface = Color(0xFFE7E9F0),
    surfaceVariant = Color(0xFF222735),
    onSurfaceVariant = Color(0xFFAAB1C4),
    outline = Color(0xFF3A4152),
    error = Color(0xFFFF6B66),
    onError = Color(0xFF3A0907),
)
