package dev.walcott.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val WalcottTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Large display figures for the time counters. */
val NumberDisplay = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1).sp)

@Composable
fun WalcottTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) WalcottDarkColors else WalcottLightColors
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides Motion(),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = WalcottTypography,
            content = content,
        )
    }
}

/** Convenient token access from any composable. */
object Tokens {
    val spacing: Spacing
        @Composable get() = LocalSpacing.current
    val motion: Motion
        @Composable get() = LocalMotion.current

    /**
     * The signature hero gradient: primary sliding into a deepened indigo. Reserved for the
     * two hero surfaces (the child's "your time today" and the parent's family card) so it
     * stays a signature, not wallpaper. Derived from the scheme, so it adapts to dark mode.
     */
    val heroBrush: Brush
        @Composable get() {
            val primary = MaterialTheme.colorScheme.primary
            return Brush.linearGradient(
                listOf(primary, lerp(primary, Color(0xFF120A3C), 0.38f)),
            )
        }
}
