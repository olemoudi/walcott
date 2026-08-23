package dev.walcott.ui.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.PinResult
import dev.walcott.sync.RescueCode
import dev.walcott.ui.WalcottViewModel
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Where a child types the six digits a parent read out to them (see [RescueCode]).
 *
 * No PIN in front of it, deliberately: the whole situation this exists for is a phone that
 * cannot be reached and a parent who is not in the room. Asking for a second secret to use the
 * first one would be asking the child to fetch the very thing that is missing.
 *
 * What guards it instead is the code: one use, one half hour, and the same escalating lockout as
 * every other code entry in this app.
 */
@Composable
fun RescueCodeDialog(viewModel: WalcottViewModel, onDismiss: () -> Unit, onOpened: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<PinResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    val openedFmt = stringResource(R.string.rescue_opened)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rescue_child_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rescue_child_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter(Char::isDigit).take(RescueCode.DIGITS)
                        error = null
                    },
                    label = { Text(stringResource(R.string.rescue_child_label)) },
                    singleLine = true,
                    isError = error != null,
                    // Not a password field: nobody is hiding this from the person typing it, and
                    // six digits read out over a bad line are hard enough to get right.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val result = error) {
                    is PinResult.Locked -> Text(
                        stringResource(
                            R.string.rescue_locked,
                            Duration.ofMillis(result.remainingMs).toMinutes() + 1,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is PinResult.NotSet -> Text(
                        stringResource(R.string.rescue_child_no_family),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is PinResult.Wrong -> Text(
                        stringResource(R.string.rescue_child_wrong),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.length == RescueCode.DIGITS && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        when (val result = viewModel.redeemRescueCode(code)) {
                            is PinResult.Ok -> {
                                onOpened(openedFmt)
                                onDismiss()
                            }
                            else -> {
                                error = result
                                checking = false
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.rescue_child_use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
