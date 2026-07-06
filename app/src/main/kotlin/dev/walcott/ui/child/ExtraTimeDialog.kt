package dev.walcott.ui.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.CategoryStatusUi
import dev.walcott.ui.WalcottViewModel
import kotlinx.coroutines.launch

/** Asks for an adult's PIN and grants extra minutes to the category. Fully local (MVP). */
@Composable
fun ExtraTimeDialog(
    viewModel: WalcottViewModel,
    card: CategoryStatusUi,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(15) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extra_title, stringResource(card.category.nameRes))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.extra_how_much), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { option ->
                        FilterChip(
                            selected = minutes == option,
                            onClick = { minutes = option },
                            label = { Text(stringResource(R.string.extra_minutes, option)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); error = false },
                    label = { Text(stringResource(R.string.pin_adult_label)) },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Text(
                        stringResource(R.string.pin_incorrect),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.isNotEmpty() && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val ok = viewModel.checkPin(pin)
                        checking = false
                        if (ok) {
                            viewModel.grantExtra(card.category.id, minutes.toLong())
                            onDismiss()
                        } else {
                            error = true
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_grant)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
