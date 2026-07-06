package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
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
) {
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val spacing = Tokens.spacing

    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val tooShort = stringResource(R.string.pin_too_short)
    val mismatch = stringResource(R.string.pin_mismatch)
    val wrongPin = stringResource(R.string.pin_incorrect)

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(
            stringResource(if (hasPin) R.string.pin_title_enter else R.string.pin_title_create),
            onBack,
        )
        Column(
            Modifier.fillMaxSize().padding(spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock, contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(if (hasPin) R.string.pin_subtitle_enter else R.string.pin_subtitle_create),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!hasPin) {
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(8); error = null },
                    label = { Text(stringResource(R.string.pin_repeat_label)) },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
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
                onClick = {
                    if (hasPin) {
                        scope.launch {
                            if (viewModel.checkPin(pin)) onUnlocked() else error = wrongPin
                        }
                    } else {
                        when {
                            pin.length < 4 -> error = tooShort
                            pin != confirm -> error = mismatch
                            else -> {
                                viewModel.createPin(pin)
                                onUnlocked()
                            }
                        }
                    }
                },
                enabled = pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
            ) { Text(stringResource(if (hasPin) R.string.action_enter else R.string.action_create_pin)) }
        }
    }
}
