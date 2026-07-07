package dev.walcott.ui

import android.app.Activity
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Full-screen lock shown in parent mode when the app lock is on, before the app content is
 * revealed. Unlock with the parent PIN, or with biometrics when enabled (auto-prompted).
 * There is no way past it other than authenticating — no back button.
 */
@Composable
fun AppLockScreen(viewModel: WalcottViewModel, onUnlocked: () -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val identity by viewModel.identity.collectAsStateWithLifecycle()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val wrongPin = stringResource(R.string.pin_incorrect)
    val pinFocus = remember { FocusRequester() }

    val biometricEnabled = identity.appLockBiometric && activity != null &&
        remember { BiometricAuth.isAvailable(context) }

    val promptTitle = stringResource(R.string.app_lock_biometric_title)
    val promptSubtitle = stringResource(R.string.app_lock_biometric_subtitle)
    val usePin = stringResource(R.string.app_lock_use_pin)

    fun promptBiometric() {
        val act = activity ?: return
        BiometricAuth.authenticate(
            activity = act,
            title = promptTitle,
            subtitle = promptSubtitle,
            negativeButton = usePin,
            onSuccess = onUnlocked,
            onCancel = { pinFocus.requestFocus() },
        )
    }

    fun submit() {
        scope.launch { if (viewModel.checkPin(pin)) onUnlocked() else error = wrongPin }
    }

    // Auto-prompt biometrics on open; otherwise focus the PIN field.
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) promptBiometric() else pinFocus.requestFocus()
    }

    Column(Modifier.fillMaxSize()) {
        // No back affordance: the lock can only be passed by authenticating.
        Text(
            stringResource(R.string.app_lock_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.lg),
        )
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
            Text(
                stringResource(R.string.app_lock_subtitle),
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
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(pinFocus),
            )
            error?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            if (biometricEnabled) {
                OutlinedButton(
                    onClick = { promptBiometric() },
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.app_lock_use_biometrics),
                        modifier = Modifier.padding(start = spacing.sm),
                    )
                }
            }
        }
    }
}
