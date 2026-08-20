package dev.walcott.ui.parent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.BuildConfig
import dev.walcott.R
import dev.walcott.update.UpdateCenter
import dev.walcott.update.UpdateInfo
import dev.walcott.update.UpdateUiState
import dev.walcott.update.UpdateWorker
import dev.walcott.update.Updater
import dev.walcott.ui.components.PermissionFixRow
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App version, manual update check and self-update diagnostics. Lives in the settings hub
 * so it is reachable in parent mode and behind the child's PIN gate. When a permission
 * blocks self-updating, it names the problem and deep-links into the fix.
 *
 * It is also the way back when an update was dismissed — the system's install prompt cancelled
 * by reflex, the notification swiped away, a check ignored for a week. That path has to work
 * with no network at all, which is why it offers the APK already on disk rather than another
 * check: the bytes are there, and asking for them again is how a mis-tap became a lost release.
 */
@Composable
internal fun AppUpdateCard(deviceOwner: Boolean) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val updateState by UpdateCenter.state.collectAsStateWithLifecycle()

    // Re-check permissions when the user comes back from the settings screens we open.
    var canInstall by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = deviceOwner || context.packageManager.canRequestPackageInstalls()
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The update already downloaded and waiting, if there is one. Re-read whenever the status
    // moves (a download finishing, an install being declined) and whenever the screen comes
    // back, so returning from a cancelled system dialog shows the offer straight away.
    // Off the main thread without exception: reading it parses the manifest of a ~50 MB file.
    var staged by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(updateState, resumeTick) {
        staged = withContext(Dispatchers.IO) { Updater(context).stagedUpdate() }
    }

    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        updateStatusText(updateState, staged, deviceOwner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!canInstall) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_install_missing),
                    action = stringResource(R.string.perm_install_fix),
                    onFix = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
            if (!notificationsEnabled && !deviceOwner) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_notifications_missing),
                    action = stringResource(R.string.perm_notifications_fix),
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }

            staged?.let { pending ->
                Spacer(Modifier.size(spacing.sm))
                Button(
                    onClick = { UpdateWorker.installStagedNow(context) },
                    enabled = updateState !is UpdateUiState.Installing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_install_staged, pending.versionName)) }
            }

            Spacer(Modifier.size(spacing.sm))
            OutlinedButton(
                onClick = { UpdateWorker.checkNow(context) },
                enabled = updateState !is UpdateUiState.Checking &&
                    updateState !is UpdateUiState.Downloading &&
                    updateState !is UpdateUiState.Installing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.update_check_now)) }
        }
    }
}

@Composable
private fun updateStatusText(state: UpdateUiState, staged: UpdateInfo?, deviceOwner: Boolean): String {
    // A downloaded-and-waiting update outranks the quiet states. "Updates are checked
    // automatically", printed directly above a button offering to install one, reads as a
    // contradiction — and the person who dismissed the prompt is precisely the one who needs
    // telling that it is still here.
    if (staged != null && (state is UpdateUiState.Idle || state is UpdateUiState.UpToDate)) {
        return stringResource(R.string.update_state_staged, staged.versionName)
    }
    return when (state) {
        is UpdateUiState.Idle ->
            stringResource(if (deviceOwner) R.string.update_silent_note else R.string.update_state_idle)
        is UpdateUiState.Checking -> stringResource(R.string.update_state_checking)
        is UpdateUiState.UpToDate -> stringResource(R.string.update_state_up_to_date)
        is UpdateUiState.Downloading -> stringResource(R.string.update_state_downloading, state.target.versionName)
        is UpdateUiState.Installing -> stringResource(R.string.update_state_installing, state.target.versionName)
        is UpdateUiState.PendingConfirmation -> stringResource(R.string.update_state_pending)
        is UpdateUiState.WaitingForParent -> stringResource(R.string.update_state_waiting_parent, state.target.versionName)
        is UpdateUiState.WaitingForWifi -> stringResource(R.string.update_state_waiting_wifi, state.target.versionName)
        is UpdateUiState.Failed -> stringResource(R.string.update_state_failed, state.step)
    }
}
