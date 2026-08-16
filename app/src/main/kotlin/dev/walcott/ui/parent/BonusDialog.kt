package dev.walcott.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.ChildSnapshot
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.theme.Tokens

/** One thing extra time can be granted to: an app the child has, or all of them. */
internal data class BonusApp(val packageName: String, val label: String)

/** The all-apps row is a sentinel, not a package. */
private const val ALL_APPS = dev.walcott.rules.ExtraTime.ALL_APPS

/** Above this many apps the list gets a search box; below it, scrolling is faster than typing. */
private const val SEARCH_THRESHOLD = 7

/**
 * The picker for the child's apps, ordered the way the grant is usually meant: what they have
 * actually been using today first, then alphabetically. A parent giving bonus time is nearly
 * always answering something that just happened on that phone.
 */
internal fun bonusApps(snapshot: ChildSnapshot): List<BonusApp> {
    val usedSeconds = snapshot.usage.associate { it.categoryId to it.seconds }
    return snapshot.apps
        .sortedWith(
            compareByDescending<dev.walcott.sync.InstalledAppInfo> { usedSeconds[it.packageName] ?: 0L }
                .thenBy { it.label.lowercase() },
        )
        .map { BonusApp(it.packageName, it.label) }
}

/**
 * Target + minutes picker for granting unsolicited bonus time to a child device: every app, or
 * one of the ones that child actually has.
 *
 * The targets are a scrolling list of icons and names rather than a wall of chips. A chip row
 * is fine for six values that fit on two lines; a phone with forty apps turned it into a page
 * of identical grey pills, alphabetical, unreadable at a glance and impossible to search — with
 * the minutes and the Grant button pushed off the bottom of the dialog.
 */
@Composable
internal fun BonusDialog(
    /** The child's apps, already ordered (see [bonusApps]). */
    apps: List<BonusApp> = emptyList(),
    /** For app icons: the local inventory misses on the parent, so they come from the sync cache. */
    viewModel: WalcottViewModel,
    /**
     * What it opens on. The general button means every app; opened from the one that has just
     * run out, it means that one — a dialog that lands on "all apps" after the parent tapped a
     * row about Roblox is asking a question they already answered.
     */
    initialTarget: String = ALL_APPS,
    onDismiss: () -> Unit,
    onGrant: (String, Int) -> Unit,
) {
    val spacing = Tokens.spacing
    var target by remember(initialTarget) { mutableStateOf(initialTarget) }
    var minutes by remember { mutableIntStateOf(15) }
    var query by remember { mutableStateOf("") }
    // Icons arriving from a child mid-dialog redraw the rows instead of leaving letter tiles.
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    val matches = remember(apps, query) {
        val needle = query.trim()
        if (needle.isBlank()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.give_bonus)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (apps.size > SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.search_app)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Bounded so the minutes and the buttons below are always on screen, whatever
                // the child has installed.
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    if (query.isBlank()) {
                        item(key = ALL_APPS) {
                            TargetRow(
                                label = stringResource(R.string.request_all_apps),
                                selected = target == ALL_APPS,
                                onClick = { target = ALL_APPS },
                            ) {
                                Box(
                                    Modifier.size(36.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(matches, key = { it.packageName }) { app ->
                        TargetRow(
                            label = app.label,
                            selected = target == app.packageName,
                            onClick = { target = app.packageName },
                        ) {
                            AppIcon(
                                packageName = app.packageName,
                                inventory = viewModel.repository.inventory,
                                size = 36.dp,
                                remoteLoader = { viewModel.childAppIcon(it) },
                                refreshKey = iconRefresh,
                                label = app.label,
                            )
                        }
                    }
                    if (matches.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.bonus_no_app_matches),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(spacing.md),
                            )
                        }
                    }
                }
                HorizontalDivider()
                dev.walcott.ui.components.MinutesChips(value = minutes, onSelect = { minutes = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onGrant(target, minutes) }) {
                Text(stringResource(R.string.request_grant_minutes, minutes))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One selectable target: its icon, its name, and the radio that says it is the one. */
@Composable
private fun TargetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val spacing = Tokens.spacing
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}
