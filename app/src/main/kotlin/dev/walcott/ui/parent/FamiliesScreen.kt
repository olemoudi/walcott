package dev.walcott.ui.parent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
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
import dev.walcott.sync.EnforcementStatus
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.Staleness
import dev.walcott.sync.SyncEngine
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
    onOpenAppSettings: () -> Unit,
    // Setup-checklist shortcuts: a first-time parent taps a pending step and lands on the
    // screen that completes it, instead of hunting through the rules hub.
    onOpenApps: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenGuidedSetup: () -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val lastSeen by viewModel.lastSeen.collectAsStateWithLifecycle()
    val requests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val asks by viewModel.pendingAsks.collectAsStateWithLifecycle()
    val pendingOps by viewModel.pendingOps.collectAsStateWithLifecycle()
    val parentVersion by viewModel.parentVersion.collectAsStateWithLifecycle()
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    var showAddChild by remember { mutableStateOf(false) }
    var removingDevice by remember { mutableStateOf<ChildSnapshot?>(null) }

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
                // App-level settings (updates, app lock, logs) live at top level, not
                // inside the family's rules.
                IconButton(onClick = onOpenAppSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.app_settings_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

        // Pending child requests, actionable right from the home (they also arrive as a
        // parent notification). Approving/denying here resolves them immediately.
        if (requests.isNotEmpty() || asks.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.pending_requests),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            items(requests, key = { "req-" + it.request.requestId }) { pending ->
                // animateItem: resolved requests slide out instead of popping (and new ones in).
                Box(Modifier.animateItem()) {
                    ExtraTimeRequestCard(
                        pending = pending,
                        onApprove = { viewModel.resolveRequest(pending.request.requestId, true, pending.request.minutes) },
                        onDeny = { viewModel.resolveRequest(pending.request.requestId, false, 0) },
                    )
                }
            }
            items(asks, key = { "ask-" + it.ask.requestId }) { pending ->
                Box(Modifier.animateItem()) {
                    AskRequestCard(
                        pending = pending,
                        onApprove = { viewModel.resolveRequest(pending.ask.requestId, true, 0) },
                        onDeny = { viewModel.resolveRequest(pending.ask.requestId, false, 0) },
                    )
                }
            }
        }

        // Everything sent to a child device that hasn't finished: queued remote fixes, pushed
        // installs waiting for their tap in Play, location requests. Visible so the parent
        // doesn't re-send blindly, and cancellable while still queued.
        if (pendingOps.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = spacing.sm)) {
                    Text(stringResource(R.string.pending_ops_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.pending_ops_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(pendingOps, key = { "op-" + it.deviceId + it.action + it.arg + it.sentAtMs }) { op ->
                Box(Modifier.animateItem()) {
                    PendingOpRow(
                        op = op,
                        childName = childNameFor(op.deviceId, settings.children, snapshots),
                        nowMs = nowMs,
                        onCancel = when {
                            op.delivered -> null
                            op.action == SyncEngine.ACTION_LOCATE -> {
                                { viewModel.cancelLocationRequest(op.deviceId) }
                            }
                            else -> {
                                { viewModel.cancelRemoteCommand(op.id) }
                            }
                        },
                    )
                }
            }
        }

        // The wall: recent relevant events, newest first. A notification can be swiped away
        // and lost; its message survives here, and tapping lands on the affected child.
        val wall = events.filter(::eventRenderable).take(HOME_FEED_COUNT)
        if (wall.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.timeline_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            items(wall, key = { "ev-" + it.id.ifBlank { "${it.atMs}-${it.type}-${it.childId}" } }) { event ->
                val name = settings.children.firstOrNull { it.childId == event.childId }?.name
                    ?: event.childName.ifBlank { stringResource(R.string.family_default_name) }
                val target = event.childId.takeIf { id -> settings.children.any { it.childId == id } }
                Box(Modifier.animateItem()) {
                    EventRow(event, name, nowMs, onClick = target?.let { id -> { onOpenChild(id) } })
                }
            }
        }

        // Onboarding coach: a brand-new family enforces nothing until apps are classified and
        // limits set. Show the remaining steps until they're done, then it disappears.
        val childDone = settings.children.isNotEmpty()
        val appsDone = settings.assignments.isNotEmpty()
        val limitsDone = settings.budgets.isNotEmpty() || settings.bedtime.isNotEmpty()
        val bedtimeDone = settings.bedtime.isNotEmpty()
        if (!(childDone && appsDone && limitsDone && bedtimeDone)) {
            // The guided wizards, front and center until the family is fully set up (they
            // stay reachable afterwards from the family rules hub).
            item { GuidedSetupCard(onOpenGuidedSetup) }
            item {
                SetupChecklistCard(
                    steps = listOf(
                        SetupStep(stringResource(R.string.setup_step_child), childDone) { showAddChild = true },
                        SetupStep(stringResource(R.string.setup_step_apps), appsDone, onOpenApps),
                        SetupStep(stringResource(R.string.setup_step_limits), limitsDone, onOpenBudgets),
                        SetupStep(stringResource(R.string.setup_step_bedtime), bedtimeDone, onOpenBudgets),
                    ),
                )
            }
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
                parentVersion = parentVersion,
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
            items(legacyDevices, key = { it.deviceId }) { device ->
                LegacyDeviceRow(device, onRemove = { removingDevice = device })
            }
        }

        item { Spacer(Modifier.height(spacing.xl)) }
    }

    removingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { removingDevice = null },
            title = { Text(stringResource(R.string.legacy_remove_title)) },
            text = { Text(stringResource(R.string.legacy_remove_confirm, device.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeLegacyDevice(device.deviceId)
                    removingDevice = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { removingDevice = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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
    // Shares the child hero's signature gradient, so both homes open on the same identity.
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().background(Tokens.heroBrush, RoundedCornerShape(24.dp)),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    pluralStringResource(R.plurals.family_children_count, childrenCount, childrenCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
                if (pendingCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.family_pending_requests, pendingCount, pendingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
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
    parentVersion: Long,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    val today = LocalDate.now().toEpochDay()
    val usageToday = snapshot?.takeIf { it.epochDay == today }
        ?.usage?.sumOf { it.seconds } ?: 0L
    // A quiet child is almost always just a phone at rest (Doze) — say so neutrally, and
    // save the red for silences longer than any benign gap (see Staleness).
    val tier = if (snapshot == null) Staleness.Tier.FRESH else Staleness.tierOf(lastSeenMs, nowMs)

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
                tint = if (tier == Staleness.Tier.SILENT) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
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
                if (tier != Staleness.Tier.FRESH) {
                    val silence = Duration.ofMillis(Staleness.silenceMs(lastSeenMs, nowMs) ?: 0).humanize()
                    Text(
                        stringResource(
                            if (tier == Staleness.Tier.SILENT) R.string.child_stale_line else R.string.child_resting_line,
                            silence,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tier == Staleness.Tier.SILENT) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (snapshot != null) StatusChips(snapshot, parentVersion)
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
private fun GuidedSetupCard(onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoFixHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.guided_setup_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.guided_setup_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private data class SetupStep(val label: String, val done: Boolean, val onClick: () -> Unit)

@Composable
private fun SetupChecklistCard(steps: List<SetupStep>) {
    val spacing = Tokens.spacing
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.setup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(spacing.sm))
            steps.forEach { step ->
                Row(
                    Modifier.fillMaxWidth()
                        // Done steps stay as a record, not a button; pending ones navigate.
                        .then(if (step.done) Modifier else Modifier.clickable(onClick = step.onClick))
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The check "lights up" when a step completes rather than swapping abruptly.
                    val stepTint by animateColorAsState(
                        targetValue = if (step.done) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                        },
                        animationSpec = tween(Tokens.motion.medium),
                        label = "stepTint",
                    )
                    Icon(
                        if (step.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = stepTint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        step.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    if (!step.done) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** At-a-glance health of a linked child: a green shield when all good, warning chips otherwise. */
@Composable
private fun StatusChips(snapshot: ChildSnapshot, parentVersion: Long) {
    val spacing = Tokens.spacing
    val warn = Color(0xFFB26A00)
    val error = MaterialTheme.colorScheme.error
    val chips = buildList {
        // Rule edits in flight: the child hasn't confirmed the latest policy version yet
        // (legacy children that don't report it never show this, rather than always).
        if (snapshot.appliedPolicyVersion in 1 until parentVersion) {
            add(Triple(Icons.Outlined.Sync, stringResource(R.string.chip_rules_syncing), warn))
        }
        when (snapshot.enforcement) {
            EnforcementStatus.DEVICE_OWNER ->
                add(Triple(Icons.Filled.Shield, stringResource(R.string.chip_protected), MaterialTheme.colorScheme.secondary))
            EnforcementStatus.ACCESSIBILITY ->
                add(Triple(Icons.Filled.Shield, stringResource(R.string.chip_partial), warn))
            EnforcementStatus.NONE ->
                add(Triple(Icons.Filled.Warning, stringResource(R.string.chip_unprotected), error))
        }
        if (!snapshot.usageAccessOn) add(Triple(Icons.Filled.Warning, stringResource(R.string.chip_usage_off), error))
        if (!snapshot.networkLocationOn) add(Triple(Icons.Filled.Warning, stringResource(R.string.chip_indoor_off), warn))
        if (snapshot.appVersionCode in 1 until dev.walcott.BuildConfig.VERSION_CODE) {
            add(Triple(Icons.Filled.Warning, stringResource(R.string.chip_outdated), warn))
        }
    }
    if (chips.isEmpty()) return
    Row(
        Modifier.padding(top = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { (icon, label, color) ->
            Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
                Row(
                    Modifier.padding(horizontal = spacing.sm, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

@Composable
private fun LegacyDeviceRow(device: ChildSnapshot, onRemove: () -> Unit) {
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
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.legacy_device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.legacy_remove_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How many wall entries the home shows (the full capped feed stays in the store). */
private const val HOME_FEED_COUNT = 8

/** The registry name for a device, falling back to what the device calls itself. */
private fun childNameFor(deviceId: String, children: List<ChildEntry>, snapshots: List<ChildSnapshot>): String {
    val snapshot = snapshots.firstOrNull { it.deviceId == deviceId } ?: return deviceId
    return children.firstOrNull { it.childId == snapshot.childId }?.name ?: snapshot.displayName
}

/**
 * One in-flight remote operation. Queued ones carry a cancel affordance; delivered ones
 * (an install prompt already opened on the child) only wait, so they just say so.
 */
@Composable
private fun PendingOpRow(
    op: SyncEngine.PendingOp,
    childName: String,
    nowMs: Long,
    onCancel: (() -> Unit)?,
) {
    val spacing = Tokens.spacing
    val (icon, title) = when (op.action) {
        RemoteAction.INSTALL_APP -> Icons.Outlined.InstallMobile to stringResource(R.string.pending_op_install, op.arg)
        RemoteAction.UPDATE_NOW -> Icons.Outlined.SystemUpdate to stringResource(R.string.remote_update_now)
        RemoteAction.REAPPLY_POLICY -> Icons.Outlined.Security to stringResource(R.string.remote_reapply)
        RemoteAction.REQUEST_PERMISSIONS -> Icons.Outlined.Key to stringResource(R.string.remote_ask_permissions)
        SyncEngine.ACTION_LOCATE -> Icons.Outlined.MyLocation to stringResource(R.string.pending_op_locate)
        // A newer build's action this one doesn't know: show it raw rather than hide it.
        else -> Icons.Outlined.PhoneAndroid to op.action
    }
    val age = Duration.ofMillis((nowMs - op.sentAtMs).coerceAtLeast(0)).humanize()

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.pending_op_meta, childName, age),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (op.delivered) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            stringResource(R.string.pending_op_waiting_install),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
