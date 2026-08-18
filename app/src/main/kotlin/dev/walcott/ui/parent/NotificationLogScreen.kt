package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.NotificationEntry
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.Tokens
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What arrived on somebody's phone, for the person supporting them.
 *
 * On demand and never live: the device sends one page when asked ([WalcottViewModel.requestNotifications]),
 * and this screen is the pages it has sent. That shape is deliberate — a log that streamed
 * continuously would be surveillance with a progress bar, and what a family actually needs is the
 * answer to "did the message from the clinic arrive?" at the moment they ask it.
 *
 * The counts are stated plainly, including what is NOT here: one message carries a few dozen
 * entries, and a screen that quietly showed those as the whole day would answer that question
 * wrongly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationLogScreen(
    viewModel: WalcottViewModel,
    deviceId: String,
    memberName: String,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val allPages = syncState.notificationPages[deviceId].orEmpty()
    val apps = remember(syncState) {
        syncState.children.firstOrNull { it.deviceId == deviceId }?.apps.orEmpty()
    }
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }

    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()

    /** "" = every app. Which pages count, what the buttons ask for, and what the counts mean. */
    var filter by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    // Only the pages that answer the question being asked: a page about one app is not part of the
    // list that claims to be everything, and mixing them would answer "what arrived yesterday?"
    // with a filtered subset (see NotificationPayload.pkg).
    val pages = remember(allPages, filter) { allPages.filter { it.pkg == filter } }
    // Pages arrive newest-page-first and each is newest-entry-first; flattening and re-sorting is
    // what makes "load older" produce one continuous list rather than a stack of overlapping ones.
    val entries = remember(pages) {
        pages.flatMap { it.entries }.distinctBy { it.atMs to it.pkg }.sortedByDescending { it.atMs }
    }
    val newest = pages.maxByOrNull { it.atMs }
    val oldestShown = entries.lastOrNull()?.atMs ?: 0L

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.notiflog_screen_title, memberName), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Column(Modifier.padding(vertical = spacing.sm)) {
                    Text(
                        stringResource(R.string.notiflog_screen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (newest != null) {
                        val asked = remember(newest.atMs) { timeOf(newest.atMs) }
                        Text(
                            stringResource(R.string.notiflog_asked_at, asked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Which question is being asked. Narrowing to one app is the lighter, more focused
            // request — fewer of somebody's messages travel, and fewer are read.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    dev.walcott.ui.components.ChoiceChip(
                        selected = filter.isEmpty(),
                        onClick = { filter = "" },
                        label = stringResource(R.string.notiflog_filter_all),
                    )
                    dev.walcott.ui.components.ChoiceChip(
                        selected = filter.isNotEmpty(),
                        onClick = { picking = true },
                        label = if (filter.isEmpty()) {
                            stringResource(R.string.notiflog_filter_one)
                        } else {
                            labels[filter] ?: filter
                        },
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.requestNotifications(deviceId, pkg = filter) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (filter.isEmpty()) {
                            stringResource(R.string.notiflog_request)
                        } else {
                            stringResource(R.string.notiflog_request_one, labels[filter] ?: filter)
                        },
                    )
                }
            }

            // Nothing has been asked for yet, which is how this screen always opens. Said out loud
            // rather than left as a blank page: an empty screen under a button reads as an answer
            // ("nothing arrived"), and it is not one — nobody has asked the question yet.
            if (newest == null) {
                item { Notice(stringResource(R.string.notiflog_never_asked)) }
            }
            // The device's own two "there is nothing here, and this is why" answers.
            if (newest?.notEnabled == true) {
                item { Notice(stringResource(R.string.notiflog_not_enabled)) }
            } else if (newest?.noAccess == true) {
                item { Notice(stringResource(R.string.notiflog_no_access)) }
            }

            if (newest != null && entries.isEmpty() && newest.total == 0 &&
                !newest.notEnabled && !newest.noAccess
            ) {
                item { Notice(stringResource(R.string.notiflog_empty)) }
            }

            if (entries.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.notiflog_showing, entries.size, newest?.total ?: entries.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    CardGroup {
                        entries.forEachIndexed { index, entry ->
                            NotificationRow(
                                entry = entry,
                                appLabel = labels[entry.pkg] ?: entry.pkg,
                                inventory = viewModel.repository.inventory,
                                iconBytes = { viewModel.childAppIcon(it) },
                                iconRefresh = iconRefresh,
                                position = cardPosition(index, entries.size),
                            )
                        }
                    }
                }
                // Only offered while there is something older to fetch, which the device told us.
                if ((newest?.total ?: 0) > entries.size) {
                    item {
                        OutlinedButton(
                            onClick = {
                                viewModel.requestNotifications(deviceId, pkg = filter, beforeMs = oldestShown)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.notiflog_older)) }
                    }
                }
            }
            item { Text("", Modifier.padding(bottom = spacing.xxl)) }
        }
    }

    if (picking) {
        // The apps that device reports having. Anything it has never had cannot have notified.
        val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
        dev.walcott.ui.components.AppPickerSheet(
            apps = remember(apps) {
                apps.map { dev.walcott.ui.components.PickableApp(it.packageName, it.label) }
            },
            inventory = viewModel.repository.inventory,
            iconBytes = { viewModel.childAppIcon(it) },
            iconRefresh = iconRefresh,
            onDismiss = { picking = false },
            onPick = { app ->
                filter = app.packageName
                picking = false
            },
        )
    }
}

@Composable
private fun Notice(text: String) {
    WalcottCard {
        Text(
            text,
            Modifier.padding(Tokens.spacing.lg),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationRow(
    entry: NotificationEntry,
    appLabel: String,
    inventory: dev.walcott.data.AppInventory,
    iconBytes: (String) -> ByteArray?,
    iconRefresh: Any?,
    position: dev.walcott.ui.components.CardPosition,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                dev.walcott.ui.components.AppHeading(
                    packageName = entry.pkg,
                    label = appLabel,
                    inventory = inventory,
                    iconBytes = iconBytes,
                    iconRefresh = iconRefresh,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    remember(entry.atMs) { timeOf(entry.atMs) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.title.isNotBlank()) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.text.isNotBlank()) {
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Date and time in the device's own locale — the reader is placing these against their own day. */
private fun timeOf(atMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
        .format(Date(atMs))
