package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.ChildEntry
import dev.walcott.data.VacationDto
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottDatePickerDialog
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
private fun fmt(epochDay: Long) = LocalDate.ofEpochDay(epochDay).format(dateFormat)

private enum class PickMode { HOLIDAY, VACATION_START, VACATION_END }

@Composable
fun CalendarScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf<PickMode?>(null) }
    var vacationStart by remember { mutableStateOf<Long?>(null) }
    // Which entry's "who does this apply to" is being edited, if any.
    var scoping by remember { mutableStateOf<Scoping?>(null) }
    // With one child, "the family" and "that child" are the same thing, so the question is noise.
    val manyChildren = settings.children.size >= 2

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_calendar_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Column(Modifier.padding(top = spacing.md)) {
                    WeekendEdgesCard(
                        startMinute = settings.weekendStartsFridayAtMinute,
                        endMinute = settings.weekendEndsSundayAtMinute,
                        onChange = viewModel::setWeekendEdges,
                    )
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.calendar_holidays),
                    icon = Icons.Outlined.CalendarMonth,
                    accent = SectionAccent.FAMILY,
                )
            }
            item {
                val holidays = settings.allHolidays().sorted()
                CardGroup {
                    holidays.forEachIndexed { index, day ->
                        RowItem(
                            label = fmt(day),
                            scope = scopeLabel(settings.holidayScope(day), settings.children),
                            position = cardPosition(index, holidays.size),
                            onEditScope = { scoping = Scoping.Holiday(day) }.takeIf { manyChildren },
                            onDelete = { viewModel.removeHoliday(day) },
                        )
                    }
                }
            }
            item {
                OutlinedButton(onClick = { mode = PickMode.HOLIDAY }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  " + stringResource(R.string.calendar_add_holiday))
                }
            }

            item {
                SectionHeader(
                    stringResource(R.string.calendar_vacations),
                    icon = Icons.Outlined.BeachAccess,
                    accent = SectionAccent.FAMILY,
                )
            }
            item {
                val vacations = settings.allVacations().sortedBy { it.startEpochDay }
                CardGroup {
                    vacations.forEachIndexed { index, vac ->
                        RowItem(
                            label = "${fmt(vac.startEpochDay)} – ${fmt(vac.endEpochDay)}",
                            scope = scopeLabel(settings.vacationScope(vac), settings.children),
                            position = cardPosition(index, vacations.size),
                            onEditScope = { scoping = Scoping.Period(vac) }.takeIf { manyChildren },
                            onDelete = { viewModel.removeVacation(vac) },
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { vacationStart = null; mode = PickMode.VACATION_START },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  " + stringResource(R.string.calendar_add_vacation))
                }
            }

            if (settings.holidays.isEmpty() && settings.vacations.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.calendar_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }
        }
    }

    // Each branch closes itself: the picker no longer dismisses on confirm, which is what lets
    // the two-step period flow hand over from the start date to the end date.
    when (mode) {
        PickMode.HOLIDAY -> WalcottDatePickerDialog(
            onDismiss = { mode = null },
            // A new day belongs to everyone; who it is really for is one tap away on its row.
            onConfirm = { day -> viewModel.addHoliday(day); mode = null; if (manyChildren) scoping = Scoping.Holiday(day) },
            title = stringResource(R.string.calendar_pick_holiday),
        )
        PickMode.VACATION_START -> WalcottDatePickerDialog(
            onDismiss = { mode = null },
            onConfirm = { start -> vacationStart = start; mode = PickMode.VACATION_END },
            title = stringResource(R.string.calendar_pick_start),
        )
        PickMode.VACATION_END -> WalcottDatePickerDialog(
            onDismiss = { mode = null; vacationStart = null },
            // Opens on the start date, so picking a same-week end is a tap rather than a hunt.
            initialEpochDay = vacationStart,
            onConfirm = { end ->
                val start = vacationStart
                // minOf/maxOf: picking the two dates backwards is a period, not an error.
                if (start != null) {
                    viewModel.addVacation(minOf(start, end), maxOf(start, end))
                    if (manyChildren) {
                        scoping = Scoping.Period(VacationDto(minOf(start, end), maxOf(start, end)))
                    }
                }
                vacationStart = null
                mode = null
            },
            title = stringResource(R.string.calendar_pick_end),
        )
        null -> Unit
    }

    scoping?.let { target ->
        val current = when (target) {
            is Scoping.Holiday -> settings.holidayScope(target.day)
            is Scoping.Period -> settings.vacationScope(target.period)
        }
        ScopeDialog(
            children = settings.children,
            selected = current,
            onDismiss = { scoping = null },
            onConfirm = { picked ->
                when (target) {
                    is Scoping.Holiday -> viewModel.setHolidayScope(target.day, picked)
                    is Scoping.Period -> viewModel.setVacationScope(target.period, picked)
                }
                scoping = null
            },
        )
    }
}

/**
 * Who a special day is for. Nothing ticked means the whole family, which is both the default and
 * the honest reading of "no child in particular" — a birthday ticks one name, a bank holiday none.
 */
@Composable
private fun ScopeDialog(
    children: List<ChildEntry>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var picked by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_scope_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.calendar_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = picked.isEmpty(), onCheckedChange = { picked = emptySet() })
                    Text(stringResource(R.string.calendar_scope_everyone))
                }
                children.forEach { child ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = child.childId in picked,
                            onCheckedChange = { on ->
                                picked = if (on) picked + child.childId else picked - child.childId
                            },
                        )
                        Text(child.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Which entry the "applies to" chooser is open for. */
private sealed interface Scoping {
    data class Holiday(val day: Long) : Scoping
    data class Period(val period: VacationDto) : Scoping
}

/** "Everyone", or the names of the children a day belongs to. */
@Composable
private fun scopeLabel(childIds: Set<String>, children: List<ChildEntry>): String =
    if (childIds.isEmpty()) stringResource(R.string.calendar_scope_everyone)
    else children.filter { it.childId in childIds }.joinToString { it.name }
        .ifBlank { stringResource(R.string.calendar_scope_everyone) }

@Composable
private fun RowItem(
    label: String,
    scope: String,
    position: CardPosition = CardPosition.Single,
    /** Null when the family has fewer than two children and the question has no answer worth asking. */
    onEditScope: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    WalcottCard(position = position, onClick = onEditScope ?: {}, enabled = onEditScope != null) {
        Row(Modifier.padding(start = Tokens.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = Tokens.spacing.sm)) {
                Text(label)
                if (onEditScope != null) {
                    Text(
                        scope,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}
