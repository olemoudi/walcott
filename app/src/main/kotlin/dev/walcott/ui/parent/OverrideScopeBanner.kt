package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens

/**
 * Shown at the top of a rule editor opened in one child's scope, so the parent always knows
 * whether a change affects the family or just this child — and, when the child still inherits
 * the section, that they are looking at the family's rules rather than editing anything.
 */
@Composable
fun OverrideScopeBanner(childName: String, editable: Boolean = true) {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(top = spacing.sm)) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Face,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                stringResource(
                    if (editable) R.string.override_scope_banner else R.string.override_scope_banner_inherited,
                    childName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
