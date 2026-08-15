package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.InstalledAppInfo
import dev.walcott.sync.UsageEntry
import dev.walcott.sync.UsageReport
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import java.time.Duration

/**
 * What to call a usage row.
 *
 * A counter is keyed by package name, and the child's own reported app list is what turns that
 * into something a parent recognises — they may never have heard of `com.zhiliaoapp.musically`.
 * Two cases need help beyond that lookup: the [UsageReport.OTHER] bucket the long tail is folded
 * into, which is not an app at all, and a package the child no longer reports (uninstalled since,
 * or trimmed off a degraded snapshot), where the raw name is still better than a blank.
 */
@Composable
fun usageLabel(entry: UsageEntry, apps: List<InstalledAppInfo>): String =
    if (entry.categoryId == UsageReport.OTHER) {
        stringResource(R.string.usage_other_apps)
    } else {
        apps.firstOrNull { it.packageName == entry.categoryId }?.label ?: entry.categoryId
    }

/**
 * One line of "where the day went": the app, then how long.
 *
 * The icon does most of the recognising. A parent scanning this list is matching it against
 * what they have watched their child open, and a column of names — several of which are the
 * raw package, because [usageLabel] can only fall back to that when the child no longer
 * reports the app — is a list you have to read rather than one you can take in. The icons are
 * the ones the children already send over sync (see [dev.walcott.sync.IconSync]); a package
 * with none yet leaves the placeholder rather than shifting the row.
 *
 * The [UsageReport.OTHER] bucket gets the generic apps glyph on purpose: it is a sum of the
 * long tail, not an app, and giving it one app's icon would say something untrue about it.
 */
@Composable
fun UsageRow(entry: UsageEntry, apps: List<InstalledAppInfo>, viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (entry.categoryId == UsageReport.OTHER) {
            Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            AppIcon(
                packageName = entry.categoryId,
                inventory = viewModel.repository.inventory,
                size = ICON_SIZE,
                // The parent doesn't have the child's apps installed, so the local lookup
                // misses and this is what answers (see AppIcon).
                remoteLoader = { viewModel.childAppIcon(it) },
                refreshKey = iconRefresh,
                label = usageLabel(entry, apps),
            )
        }
        Text(
            usageLabel(entry, apps),
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(Duration.ofSeconds(entry.seconds).humanize(), style = MaterialTheme.typography.bodyMedium)
    }
}

private val ICON_SIZE = 28.dp
