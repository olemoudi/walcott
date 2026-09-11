package dev.walcott.ui.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.PinResult
import dev.walcott.sync.RescueCode
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.PinEntryField
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

    fun redeem() {
        if (checking || code.length != RescueCode.DIGITS) return
        checking = true
        scope.launch {
            when (val result = viewModel.redeemRescueCode(code)) {
                is PinResult.Ok -> {
                    onOpened(openedFmt)
                    onDismiss()
                }
                else -> {
                    error = result
                    // Cleared so the next attempt starts from an empty row rather than from
                    // six digits that have already been refused.
                    code = ""
                    checking = false
                }
            }
        }
    }

    // Six digits is the whole code, so the sixth one IS the "use it" tap. Nobody reading a
    // number off a phone call should have to find a button afterwards.
    LaunchedEffect(code) { if (code.length == RescueCode.DIGITS) redeem() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rescue_child_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rescue_child_desc), style = MaterialTheme.typography.bodyMedium)
                PinEntryField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    label = stringResource(R.string.rescue_child_label),
                    slots = RescueCode.DIGITS,
                    maxLength = RescueCode.DIGITS,
                    enabled = !checking,
                    isError = error != null,
                    autoFocus = true,
                    // Nobody is hiding this from the person typing it, and six digits read out
                    // over a bad line are hard enough to get right without dots.
                    masked = false,
                    onImeAction = ::redeem,
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
                onClick = ::redeem,
            ) { Text(stringResource(R.string.rescue_child_use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
