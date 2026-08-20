package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
fun OverrideScopeBanner(
    childName: String,
    editable: Boolean = true,
    /**
     * Opens this member's own rules — where the switch that owns this section lives.
     *
     * Only offered while the section is INHERITED, which is the state where this banner asks for
     * something that cannot be done on the screen it is written on. Told to turn a switch on
     * "their screen" and left there, the parent has to work out that the screen meant is the
     * member's page, that the switch is inside a fold near the bottom of it, and how to get back.
     * Once the section is theirs the banner is only describing where the edits will land, and
     * there is nothing to go and do.
     */
    onOpenMemberRules: (() -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(top = spacing.sm)) {
        Column(Modifier.padding(spacing.md)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Face,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp),
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
            if (!editable && onOpenMemberRules != null) {
                // Its own line, at the end: the sentence above is three lines of prose in either
                // language, and a button sharing that row would be measured into whatever was
                // left of it. Same corner on every banner, which is the point of a fixed slot.
                TextButton(
                    onClick = onOpenMemberRules,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        stringResource(R.string.override_scope_goto_rules),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
