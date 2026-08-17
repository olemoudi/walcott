package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens

private data class RestrictionUi(
    val key: String,
    val titleRes: Int,
    val descRes: Int,
    val group: DeviceRestrictions.Group,
)

/**
 * Every lock this app offers, in the three groups the screen is read as (see
 * [DeviceRestrictions.Group]).
 *
 * Grouped rather than listed since 0.63, when the list went from eight switches to eighteen: at
 * that length a flat column is scanned once, given up on, and left at whatever it came with.
 * "Somebody is trying to get around the rules" and "somebody pressed the wrong thing" are two
 * different worries, and a parent almost always arrives with exactly one of them.
 */
private val RESTRICTIONS = listOf(
    RestrictionUi(DeviceRestrictions.KEY_VPN, R.string.restriction_vpn_title, R.string.restriction_vpn_desc, DeviceRestrictions.Group.TAMPER),
    RestrictionUi(DeviceRestrictions.KEY_LOCATION, R.string.restriction_location_title, R.string.restriction_location_desc, DeviceRestrictions.Group.TAMPER),
    RestrictionUi(DeviceRestrictions.KEY_DATETIME, R.string.restriction_datetime_title, R.string.restriction_datetime_desc, DeviceRestrictions.Group.TAMPER),
    RestrictionUi(DeviceRestrictions.KEY_BIOMETRICS, R.string.restriction_biometrics_title, R.string.restriction_biometrics_desc, DeviceRestrictions.Group.TAMPER),
    RestrictionUi(DeviceRestrictions.KEY_ADD_USER, R.string.restriction_add_user_title, R.string.restriction_add_user_desc, DeviceRestrictions.Group.TAMPER),

    RestrictionUi(DeviceRestrictions.KEY_AIRPLANE, R.string.restriction_airplane_title, R.string.restriction_airplane_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_LOCALE, R.string.restriction_locale_title, R.string.restriction_locale_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_BRIGHTNESS, R.string.restriction_brightness_title, R.string.restriction_brightness_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_SCREEN_TIMEOUT, R.string.restriction_screen_timeout_title, R.string.restriction_screen_timeout_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_MOBILE_NETWORKS, R.string.restriction_mobile_networks_title, R.string.restriction_mobile_networks_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_WIFI, R.string.restriction_wifi_title, R.string.restriction_wifi_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_NETWORK_RESET, R.string.restriction_network_reset_title, R.string.restriction_network_reset_desc, DeviceRestrictions.Group.SETTINGS),
    RestrictionUi(DeviceRestrictions.KEY_ACCOUNTS, R.string.restriction_accounts_title, R.string.restriction_accounts_desc, DeviceRestrictions.Group.SETTINGS),

    RestrictionUi(DeviceRestrictions.KEY_INSTALLS, R.string.restriction_installs_title, R.string.restriction_installs_desc, DeviceRestrictions.Group.APPS),
    RestrictionUi(DeviceRestrictions.KEY_UNKNOWN_SOURCES, R.string.restriction_unknown_sources_title, R.string.restriction_unknown_sources_desc, DeviceRestrictions.Group.APPS),
    RestrictionUi(DeviceRestrictions.KEY_UNINSTALL, R.string.restriction_uninstall_title, R.string.restriction_uninstall_desc, DeviceRestrictions.Group.APPS),
    RestrictionUi(DeviceRestrictions.KEY_APPS_CONTROL, R.string.restriction_apps_control_title, R.string.restriction_apps_control_desc, DeviceRestrictions.Group.APPS),
    RestrictionUi(DeviceRestrictions.KEY_DEFAULT_APPS, R.string.restriction_default_apps_title, R.string.restriction_default_apps_desc, DeviceRestrictions.Group.APPS),
)

private fun groupTitle(group: DeviceRestrictions.Group): Int = when (group) {
    DeviceRestrictions.Group.TAMPER -> R.string.restriction_group_tamper
    DeviceRestrictions.Group.SETTINGS -> R.string.restriction_group_settings
    DeviceRestrictions.Group.APPS -> R.string.restriction_group_apps
}

/**
 * Toggles that stop the child from changing critical device settings (Device Owner).
 * With a [childId] it edits that child's override instead of the family policy.
 */
@Composable
fun DeviceProtectionScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    childId: String? = null,
    childName: String? = null,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overrides = settings.children.firstOrNull { it.childId == childId }?.overrides
    // What this child ACTUALLY gets: its own set once customized, the family's while it still
    // inherits — read-only in that case, rather than an empty list that reads as "unprotected".
    val editable = childId == null || overrides?.deviceRestrictions != null
    val enabledKeys = if (childId == null) {
        settings.deviceRestrictions
    } else {
        overrides?.deviceRestrictions ?: settings.deviceRestrictions
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_protection_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (childName != null) {
                item { OverrideScopeBanner(childName, editable = editable) }
            }
            item {
                Text(
                    stringResource(R.string.protection_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (group in DeviceRestrictions.Group.entries) {
                val rows = RESTRICTIONS.filter { it.group == group }
                item(key = "group-${group.name}") {
                    SectionHeader(stringResource(groupTitle(group)), accent = SectionAccent.DEVICE)
                }
                item(key = "rows-${group.name}") {
                    CardGroup {
                        rows.forEachIndexed { index, restriction ->
                            RestrictionRow(
                                title = stringResource(restriction.titleRes),
                                description = stringResource(restriction.descRes),
                                checked = restriction.key in enabledKeys,
                                enabled = editable,
                                position = cardPosition(index, rows.size),
                                onToggle = { on -> viewModel.setDeviceRestriction(restriction.key, on, childId) },
                            )
                        }
                    }
                }
            }
            // Family-wide alert (not a per-child override), most useful when installs aren't blocked.
            if (childId == null && DeviceRestrictions.KEY_INSTALLS !in enabledKeys) {
                item {
                    RestrictionRow(
                        title = stringResource(R.string.new_app_alerts_title),
                        description = stringResource(R.string.new_app_alerts_desc),
                        checked = settings.newAppAlerts,
                        onToggle = { on -> viewModel.setNewAppAlerts(on) },
                    )
                }
            }
            item { Spacer(Modifier.height(spacing.xl)) }
        }
    }
}

@Composable
private fun RestrictionRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    position: CardPosition = CardPosition.Single,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
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
            Switch(checked = checked, enabled = enabled, onCheckedChange = onToggle)
        }
    }
}
