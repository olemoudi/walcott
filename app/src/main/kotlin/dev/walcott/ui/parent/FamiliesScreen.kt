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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.provider.Settings
import dev.walcott.R
import dev.walcott.data.ChildEntry
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.DeviceMode
import dev.walcott.sync.Staleness
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.ModeBadge
import dev.walcott.ui.components.PermissionFixRow
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate

/**
 * Parent-mode home: the family (list-shaped for future multi-family support) and its
 * children. Tapping the family opens the family-wide rules; tapping a child opens its
 * detail (enrollment QR, stats, per-child overrides).
 */
@Composable
fun FamiliesScreen(
    viewModel: WalcottViewModel,
    onOpenFamily: () -> Unit,
    onOpenChild: (String) -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val lastSeen by viewModel.lastSeen.collectAsStateWithLifecycle()
    val requests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val asks by viewModel.pendingAsks.collectAsStateWithLifecycle()
    var showAddChild by remember { mutableStateOf(false) }

    // Re-check when the user comes back from the notification settings we deep-link into.
    var notificationsEnabled by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Minute tick so the staleness line ages without new data arriving.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }

    val registryIds = settings.children.map { it.childId }.toSet()
    val legacyDevices = snapshots.filter { it.childId !in registryIds }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = spacing.screen),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = spacing.xxl, bottom = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.families_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                ModeBadge(DeviceMode.PARENT)
            }
        }

        // Every child alert (requests, tamper, stale) arrives as a notification; if the parent
        // turned them off, the whole alerting channel is silently dead — surface that here.
        if (!notificationsEnabled) {
            item {
                PermissionFixRow(
                    text = stringResource(R.string.perm_parent_notifications_missing),
                    action = stringResource(R.string.perm_notifications_fix),
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
        }

        item {
            FamilyCard(
                name = settings.familyName.ifBlank { stringResource(R.string.family_default_name) },
                childrenCount = settings.children.size,
                pendingCount = requests.size + asks.size,
                onClick = onOpenFamily,
            )
        }

        item {
            Text(
                stringResource(R.string.children_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
        if (settings.children.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.children_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(settings.children, key = { it.childId }) { entry ->
            val snapshot = snapshots.firstOrNull { it.childId == entry.childId }
            ChildRow(
                entry = entry,
                snapshot = snapshot,
                lastSeenMs = snapshot?.let { lastSeen[it.deviceId] },
                nowMs = nowMs,
                onClick = { onOpenChild(entry.childId) },
            )
        }
        item {
            OutlinedButton(onClick = { showAddChild = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.add_child))
            }
        }

        if (legacyDevices.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.legacy_devices_header),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            items(legacyDevices, key = { it.deviceId }) { device -> LegacyDeviceRow(device) }
        }

        item { Spacer(Modifier.height(spacing.xl)) }
    }

    if (showAddChild) {
        AddChildDialog(
            onDismiss = { showAddChild = false },
            onAdd = { name ->
                showAddChild = false
                onOpenChild(viewModel.addChild(name))
            },
        )
    }
}

@Composable
private fun FamilyCard(name: String, childrenCount: Int, pendingCount: Int, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    pluralStringResource(R.plurals.family_children_count, childrenCount, childrenCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (pendingCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.family_pending_requests, pendingCount, pendingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ChildRow(
    entry: ChildEntry,
    snapshot: ChildSnapshot?,
    lastSeenMs: Long?,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    val today = LocalDate.now().toEpochDay()
    val usageToday = snapshot?.takeIf { it.epochDay == today }
        ?.usage?.sumOf { it.seconds } ?: 0L
    val stale = snapshot != null && Staleness.isWarn(lastSeenMs, nowMs)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Face,
                contentDescription = null,
                tint = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (snapshot == null) {
                        stringResource(R.string.device_not_linked)
                    } else {
                        stringResource(R.string.usage_today_summary, Duration.ofSeconds(usageToday).humanize())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stale) {
                    Text(
                        stringResource(
                            R.string.child_stale_line,
                            Duration.ofMillis(Staleness.silenceMs(lastSeenMs, nowMs) ?: 0).humanize(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegacyDeviceRow(device: ChildSnapshot) {
    val spacing = Tokens.spacing
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.legacy_device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddChildDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_child)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.child_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name.trim()) }) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
