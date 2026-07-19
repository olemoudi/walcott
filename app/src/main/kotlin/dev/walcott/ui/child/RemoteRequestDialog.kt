package dev.walcott.ui.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.CategoryStatusUi
import dev.walcott.ui.components.MinutesChips

/** Child-side: send a time request to the parent over the sync bus (no local PIN). */
@Composable
fun RemoteRequestDialog(
    card: CategoryStatusUi,
    onDismiss: () -> Unit,
    onSend: (minutes: Int, reason: String) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(15) }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extra_title, stringResource(card.category.nameRes))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.extra_how_much), style = MaterialTheme.typography.bodyMedium)
                MinutesChips(value = minutes, onSelect = { minutes = it })
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(120) },
                    label = { Text(stringResource(R.string.reason_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(minutes, reason) }) { Text(stringResource(R.string.send_request)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
