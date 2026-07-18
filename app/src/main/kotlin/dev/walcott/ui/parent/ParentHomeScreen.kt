package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import dev.walcott.R
import dev.walcott.enforcement.AppBlockerService
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/**
 * Rules hub. In parent mode it is the family's settings (title = family name); in child
 * mode it sits behind the PIN gate as this device's local settings (fallback path).
 * App-level settings (updates, app lock, logs, device mode) live in [AppSettingsScreen].
 */
@Composable
fun ParentHomeScreen(
    viewModel: WalcottViewModel,
    title: String,
    deviceOwner: Boolean,
    childDevice: Boolean,
    onOpenApps: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenChildren: () -> Unit,
    onOpenEarn: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenWebFilter: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    var showRenameFamily by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(title, onBack) {
            // The family name is user data, not chrome — let the parent change it in place.
            if (!childDevice) {
                IconButton(onClick = { showRenameFamily = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename_family))
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Device-owner status describes THIS device's enforcement — child devices only.
            if (childDevice) {
                ProtectionBanner(deviceOwner)
                Spacer(Modifier.height(spacing.xs))
            }
            NavCard(Icons.Outlined.Apps, stringResource(R.string.nav_apps_title), stringResource(R.string.nav_apps_subtitle), onOpenApps)
            NavCard(Icons.Outlined.Schedule, stringResource(R.string.nav_limits_title), stringResource(R.string.nav_limits_subtitle), onOpenBudgets)
            NavCard(Icons.Outlined.Language, stringResource(R.string.nav_webfilter_title), stringResource(R.string.nav_webfilter_subtitle), onOpenWebFilter)
            NavCard(Icons.Outlined.Security, stringResource(R.string.nav_protection_title), stringResource(R.string.nav_protection_subtitle), onOpenProtection)
            NavCard(Icons.Outlined.LocationOn, stringResource(R.string.nav_location_title), stringResource(R.string.nav_location_subtitle), onOpenLocation)
            NavCard(Icons.Outlined.EmojiEvents, stringResource(R.string.nav_earn_title), stringResource(R.string.nav_earn_subtitle), onOpenEarn)
            NavCard(Icons.Outlined.CalendarMonth, stringResource(R.string.nav_calendar_title), stringResource(R.string.nav_calendar_subtitle), onOpenCalendar)
            if (!childDevice) {
                NavCard(Icons.Outlined.Groups, stringResource(R.string.nav_children_title), stringResource(R.string.nav_children_subtitle), onOpenChildren)
            }
            NavCard(Icons.Outlined.InsertChart, stringResource(R.string.nav_report_title), stringResource(R.string.nav_report_subtitle), onOpenReport)
            // On the parent the gear on the home screen opens App settings; only the
            // child's PIN-gated device-settings hub keeps an inline entry.
            if (childDevice) {
                NavCard(
                    Icons.Outlined.Settings,
                    stringResource(R.string.app_settings_title),
                    stringResource(R.string.app_settings_subtitle),
                    onClick = onOpenAppSettings,
                )
            }
        }
    }

    if (showRenameFamily) {
        RenameFamilyDialog(
            initial = title,
            onDismiss = { showRenameFamily = false },
            onRename = { name ->
                viewModel.renameFamily(name)
                showRenameFamily = false
            },
        )
    }
}

@Composable
private fun RenameFamilyDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_family)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.family_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ProtectionBanner(deviceOwner: Boolean) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    // Cheap re-read on each composition; returning from Accessibility settings recomposes this.
    val accessibilityOn = !deviceOwner && AppBlockerService.isEnabled(context)
    val amber = Color(0xFFB26A00)
    val (color, icon, textRes) = when {
        deviceOwner -> Triple(MaterialTheme.colorScheme.secondary, Icons.Filled.CheckCircle, R.string.protection_active)
        accessibilityOn -> Triple(amber, Icons.Filled.CheckCircle, R.string.protection_accessibility)
        else -> Triple(MaterialTheme.colorScheme.error, Icons.Filled.Warning, R.string.protection_inactive)
    }
    val base = Modifier.fillMaxWidth().padding(top = spacing.md)
    val modifier = if (deviceOwner) {
        base
    } else {
        base.clickable {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = color.copy(alpha = 0.12f), modifier = modifier) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(spacing.sm))
            Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
            if (!deviceOwner) {
                Text(stringResource(R.string.protection_enable_action), style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    }
}
