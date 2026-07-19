package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import dev.walcott.data.IdleEarnDto
import dev.walcott.data.WindowDto
import dev.walcott.rules.DayType
import dev.walcott.ui.DAY_TYPES
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.Stepper
import dev.walcott.ui.components.TimePickerDialog
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.labelRes
import dev.walcott.ui.theme.Tokens
import java.time.LocalTime

/**
 * Idle-earn editor (token-window model): the child banks idle time — time NOT on managed apps,
 * screen off included — and it converts into extra minutes for a target category, capped by a
 * rolling window and a weekly ceiling, and only during the enabled earn windows (so it can't
 * earn during class).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarnRulesScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cfg = settings.idleEarn

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_earn_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Text(
                    stringResource(R.string.earn_idle_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.earn_enable), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = cfg != null,
                            onCheckedChange = { on -> viewModel.setIdleEarn(if (on) defaultConfig() else null) },
                        )
                    }
                }
            }
            if (cfg != null) {
                item { RateCard(cfg, onChange = viewModel::setIdleEarn) }
                item { CapsCard(cfg, onChange = viewModel::setIdleEarn) }
                item { TargetCard(cfg, onChange = viewModel::setIdleEarn) }
                item { EarnWindowsCard(cfg, onSetWindow = { d, w -> viewModel.setEarnWindow(d, w) }) }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}

private fun defaultConfig() = IdleEarnDto(
    targetCategoryId = AppCategory.GAMES.id,
    minutesIdlePerReward = 10,
    rewardMinutes = 5,
    windowHours = 4,
    windowCapMinutes = 20,
    weeklyCapMinutes = 120,
)

@Composable
private fun RateCard(cfg: IdleEarnDto, onChange: (IdleEarnDto) -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.earn_rate_title), style = MaterialTheme.typography.titleMedium)
            StepperRow(
                label = stringResource(R.string.earn_idle_per),
                valueLabel = stringResource(R.string.minutes_fmt, cfg.minutesIdlePerReward),
                onDecrement = { onChange(cfg.copy(minutesIdlePerReward = (cfg.minutesIdlePerReward - 5).coerceAtLeast(5))) },
                onIncrement = { onChange(cfg.copy(minutesIdlePerReward = cfg.minutesIdlePerReward + 5)) },
            )
            StepperRow(
                label = stringResource(R.string.earn_reward_each),
                valueLabel = stringResource(R.string.minutes_fmt, cfg.rewardMinutes),
                onDecrement = { onChange(cfg.copy(rewardMinutes = (cfg.rewardMinutes - 5).coerceAtLeast(5))) },
                onIncrement = { onChange(cfg.copy(rewardMinutes = cfg.rewardMinutes + 5)) },
            )
        }
    }
}

@Composable
private fun CapsCard(cfg: IdleEarnDto, onChange: (IdleEarnDto) -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.earn_caps_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.earn_caps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StepperRow(
                label = stringResource(R.string.earn_window_hours),
                valueLabel = stringResource(R.string.hours_fmt, cfg.windowHours),
                onDecrement = { onChange(cfg.copy(windowHours = (cfg.windowHours - 1).coerceAtLeast(1))) },
                onIncrement = { onChange(cfg.copy(windowHours = cfg.windowHours + 1)) },
            )
            StepperRow(
                label = stringResource(R.string.earn_window_cap),
                valueLabel = stringResource(R.string.minutes_fmt, cfg.windowCapMinutes),
                onDecrement = { onChange(cfg.copy(windowCapMinutes = (cfg.windowCapMinutes - 5).coerceAtLeast(5))) },
                onIncrement = { onChange(cfg.copy(windowCapMinutes = cfg.windowCapMinutes + 5)) },
            )
            StepperRow(
                label = stringResource(R.string.earn_weekly_cap),
                valueLabel = stringResource(R.string.minutes_fmt, cfg.weeklyCapMinutes),
                onDecrement = { onChange(cfg.copy(weeklyCapMinutes = (cfg.weeklyCapMinutes - 15).coerceAtLeast(15))) },
                onIncrement = { onChange(cfg.copy(weeklyCapMinutes = cfg.weeklyCapMinutes + 15)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(cfg: IdleEarnDto, onChange: (IdleEarnDto) -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.earn_target_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                AppCategory.entries.forEach { category ->
                    dev.walcott.ui.components.ChoiceChip(
                        selected = cfg.targetCategoryId == category.id,
                        onClick = { onChange(cfg.copy(targetCategoryId = category.id)) },
                        label = stringResource(category.nameRes),
                    )
                }
            }
        }
    }
}

@Composable
private fun EarnWindowsCard(cfg: IdleEarnDto, onSetWindow: (DayType, WindowDto?) -> Unit) {
    val spacing = Tokens.spacing
    var editing by remember { mutableStateOf<Pair<DayType, Boolean>?>(null) } // dayType, isStart

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.earn_windows_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.earn_windows_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DAY_TYPES.forEach { dayType ->
                val window = cfg.earnWindows[dayType.name]?.firstOrNull()
                Column(Modifier.padding(top = spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(dayType.labelRes()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(
                            checked = window != null,
                            onCheckedChange = { on ->
                                onSetWindow(dayType, if (on) WindowDto(16 * 60, 21 * 60) else null)
                            },
                        )
                    }
                    if (window != null) {
                        val start = LocalTime.ofSecondOfDay(window.startMinute * 60L)
                        val end = LocalTime.ofSecondOfDay(window.endMinute * 60L)
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), modifier = Modifier.padding(top = spacing.xs)) {
                            WindowChip(stringResource(R.string.from), start.hhmm()) { editing = dayType to true }
                            WindowChip(stringResource(R.string.to), end.hhmm()) { editing = dayType to false }
                        }
                    } else {
                        Text(
                            stringResource(R.string.earn_window_all_day),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    editing?.let { (dayType, isStart) ->
        val window = cfg.earnWindows[dayType.name]?.firstOrNull() ?: WindowDto(16 * 60, 21 * 60)
        val start = LocalTime.ofSecondOfDay(window.startMinute * 60L)
        val end = LocalTime.ofSecondOfDay(window.endMinute * 60L)
        TimePickerDialog(
            initial = if (isStart) start else end,
            title = stringResource(if (isStart) R.string.from else R.string.to),
            onDismiss = { editing = null },
            onConfirm = { picked ->
                val pm = picked.hour * 60 + picked.minute
                onSetWindow(dayType, if (isStart) window.copy(startMinute = pm) else window.copy(endMinute = pm))
                editing = null
            },
        )
    }
}

@Composable
private fun StepperRow(label: String, valueLabel: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Stepper(valueLabel = valueLabel, decrementEnabled = true, onDecrement = onDecrement, onIncrement = onIncrement)
    }
}

@Composable
private fun WindowChip(label: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
