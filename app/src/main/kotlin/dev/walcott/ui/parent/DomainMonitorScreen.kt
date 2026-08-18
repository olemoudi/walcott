package dev.walcott.ui.parent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.net.DomainMonitor
import dev.walcott.sync.DomainBatch
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/**
 * What a parent sees after picking up the child's phone to find out what an app is talking to.
 *
 * The flow this screen is shaped around: start looking, leave Walcott, use the app for a minute,
 * come back — the app you just used is at the top because the list is ordered by when each app
 * was last heard from. Tick the domains worth blocking and they go to the parent phone as a
 * request, which is where blocking decisions belong.
 *
 * Nothing here is stored (see [DomainMonitor]); the session expires on its own.
 */
@Composable
fun DomainMonitorScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val monitor by viewModel.domainMonitor.collectAsStateWithLifecycle()
    val labels by viewModel.installedLabels.collectAsStateWithLifecycle()
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    val tunnelUp by viewModel.dnsTunnelUp.collectAsStateWithLifecycle()
    val selected = remember { mutableStateMapOf<Pair<String?, String>, Boolean>() }
    val delivery by viewModel.domainDelivery.collectAsStateWithLifecycle()

    // Ticks so the countdown moves and the screen notices the session expiring by itself.
    val nowMs by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val active = monitor.isActive(nowMs)
    val groups = remember(monitor) { monitor.byApp() }
    // Everything ticked, across every app group — the fixed bar sends one batch, and a parent
    // who ticked in two apps expects one tap to cover both.
    val chosen = groups.flatMap { (packageName, sightings) ->
        if (packageName == null) emptyList()
        else sightings.filter { selected[packageName to it.domain] == true }.map { packageName to it.domain }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.domain_monitor_title), onBack)
        LazyColumn(
            // weight(1f): the list takes what is left after the send bar, so the bar stays put
            // however far the list scrolls.
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                WalcottCard(modifier = Modifier.padding(top = spacing.md)) {
                    Column(Modifier.padding(spacing.lg).animateContentSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(spacing.md))
                            Text(
                                stringResource(
                                    if (active) R.string.domain_monitor_running else R.string.domain_monitor_idle,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (active) {
                                val left = ((monitor.activeUntilMs - nowMs) / 60_000).toInt() + 1
                                Text(
                                    pluralStringResource(R.plurals.domain_monitor_minutes_left, left, left),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            stringResource(
                                if (active) R.string.domain_monitor_hint_running else R.string.domain_monitor_hint_idle,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.sm),
                        )
                        // Asking for the tunnel and having one are different things, and an
                        // empty list would otherwise read as "this app talks to nobody".
                        if (active && !tunnelUp) {
                            Text(
                                stringResource(R.string.domain_monitor_no_tunnel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = spacing.sm),
                            )
                        }
                        if (active) {
                            OutlinedButton(
                                onClick = { viewModel.stopDomainMonitor(); selected.clear() },
                                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                            ) { Text(stringResource(R.string.domain_monitor_stop)) }
                        } else {
                            Button(
                                onClick = { selected.clear(); viewModel.startDomainMonitor() },
                                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                            ) { Text(stringResource(R.string.domain_monitor_start)) }
                        }
                    }
                }
            }

            // What became of the last selection. Sending is not instant and has no delivery
            // guarantee, so "sent" is not a claim this screen gets to make on its own: it says
            // confirmed, still going, or never arrived.
            if (delivery != null) {
                item { DeliveryStatus(delivery!!) }
            }

            if (active && groups.isEmpty() && tunnelUp) {
                item {
                    Text(
                        stringResource(R.string.domain_monitor_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }

            groups.forEach { (packageName, sightings) ->
                item(key = "header-${packageName ?: "unknown"}") {
                    if (packageName == null) {
                        // Nothing to put an icon on: these are the lookups nobody could attribute.
                        SectionHeader(stringResource(R.string.domain_monitor_unattributed))
                    } else {
                        dev.walcott.ui.components.AppHeading(
                            packageName = packageName,
                            label = labels[packageName] ?: packageName,
                            inventory = viewModel.repository.inventory,
                            iconBytes = { viewModel.childAppIcon(it) },
                            iconRefresh = iconRefresh,
                            modifier = Modifier.padding(top = spacing.lg, start = spacing.xs),
                        )
                    }
                }
                item(key = "card-${packageName ?: "unknown"}") {
                    WalcottCard {
                        Column(Modifier.padding(vertical = spacing.xs)) {
                            sightings.forEach { sighting ->
                                val key = packageName to sighting.domain
                                DomainRow(
                                    sighting = sighting,
                                    checked = selected[key] == true,
                                    onToggle = { selected[key] = it },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }

        // Pinned, outside the list: a parent who scrolled to the bottom of a long list of
        // lookups was previously hunting for a button that had scrolled away with its group.
        if (chosen.isNotEmpty()) {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        // One batch per tap. The package of the first pick names it, because a
                        // rule needs one app to hang on — and a selection spanning two apps is
                        // the parent's own doing, not something to silently split.
                        val packageName = chosen.first().first
                        viewModel.sendDomainsToParent(
                            packageName = packageName,
                            label = labels[packageName] ?: packageName,
                            domains = chosen.map { it.second },
                        )
                        selected.clear()
                    },
                    modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                ) {
                    Text(pluralStringResource(R.plurals.domain_monitor_send, chosen.size, chosen.size))
                }
            }
        }
    }
}

/** Where the last selection got to: confirmed by the parent, still travelling, or given up on. */
@Composable
private fun DeliveryStatus(batch: DomainBatch) {
    val spacing = Tokens.spacing
    val confirmed = batch.slices.sumOf { s -> if (s.index in batch.ackedIndexes) s.domains.size else 0 }
    val (text, color) = when {
        batch.delivered -> pluralStringResource(
            R.plurals.domain_monitor_sent, batch.domainCount, batch.domainCount,
        ) to MaterialTheme.colorScheme.primary
        batch.abandoned -> stringResource(R.string.domain_send_failed) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.domain_send_progress, confirmed, batch.domainCount) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(top = spacing.sm),
    )
}

@Composable
private fun DomainRow(sighting: DomainMonitor.Sighting, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(sighting.domain, style = MaterialTheme.typography.bodyMedium)
            Text(
                pluralStringResource(R.plurals.domain_monitor_lookups, sighting.count, sighting.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
