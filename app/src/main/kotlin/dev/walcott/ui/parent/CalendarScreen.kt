package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.walcott.ui.components.WalcottDatePickerDialog
import dev.walcott.ui.components.WalcottTopBar
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

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_calendar_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item { SectionHeader(stringResource(R.string.calendar_holidays)) }
            items(settings.holidays.sorted(), key = { it }) { day ->
                RowItem(fmt(day), onDelete = { viewModel.removeHoliday(day) })
            }
            item {
                OutlinedButton(onClick = { mode = PickMode.HOLIDAY }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  " + stringResource(R.string.calendar_add_holiday))
                }
            }

            item { SectionHeader(stringResource(R.string.calendar_vacations)) }
            itemsIndexed(settings.vacations) { index, vac ->
                RowItem("${fmt(vac.startEpochDay)} – ${fmt(vac.endEpochDay)}", onDelete = { viewModel.removeVacation(index) })
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

    when (mode) {
        PickMode.HOLIDAY -> WalcottDatePickerDialog(
            onDismiss = { mode = null },
            onConfirm = { viewModel.addHoliday(it) },
        )
        PickMode.VACATION_START -> WalcottDatePickerDialog(
            onDismiss = { mode = null },
            onConfirm = { start -> vacationStart = start; mode = PickMode.VACATION_END },
        )
        PickMode.VACATION_END -> WalcottDatePickerDialog(
            onDismiss = { mode = null },
            onConfirm = { end ->
                val start = vacationStart
                if (start != null) viewModel.addVacation(minOf(start, end), maxOf(start, end))
            },
        )
        null -> Unit
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Tokens.spacing.md))
}

@Composable
private fun RowItem(label: String, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Tokens.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}
