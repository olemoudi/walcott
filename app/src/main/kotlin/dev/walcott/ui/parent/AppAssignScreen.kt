package dev.walcott.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.ui.AppRow
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAssignScreen(viewModel: WalcottViewModel, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    val spacing = Tokens.spacing
    val rows by viewModel.appRows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // Per-child filter (null = everyone). Only offered once there are two+ children to
    // tell apart — a single-child family gains nothing from the extra chrome.
    var ownerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val allOwners = remember(rows) {
        rows.flatMap { it.owners }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
    val showOwners = allOwners.size > 1

    val filtered = remember(rows, query, ownerFilter) {
        rows.filter { row ->
            (query.isBlank() || row.app.label.contains(query, ignoreCase = true)) &&
                (ownerFilter == null || row.owners.any { it.id == ownerFilter })
        }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_apps_title), onBack)
        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(spacing.screen),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.apps_no_remote),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_app)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen),
            )
            if (showOwners) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    ChoiceChip(
                        selected = ownerFilter == null,
                        onClick = { ownerFilter = null },
                        label = stringResource(R.string.apps_filter_all),
                    )
                    allOwners.forEach { owner ->
                        ChoiceChip(
                            selected = ownerFilter == owner.id,
                            onClick = { ownerFilter = if (ownerFilter == owner.id) null else owner.id },
                            label = owner.name,
                        )
                    }
                }
            }
            Spacer(Modifier.width(spacing.sm))
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = spacing.sm),
                contentPadding = PaddingValues(vertical = spacing.sm),
            ) {
                items(filtered, key = { it.app.packageName }) { row ->
                    AppAssignRow(
                        viewModel,
                        row,
                        hasOverride = row.app.packageName in settings.appPolicies,
                        showOwners = showOwners,
                        iconRefresh = iconRefresh,
                        onClick = { onOpenApp(row.app.packageName) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppAssignRow(
    viewModel: WalcottViewModel,
    row: AppRow,
    hasOverride: Boolean,
    showOwners: Boolean,
    iconRefresh: Int,
    onClick: () -> Unit,
) {
    val category = row.categoryId?.let { AppCategory.byId(it) }
    ListItem(
        headlineContent = { Text(row.app.label) },
        supportingContent = {
            Column {
                // Category name (or "unclassified"), plus a hint when the app carries its own rules.
                val base = category?.let { stringResource(it.nameRes) } ?: stringResource(R.string.unclassified_blocked)
                Text(
                    if (hasOverride) "$base · ${stringResource(R.string.app_has_override)}" else base,
                    color = category?.color ?: MaterialTheme.colorScheme.error,
                )
                // Who has it: one small tag per child (only in multi-child families).
                if (showOwners && row.owners.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        row.owners.forEach { owner ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    owner.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        leadingContent = {
            AppIcon(
                row.app.packageName,
                viewModel.repository.inventory,
                size = 40.dp,
                remoteLoader = { viewModel.childAppIcon(it) },
                refreshKey = iconRefresh,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (category != null) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(category.color))
                } else {
                    Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}
