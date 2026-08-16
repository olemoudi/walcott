package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.walcott.R
import dev.walcott.rules.Blocklists
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.Tokens

/**
 * The built-in blocklists as switch rows, shared by the setup wizard and the web-filter screen
 * so both offer exactly the same thing — the wizard is a door onto this, not a second feature.
 *
 * Each row says how many domains it stands for, because "Adult content" with no number is a
 * promise of unknown size, and the one list that can plausibly break an app says so on the row
 * rather than in a paragraph somebody has to find later.
 */
@Composable
fun BlocklistRows(
    enabled: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    editable: Boolean = true,
) {
    CardGroup {
        Blocklists.ALL.forEachIndexed { index, id ->
            BlocklistRow(
                id = id,
                checked = id in enabled,
                editable = editable,
                position = cardPosition(index, Blocklists.ALL.size),
                onToggle = { on -> onToggle(id, on) },
            )
        }
    }
}

@Composable
private fun BlocklistRow(
    id: String,
    checked: Boolean,
    editable: Boolean,
    position: CardPosition,
    onToggle: (Boolean) -> Unit,
) {
    val spacing = Tokens.spacing
    val titleRes = when (id) {
        Blocklists.ADULT -> R.string.blocklist_adult_title
        Blocklists.GAMBLING -> R.string.blocklist_gambling_title
        else -> R.string.blocklist_trackers_title
    }
    val descRes = when (id) {
        Blocklists.ADULT -> R.string.blocklist_adult_desc
        Blocklists.GAMBLING -> R.string.blocklist_gambling_desc
        else -> R.string.blocklist_trackers_desc
    }
    WalcottCard(position = position) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    pluralStringResource(
                        R.plurals.blocklist_domain_count,
                        Blocklists.size(id),
                        Blocklists.size(id),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only this one can plausibly get in an app's way, and only while it is on.
                if (id == Blocklists.TRACKERS && checked) {
                    Text(
                        stringResource(R.string.blocklist_trackers_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Switch(checked = checked, enabled = editable, onCheckedChange = onToggle)
        }
    }
}
