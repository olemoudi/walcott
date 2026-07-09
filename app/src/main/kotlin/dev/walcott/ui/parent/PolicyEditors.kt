package dev.walcott.ui.parent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.data.WindowDto
import dev.walcott.rules.DayType
import dev.walcott.ui.DAY_TYPES
import dev.walcott.ui.components.Stepper
import dev.walcott.ui.components.TimePickerDialog
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.labelRes
import java.time.Duration
import java.time.LocalTime
import dev.walcott.ui.theme.Tokens

/**
 * Value-based policy editors, shared by the family editor (BudgetsScreen) and the
 * per-child override editor (ChildDetailScreen): they render a slice of PolicySettings
 * and report the whole new value through onChange.
 */

@Composable
internal fun BedtimeCard(bedtime: Map<String, WindowDto>, onChange: (Map<String, WindowDto>) -> Unit) {
    val spacing = Tokens.spacing
    // The MVP applies the same bedtime window to every day type.
    val window = bedtime[DayType.SCHOOL.name]
    val enabled = window != null
    val start = window?.let { LocalTime.ofSecondOfDay(it.startMinute * 60L) } ?: LocalTime.of(21, 30)
    val end = window?.let { LocalTime.ofSecondOfDay(it.endMinute * 60L) } ?: LocalTime.of(7, 30)

    var editing by remember { mutableStateOf<BedtimeEdit?>(null) }

    fun applyAll(s: LocalTime?, e: LocalTime?) {
        onChange(
            if (s == null || e == null) {
                emptyMap()
            } else {
                DAY_TYPES.associate { it.name to WindowDto(s.toMinute(), e.toMinute()) }
            },
        )
    }

    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bedtime_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on -> if (on) applyAll(start, end) else applyAll(null, null) },
                )
            }
            if (enabled) {
                Spacer(Modifier.size(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    TimeButton(stringResource(R.string.from), start.hhmm()) { editing = BedtimeEdit.START }
                    TimeButton(stringResource(R.string.to), end.hhmm()) { editing = BedtimeEdit.END }
                }
            }
        }
    }

    editing?.let { which ->
        TimePickerDialog(
            initial = if (which == BedtimeEdit.START) start else end,
            title = stringResource(if (which == BedtimeEdit.START) R.string.bedtime_start_title else R.string.bedtime_end_title),
            onDismiss = { editing = null },
            onConfirm = { picked ->
                if (which == BedtimeEdit.START) applyAll(picked, end) else applyAll(start, picked)
                editing = null
            },
        )
    }
}

private enum class BedtimeEdit { START, END }

private fun LocalTime.toMinute() = hour * 60 + minute

@Composable
private fun TimeButton(label: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun BudgetPreset(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun CategoryBudgetCard(
    category: AppCategory,
    perDay: Map<String, Int>,
    onSetBudget: (DayType, Int?) -> Unit,
) {
    val spacing = Tokens.spacing
    var expanded by remember { mutableStateOf(false) }
    val limitedDays = DAY_TYPES.count { perDay[it.name] != null }
    val summary = if (limitedDays == 0) stringResource(R.string.no_limit)
    else pluralStringResource(R.plurals.days_with_limit, limitedDays, limitedDays)

    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize().clickable { expanded = !expanded }.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(category.icon, contentDescription = null, tint = category.color)
                }
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(category.nameRes), style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                Spacer(Modifier.size(spacing.md))
                HorizontalDivider()
                // Quick presets applied to every day type at once — the common case, far fewer
                // taps than stepping each of the three rows up from "no limit".
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.budget_apply_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    BudgetPreset(stringResource(R.string.no_limit)) { DAY_TYPES.forEach { onSetBudget(it, null) } }
                    BudgetPreset("30m") { DAY_TYPES.forEach { onSetBudget(it, 30) } }
                    BudgetPreset("1h") { DAY_TYPES.forEach { onSetBudget(it, 60) } }
                    BudgetPreset("2h") { DAY_TYPES.forEach { onSetBudget(it, 120) } }
                }
                DAY_TYPES.forEach { dayType ->
                    val minutes = perDay[dayType.name]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(dayType.labelRes()), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Stepper(
                            valueLabel = minutes?.let { Duration.ofMinutes(it.toLong()).humanize() }
                                ?: stringResource(R.string.no_limit),
                            decrementEnabled = minutes != null,
                            onDecrement = {
                                val next = (minutes ?: 0) - 15
                                onSetBudget(dayType, if (next < 15) null else next)
                            },
                            onIncrement = { onSetBudget(dayType, (minutes ?: 0) + 15) },
                        )
                    }
                }
            }
        }
    }
}
