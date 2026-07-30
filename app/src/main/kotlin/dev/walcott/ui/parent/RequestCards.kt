package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.sync.ChildRequest
import dev.walcott.sync.DomainAsk
import dev.walcott.sync.SyncManager
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens

/**
 * Pending child request cards, shared between the parent home ([FamiliesScreen]) and the
 * children hub ([ChildrenScreen]) so approve/deny looks and behaves identically in both.
 */

@Composable
fun ExtraTimeRequestCard(pending: SyncManager.PendingRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val spacing = Tokens.spacing
    val key = pending.request.categoryId
    val category = AppCategory.byId(key)
    // The target can be a category, a single app, or "all apps" — name it the way the child chose.
    val targetName = when {
        key == dev.walcott.rules.ExtraTime.ALL_APPS -> stringResource(R.string.request_all_apps)
        category != null -> stringResource(category.nameRes)
        pending.request.targetLabel.isNotBlank() -> pending.request.targetLabel
        else -> key
    }

    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.request_summary, targetName, pending.request.minutes),
                color = category?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pending.request.reason.isNotBlank()) {
                Text(
                    "“${pending.request.reason}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ApproveDenyRow(onApprove, onDeny)
        }
    }
}

/**
 * A domains ask, as sent by the monitor on the child device. Its own card because approving it
 * is not a yes/no: the parent chooses the reach. Blocking in the one app that resolved them is
 * the precise answer — the monitor knows who asked — and blocking everywhere is there for the
 * domain that has no business on the phone at all.
 *
 * Falls back to the generic ask card when the payload can't be read, so a request from a newer
 * child is never a dead end.
 */
@Composable
fun DomainsAskCard(
    pending: SyncManager.PendingAsk,
    parsed: DomainAsk.Parsed,
    onBlockInApp: (List<String>, String) -> Unit,
    onBlockEverywhere: (List<String>) -> Unit,
    onDone: () -> Unit,
    onDeny: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ask_domains_title, parsed.label))
            parsed.domains.forEach { domain ->
                Text(
                    domain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.sm),
                )
            }
            Button(
                onClick = { onBlockInApp(parsed.domains, parsed.packageName); onDone() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ask_domains_block_app, parsed.label)) }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedButton(
                    onClick = { onBlockEverywhere(parsed.domains); onDone() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ask_domains_block_all)) }
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.deny))
                }
            }
        }
    }
}

/**
 * The right card for an ask: the domains one when the payload reads as domains, the generic
 * approve/deny otherwise. Both screens that list asks go through here so they can't drift.
 */
@Composable
fun AskCard(pending: SyncManager.PendingAsk, viewModel: dev.walcott.ui.WalcottViewModel) {
    val parsed = if (pending.ask.kind == ChildRequest.KIND_DOMAINS) DomainAsk.decode(pending.ask.text) else null
    if (parsed != null) {
        DomainsAskCard(
            pending = pending,
            parsed = parsed,
            onBlockInApp = { domains, pkg ->
                // The precise rule: block the domain in the app that asked for it, nowhere else.
                domains.forEach { viewModel.addDomainAppRule(it, pkg, allowOnlyFromApp = false) }
            },
            onBlockEverywhere = { domains -> domains.forEach { viewModel.addBlockedDomain(it) } },
            onDone = { viewModel.resolveRequest(pending.ask.requestId, true, 0) },
            onDeny = { viewModel.resolveRequest(pending.ask.requestId, false, 0) },
        )
    } else {
        AskRequestCard(
            pending = pending,
            onApprove = { viewModel.resolveRequest(pending.ask.requestId, true, 0) },
            onDeny = { viewModel.resolveRequest(pending.ask.requestId, false, 0) },
        )
    }
}

@Composable
fun AskRequestCard(pending: SyncManager.PendingAsk, onApprove: () -> Unit, onDeny: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (pending.ask.kind == ChildRequest.KIND_APP) R.string.ask_summary_app else R.string.ask_summary_other,
                    pending.ask.text,
                ),
            )
            if (pending.ask.kind == ChildRequest.KIND_APP) {
                Text(
                    stringResource(R.string.ask_approve_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ApproveDenyRow(onApprove, onDeny)
        }
    }
}

@Composable
private fun ApproveDenyRow(onApprove: () -> Unit, onDeny: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.approve)) }
        OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.deny)) }
    }
}
