package dev.walcott.ui.parent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.BiometricAuth
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.theme.Tokens

/** Parent-mode toggle: require the PIN (and optionally biometrics) to open this app. */
@Composable
internal fun AppLockCard(viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }

    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_lock_setting_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.app_lock_setting_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = identity.appLock, onCheckedChange = viewModel::setAppLock)
            }
            AnimatedVisibility(identity.appLock && biometricAvailable) {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_lock_biometric_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = identity.appLockBiometric, onCheckedChange = viewModel::setAppLockBiometric)
                }
            }
        }
    }
}
