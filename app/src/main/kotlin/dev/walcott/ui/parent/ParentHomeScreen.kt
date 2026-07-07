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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

/**
 * Rules hub. In parent mode it is the family's settings (title = family name); in child
 * mode it sits behind the PIN gate as this device's local settings (fallback path).
 */
@Composable
fun ParentHomeScreen(
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
    onChangeMode: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    var confirmChangeMode by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(title, onBack)
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
            NavCard(Icons.Outlined.EmojiEvents, stringResource(R.string.nav_earn_title), stringResource(R.string.nav_earn_subtitle), onOpenEarn)
            NavCard(Icons.Outlined.CalendarMonth, stringResource(R.string.nav_calendar_title), stringResource(R.string.nav_calendar_subtitle), onOpenCalendar)
            if (!childDevice) {
                NavCard(Icons.Outlined.Groups, stringResource(R.string.nav_children_title), stringResource(R.string.nav_children_subtitle), onOpenChildren)
            }
            NavCard(Icons.Outlined.InsertChart, stringResource(R.string.nav_report_title), stringResource(R.string.nav_report_subtitle), onOpenReport)
            AppUpdateCard(deviceOwner)
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
        AlertDialog(
            onDismissRequest = { confirmChangeMode = false },
            title = { Text(stringResource(R.string.change_device_mode)) },
            text = { Text(stringResource(R.string.change_mode_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmChangeMode = false
                    onChangeMode()
                }) { Text(stringResource(R.string.change_mode_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmChangeMode = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ProtectionBanner(deviceOwner: Boolean) {
    val spacing = Tokens.spacing
    val (color, icon, text) = if (deviceOwner) {
        Triple(MaterialTheme.colorScheme.secondary, Icons.Filled.CheckCircle, stringResource(R.string.protection_active))
    } else {
        Triple(MaterialTheme.colorScheme.error, Icons.Filled.Warning, stringResource(R.string.protection_inactive))
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
    ) {
        Row(Modifier.padding(spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(spacing.sm))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun NavCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
