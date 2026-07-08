package dev.walcott.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Search
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
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAssignScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val rows by viewModel.appRows.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<AppRow?>(null) }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { it.app.label.contains(query, ignoreCase = true) }
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
            Spacer(Modifier.width(spacing.sm))
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = spacing.sm),
                contentPadding = PaddingValues(vertical = spacing.sm),
            ) {
                items(filtered, key = { it.app.packageName }) { row ->
                    AppAssignRow(viewModel, row, onClick = { picking = row })
                }
            }
        }
    }

    picking?.let { row ->
        CategoryPickerSheet(
            current = row.categoryId,
            onDismiss = { picking = null },
            onPick = { category ->
                if (category == null) viewModel.unassign(row.app.packageName)
                else viewModel.assign(row.app.packageName, category.id)
                picking = null
            },
        )
    }
}

@Composable
private fun AppAssignRow(viewModel: WalcottViewModel, row: AppRow, onClick: () -> Unit) {
    val category = row.categoryId?.let { AppCategory.byId(it) }
    ListItem(
        headlineContent = { Text(row.app.label) },
        supportingContent = {
            Text(
                category?.let { stringResource(it.nameRes) } ?: stringResource(R.string.unclassified_blocked),
                color = category?.color ?: MaterialTheme.colorScheme.error,
            )
        },
        leadingContent = { AppIcon(row.app.packageName, viewModel.repository.inventory, size = 40.dp) },
        trailingContent = {
            if (category != null) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(category.color))
            } else {
                Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    current: String?,
    onDismiss: () -> Unit,
    onPick: (AppCategory?) -> Unit,
) {
    val spacing = Tokens.spacing
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = spacing.screen).padding(bottom = spacing.xxl)) {
            Text(
                stringResource(R.string.classify_into),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = spacing.md),
            )
            AppCategory.entries.forEach { category ->
                CategoryOption(
                    color = category.color,
                    label = stringResource(category.nameRes),
                    selected = current == category.id,
                    onClick = { onPick(category) },
                    icon = { Icon(category.icon, contentDescription = null, tint = category.color) },
                )
            }
            CategoryOption(
                color = MaterialTheme.colorScheme.error,
                label = stringResource(R.string.unclassified_block_action),
                selected = current == null,
                onClick = { onPick(null) },
                icon = { Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

@Composable
private fun CategoryOption(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) color.copy(alpha = 0.14f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(spacing.md))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
