package dev.walcott.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import dev.walcott.ui.theme.SectionAccent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.walcott.ui.theme.Tokens

/**
 * The app-wide card language. Every full-width card goes through here so the whole app
 * shares one radius, one hairline border, and one grouping idiom.
 *
 * Cards that belong to the same section are *connected*: they sit [Tokens.spacing.groupGap]
 * apart and flatten the corners facing each other ([CardPosition]), so membership in a group
 * is visible geometry, not just proximity.
 */
enum class CardPosition { Single, First, Middle, Last }

private val OuterRadius = 20.dp
private val InnerRadius = 6.dp

val CardPosition.shape: RoundedCornerShape
    get() = when (this) {
        CardPosition.Single -> RoundedCornerShape(OuterRadius)
        CardPosition.First -> RoundedCornerShape(
            topStart = OuterRadius, topEnd = OuterRadius, bottomStart = InnerRadius, bottomEnd = InnerRadius,
        )
        CardPosition.Middle -> RoundedCornerShape(InnerRadius)
        CardPosition.Last -> RoundedCornerShape(
            topStart = InnerRadius, topEnd = InnerRadius, bottomStart = OuterRadius, bottomEnd = OuterRadius,
        )
    }

/** Position for the card at [index] of a connected group of [count]. */
fun cardPosition(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.Single
    index == 0 -> CardPosition.First
    index == count - 1 -> CardPosition.Last
    else -> CardPosition.Middle
}

@Composable
fun WalcottCard(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Single,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Plain surface cards get the hairline; tinted cards (alerts, containers) already
    // separate from the background by color alone and would only look noisy with one.
    val border = if (color == MaterialTheme.colorScheme.surface) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val sized = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = position.shape,
            color = color,
            border = border,
            modifier = sized,
            content = content,
        )
    } else {
        Surface(shape = position.shape, color = color, border = border, modifier = sized, content = content)
    }
}

/** Lays out a connected card group: children pass their [CardPosition] themselves. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacing.groupGap),
        content = content,
    )
}

/**
 * The one way a screen introduces a section.
 *
 * Given an [icon] and an [accent] it draws a chapter heading: a tinted keycap carrying the
 * section's icon, sitting in the same column as the icons of the rows below it, so the section
 * reads as a labelled block rather than a gap in a list. The rows take the same accent
 * ([NavCard]), which is the point — on a screen with forty settings the colour of a row's icon
 * still says which chapter it belongs to long after its heading has scrolled away.
 *
 * Without them it stays the quiet eyebrow it has always been, so screens that are one list
 * rather than several chapters are untouched.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    accent: SectionAccent? = null,
) {
    val spacing = Tokens.spacing
    val tint = accent?.let { Tokens.accent(it) }
    if (icon == null || tint == null) {
        Column(modifier.fillMaxWidth().padding(top = spacing.lg, start = spacing.xs, end = spacing.xs)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth()
            // Air above, and the keycap aligned with the row icons underneath: the same
            // start inset the cards give their own icons (see NavCard), so the section and
            // its rows share one vertical line.
            .padding(top = spacing.xl, start = spacing.lg, end = spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(KeycapRadius), color = Tokens.accentTint(accent)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.padding(KeycapPadding).size(KeycapIcon),
                )
            }
            Spacer(Modifier.width(spacing.md))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Hung under the title rather than under the keycap: the supporting line is
                // about the section, not about its icon.
                modifier = Modifier.padding(top = spacing.xs, start = KeycapBox + spacing.md),
            )
        }
    }
}

private val KeycapRadius = 9.dp
private val KeycapPadding = 5.dp
private val KeycapIcon = 18.dp
private val KeycapBox = KeycapIcon + KeycapPadding * 2
