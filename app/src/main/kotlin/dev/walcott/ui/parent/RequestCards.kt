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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.install.PlayIntents
import dev.walcott.sync.ChildRequest
import dev.walcott.sync.DomainAsk
import dev.walcott.sync.SyncManager
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.MinutesChips
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens

/**
 * Pending child request cards, shared between the parent home ([FamiliesScreen]) and the
 * children hub ([ChildrenScreen]) so approve/deny looks and behaves identically in both.
 */

/**
 * A child's extra-time request, and the two things answering one actually needs.
 *
 * **How much they have already had today**, because that is what decides the answer and the
 * parent would otherwise have to leave this screen to find it (see [ChildStats.usedTodayOn]).
 *
 * **How many minutes to give**, which is not the same question as yes-or-no. "Twenty, not
 * forty" was always expressible on the wire — [SyncManager.resolveRequest] has carried the
 * granted amount since the beginning, and the child applies exactly that — but the button used
 * to hard-code whatever was asked for, so the only honest answers were all of it or none.
 *
 * The app's icon leads the card when the target is one app. The parent doesn't have the child's
 * apps installed, so it comes from the cache the children fill over sync
 * ([dev.walcott.sync.IconSync]) — which already holds it, because the same list that feeds the
 * parent's app screens is what asks for icons in the first place.
 */
@Composable
fun ExtraTimeRequestCard(
    pending: SyncManager.PendingRequest,
    settings: dev.walcott.data.PolicySettings,
    viewModel: dev.walcott.ui.WalcottViewModel,
    onResolve: (approved: Boolean, minutes: Int) -> Unit,
) {
    val spacing = Tokens.spacing
    val key = pending.request.categoryId
    // "All apps" is a sentinel, not a package: there is no one icon that stands for it.
    val targetPackage = key.takeIf { it != dev.walcott.rules.ExtraTime.ALL_APPS && it.isNotBlank() }
    // The target is one app or all of them — name it the way the child chose.
    val targetName = when {
        targetPackage == null -> stringResource(R.string.request_all_apps)
        pending.request.targetLabel.isNotBlank() -> pending.request.targetLabel
        else -> key
    }
    // Starts at what was asked for, so the common answer stays one tap.
    var minutes by remember(pending.request.requestId) { mutableStateOf(pending.request.minutes) }
    // An icon that arrives after the card is drawn (the cache fills in the background) redraws
    // it, rather than leaving a placeholder until the parent navigates away and back.
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()

    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (targetPackage != null) {
                    AppIcon(
                        packageName = targetPackage,
                        inventory = viewModel.repository.inventory,
                        size = 40.dp,
                        remoteLoader = { viewModel.childAppIcon(it) },
                        refreshKey = iconRefresh,
                        label = targetName,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(pending.childName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.request_summary, targetName, pending.request.minutes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SpentTodayLine(pending, settings)
            if (pending.request.reason.isNotBlank()) {
                Text(
                    "“${pending.request.reason}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MinutesChips(value = minutes, onSelect = { minutes = it })
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Button(onClick = { onResolve(true, minutes) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.request_grant_minutes, minutes))
                }
                OutlinedButton(onClick = { onResolve(false, 0) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.deny))
                }
            }
        }
    }
}

/** "Already 1h 20m of 45m today" — silent when the child's counters aren't today's. */
@Composable
private fun SpentTodayLine(pending: SyncManager.PendingRequest, settings: dev.walcott.data.PolicySettings) {
    val target = pending.request.categoryId
    val parentNow = java.time.LocalDateTime.now()
    val used = dev.walcott.data.ChildStats.usedTodayOn(
        target = target,
        usage = pending.usage,
        epochDay = pending.epochDay,
        tzOffsetMinutes = pending.tzOffsetMinutes,
        nowMs = System.currentTimeMillis(),
        parentNow = parentNow,
    ) ?: return
    // The child's own clock, for the same reason the counters are theirs: a day type — and with
    // it the allowance — flips at their midnight, not the parent's.
    val childNow = dev.walcott.data.ChildStats
        .localNow(pending.tzOffsetMinutes, System.currentTimeMillis(), parentNow)
    val config = remember(settings, pending.childId) {
        settings.resolveForChild(pending.childId).toFamilyConfig(emptySet())
    }
    val limit = dev.walcott.data.ChildStats.limitTodayOn(config, target, childNow)
    Text(
        if (limit == null) {
            stringResource(R.string.request_used_today, used.humanize())
        } else {
            stringResource(R.string.request_used_today_of, used.humanize(), limit.humanize())
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    } else if (pending.ask.kind == ChildRequest.KIND_INSTALL && pending.ask.pkg.isNotBlank()) {
        InstallAskCard(
            pending = pending,
            onApprove = { viewModel.approveInstallAsk(pending.ask.requestId) },
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

/**
 * A child's one-app install request, shared from its Play page: title + package, and a look at
 * the store listing before deciding. Approving installs only this package — installs stay
 * blocked throughout — and the app arrives with no limit, like any other new app.
 */
@Composable
fun InstallAskCard(
    pending: SyncManager.PendingAsk,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current

    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ask_summary_app, pending.ask.text))
            Text(
                pending.ask.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(PlayIntents.storePage(context, pending.ask.pkg)) }
            }) { Text(stringResource(R.string.ask_install_view_play)) }
            Text(
                stringResource(R.string.ask_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ApproveDenyRow(onApprove = onApprove, onDeny = onDeny)
        }
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
