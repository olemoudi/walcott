package dev.walcott.ui.parent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.Pin
import dev.walcott.data.PinResult
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.PinEntryField
import dev.walcott.ui.components.PinSetup
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Creates the PIN the first time, or asks for it to enter parent mode.
 *
 * Creating is two steps — choose it, then type it again from memory (see [PinSetup]) — rather
 * than two boxes on one screen. It is what every phone's own lock screen does, and for the same
 * reason: a second box you can compare against the first by looking at it confirms nothing.
 */
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

    var setup by remember { mutableStateOf(PinSetup()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Deriving PBKDF2 takes a beat even off-main; the button says so instead of going dead.
    var verifying by remember { mutableStateOf(false) }

    val wrongPin = stringResource(R.string.pin_incorrect)
    val lockedFmt = stringResource(R.string.pin_locked)

    fun submit() {
        if (verifying || !hasPin) return
        verifying = true
        scope.launch {
            when (val result = viewModel.verifyPin(pin)) {
                is PinResult.Ok -> onUnlocked()
                // Cleared as well as refused, like every lock screen on the phone.
                is PinResult.Wrong -> { error = wrongPin; pin = "" }
                is PinResult.Locked -> {
                    val mins = ((result.remainingMs + 59_999) / 60_000).toInt()
                    error = lockedFmt.format(mins)
                    pin = ""
                }
                // Unreachable while `hasPin` gates this branch; if the PIN is cleared
                // under us the screen re-renders into create mode by itself.
                is PinResult.NotSet -> Unit
            }
            verifying = false
        }
    }

    // Saved the moment both halves agree, so the last digit is the last thing anybody types.
    LaunchedEffect(setup.completed) {
        setup.completed?.let {
            viewModel.setPin(it)
            onUnlocked()
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

            if (creating) {
                // The two steps slide past each other, so "type it again" reads as a step
                // forward rather than as the same box having emptied itself.
                AnimatedContent(
                    targetState = setup.confirming,
                    transitionSpec = {
                        val forward = targetState
                        (slideInHorizontally { if (forward) it else -it } + fadeIn())
                            .togetherWith(slideOutHorizontally { if (forward) -it else it } + fadeOut())
                    },
                    label = "pin step",
                ) { confirming ->
                    Text(
                        stringResource(if (confirming) R.string.pin_step_repeat else R.string.pin_step_choose),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                    )
                }
                Text(
                    stringResource(R.string.pin_length_hint, Pin.MIN_LENGTH, Pin.MAX_LENGTH),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = spacing.xs, bottom = spacing.lg),
                )
                PinEntryField(
                    value = setup.entry,
                    onValueChange = { setup = setup.typed(it, Pin.MAX_LENGTH) },
                    label = stringResource(R.string.pin_label),
                    slots = Pin.MIN_LENGTH,
                    maxLength = Pin.MAX_LENGTH,
                    isError = setup.mismatch,
                    autoFocus = true,
                    imeAction = ImeAction.Done,
                    onImeAction = { setup = setup.advance(Pin.MIN_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (setup.mismatch) {
                    Text(
                        stringResource(R.string.pin_mismatch_restart),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                    )
                }
                // Why this is being asked for at all, said where somebody is actually reading.
                if (!setup.confirming) {
                    Text(
                        stringResource(R.string.parent_pin_create_why),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                }
                Button(
                    onClick = { setup = setup.advance(Pin.MIN_LENGTH) },
                    enabled = setup.canAdvance(Pin.MIN_LENGTH),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
                ) {
                    Text(
                        stringResource(
                            if (setup.confirming) R.string.action_create_pin else R.string.action_continue,
                        ),
                    )
                }
                if (setup.confirming) {
                    TextButton(onClick = { setup = PinSetup() }) {
                        Text(stringResource(R.string.pin_step_back))
                    }
                }
                return@Column
            }

            Text(
                stringResource(R.string.pin_subtitle_enter),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = spacing.md),
            )
            PinEntryField(
                value = pin,
                onValueChange = { pin = it; error = null },
                label = stringResource(R.string.pin_label),
                maxLength = Pin.MAX_LENGTH,
                enabled = !verifying,
                isError = error != null,
                autoFocus = true,
                onImeAction = ::submit,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
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
                    Text(stringResource(R.string.action_enter))
                }
            }
        }
    }
}
