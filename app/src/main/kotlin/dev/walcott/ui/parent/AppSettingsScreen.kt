package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.PinResult
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Settings about the Walcott app itself — updates, the parent app lock, debug logs, and
 * device-level actions — kept apart from the family's rules so the rules hub stays about
 * the children, not about the tool.
 */
@Composable
fun AppSettingsScreen(
    viewModel: WalcottViewModel,
    deviceOwner: Boolean,
    childDevice: Boolean,
    installsBlocked: Boolean,
    installExemptionUntil: Long,
    onAllowInstalls: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    onChangeMode: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    var confirmChangeMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.app_settings_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (!childDevice) {
                AppLockCard(viewModel)
            }
            // The family's disaster recovery lives on the parent, whose keys are the family.
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            if (identity.role == dev.walcott.sync.Role.PARENT) {
                FamilyBackupCard(viewModel)
            }
            AppUpdateCard(deviceOwner)
            // Wi-Fi-only updates: a family policy, so it's only editable on the parent.
            if (!childDevice) {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                UpdateWifiOnlyCard(
                    enabled = settings.updateWifiOnly,
                    onToggle = { viewModel.setUpdateWifiOnly(it) },
                )
            }
            NavCard(
                Icons.Outlined.BugReport,
                stringResource(R.string.nav_debug_title),
                stringResource(R.string.nav_debug_subtitle),
                onClick = onOpenDebugLogs,
            )
            if (childDevice && installsBlocked) {
                val remainingMs = installExemptionUntil - System.currentTimeMillis()
                NavCard(
                    Icons.Outlined.InstallMobile,
                    stringResource(R.string.allow_installs_title),
                    if (remainingMs > 0) {
                        stringResource(R.string.allow_installs_active, Duration.ofMillis(remainingMs).humanize())
                    } else {
                        stringResource(R.string.allow_installs_desc)
                    },
                    onClick = onAllowInstalls,
                )
            }
            if (childDevice) {
                NavCard(
                    Icons.Outlined.SwapHoriz,
                    stringResource(R.string.change_device_mode),
                    stringResource(R.string.change_device_mode_subtitle),
                    onClick = { confirmChangeMode = true },
                )
            }
        }
    }

    if (confirmChangeMode) {
        // Re-verify the PIN before leaving child mode (which would drop enforcement).
        val scope = rememberCoroutineScope()
        var pin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf<String?>(null) }
        val wrongPin = stringResource(R.string.pin_incorrect)
        val lockedFmt = stringResource(R.string.pin_locked)
        AlertDialog(
            onDismissRequest = { confirmChangeMode = false },
            title = { Text(stringResource(R.string.change_device_mode)) },
            text = {
                Column {
                    Text(stringResource(R.string.change_mode_confirm))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8); pinError = null },
                        label = { Text(stringResource(R.string.pin_label)) },
                        singleLine = true,
                        isError = pinError != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                    )
                    pinError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = pin.isNotEmpty(), onClick = {
                    scope.launch {
                        when (val result = viewModel.verifyPin(pin)) {
                            is PinResult.Ok -> { confirmChangeMode = false; onChangeMode() }
                            is PinResult.Wrong -> pinError = wrongPin
                            is PinResult.Locked ->
                                pinError = lockedFmt.format(((result.remainingMs + 59_999) / 60_000).toInt())
                        }
                    }
                }) { Text(stringResource(R.string.change_mode_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmChangeMode = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun UpdateWifiOnlyCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_wifi_only_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.update_wifi_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
