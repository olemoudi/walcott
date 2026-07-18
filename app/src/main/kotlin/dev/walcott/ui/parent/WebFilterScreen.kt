package dev.walcott.ui.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/** Web filter editor; with a [childId] it edits that child's blocked-domain override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebFilterScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    childId: String? = null,
    childName: String? = null,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val blockedDomains = if (childId == null) {
        settings.blockedDomains
    } else {
        settings.children.firstOrNull { it.childId == childId }?.overrides?.blockedDomains.orEmpty()
    }
    val apps by viewModel.appRows.collectAsStateWithLifecycle()
    val labelOf = remember(apps) { apps.associate { it.app.packageName to it.app.label } }

    var newDomain by remember { mutableStateOf("") }
    var ruleDomain by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var allowOnly by remember { mutableStateOf(true) }
    var pickingApp by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_webfilter_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (childName != null) {
                item { OverrideScopeBanner(childName) }
            }
            item {
                Text(
                    stringResource(R.string.webfilter_dns_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = spacing.sm),
                )
            }

            item { Text(stringResource(R.string.webfilter_blocked_domains), style = MaterialTheme.typography.titleMedium) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedTextField(
                        value = newDomain,
                        onValueChange = { newDomain = it },
                        placeholder = { Text(stringResource(R.string.webfilter_domain_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { viewModel.addBlockedDomain(newDomain, childId); newDomain = "" }) {
                        Text(stringResource(R.string.action_add))
                    }
                }
            }
            if (blockedDomains.isEmpty()) {
                item { Text(stringResource(R.string.webfilter_empty_domains), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(blockedDomains.sorted(), key = { it }) { domain ->
                DeletableRow(domain, onDelete = { viewModel.removeBlockedDomain(domain, childId) })
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
                Text(
                    stringResource(R.string.webfilter_advanced),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }
            if (childId == null) {
                itemsIndexed(settings.domainAppRules) { index, rule ->
                    val appLabel = labelOf[rule.packageName] ?: rule.packageName
                    val text = if (rule.allowOnlyFromApp) {
                        stringResource(R.string.webfilter_rule_allow_only, rule.domain, appLabel)
                    } else {
                        stringResource(R.string.webfilter_rule_block_in, rule.domain, appLabel)
                    }
                    DeletableRow(text, onDelete = { viewModel.removeDomainAppRule(index) })
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
        ModalBottomSheet(onDismissRequest = { pickingApp = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = spacing.xxl)) {
                items(apps, key = { it.app.packageName }) { row ->
                    ListItem(
                        headlineContent = { Text(row.app.label) },
                        leadingContent = { AppIcon(row.app.packageName, viewModel.repository.inventory, size = 36.dp) },
                        modifier = Modifier.clickable { selectedPkg = row.app.packageName; pickingApp = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletableRow(label: String, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Tokens.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            IconButton(onClick = onDelete) {
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
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
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
            FilterChip(selected = allowOnly, onClick = { onModeChange(true) }, label = { Text(stringResource(R.string.webfilter_mode_allow_only)) })
            FilterChip(selected = !allowOnly, onClick = { onModeChange(false) }, label = { Text(stringResource(R.string.webfilter_mode_block_in)) })
            OutlinedButton(onClick = onAdd, enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  " + stringResource(R.string.action_add))
            }
        }
    }
}
