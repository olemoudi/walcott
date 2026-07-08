package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

private data class RestrictionUi(val key: String, val titleRes: Int, val descRes: Int)

private val RESTRICTIONS = listOf(
    RestrictionUi(DeviceRestrictions.KEY_VPN, R.string.restriction_vpn_title, R.string.restriction_vpn_desc),
    RestrictionUi(DeviceRestrictions.KEY_LOCATION, R.string.restriction_location_title, R.string.restriction_location_desc),
    RestrictionUi(DeviceRestrictions.KEY_DATETIME, R.string.restriction_datetime_title, R.string.restriction_datetime_desc),
    RestrictionUi(DeviceRestrictions.KEY_BIOMETRICS, R.string.restriction_biometrics_title, R.string.restriction_biometrics_desc),
    RestrictionUi(DeviceRestrictions.KEY_INSTALLS, R.string.restriction_installs_title, R.string.restriction_installs_desc),
    RestrictionUi(DeviceRestrictions.KEY_ADD_USER, R.string.restriction_add_user_title, R.string.restriction_add_user_desc),
    RestrictionUi(DeviceRestrictions.KEY_APPS_CONTROL, R.string.restriction_apps_control_title, R.string.restriction_apps_control_desc),
    RestrictionUi(DeviceRestrictions.KEY_UNKNOWN_SOURCES, R.string.restriction_unknown_sources_title, R.string.restriction_unknown_sources_desc),
)

/** Toggles that stop the child from changing critical device settings (Device Owner). */
@Composable
fun DeviceProtectionScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_protection_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Text(
                    stringResource(R.string.protection_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(RESTRICTIONS, key = { it.key }) { restriction ->
                RestrictionRow(
                    title = stringResource(restriction.titleRes),
                    description = stringResource(restriction.descRes),
                    checked = restriction.key in settings.deviceRestrictions,
                    onToggle = { on -> viewModel.setDeviceRestriction(restriction.key, on) },
                )
            }
            item { Spacer(Modifier.height(spacing.xl)) }
        }
    }
}

@Composable
private fun RestrictionRow(title: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.md))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}
