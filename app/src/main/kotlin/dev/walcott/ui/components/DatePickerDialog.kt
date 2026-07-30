package dev.walcott.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import java.time.LocalDate

/**
 * Date selection dialog; hands the chosen day back as an epochDay.
 *
 * Confirming does NOT dismiss on its own — [onConfirm] decides what happens next. That matters for
 * anything picking two dates in a row: this used to call [onDismiss] straight after [onConfirm],
 * so a caller that answered "now show me the second picker" had its own state wiped a line later
 * and the second dialog never appeared. Cancelling still goes through [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalcottDatePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (epochDay: Long) -> Unit,
    /** Day the picker opens on, e.g. the start date when choosing the end of a period. */
    initialEpochDay: Long? = null,
    /** Which of the two dates this is; null keeps the platform default header. */
    title: String? = null,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialEpochDay?.let { it * 86_400_000L },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                // Nothing to hand back until a day is actually picked.
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onConfirm(it / 86_400_000L) } },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(
            state = state,
            title = title?.let { { Text(it, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) } },
        )
    }
}

fun Long.epochDayToLocalDate(): LocalDate = LocalDate.ofEpochDay(this)
