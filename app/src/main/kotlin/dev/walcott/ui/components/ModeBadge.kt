package dev.walcott.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.SupervisorAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.sync.DeviceMode
import dev.walcott.ui.theme.Tokens

/** Small pill that keeps the current device mode visible at all times. */
@Composable
fun ModeBadge(mode: DeviceMode, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val parent = mode == DeviceMode.PARENT
    val container = if (parent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (parent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (parent) Icons.Outlined.SupervisorAccount else Icons.Outlined.Face,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(spacing.xs))
            Text(
                stringResource(if (parent) R.string.badge_parent_mode else R.string.badge_child_mode),
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}
