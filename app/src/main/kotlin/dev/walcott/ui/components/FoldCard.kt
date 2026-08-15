package dev.walcott.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import dev.walcott.ui.theme.SectionAccent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.walcott.ui.theme.Tokens

/**
 * A fold that keeps a group of rarely-needed cards out of the way (a child's rule overrides,
 * the technical tail of a screen, optional category limits). One tap opens it in place; the
 * caller owns the expanded state, so each screen decides what "starts open" means.
 */
@Composable
fun FoldCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: SectionAccent? = null,
    position: CardPosition = CardPosition.Single,
) {
    val spacing = Tokens.spacing
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Tokens.motion.medium),
        label = "foldChevron",
    )
    val tint = accent?.let { Tokens.accent(it) } ?: MaterialTheme.colorScheme.primary
    WalcottCard(onClick = onToggle, position = position) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/**
 * A fold that OWNS what it opens.
 *
 * The old arrangement put a fold's rows in the list beside it, at the same indentation and in
 * the same card language as everything else on the screen — so on a page of forty settings
 * there was nothing to say where the opened section ended and the next one began, and the only
 * way to find out was to close it again and watch what disappeared.
 *
 * Here the content is inside: indented a step, and connected to its header by a rail in the
 * section's colour that runs the whole height of it. Containment becomes something the eye can
 * follow rather than something to remember, and closing the fold takes its rows with it because
 * they were never siblings in the first place.
 */
@Composable
fun FoldSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    accent: SectionAccent? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = Tokens.spacing
    val railTint = accent?.let { Tokens.accentTint(it, strong = true) }
        ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.groupGap)) {
        FoldCard(
            icon = icon,
            title = title,
            subtitle = subtitle,
            expanded = expanded,
            onToggle = onToggle,
            accent = accent,
            // Flat where it meets what it opened: the card language already says "this one
            // continues below" that way (see CardPosition).
            position = if (expanded) CardPosition.First else CardPosition.Single,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Tokens.motion.medium, easing = Tokens.motion.emphasized)) + fadeIn(tween(Tokens.motion.fast)),
            exit = shrinkVertically(tween(Tokens.motion.fast)) + fadeOut(tween(Tokens.motion.fast)),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier.width(RailWidth).fillMaxHeight()
                        .padding(vertical = spacing.xs)
                        .clip(RoundedCornerShape(RailWidth))
                        .background(railTint),
                )
                Spacer(Modifier.width(spacing.md))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                    content = content,
                )
            }
        }
    }
}

private val RailWidth = 3.dp
