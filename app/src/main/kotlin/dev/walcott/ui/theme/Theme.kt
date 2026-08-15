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
import dev.walcott.data.ThemeMode

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

/** Whether this preference renders dark right now (SYSTEM follows the device). */
@Composable
fun ThemeMode.resolvesToDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun WalcottTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) WalcottDarkColors else WalcottLightColors
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides Motion(),
        LocalDarkTheme provides darkTheme,
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

    /** The colour a family of settings is recognised by, resolved for the current theme. */
    @Composable
    fun accent(accent: SectionAccent): Color = accent.color(LocalDarkTheme.current)

    /**
     * The same colour as a wash, for the keycap behind a section's icon and the rail down the
     * side of what a fold opened.
     *
     * Stronger on the dark scheme, and not as a matter of taste: the same alpha over a near-black
     * surface lands a fraction of the contrast it lands over white, so a tint chosen in the light
     * disappears in the dark — which is where this family reads their phone.
     */
    @Composable
    fun accentTint(accent: SectionAccent, strong: Boolean = false): Color {
        val dark = LocalDarkTheme.current
        val alpha = when {
            strong -> if (dark) 0.5f else 0.35f
            else -> if (dark) 0.22f else 0.14f
        }
        return accent.color(dark).copy(alpha = alpha)
    }

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
