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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.SyncManager
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import java.time.Duration

@Composable
fun ChildrenScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val children by viewModel.children.collectAsStateWithLifecycle()
    val requests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    var bonusTarget by remember { mutableStateOf<ChildSnapshot?>(null) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_children_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { Text(stringResource(R.string.pending_requests), style = MaterialTheme.typography.titleMedium) }
            if (requests.isEmpty()) {
                item { Text(stringResource(R.string.no_requests), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(requests, key = { it.request.requestId }) { pending ->
                    RequestCard(
                        pending = pending,
                        onApprove = { viewModel.resolveRequest(pending.request.requestId, true, pending.request.minutes) },
                        onDeny = { viewModel.resolveRequest(pending.request.requestId, false, 0) },
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.usage_today),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            if (children.isEmpty()) {
                item { Text(stringResource(R.string.no_children), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(children, key = { it.deviceId }) { child ->
                    ChildUsageCard(child, onGiveBonus = { bonusTarget = child })
                }
            }
        }
    }

    bonusTarget?.let { child ->
        BonusDialog(
            onDismiss = { bonusTarget = null },
            onGrant = { categoryId, minutes ->
                viewModel.giveBonus(child.deviceId, categoryId, minutes)
                bonusTarget = null
            },
        )
    }
}

@Composable
private fun BonusDialog(onDismiss: () -> Unit, onGrant: (String, Int) -> Unit) {
    val spacing = Tokens.spacing
    var category by remember { mutableStateOf(AppCategory.GAMES) }
    var minutes by remember { mutableIntStateOf(15) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.give_bonus)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AppCategory.GAMES, AppCategory.VIDEO, AppCategory.SOCIAL).forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(stringResource(c.nameRes)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(stringResource(R.string.extra_minutes, m)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onGrant(category.id, minutes) }) { Text(stringResource(R.string.action_grant)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RequestCard(pending: SyncManager.PendingRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val spacing = Tokens.spacing
    val category = AppCategory.byId(pending.request.categoryId)
    val categoryName = category?.let { stringResource(it.nameRes) } ?: pending.request.categoryId

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.request_summary, categoryName, pending.request.minutes),
                color = category?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pending.request.reason.isNotBlank()) {
                Text("“${pending.request.reason}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.approve)) }
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.deny)) }
            }
        }
    }
}

@Composable
private fun ChildUsageCard(child: ChildSnapshot, onGiveBonus: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(child.displayName, style = MaterialTheme.typography.titleMedium)
            if (child.usage.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                child.usage.forEach { entry ->
                    val category = AppCategory.byId(entry.categoryId)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            category?.let { stringResource(it.nameRes) } ?: entry.categoryId,
                            Modifier.weight(1f),
                            color = category?.color ?: MaterialTheme.colorScheme.onSurface,
                        )
                        Text(Duration.ofSeconds(entry.seconds).humanize(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.size(spacing.sm))
            OutlinedButton(onClick = onGiveBonus, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.give_bonus))
            }
        }
    }
}
