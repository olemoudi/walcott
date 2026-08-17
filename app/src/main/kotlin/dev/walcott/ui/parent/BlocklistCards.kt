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
 * promise of unknown size, and every list that can plausibly break an app says so on the row
 * rather than in a paragraph somebody has to find later.
 *
 * A list backed by a public source says "about 42.000" instead of an exact count, and means it:
 * this phone never downloads those lists (it is the parent's, it filters nothing), so the only
 * honest exact number is the one the child reports back once it has them — which is what the
 * child's own screen shows.
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
    val titleRes = blocklistTitle(id)
    val descRes = when (id) {
        Blocklists.ADULT -> R.string.blocklist_adult_desc
        Blocklists.GAMBLING -> R.string.blocklist_gambling_desc
        Blocklists.SOCIAL -> R.string.blocklist_social_desc
        Blocklists.VIDEO -> R.string.blocklist_video_desc
        Blocklists.PIRACY -> R.string.blocklist_piracy_desc
        Blocklists.SCAM -> R.string.blocklist_scam_desc
        Blocklists.BYPASS -> R.string.blocklist_bypass_desc
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
                // What the list stands for, and where it comes from. A public list is an
                // approximation on purpose: this phone never downloads it (it filters nothing),
                // so the exact number is the child's to report, not the parent's to promise.
                Text(
                    if (Blocklists.sources(id).isEmpty()) {
                        pluralStringResource(
                            R.plurals.blocklist_domain_count,
                            Blocklists.seedSize(id),
                            Blocklists.seedSize(id),
                        )
                    } else {
                        stringResource(
                            R.string.blocklist_domain_count_public,
                            roundedCount(Blocklists.approxDomains(id)),
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only some lists can get in the way of an app nobody meant to block, and only
                // while they are on.
                if (Blocklists.mayBreakApps(id) && checked) {
                    Text(
                        stringResource(R.string.blocklist_may_break_warning),
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

/**
 * A list's name, by id. Lives here next to the rows and is used by the child's diagnostics too,
 * so a list the parent switched on is called the same thing wherever it is mentioned.
 */
internal fun blocklistTitle(id: String): Int = when (id) {
    Blocklists.ADULT -> R.string.blocklist_adult_title
    Blocklists.GAMBLING -> R.string.blocklist_gambling_title
    Blocklists.SOCIAL -> R.string.blocklist_social_title
    Blocklists.VIDEO -> R.string.blocklist_video_title
    Blocklists.PIRACY -> R.string.blocklist_piracy_title
    Blocklists.SCAM -> R.string.blocklist_scam_title
    Blocklists.BYPASS -> R.string.blocklist_bypass_title
    else -> R.string.blocklist_trackers_title
}

/**
 * A list's size as a number a person reads rather than a number a machine counted: "42.000",
 * not "42.055". Grouped in the device's locale, and rounded only where the precision would be
 * false anyway — a public list has changed size by the time this screen is drawn.
 */
private fun roundedCount(domains: Int): String {
    val rounded = if (domains >= 2_000) (domains / 1_000) * 1_000 else domains
    return java.text.NumberFormat.getIntegerInstance().format(rounded)
}
