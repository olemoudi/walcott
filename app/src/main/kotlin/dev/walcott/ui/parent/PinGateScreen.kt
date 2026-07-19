package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.PinResult
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/** Creates the PIN the first time, or asks for it to enter parent mode. */
@Composable
fun PinGateScreen(
    viewModel: WalcottViewModel,
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
    // Creating a PIN is only allowed in the parent's initial setup; a child device must never
    // be able to set its own PIN to walk into parent settings (gate fail-open fix).
    allowCreate: Boolean,
) {
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val spacing = Tokens.spacing

    val creating = !hasPin && allowCreate
    val blocked = !hasPin && !allowCreate

    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Deriving PBKDF2 takes a beat even off-main; the button says so instead of going dead.
    var verifying by remember { mutableStateOf(false) }

    val tooShort = stringResource(R.string.pin_too_short)
    val mismatch = stringResource(R.string.pin_mismatch)
    val wrongPin = stringResource(R.string.pin_incorrect)
    val lockedFmt = stringResource(R.string.pin_locked)

    val pinFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    // Open the keyboard on the PIN field straight away so there's no extra tap.
    LaunchedEffect(blocked) { if (!blocked) pinFocus.requestFocus() }

    fun submit() {
        if (verifying) return
        if (hasPin) {
            verifying = true
            scope.launch {
                when (val result = viewModel.verifyPin(pin)) {
                    is PinResult.Ok -> onUnlocked()
                    is PinResult.Wrong -> error = wrongPin
                    is PinResult.Locked -> {
                        val mins = ((result.remainingMs + 59_999) / 60_000).toInt()
                        error = lockedFmt.format(mins)
                    }
                }
                verifying = false
            }
        } else if (creating) {
            when {
                pin.length < 4 -> error = tooShort
                pin != confirm -> error = mismatch
                else -> {
                    viewModel.createPin(pin)
                    onUnlocked()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(
            stringResource(if (creating) R.string.pin_title_create else R.string.pin_title_enter),
            onBack,
        )
        // imePadding lifts the whole block above the keyboard when it appears.
        Column(
            Modifier.fillMaxSize().imePadding().padding(spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock, contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            if (blocked) {
                // Child device with no synced parent PIN yet: never let them in or set one.
                Text(
                    stringResource(R.string.pin_ask_parent),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = spacing.md),
                )
                return@Column
            }
            Text(
                stringResource(if (creating) R.string.pin_subtitle_create else R.string.pin_subtitle_enter),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = spacing.md),
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8); error = null },
                label = { Text(stringResource(R.string.pin_label)) },
                singleLine = true,
                isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = if (creating) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submit() },
                    onNext = { confirmFocus.requestFocus() },
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(pinFocus),
            )
            if (creating) {
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(8); error = null },
                    label = { Text(stringResource(R.string.pin_repeat_label)) },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm).focusRequester(confirmFocus),
                )
            }
            error?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            Button(
                onClick = ::submit,
                enabled = pin.isNotEmpty() && !verifying,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
            ) {
                if (verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Text(stringResource(if (creating) R.string.action_create_pin else R.string.action_enter))
                }
            }
        }
    }
}
