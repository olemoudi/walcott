package dev.walcott.ui.parent

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.WalcottApplication
import dev.walcott.data.PinResult
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.data.ThemeMode
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
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
    onAllowInstalls: (durationMs: Long) -> Unit,
    onEndInstallWindow: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    onChangeMode: () -> Unit,
    onReleased: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmChangeMode by remember { mutableStateOf(false) }
    var confirmRelease by remember { mutableStateOf(false) }
    var releasing by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    var showAllowInstalls by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.app_settings_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            SectionHeader(stringResource(R.string.app_settings_section_appearance))
            ThemeCard()
            if (!childDevice) {
                SectionHeader(stringResource(R.string.app_settings_section_security))
                AppLockCard(viewModel)
            }
            // The family's disaster recovery lives on the parent, whose keys are the family.
            val identity by viewModel.identity.collectAsStateWithLifecycle()
            if (identity.role == dev.walcott.sync.Role.PARENT) {
                // Parent-only, and for the same reason PinGateScreen refuses to create a PIN on
                // a child device: the PIN is what releases a child's phone.
                ParentPinCard(viewModel)
                FamilyBackupCard(viewModel)
            }
            SectionHeader(stringResource(R.string.app_settings_section_updates))
            AppUpdateCard(deviceOwner)
            // Wi-Fi-only updates: a family policy, so it's only editable on the parent.
            if (!childDevice) {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                UpdateWifiOnlyCard(
                    enabled = settings.updateWifiOnly,
                    onToggle = { viewModel.setUpdateWifiOnly(it) },
                )
            }
            SectionHeader(stringResource(R.string.app_settings_section_device))
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
                    onClick = { showAllowInstalls = true },
                )
            }
            if (childDevice) {
                NavCard(
                    Icons.Outlined.SwapHoriz,
                    stringResource(R.string.change_device_mode),
                    stringResource(R.string.change_device_mode_subtitle),
                    onClick = { confirmChangeMode = true },
                )
                // The parent-PIN way out: for the family whose parent phone (and backup) is
                // gone but who still knows the PIN. Unlike "change mode", which only unlinks,
                // this hands the whole device back — see PanicRelease.
                DangerCard(
                    title = stringResource(R.string.release_device_title),
                    description = stringResource(R.string.release_device_subtitle),
                    onClick = { confirmRelease = true },
                )
            }
        }
    }

    if (showAllowInstalls) {
        AllowInstallsDialog(
            windowOpen = installExemptionUntil > System.currentTimeMillis(),
            onPick = { durationMs -> showAllowInstalls = false; onAllowInstalls(durationMs) },
            onReblock = { showAllowInstalls = false; onEndInstallWindow() },
            onDismiss = { showAllowInstalls = false },
        )
    }

    if (confirmChangeMode) {
        // Re-verify the PIN before leaving child mode (which would drop enforcement).
        PinConfirmDialog(
            viewModel = viewModel,
            title = stringResource(R.string.change_device_mode),
            message = stringResource(R.string.change_mode_confirm),
            confirmLabel = stringResource(R.string.change_mode_confirm_button),
            onDismiss = { confirmChangeMode = false },
            onConfirmed = { confirmChangeMode = false; onChangeMode() },
        )
    }

    if (confirmRelease) {
        // The PIN again, for the same reason it guards leaving child mode — and here the stakes
        // are higher: this is irreversible without re-enrolling the device from scratch.
        PinConfirmDialog(
            viewModel = viewModel,
            title = stringResource(R.string.release_device_title),
            message = stringResource(R.string.release_device_confirm),
            confirmLabel = stringResource(R.string.release_device_confirm_button),
            busy = releasing,
            onDismiss = { confirmRelease = false },
            onConfirmed = {
                releasing = true
                (context.applicationContext as dev.walcott.WalcottApplication).releaseDevice {
                    releasing = false
                    confirmRelease = false
                    released = true
                }
            },
        )
    }

    if (released) {
        // Done, and the app no longer manages anything. Offer the last step — removing it —
        // since "as if it had never been installed" is the whole point of this door.
        AlertDialog(
            onDismissRequest = { released = false; onReleased() },
            title = { Text(stringResource(R.string.release_done_title)) },
            text = { Text(stringResource(R.string.release_done_text)) },
            confirmButton = {
                TextButton(onClick = { dev.walcott.enforcement.PanicRelease.requestUninstall(context) }) {
                    Text(stringResource(R.string.release_uninstall_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { released = false; onReleased() }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/**
 * The PIN-gated "allow installs" flow, in the two steps the situation deserves: is this
 * temporary — and if so, for how long? "I don't know" opens the long window (8 h) with hourly
 * reminders on the parent phone from the first hour, and the block re-arms itself at the end.
 * Not-temporary is a policy change, which belongs in Device protection on the parent's phone —
 * a local policy edit here would be overwritten by the next sync.
 */
@Composable
private fun AllowInstallsDialog(
    windowOpen: Boolean,
    onPick: (durationMs: Long) -> Unit,
    onReblock: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Step 1 asks "temporary?"; "yes" advances, "no" swaps in the permanent-path hint.
    var step by remember { mutableStateOf(if (windowOpen) Step.DURATION else Step.TEMPORARY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.allow_installs_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                when (step) {
                    Step.TEMPORARY -> Text(stringResource(R.string.allow_installs_q_temporary))
                    Step.PERMANENT_HINT -> Text(stringResource(R.string.allow_installs_permanent_hint))
                    Step.DURATION -> {
                        Text(stringResource(R.string.allow_installs_duration_q))
                        DurationOption(stringResource(R.string.allow_installs_10)) {
                            onPick(DeviceRestrictions.INSTALL_EXEMPTION_SHORT_MS)
                        }
                        DurationOption(stringResource(R.string.allow_installs_30)) {
                            onPick(DeviceRestrictions.INSTALL_EXEMPTION_MEDIUM_MS)
                        }
                        DurationOption(
                            stringResource(R.string.allow_installs_unsure),
                            supporting = stringResource(R.string.allow_installs_unsure_hint),
                        ) { onPick(DeviceRestrictions.INSTALL_EXEMPTION_UNSURE_MS) }
                        if (windowOpen) {
                            DurationOption(stringResource(R.string.allow_installs_reblock)) { onReblock() }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step == Step.TEMPORARY) {
                TextButton(onClick = { step = Step.DURATION }) {
                    Text(stringResource(R.string.allow_installs_temp_yes))
                }
            }
        },
        dismissButton = {
            when (step) {
                Step.TEMPORARY -> TextButton(onClick = { step = Step.PERMANENT_HINT }) {
                    Text(stringResource(R.string.allow_installs_temp_no))
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private enum class Step { TEMPORARY, PERMANENT_HINT, DURATION }

/** One tappable duration row inside [AllowInstallsDialog]. */
@Composable
private fun DurationOption(label: String, supporting: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Tokens.spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A destructive action's card: same shape as [NavCard] but in the error colour, because the
 * two actions that use it (leaving child mode, releasing the device) can't be undone from
 * this phone.
 */
@Composable
private fun DangerCard(title: String, description: String, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(onClick = onClick, color = color.copy(alpha = 0.10f)) {
        Column(Modifier.padding(spacing.lg)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(description, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/**
 * Asks for the parent PIN before an irreversible local action, with the same brute-force
 * lockout as every other PIN entry (see [WalcottViewModel.verifyPin]).
 */
@Composable
private fun PinConfirmDialog(
    viewModel: WalcottViewModel,
    title: String,
    message: String,
    confirmLabel: String,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val wrongPin = stringResource(R.string.pin_incorrect)
    val lockedFmt = stringResource(R.string.pin_locked)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(message)
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
            TextButton(enabled = pin.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    when (val result = viewModel.verifyPin(pin)) {
                        is PinResult.Ok -> onConfirmed()
                        is PinResult.Wrong -> pinError = wrongPin
                        is PinResult.Locked ->
                            pinError = lockedFmt.format(((result.remainingMs + 59_999) / 60_000).toInt())
                    }
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun UpdateWifiOnlyCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard {
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

/**
 * Manual light/dark choice, stored on this device only — a display preference, not policy,
 * so it never syncs to the family.
 */
@Composable
private fun ThemeCard() {
    val spacing = Tokens.spacing
    val context = androidx.compose.ui.platform.LocalContext.current
    val themeStore = (context.applicationContext as WalcottApplication).themeStore
    val mode by themeStore.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()
    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.padding(top = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ThemeMode.entries.forEach { candidate ->
                    ChoiceChip(
                        selected = mode == candidate,
                        onClick = { scope.launch { themeStore.setMode(candidate) } },
                        label = stringResource(
                            when (candidate) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            },
                        ),
                    )
                }
            }
        }
    }
}
