package dev.walcott.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.walcott.R
import java.time.LocalDate

/** Date selection dialog; returns the chosen day as an epochDay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalcottDatePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (epochDay: Long) -> Unit,
) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(it / 86_400_000L) }
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

fun Long.epochDayToLocalDate(): LocalDate = LocalDate.ofEpochDay(this)
