package dev.walcott.ui.parent

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.walcott.R
import dev.walcott.sync.InstalledAppInfo
import dev.walcott.sync.UsageEntry
import dev.walcott.sync.UsageReport

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
