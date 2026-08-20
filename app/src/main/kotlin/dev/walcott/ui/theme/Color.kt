package dev.walcott.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF5B49E0)
private val VioletLight = Color(0xFF9E90FF)
private val Teal = Color(0xFF0F7B94)

/**
 * The two ends of the map's location trail, and deliberately NOT taken from the colour scheme.
 *
 * Every other surface in this app is one this app chose. The trail is drawn on OpenStreetMap
 * tiles, which are the same bright beige whatever theme the phone is in — so a line picked for a
 * dark surface is a line picked for a background it will never be on. The dark scheme's pale
 * violet lands at roughly 2.7:1 against those tiles and reads as a smudge; these two hold their
 * own on a bright map in either theme, and in light mode they are exactly the palette's own
 * violet and the location section's teal, so nothing changes there.
 */
val MapTrailHead = Violet
val MapTrailTail = Teal

/**
 * The colour each family of settings is recognised by.
 *
 * Parent mode is a long stack of settings, and every one of them used to be the same violet on
 * the same white card: nothing on screen said which chapter a row belonged to once its heading
 * had scrolled away. Four hues, assigned by MEANING and reused on every screen, so the colour is
 * something to learn rather than decoration — the rules are violet wherever they appear.
 *
 * Four, and not one per section: past four, a palette stops helping recognition and starts
 * being a circus. And deliberately clear of the two colours that already mean something in this
 * app — the error red and the green that means "fine, approved" — so a section can never be
 * mistaken for a state.
 */
enum class SectionAccent {
    /** What a child may do: limits, schedules, the web filter, the locks. */
    RULES,

    /** People and places: the children themselves, enrollment, location. */
    FAMILY,

    /** What happened: screen time, reports, the activity wall. */
    ACTIVITY,

    /** The phone rather than the family: updates, logs, diagnostics. */
    DEVICE,
    ;

    internal fun color(dark: Boolean): Color = when (this) {
        RULES -> if (dark) VioletLight else Violet
        FAMILY -> if (dark) Color(0xFF5CC8DF) else Teal
        ACTIVITY -> if (dark) Color(0xFFF0A0CE) else Color(0xFFA0417E)
        DEVICE -> if (dark) Color(0xFF9BA5BC) else Color(0xFF5A6275)
    }
}

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
    // Lighter than the surface it edges by a clear step: on the dark scheme the cards
    // carry no shadow and only a hairline, and at the height of a whole editor a border
    // you cannot quite see is a card that has no end.
    outlineVariant = Color(0xFF39425A),
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
