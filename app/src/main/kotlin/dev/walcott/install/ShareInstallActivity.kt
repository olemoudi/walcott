package dev.walcott.install

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.WalcottApplication
import dev.walcott.data.PinResult
import dev.walcott.sync.DeviceMode
import kotlinx.coroutines.launch

/**
 * Share-sheet target: the parent shares an app from the Play Store into Walcott to push an
 * assisted install to one of their children. Parses the shared Play link, lets the parent pick
 * the child and (optionally) a category, then classifies the app and sends the install command.
 * A lightweight standalone activity that finishes as soon as it has sent — the actual send runs
 * on the application scope ([WalcottApplication.pushAppInstall]) so finishing doesn't cancel it.
 */
class ShareInstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WalcottApplication

        val pkg = PlayLink.parsePackage(intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (pkg == null) {
            toastAndFinish(R.string.install_share_bad_link)
            return
        }

        setContent {
            dev.walcott.ui.theme.WalcottTheme {
                val identity by app.syncManager.identity.collectAsStateWithLifecycle()
                val settings by app.repository.settingsFlow.collectAsStateWithLifecycle(initialValue = null)
                val snapshots by app.syncManager.state.collectAsStateWithLifecycle()

                // Hold until the persisted identity actually loads: judging the mode (and
                // initializing the PIN gate below) against the UNSET default would finish a
                // legitimate cold-start share — or skip the gate it should have asserted.
                val bootMode by app.syncManager.bootMode.collectAsStateWithLifecycle()
                if (bootMode == null) return@WalcottTheme

                if (identity.effectiveMode != DeviceMode.PARENT) {
                    toastAndFinish(R.string.install_share_parent_only)
                    return@WalcottTheme
                }
                val loaded = settings ?: return@WalcottTheme

                // Gate policy-writing entry points behind the parent PIN whenever app lock is on:
                // the share sheet bypasses the main app's lock, so re-assert it here.
                var unlocked by remember { mutableStateOf(!identity.appLock) }
                // Like the main app lock, an unlock must not survive the background: re-lock
                // on ON_STOP so a parked share dialog can't be resumed by someone else.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP && identity.appLock) unlocked = false
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                if (!unlocked) {
                    PinGate(
                        verify = { app.syncManager.verifyPinGuarded(it) },
                        onUnlocked = { unlocked = true },
                        onCancel = { finish() },
                    )
                    return@WalcottTheme
                }

                val childrenById = snapshots.children.associateBy { it.childId }
                val targets = loaded.children.mapNotNull { entry ->
                    childrenById[entry.childId]?.let { snap -> InstallTarget(entry.name, snap.deviceId) }
                }
                if (targets.isEmpty()) {
                    toastAndFinish(R.string.install_share_no_children)
                    return@WalcottTheme
                }

                InstallDialog(
                    pkg = pkg,
                    targets = targets,
                    onDismiss = { finish() },
                    onConfirm = { target, categoryId ->
                        app.pushAppInstall(target.deviceId, pkg, categoryId)
                        Toast.makeText(this, getString(R.string.install_share_sent, target.name), Toast.LENGTH_SHORT).show()
                        finish()
                    },
                )
            }
        }
    }

    private fun toastAndFinish(res: Int) {
        Toast.makeText(this, getString(res), Toast.LENGTH_LONG).show()
        finish()
    }
}

private data class InstallTarget(val name: String, val deviceId: String)

@Composable
private fun PinGate(
    verify: suspend (String) -> PinResult,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val wrong = stringResource(R.string.pin_incorrect)
    val lockedFmt = stringResource(R.string.pin_locked)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.app_lock_subtitle)) },
        text = {
            Column {
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = pin.isNotEmpty(), onClick = {
                scope.launch {
                    when (val r = verify(pin)) {
                        is PinResult.Ok -> onUnlocked()
                        is PinResult.Wrong -> error = wrong
                        is PinResult.Locked -> error = lockedFmt.format(((r.remainingMs + 59_999) / 60_000).toInt())
                    }
                }
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InstallDialog(
    pkg: String,
    targets: List<InstallTarget>,
    onDismiss: () -> Unit,
    onConfirm: (InstallTarget, categoryId: String?) -> Unit,
) {
    var selected by remember { mutableStateOf(targets.first()) }
    var category by remember { mutableStateOf<AppCategory?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.install_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_share_app, pkg), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.install_share_pick_child), style = MaterialTheme.typography.titleSmall)
                targets.forEach { target ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected == target, onClick = { selected = target }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == target, onClick = { selected = target })
                        Text(target.name)
                    }
                }
                Text(stringResource(R.string.install_share_pick_category), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppCategory.entries.forEach { cat ->
                        dev.walcott.ui.components.ChoiceChip(
                            selected = category == cat,
                            onClick = { category = if (category == cat) null else cat },
                            label = stringResource(cat.nameRes),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, category?.id) }) {
                Text(stringResource(R.string.install_share_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
