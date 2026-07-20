package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.rules.DayType
import dev.walcott.ui.DAY_TYPES
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.ComfortableChipPadding
import dev.walcott.ui.components.CustomValueChip
import dev.walcott.ui.components.MinutesPickerDialog
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.labelRes
import dev.walcott.ui.theme.Tokens
import java.time.Duration

/**
 * Per-app settings, reached by tapping an app in "Apps & categories": its category, plus
 * app-specific restrictions that TIGHTEN the category — an own daily budget, an own blocked
 * window, and a shortcut into the web filter. Everything here only ever restricts further.
 */
@Composable
fun AppDetailScreen(
    viewModel: WalcottViewModel,
    packageName: String,
    onBack: () -> Unit,
    onOpenWebFilter: () -> Unit,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rows by viewModel.appRows.collectAsStateWithLifecycle()
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    val label = rows.firstOrNull { it.app.packageName == packageName }?.app?.label ?: packageName
    val categoryId = settings.assignments[packageName]
    val appPolicy = settings.appPolicies[packageName]

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(label, onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(
                        packageName,
                        viewModel.repository.inventory,
                        size = 44.dp,
                        remoteLoader = { viewModel.childAppIcon(it) },
                        refreshKey = iconRefresh,
                    )
                    Spacer(Modifier.width(spacing.md))
                    Text(label, style = MaterialTheme.typography.titleLarge)
                }
            }

            item { SectionTitle(stringResource(R.string.classify_into)) }
            item {
                CategorySelector(
                    current = categoryId,
                    onPick = { category ->
                        if (category == null) viewModel.unassign(packageName)
                        else viewModel.assign(packageName, category.id)
                    },
                )
            }

            item { SectionTitle(stringResource(R.string.app_own_limit)) }
            item {
                Text(
                    stringResource(R.string.app_own_limit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PerDayBudgetCard(
                    perDay = appPolicy?.budgets.orEmpty(),
                    onSetBudget = { dayType, minutes -> viewModel.setAppBudget(packageName, dayType, minutes) },
                    onSetAllDays = { minutes -> viewModel.setAppBudgetAllDays(packageName, minutes) },
                )
            }

            item { SectionTitle(stringResource(R.string.app_own_window)) }
            item {
                BlockedWindowsCard(
                    title = null,
                    hint = stringResource(R.string.app_windows_hint),
                    windows = appPolicy?.blockedWindows?.get(DayType.SCHOOL.name).orEmpty(),
                    onChange = { viewModel.setAppWindows(packageName, it) },
                )
            }

            item { SectionTitle(stringResource(R.string.app_web_filter)) }
            item {
                Surface(
                    onClick = onOpenWebFilter,
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_web_filter_link),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Tokens.spacing.sm))
}

@Composable
private fun CategorySelector(current: String?, onPick: (AppCategory?) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppCategory.entries.forEach { category ->
                CategoryOptionRow(
                    color = category.color,
                    label = stringResource(category.nameRes),
                    selected = current == category.id,
                    onClick = { onPick(category) },
                    icon = { Icon(category.icon, contentDescription = null, tint = category.color) },
                )
            }
            CategoryOptionRow(
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
private fun CategoryOptionRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) color.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Tokens.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(Tokens.spacing.md))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Per-app daily limit editor. Each day type is one of three states — Blocked (a 0-minute cap
 * that shuts the app even inside its category), a timed limit, or No limit — and an "apply to
 * all days" row sets the common case (one limit everywhere, or block it outright) in one tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerDayBudgetCard(
    perDay: Map<String, Int>,
    onSetBudget: (DayType, Int?) -> Unit,
    onSetAllDays: (Int?) -> Unit,
) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            // Quick apply-all, so a single daily limit (or a blanket block) needs one tap, not three.
            Text(
                stringResource(R.string.budget_apply_all),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                Modifier.padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                QuickChip(stringResource(R.string.app_limit_blocked)) { onSetAllDays(0) }
                QuickChip(stringResource(R.string.no_limit)) { onSetAllDays(null) }
                QuickChip("1h") { onSetAllDays(60) }
                QuickChip("2h") { onSetAllDays(120) }
                // Type any daily limit (up to 24h) applied to every day type at once.
                var customAll by remember { mutableStateOf(false) }
                QuickChip(stringResource(R.string.custom_value)) { customAll = true }
                if (customAll) {
                    MinutesPickerDialog(
                        title = stringResource(R.string.custom_minutes_title),
                        initial = 60,
                        onDismiss = { customAll = false },
                        onConfirm = { onSetAllDays(it); customAll = false },
                    )
                }
            }

            DAY_TYPES.forEach { dayType ->
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                val minutes = perDay[dayType.name]
                Text(stringResource(dayType.labelRes()), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    Modifier.padding(top = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ChoiceChip(
                        selected = minutes == 0,
                        onClick = { onSetBudget(dayType, 0) },
                        label = stringResource(R.string.app_limit_blocked),
                    )
                    ChoiceChip(
                        selected = minutes == null,
                        onClick = { onSetBudget(dayType, null) },
                        label = stringResource(R.string.no_limit),
                    )
                    if (minutes != null && minutes > 0) {
                        // A timed limit: the chip shows it and taps open a dialog to set any value up to 24h.
                        CustomValueChip(
                            selected = true,
                            customLabel = Duration.ofMinutes(minutes.toLong()).humanize(),
                            dialogTitle = stringResource(R.string.custom_minutes_title),
                            initial = minutes,
                            onConfirm = { onSetBudget(dayType, it) },
                        )
                    } else {
                        ChoiceChip(
                            selected = false,
                            onClick = { onSetBudget(dayType, 60) }, // enter "limit" at a sensible default
                            label = stringResource(R.string.app_limit_timed),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(ComfortableChipPadding),
        )
    }
}

