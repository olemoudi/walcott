package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.ChildSnapshot
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens
import java.time.Duration

@Composable
fun ChildrenScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val children by viewModel.children.collectAsStateWithLifecycle()
    val requests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val asks by viewModel.pendingAsks.collectAsStateWithLifecycle()
    // The request cards read the limits out of it to say what a child has already had today.
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var bonusTarget by remember { mutableStateOf<ChildSnapshot?>(null) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_children_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                SectionHeader(
                    stringResource(R.string.pending_requests),
                    icon = Icons.Outlined.MoreTime,
                    accent = SectionAccent.RULES,
                )
            }
            if (requests.isEmpty() && asks.isEmpty()) {
                item { Text(stringResource(R.string.no_requests), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(requests, key = { it.request.requestId }) { pending ->
                    ExtraTimeRequestCard(
                        pending = pending,
                        settings = settings,
                        viewModel = viewModel,
                        onResolve = { approved, minutes ->
                            viewModel.resolveRequest(pending.request.requestId, approved, minutes)
                        },
                    )
                }
                items(asks, key = { it.ask.requestId }) { pending ->
                    AskCard(pending, viewModel)
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.usage_today),
                    icon = Icons.Outlined.InsertChart,
                    accent = SectionAccent.ACTIVITY,
                )
            }
            if (children.isEmpty()) {
                item { Text(stringResource(R.string.no_children), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(children, key = { it.deviceId }) { child ->
                    ChildUsageCard(child, viewModel, onGiveBonus = { bonusTarget = child })
                }
            }
        }
    }

    bonusTarget?.let { child ->
        BonusDialog(
            apps = child.apps.map { it.packageName to it.label },
            onDismiss = { bonusTarget = null },
            onGrant = { categoryId, minutes ->
                viewModel.giveBonus(child.deviceId, categoryId, minutes)
                bonusTarget = null
            },
        )
    }
}

@Composable
private fun ChildUsageCard(
    child: ChildSnapshot,
    viewModel: dev.walcott.ui.WalcottViewModel,
    onGiveBonus: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            Text(child.displayName, style = MaterialTheme.typography.titleMedium)
            if (child.usage.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                // Usage is per app now, so the child's own reported app list names it.
                child.usage.sortedByDescending { it.seconds }.take(USAGE_ROWS).forEach { entry ->
                    UsageRow(entry, child.apps, viewModel)
                }
            }
            Spacer(Modifier.size(spacing.sm))
            OutlinedButton(onClick = onGiveBonus, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.give_bonus))
            }
        }
    }
}
