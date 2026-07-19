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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import dev.walcott.data.WindowDto
import dev.walcott.rules.DayType
import dev.walcott.ui.DAY_TYPES
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.Stepper
import dev.walcott.ui.components.TimePickerDialog
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.labelRes
import dev.walcott.ui.theme.Tokens
import java.time.Duration
import java.time.LocalTime

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
                AppWindowCard(
                    window = appPolicy?.blockedWindows?.get(DayType.SCHOOL.name)?.firstOrNull(),
                    onChange = { viewModel.setAppWindow(packageName, it) },
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
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                QuickChip(stringResource(R.string.app_limit_blocked)) { onSetAllDays(0) }
                QuickChip(stringResource(R.string.no_limit)) { onSetAllDays(null) }
                QuickChip("30m") { onSetAllDays(30) }
                QuickChip("1h") { onSetAllDays(60) }
                QuickChip("2h") { onSetAllDays(120) }
            }

            DAY_TYPES.forEach { dayType ->
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                val minutes = perDay[dayType.name]
                Text(stringResource(dayType.labelRes()), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    Modifier.padding(top = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.Center,
                ) {
                    FilterChip(
                        selected = minutes == 0,
                        onClick = { onSetBudget(dayType, 0) },
                        label = { Text(stringResource(R.string.app_limit_blocked)) },
                    )
                    FilterChip(
                        selected = minutes == null,
                        onClick = { onSetBudget(dayType, null) },
                        label = { Text(stringResource(R.string.no_limit)) },
                    )
                    FilterChip(
                        selected = minutes != null && minutes > 0,
                        // Entering "limit" from Blocked/No-limit starts at 15m; keeps the current value otherwise.
                        onClick = { if (minutes == null || minutes == 0) onSetBudget(dayType, 15) },
                        label = { Text(stringResource(R.string.app_limit_timed)) },
                    )
                }
                if (minutes != null && minutes > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = spacing.xs),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Stepper(
                            valueLabel = Duration.ofMinutes(minutes.toLong()).humanize(),
                            decrementEnabled = minutes > 15,
                            onDecrement = { onSetBudget(dayType, (minutes - 15).coerceAtLeast(15)) },
                            onIncrement = { onSetBudget(dayType, minutes + 15) },
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A single blocked window (start–end), applied to every day type, like the bedtime editor. */
@Composable
private fun AppWindowCard(window: WindowDto?, onChange: (WindowDto?) -> Unit) {
    val spacing = Tokens.spacing
    val enabled = window != null
    val start = window?.let { LocalTime.ofSecondOfDay(it.startMinute * 60L) } ?: LocalTime.of(15, 0)
    val end = window?.let { LocalTime.ofSecondOfDay(it.endMinute * 60L) } ?: LocalTime.of(17, 0)
    var editing by remember { mutableStateOf<Int?>(null) } // 0 = start, 1 = end

    fun toMin(t: LocalTime) = t.hour * 60 + t.minute
    fun apply(s: LocalTime, e: LocalTime) = onChange(WindowDto(toMin(s), toMin(e)))

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_window_toggle), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { on -> if (on) apply(start, end) else onChange(null) })
            }
            if (enabled) {
                Spacer(Modifier.size(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    WindowTimeButton(stringResource(R.string.from), start.hhmm()) { editing = 0 }
                    WindowTimeButton(stringResource(R.string.to), end.hhmm()) { editing = 1 }
                }
            }
        }
    }

    editing?.let { which ->
        TimePickerDialog(
            initial = if (which == 0) start else end,
            title = stringResource(if (which == 0) R.string.from else R.string.to),
            onDismiss = { editing = null },
            onConfirm = { picked ->
                if (which == 0) apply(picked, end) else apply(start, picked)
                editing = null
            },
        )
    }
}

@Composable
private fun WindowTimeButton(label: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
