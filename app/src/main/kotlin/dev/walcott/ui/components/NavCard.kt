package dev.walcott.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens

/** Standard navigation row: icon, title, subtitle, chevron. */
@Composable
fun NavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    position: CardPosition = CardPosition.Single,
    /** True while this section holds a rule edit that hasn't been sent yet (see PendingChip). */
    pending: Boolean = false,
    /**
     * The section this row belongs to. Its icon takes that colour, so a row still says which
     * chapter it came from once the heading has scrolled off the top (see [SectionHeader]).
     */
    accent: SectionAccent? = null,
) {
    val spacing = Tokens.spacing
    val tint = accent?.let { Tokens.accent(it) } ?: MaterialTheme.colorScheme.primary
    WalcottCard(onClick = onClick, position = position) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pending) PendingChip(Modifier.padding(top = 4.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
