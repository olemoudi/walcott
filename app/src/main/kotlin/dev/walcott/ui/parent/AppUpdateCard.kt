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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.walcott.update.UpdateUiState
import dev.walcott.update.UpdateWorker
import dev.walcott.ui.components.PermissionFixRow
import dev.walcott.ui.theme.Tokens

/**
 * App version, manual update check and self-update diagnostics. Lives in the settings hub
 * so it is reachable in parent mode and behind the child's PIN gate. When a permission
 * blocks self-updating, it names the problem and deep-links into the fix.
 */
@Composable
internal fun AppUpdateCard(deviceOwner: Boolean) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val updateState by UpdateCenter.state.collectAsStateWithLifecycle()

    // Re-check permissions when the user comes back from the settings screens we open.
    var canInstall by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = deviceOwner || context.packageManager.canRequestPackageInstalls()
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
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
                        updateStatusText(updateState, deviceOwner),
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

            Spacer(Modifier.size(spacing.sm))
            OutlinedButton(
                onClick = { UpdateWorker.runNow(context) },
                enabled = updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.update_check_now)) }
        }
    }
}

@Composable
private fun updateStatusText(state: UpdateUiState, deviceOwner: Boolean): String = when (state) {
    is UpdateUiState.Idle ->
        stringResource(if (deviceOwner) R.string.update_silent_note else R.string.update_state_idle)
    is UpdateUiState.Checking -> stringResource(R.string.update_state_checking)
    is UpdateUiState.UpToDate -> stringResource(R.string.update_state_up_to_date)
    is UpdateUiState.Downloading -> stringResource(R.string.update_state_downloading, state.target.versionName)
    is UpdateUiState.PendingConfirmation -> stringResource(R.string.update_state_pending)
    is UpdateUiState.WaitingForParent -> stringResource(R.string.update_state_waiting_parent, state.target.versionName)
    is UpdateUiState.Failed -> stringResource(R.string.update_state_failed, state.step)
}

