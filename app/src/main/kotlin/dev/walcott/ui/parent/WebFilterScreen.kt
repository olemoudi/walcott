package dev.walcott.ui.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens

/** Web filter editor; with a [childId] it edits that child's blocked-domain override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebFilterScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    childId: String? = null,
    childName: String? = null,
    /** Opens the short guided run through the lists; null hides the entry (child scope). */
    onQuickSetup: (() -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overrides = settings.children.firstOrNull { it.childId == childId }?.overrides
    // In child scope this shows what the child ACTUALLY gets: its own list once customized,
    // the family's while it still inherits — read-only in that case, never an empty list that
    // reads as "nothing is blocked for them".
    val editable = childId == null || overrides?.blockedDomains != null
    val blockedDomains = if (childId == null) {
        settings.blockedDomains
    } else {
        overrides?.blockedDomains ?: settings.blockedDomains
    }
    val apps by viewModel.appRows.collectAsStateWithLifecycle()
    val labelOf = remember(apps) { apps.associate { it.app.packageName to it.app.label } }

    var newDomain by remember { mutableStateOf("") }
    var ruleDomain by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var allowOnly by remember { mutableStateOf(true) }
    var pickingApp by remember { mutableStateOf(false) }
    // The same picker sheet serves two sections; this says which one asked for it.
    var pickingExempt by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_webfilter_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (childName != null) {
                item { OverrideScopeBanner(childName, editable = editable) }
            }
            item {
                Text(
                    stringResource(R.string.webfilter_dns_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = spacing.sm),
                )
            }

            // The lists first: they are what a family should be turning on, and the hand-typed
            // domains below them are the exception rather than the starting point.
            item {
                SectionHeader(
                    stringResource(R.string.blocklist_section_title),
                    icon = Icons.Outlined.Shield,
                    accent = SectionAccent.RULES,
                    supporting = stringResource(R.string.blocklist_section_hint),
                )
            }
            item {
                BlocklistRows(
                    enabled = settings.enabledBlocklists,
                    onToggle = { id, on -> viewModel.setBlocklist(id, on) },
                    // Lists are a family decision; in a child's scope they are shown, not edited.
                    editable = childId == null,
                )
            }
            // How often the children re-download the public lists. Only shown once a list that
            // HAS a public source is on: on a family using only the bundled lists there is
            // nothing to refresh, and the row would be a question about nothing.
            if (childId == null && dev.walcott.rules.Blocklists.withSources(settings.enabledBlocklists).isNotEmpty()) {
                item {
                    BlocklistRefreshCard(
                        hours = settings.blocklistRefreshHours,
                        onPick = { viewModel.setBlocklistRefreshHours(it) },
                    )
                }
            }

            if (childId != null) {
                item {
                    Text(
                        stringResource(R.string.blocklist_family_scope_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (onQuickSetup != null) {
                item {
                    OutlinedButton(onClick = onQuickSetup, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.blocklist_quick_setup))
                    }
                }
            }

            // Directly under the lists, because it is only ever read when one of them has just
            // broken something. Family scope only: the lists are one decision for the household,
            // and so is an app that cannot live with them.
            if (childId == null && settings.enabledBlocklists.isNotEmpty()) {
                item {
                    SectionHeader(
                        stringResource(R.string.blocklist_exempt_title),
                        icon = Icons.AutoMirrored.Outlined.Rule,
                        accent = SectionAccent.RULES,
                        supporting = stringResource(R.string.blocklist_exempt_hint),
                    )
                }
                item {
                    val exempt = settings.blocklistExemptApps.sortedBy { labelOf[it] ?: it }
                    CardGroup {
                        exempt.forEachIndexed { index, pkg ->
                            DeletableRow(
                                labelOf[pkg] ?: pkg,
                                position = cardPosition(index, exempt.size),
                            ) { viewModel.setBlocklistExempt(pkg, false) }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { pickingExempt = true; pickingApp = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.blocklist_exempt_add)) }
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.webfilter_blocked_domains),
                    icon = Icons.Outlined.Language,
                    accent = SectionAccent.RULES,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedTextField(
                        value = newDomain,
                        onValueChange = { newDomain = it },
                        placeholder = { Text(stringResource(R.string.webfilter_domain_hint)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = editable,
                        onClick = { viewModel.addBlockedDomain(newDomain, childId); newDomain = "" },
                    ) { Text(stringResource(R.string.action_add)) }
                }
            }
            if (blockedDomains.isEmpty()) {
                item { Text(stringResource(R.string.webfilter_empty_domains), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                val sorted = blockedDomains.sorted()
                CardGroup {
                    sorted.forEachIndexed { index, domain ->
                        DeletableRow(
                            domain,
                            position = cardPosition(index, sorted.size),
                            onDelete = if (editable) ({ viewModel.removeBlockedDomain(domain, childId) }) else null,
                        )
                    }
                }
            }

            // Per-app domain rules aren't part of the per-child overrides; in child scope
            // just say so instead of silently hiding a family-wide behavior.
            if (childId != null) {
                item {
                    Text(
                        stringResource(R.string.webfilter_child_rules_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }
            if (childId == null) item {
                SectionHeader(
                    stringResource(R.string.webfilter_advanced),
                    icon = Icons.Outlined.Tune,
                    accent = SectionAccent.DEVICE,
                )
            }
            if (childId == null) {
                item {
                    CardGroup {
                        settings.domainAppRules.forEachIndexed { index, rule ->
                            val appLabel = labelOf[rule.packageName] ?: rule.packageName
                            val text = if (rule.allowOnlyFromApp) {
                                stringResource(R.string.webfilter_rule_allow_only, rule.domain, appLabel)
                            } else {
                                stringResource(R.string.webfilter_rule_block_in, rule.domain, appLabel)
                            }
                            DeletableRow(
                                text,
                                position = cardPosition(index, settings.domainAppRules.size),
                            ) { viewModel.removeDomainAppRule(index) }
                        }
                    }
                }
            }
            if (childId == null) item {
                AddRuleCard(
                    domain = ruleDomain,
                    onDomainChange = { ruleDomain = it },
                    selectedLabel = selectedPkg?.let { labelOf[it] ?: it },
                    onPickApp = { pickingApp = true },
                    allowOnly = allowOnly,
                    onModeChange = { allowOnly = it },
                    canAdd = ruleDomain.isNotBlank() && selectedPkg != null,
                    onAdd = {
                        viewModel.addDomainAppRule(ruleDomain, selectedPkg!!, allowOnly)
                        ruleDomain = ""; selectedPkg = null
                    },
                )
            }
        }
    }

    if (pickingApp) {
        ModalBottomSheet(onDismissRequest = { pickingApp = false; pickingExempt = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = spacing.xxl)) {
                items(apps, key = { it.app.packageName }) { row ->
                    ListItem(
                        headlineContent = { Text(row.app.label) },
                        leadingContent = { AppIcon(row.app.packageName, viewModel.repository.inventory, size = 36.dp) },
                        modifier = Modifier.clickable {
                            if (pickingExempt) {
                                viewModel.setBlocklistExempt(row.app.packageName, true)
                                pickingExempt = false
                            } else {
                                selectedPkg = row.app.packageName
                            }
                            pickingApp = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * How often each child re-downloads the public lists.
 *
 * Worded as a trade-off rather than as a setting, because that is what it is: the sources are
 * rebuilt hourly, and the cost of keeping up with them is the child's mobile data.
 */
@Composable
private fun BlocklistRefreshCard(hours: Int, onPick: (Int) -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(stringResource(R.string.blocklist_refresh_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.blocklist_refresh_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                dev.walcott.rules.Blocklists.REFRESH_HOUR_CHOICES.forEach { choice ->
                    dev.walcott.ui.components.ChoiceChip(
                        selected = hours == choice,
                        onClick = { onPick(choice) },
                        label = stringResource(refreshLabel(choice)),
                    )
                }
            }
        }
    }
}

private fun refreshLabel(hours: Int): Int = when (hours) {
    24 -> R.string.blocklist_refresh_daily
    72 -> R.string.blocklist_refresh_three_days
    else -> R.string.blocklist_refresh_weekly
}

@Composable
private fun DeletableRow(label: String, position: CardPosition = CardPosition.Single, onDelete: (() -> Unit)?) {
    WalcottCard(position = position) {
        Row(Modifier.padding(start = Tokens.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            // Null while the entry is inherited: it is shown, not owned by this child.
            IconButton(onClick = onDelete ?: {}, enabled = onDelete != null) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun AddRuleCard(
    domain: String,
    onDomainChange: (String) -> Unit,
    selectedLabel: String?,
    onPickApp: () -> Unit,
    allowOnly: Boolean,
    onModeChange: (Boolean) -> Unit,
    canAdd: Boolean,
    onAdd: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedTextField(
                value = domain,
                onValueChange = onDomainChange,
                placeholder = { Text(stringResource(R.string.webfilter_domain_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel ?: stringResource(R.string.webfilter_choose_app))
            }
            dev.walcott.ui.components.ChoiceChip(selected = allowOnly, onClick = { onModeChange(true) }, label = stringResource(R.string.webfilter_mode_allow_only))
            dev.walcott.ui.components.ChoiceChip(selected = !allowOnly, onClick = { onModeChange(false) }, label = stringResource(R.string.webfilter_mode_block_in))
            OutlinedButton(onClick = onAdd, enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  " + stringResource(R.string.action_add))
            }
        }
    }
}
