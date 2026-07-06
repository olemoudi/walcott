package dev.walcott.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Single spacing scale; screens never use loose magic dp values. */
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val screen: Dp = 20.dp,
)

/**
 * Motion tokens. Short and purposeful (the "snappy" principle): nothing exceeds ~250ms.
 */
data class Motion(
    val fast: Int = 140,
    val medium: Int = 220,
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalMotion = staticCompositionLocalOf { Motion() }
