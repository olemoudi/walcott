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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

@Composable
fun ParentHomeScreen(
    deviceOwner: Boolean,
    onOpenApps: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenChildSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.parent_mode), onBack)
        Column(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ProtectionBanner(deviceOwner)
            Spacer(Modifier.height(spacing.xs))
            NavCard(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.nav_apps_title),
                subtitle = stringResource(R.string.nav_apps_subtitle),
                onClick = onOpenApps,
            )
            NavCard(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.nav_limits_title),
                subtitle = stringResource(R.string.nav_limits_subtitle),
                onClick = onOpenBudgets,
            )
            NavCard(
                icon = Icons.Outlined.PhoneAndroid,
                title = stringResource(R.string.nav_child_title),
                subtitle = stringResource(R.string.nav_child_subtitle),
                onClick = onOpenChildSetup,
            )
        }
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
