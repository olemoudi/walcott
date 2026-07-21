package dev.walcott.ui.parent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.BuildConfig
import dev.walcott.Distribution
import dev.walcott.R
import dev.walcott.data.ChildEntry
import dev.walcott.data.withBudget
import dev.walcott.provisioning.DeviceOwnerProvisioning
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.ClockGuard
import dev.walcott.sync.DiagPayload
import dev.walcott.sync.EnforcementStatus
import dev.walcott.sync.PairingPayload
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.Role
import dev.walcott.sync.SyncNotifications
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.format.humanize
import dev.walcott.ui.qr.rememberQrBitmap
import dev.walcott.ui.theme.Tokens
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One registered child: enrollment QRs, live stats from its linked device, and the
 * per-child overrides that customize the inherited family policy.
 */
@Composable
fun ChildDetailScreen(
    viewModel: WalcottViewModel,
    childId: String,
    onBack: () -> Unit,
    onOpenMap: (String) -> Unit,
    onEditWebFilter: () -> Unit,
    onEditProtection: () -> Unit,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val pendingOps by viewModel.pendingOps.collectAsStateWithLifecycle()
    val parentVersion by viewModel.parentVersion.collectAsStateWithLifecycle()
    val diagReports by viewModel.diagReports.collectAsStateWithLifecycle()
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    val ledgers by viewModel.usageLedgers.collectAsStateWithLifecycle()

    // Minute tick so the dashboard and feed ages stay fresh without new data arriving.
    val nowMs by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Brief nulls are expected: right after "Add child" (store write in flight) or removal.
    val entry = settings.children.firstOrNull { it.childId == childId } ?: return
    val snapshot = snapshots.firstOrNull { it.childId == childId }

    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var showBonus by remember { mutableStateOf(false) }
    var showCode by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(
            title = entry.name,
            onBack = onBack,
            onRename = { showRename = true },
            onRemove = { showRemove = true },
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // --- Enrollment ---
            if (snapshot == null || showCode) {
                item {
                    EnrollmentSection(
                        entry = entry,
                        pairingText = if (identity.role == Role.PARENT) {
                            PairingPayload(
                                topic = identity.topic,
                                familyKeyB64 = identity.familyKeyB64,
                                parentPublicKeyB64 = identity.parentPublicKeyB64,
                                ntfyServer = identity.ntfyServer,
                                childId = entry.childId,
                                childName = entry.name,
                                familyName = settings.familyName,
                            ).encode()
                        } else {
                            null
                        },
                    )
                }
            } else {
                item {
                    LinkedCard(
                        snapshot,
                        rulesSyncing = snapshot.appliedPolicyVersion in 1 until parentVersion,
                        onShowCode = { showCode = true },
                    )
                }
            }

            // --- Dashboard: the child's day at a glance, plus their recent events ---
            if (snapshot != null) {
                item {
                    val today = LocalDate.now().toEpochDay()
                    val reportedToday = snapshot.epochDay == today
                    val config = remember(settings, childId) {
                        settings.resolveForChild(childId).toFamilyConfig(emptySet())
                    }
                    val usage = if (reportedToday) {
                        snapshot.usage.associate { it.categoryId to Duration.ofSeconds(it.seconds) }
                    } else {
                        emptyMap()
                    }
                    val extra = if (reportedToday) {
                        snapshot.extra.associate { it.categoryId to Duration.ofSeconds(it.seconds) }
                    } else {
                        emptyMap()
                    }
                    ChildDashboardCard(
                        childName = entry.name,
                        usedToday = Duration.ofSeconds(usage.values.sumOf { it.seconds }),
                        average = dev.walcott.sync.UsageLedger.averageDaily(
                            ledgers[dev.walcott.sync.UsageLedger.keyOf(snapshot.childId, snapshot.deviceId)].orEmpty(),
                            today,
                        ),
                        remaining = dev.walcott.data.ChildStats.remainingToday(
                            config, LocalDate.ofEpochDay(today), usage, extra,
                        ),
                        events = events.filter { it.childId == childId && eventRenderable(it) }.take(3),
                        nowMs = nowMs,
                    )
                }
            }

            // --- Enforcement status (warn if blocking isn't fully active on the child) ---
            if (snapshot != null && snapshot.enforcement != EnforcementStatus.DEVICE_OWNER &&
                snapshot.enforcement != EnforcementStatus.UNKNOWN
            ) {
                item { EnforcementWarningCard(snapshot.enforcement) }
            }

            // --- Usage access (screen-time counting silently stops without it) ---
            if (snapshot != null && !snapshot.usageAccessOn) {
                item { UsageAccessWarningCard() }
            }

            // --- Self-test gap ("looks healthy, isn't blocking") ---
            if (snapshot != null && snapshot.enforcementGaps.isNotEmpty()) {
                item { EnforcementGapCard(snapshot.enforcementGaps.size) }
            }

            // --- Clock tamper (device clock far off the sync server's) ---
            if (snapshot != null && ClockGuard.isTampered(snapshot.clockSkewMs)) {
                item { ClockTamperCard(snapshot.clockSkewMs) }
            }

            // --- Wrong-PIN attempts (someone is trying to guess the parent PIN on the child) ---
            if (snapshot != null && snapshot.pinWrongTotal > 0) {
                item { WrongPinCard(snapshot.pinWrongTotal, snapshot.lastWrongPinMs) }
            }

            // --- Stats ---
            if (snapshot != null) {
                item { UsageTodayCard(snapshot, onGiveBonus = { showBonus = true }) }
                if (snapshot.history.isNotEmpty()) {
                    item { HistoryCard(snapshot) }
                }
            }

            // --- Remote fixes (only meaningful once a device is actually linked) ---
            if (snapshot != null) {
                item {
                    RemoteFixCard(
                        snapshot = snapshot,
                        onCommand = { action -> viewModel.sendRemoteCommand(snapshot.deviceId, action) },
                    )
                }
                item {
                    DiagnosticsCard(
                        deviceId = snapshot.deviceId,
                        report = diagReports[snapshot.deviceId],
                        onRequest = { viewModel.sendRemoteCommand(snapshot.deviceId, RemoteAction.DIAGNOSE) },
                    )
                }
            }

            // --- Location (inherits the family defaults unless customized) ---
            item {
                val resolved = settings.resolveForChild(childId)
                LocationCard(
                    customized = entry.overrides.trackingIntervalMinutes != null ||
                        entry.overrides.locationHistoryEnabled != null,
                    onSetCustomized = { on ->
                        viewModel.setChildOverrides(
                            childId,
                            entry.overrides.copy(
                                trackingIntervalMinutes = if (on) resolved.trackingIntervalMinutes else null,
                                locationHistoryEnabled = if (on) resolved.locationHistoryEnabled else null,
                            ),
                        )
                    },
                    intervalMinutes = resolved.trackingIntervalMinutes,
                    onSetInterval = { viewModel.setTrackingInterval(childId, it) },
                    historyEnabled = resolved.locationHistoryEnabled,
                    onSetHistory = { viewModel.setLocationHistory(childId, it) },
                    hasDevice = snapshot != null,
                    // Live feedback: the button spins from tap until the device answers.
                    locating = snapshot != null &&
                        dev.walcott.sync.SyncEngine.locatePending(pendingOps, snapshot.deviceId),
                    onLocateNow = { snapshot?.let { viewModel.requestLocation(it.deviceId) } },
                    onOpenMap = { onOpenMap(childId) },
                )
            }

            // --- Per-child overrides ---
            item {
                Column {
                    Text(
                        stringResource(R.string.override_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                    Text(
                        stringResource(R.string.override_inherited_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OverrideSwitchRow(
                    title = stringResource(R.string.override_bedtime_title),
                    checked = entry.overrides.bedtime != null,
                    onToggle = { on ->
                        viewModel.setChildOverrides(
                            childId,
                            entry.overrides.copy(bedtime = if (on) settings.bedtime else null),
                        )
                    },
                )
            }
            entry.overrides.bedtime?.let { bedtime ->
                item {
                    BedtimeCard(bedtime) { updated ->
                        viewModel.setChildOverrides(childId, entry.overrides.copy(bedtime = updated))
                    }
                }
            }
            item {
                OverrideSwitchRow(
                    title = stringResource(R.string.override_budgets_title),
                    checked = entry.overrides.budgets != null,
                    onToggle = { on ->
                        viewModel.setChildOverrides(
                            childId,
                            entry.overrides.copy(budgets = if (on) settings.budgets else null),
                        )
                    },
                )
            }
            entry.overrides.budgets?.let { budgets ->
                items(AppCategory.entries.toList(), key = { it.id }) { category ->
                    CategoryBudgetCard(
                        category = category,
                        perDay = budgets[category.id].orEmpty(),
                        onSetBudget = { dayType, minutes ->
                            viewModel.setChildOverrides(
                                childId,
                                entry.overrides.copy(budgets = budgets.withBudget(category.id, dayType.name, minutes)),
                            )
                        },
                    )
                }
            }
            item {
                OverrideSwitchRow(
                    title = stringResource(R.string.override_webfilter_title),
                    checked = entry.overrides.blockedDomains != null,
                    onToggle = { on ->
                        viewModel.setChildOverrides(
                            childId,
                            entry.overrides.copy(blockedDomains = if (on) settings.blockedDomains else null),
                        )
                    },
                    onEdit = onEditWebFilter.takeIf { entry.overrides.blockedDomains != null },
                )
            }
            item {
                OverrideSwitchRow(
                    title = stringResource(R.string.override_protection_title),
                    checked = entry.overrides.deviceRestrictions != null,
                    onToggle = { on ->
                        viewModel.setChildOverrides(
                            childId,
                            entry.overrides.copy(deviceRestrictions = if (on) settings.deviceRestrictions else null),
                        )
                    },
                    onEdit = onEditProtection.takeIf { entry.overrides.deviceRestrictions != null },
                )
            }
            item {
                UpdateWifiOverrideCard(
                    override = entry.overrides.updateWifiOnly,
                    familyValue = settings.updateWifiOnly,
                    onSetOverride = { value ->
                        viewModel.setChildOverrides(childId, entry.overrides.copy(updateWifiOnly = value))
                    },
                )
            }

            item { Spacer(Modifier.height(spacing.xl)) }
        }
    }

    if (showRename) {
        RenameDialog(
            initial = entry.name,
            onDismiss = { showRename = false },
            onRename = { name ->
                viewModel.renameChild(childId, name)
                showRename = false
            },
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text(stringResource(R.string.remove_child)) },
            text = { Text(stringResource(R.string.remove_child_confirm, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    onBack()
                    viewModel.removeChild(childId)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showBonus && snapshot != null) {
        BonusDialog(
            onDismiss = { showBonus = false },
            onGrant = { categoryId, minutes ->
                viewModel.giveBonus(snapshot.deviceId, categoryId, minutes)
                showBonus = false
            },
        )
    }
}

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val spacing = Tokens.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Spacer(Modifier.width(spacing.xs))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename_child))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove_child))
        }
    }
}

private enum class EnrollMode { DEVICE_OWNER, FALLBACK }

@Composable
private fun EnrollmentSection(entry: ChildEntry, pairingText: String?) {
    val spacing = Tokens.spacing
    // Device Owner is the strong path (full blocking); the fallback works without a factory reset.
    var mode by remember { mutableStateOf(EnrollMode.DEVICE_OWNER) }
    // Two-step wizard: only one QR is ever on screen at a time, so the child's camera can't
    // lock onto the wrong code when two are shown together.
    var step by rememberSaveable { mutableStateOf(0) }

    // Without a pairing code (non-parent device) there's only the install QR — no second step.
    if (pairingText == null) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            EnrollModeChips(mode, onSelect = { mode = it })
            EnrollInstallStep(mode)
        }
        return
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(220)) { w -> dir * w } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(220)) { w -> -dir * w } + fadeOut(tween(220)))
        },
        label = "enrollStep",
    ) { currentStep ->
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            if (currentStep == 0) {
                EnrollModeChips(mode, onSelect = { mode = it })
                EnrollInstallStep(mode)
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.enroll_next))
                    Spacer(Modifier.width(spacing.xs))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            } else {
                Text(stringResource(R.string.pairing_step_link), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.child_enroll_qr_instructions, entry.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                QrCard(rememberQrBitmap(pairingText, size = 200.dp))
                TextButton(onClick = { step = 0 }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}

@Composable
private fun EnrollModeChips(mode: EnrollMode, onSelect: (EnrollMode) -> Unit) {
    val spacing = Tokens.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        dev.walcott.ui.components.ChoiceChip(
            selected = mode == EnrollMode.DEVICE_OWNER,
            onClick = { onSelect(EnrollMode.DEVICE_OWNER) },
            label = stringResource(R.string.enroll_mode_do),
        )
        dev.walcott.ui.components.ChoiceChip(
            selected = mode == EnrollMode.FALLBACK,
            onClick = { onSelect(EnrollMode.FALLBACK) },
            label = stringResource(R.string.enroll_mode_fallback),
        )
    }
}

@Composable
private fun EnrollInstallStep(mode: EnrollMode) {
    val context = LocalContext.current
    if (mode == EnrollMode.DEVICE_OWNER) {
        Text(stringResource(R.string.enroll_do_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.enroll_do_instructions), style = MaterialTheme.typography.bodyMedium)
        val payload = remember(context) { DeviceOwnerProvisioning.qrPayload(context) }
        QrCard(rememberQrBitmap(payload, size = 200.dp))
    } else {
        Text(stringResource(R.string.pairing_step_download), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.qr_instructions), style = MaterialTheme.typography.bodyMedium)
        QrCard(rememberQrBitmap(Distribution.CHILD_APK_URL, size = 200.dp))
        Text(
            stringResource(R.string.qr_provision_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkedCard(snapshot: ChildSnapshot, rulesSyncing: Boolean, onShowCode: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.child_detail_linked, snapshot.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // A rule edit is on its way: honest about eventual consistency, and clears
                // the moment the child confirms the new version.
                if (rulesSyncing) {
                    Text(
                        stringResource(R.string.detail_rules_syncing),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB26A00),
                    )
                }
                // Battery at a glance (legacy children report -1 and show nothing).
                if (snapshot.batteryPercent in 0..100) {
                    val low = snapshot.batteryPercent < 20 && !snapshot.charging
                    Text(
                        stringResource(
                            if (snapshot.charging) R.string.child_battery_charging else R.string.child_battery,
                            snapshot.batteryPercent,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The fleet is sideloaded + self-updating; a child stuck behind our own build
                // (0 = legacy child that doesn't report it yet) is worth a red flag.
                if (snapshot.appVersionCode > 0) {
                    val outdated = snapshot.appVersionCode < BuildConfig.VERSION_CODE
                    Text(
                        if (outdated) {
                            stringResource(
                                R.string.child_version_outdated, snapshot.appVersionName, snapshot.appVersionCode,
                            )
                        } else {
                            stringResource(R.string.child_version, snapshot.appVersionName, snapshot.appVersionCode)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outdated) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            TextButton(onClick = onShowCode) { Text(stringResource(R.string.child_detail_show_code)) }
        }
    }
}

/**
 * The child's day at a glance: screen time so far, budget left, and the daily average from
 * the parent-side ledger — plus this child's slice of the activity feed. Sits at the top of
 * the detail so the answer to "how are they doing?" needs no scrolling.
 */
@Composable
private fun ChildDashboardCard(
    childName: String,
    usedToday: Duration,
    average: dev.walcott.sync.UsageLedger.Average?,
    /** Budget left today across categories; null = nothing has a budget today ("no limit"). */
    remaining: Duration?,
    events: List<dev.walcott.sync.ParentEvent>,
    nowMs: Long,
) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(Modifier.fillMaxWidth()) {
                StatTile(
                    value = usedToday.humanize(),
                    label = stringResource(R.string.dashboard_used_today),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = remaining?.humanize() ?: stringResource(R.string.no_limit),
                    label = stringResource(R.string.dashboard_remaining),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = average?.let { Duration.ofSeconds(it.seconds).humanize() } ?: "—",
                    label = stringResource(R.string.dashboard_avg),
                    caption = average?.let {
                        pluralStringResource(R.plurals.dashboard_avg_days, it.daysCounted, it.daysCounted)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (events.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = spacing.md))
                events.forEach { event -> EventLine(event, childName, nowMs) }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, caption: String? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageTodayCard(snapshot: ChildSnapshot, onGiveBonus: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.usage_today), style = MaterialTheme.typography.titleMedium)
            if (snapshot.usage.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                snapshot.usage.forEach { entry ->
                    val category = AppCategory.byId(entry.categoryId)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            category?.let { stringResource(it.nameRes) } ?: entry.categoryId,
                            Modifier.weight(1f),
                            color = category?.color ?: MaterialTheme.colorScheme.onSurface,
                        )
                        Text(Duration.ofSeconds(entry.seconds).humanize(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.size(spacing.sm))
            OutlinedButton(onClick = onGiveBonus, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.give_bonus))
            }
        }
    }
}

@Composable
private fun HistoryCard(snapshot: ChildSnapshot) {
    val spacing = Tokens.spacing
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val days = snapshot.history.sortedByDescending { it.epochDay }.take(7)
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.last_7_days), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = spacing.sm))
            days.forEach { day ->
                val total = day.usage.sumOf { it.seconds }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        LocalDate.ofEpochDay(day.epochDay).format(formatter).replaceFirstChar { it.uppercase() },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (total > 0) Duration.ofSeconds(total).humanize() else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnforcementWarningCard(status: String) {
    val spacing = Tokens.spacing
    val accessibility = status == EnforcementStatus.ACCESSIBILITY
    val color = if (accessibility) Color(0xFFB26A00) else MaterialTheme.colorScheme.error
    val text = stringResource(
        if (accessibility) R.string.enforcement_accessibility_child else R.string.enforcement_none_child,
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun UsageAccessWarningCard() {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Text(stringResource(R.string.usage_access_off_child), style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

/** The self-test caught apps that should be suspended but aren't — the silent failure class. */
@Composable
private fun EnforcementGapCard(count: Int) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Text(
                pluralStringResource(R.plurals.enforcement_gap_child, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

/** The child's clock disagrees with the sync server far beyond drift — a limits bypass. */
@Composable
private fun ClockTamperCard(skewMs: Long) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Text(
                stringResource(R.string.clock_tamper_child, SyncNotifications.formatSkew(context, skewMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun WrongPinCard(total: Int, lastAttemptMs: Long) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.wrong_pin_child, total, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
                if (lastAttemptMs > 0) {
                    val stamp = remember(lastAttemptMs) {
                        java.text.DateFormat
                            .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault())
                            .format(java.util.Date(lastAttemptMs))
                    }
                    Text(
                        stringResource(R.string.wrong_pin_last_attempt, stamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
        }
    }
}

/**
 * The remote-fix panel: everything the parent can repair on a linked child device without
 * holding it. Each row states what it does, because "Re-apply protection" is meaningless
 * on its own, and the last command's outcome is echoed back so an action isn't a shot in
 * the dark. Permissions that genuinely need someone at the device get "Ask to fix", which
 * raises a guided notification there rather than pretending to fix them from here.
 */
@Composable
private fun RemoteFixCard(snapshot: ChildSnapshot, onCommand: (String) -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    // Local echo: the child only acknowledges on its next check-in, so without this the
    // button would look inert for up to a re-emit interval. The send time is tracked too,
    // so re-running an action shows "sent" rather than the previous run's stale result.
    var sentAtMs by remember(snapshot.deviceId) { mutableStateOf(0L) }
    var awaitingAck by remember(snapshot.deviceId) { mutableStateOf(false) }
    val outdated = snapshot.appVersionCode in 1 until BuildConfig.VERSION_CODE
    val needsPermissionNudge = !snapshot.usageAccessOn || !snapshot.networkLocationOn
    // Deliberately waiting for the canary (this phone) is not a failure — don't paint it red.
    val waitingForParent = snapshot.updateError == "waiting_parent"

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.remote_fix_section), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.remote_fix_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // A child that couldn't self-update is the case "Update now" exists for; say why.
            if (snapshot.updateError.isNotBlank()) {
                Text(
                    if (waitingForParent) {
                        stringResource(R.string.child_update_waiting_parent)
                    } else {
                        stringResource(R.string.child_update_error, remoteResultLabel(context, snapshot.updateError))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (waitingForParent) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }

            RemoteFixRow(
                title = stringResource(R.string.remote_update_now),
                description = stringResource(R.string.remote_update_desc),
                emphasized = outdated || (snapshot.updateError.isNotBlank() && !waitingForParent),
                onClick = {
                    onCommand(RemoteAction.UPDATE_NOW)
                    sentAtMs = System.currentTimeMillis()
                    awaitingAck = true
                },
            )
            RemoteFixRow(
                title = stringResource(R.string.remote_reapply),
                description = stringResource(R.string.remote_reapply_desc),
                emphasized = snapshot.enforcement == EnforcementStatus.NONE,
                onClick = {
                    onCommand(RemoteAction.REAPPLY_POLICY)
                    sentAtMs = System.currentTimeMillis()
                    awaitingAck = true
                },
            )
            RemoteFixRow(
                title = stringResource(R.string.remote_ask_permissions),
                description = stringResource(R.string.remote_ask_permissions_desc),
                emphasized = needsPermissionNudge,
                onClick = {
                    onCommand(RemoteAction.REQUEST_PERMISSIONS)
                    sentAtMs = System.currentTimeMillis()
                    awaitingAck = true
                },
            )

            // An acknowledgement only counts for the command we just sent if the child
            // completed it after we sent it; otherwise we are still waiting.
            val ack = snapshot.lastCommand
            val stillWaiting = awaitingAck && (ack == null || ack.completedAtMs < sentAtMs)
            if (stillWaiting) {
                Text(
                    stringResource(R.string.remote_command_sent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            } else if (ack != null) {
                Text(
                    stringResource(
                        if (ack.ok) R.string.remote_command_ok else R.string.remote_command_failed,
                        remoteResultLabel(context, ack.detail),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ack.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun RemoteFixRow(title: String, description: String, emphasized: Boolean, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Row(
        Modifier.fillMaxWidth().padding(top = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                // Highlight the action that matches a problem this child actually has.
                color = if (emphasized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(spacing.sm))
        if (emphasized) {
            Button(onClick = onClick) { Text(stringResource(R.string.action_fix)) }
        } else {
            OutlinedButton(onClick = onClick) { Text(stringResource(R.string.action_run)) }
        }
    }
}

/**
 * Maps a command's machine-readable detail onto localized text. Unknown details (a newer
 * child reporting something this build doesn't know) fall through verbatim.
 */
private fun remoteResultLabel(context: android.content.Context, detail: String): String = when (detail) {
    "up_to_date" -> context.getString(R.string.remote_result_up_to_date)
    "installing" -> context.getString(R.string.remote_result_installing)
    "download_failed" -> context.getString(R.string.remote_result_download_failed)
    "install_failed" -> context.getString(R.string.remote_result_install_failed)
    "reapplied" -> context.getString(R.string.remote_result_reapplied)
    "nothing_missing" -> context.getString(R.string.remote_result_nothing_missing)
    "opened" -> context.getString(R.string.remote_result_install_opened)
    "installed" -> context.getString(R.string.remote_result_installed)
    "already_installed" -> context.getString(R.string.remote_result_already_installed)
    "no_package" -> context.getString(R.string.remote_result_no_package)
    "waiting_parent" -> context.getString(R.string.remote_result_waiting_parent)
    "diag_sent" -> context.getString(R.string.remote_result_diag_sent)
    else -> if (detail.contains('_')) context.getString(R.string.remote_result_notified) else detail
}

/**
 * Remote health report: request a diagnostics snapshot from the child (it arrives on the
 * device's next check-in, as its own message) and render the latest one received. The
 * "requested…" line clears itself when a report newer than the request lands.
 */
@Composable
private fun DiagnosticsCard(deviceId: String, report: DiagPayload?, onRequest: () -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    var sentAtMs by remember(deviceId) { mutableStateOf(0L) }
    var showLog by remember(deviceId) { mutableStateOf(false) }
    val waiting = sentAtMs > 0 && (report == null || report.atMs < sentAtMs)

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_section), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.diag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                OutlinedButton(onClick = { onRequest(); sentAtMs = System.currentTimeMillis() }, enabled = !waiting) {
                    Text(stringResource(if (report == null) R.string.diag_request else R.string.diag_refresh))
                }
            }
            if (waiting) {
                Text(
                    stringResource(R.string.remote_command_sent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            if (report != null) {
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                val stamp = remember(report.atMs) {
                    java.text.DateFormat
                        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault())
                        .format(java.util.Date(report.atMs))
                }
                Text(
                    stringResource(R.string.diag_taken_at, stamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DiagRow(
                    label = stringResource(R.string.diag_enforcement),
                    value = stringResource(
                        when (report.enforcement) {
                            EnforcementStatus.DEVICE_OWNER -> R.string.diag_enforcement_do
                            EnforcementStatus.ACCESSIBILITY -> R.string.diag_enforcement_accessibility
                            else -> R.string.diag_enforcement_none
                        },
                    ),
                    ok = report.enforcement == EnforcementStatus.DEVICE_OWNER,
                )
                DiagRow(
                    label = stringResource(R.string.diag_usage_access),
                    value = stringResource(if (report.usageAccess) R.string.summary_on else R.string.summary_off),
                    ok = report.usageAccess,
                )
                DiagRow(
                    label = stringResource(R.string.diag_location_permission),
                    value = stringResource(if (report.locationPermission) R.string.summary_on else R.string.summary_off),
                    ok = report.locationPermission,
                )
                DiagRow(
                    label = stringResource(R.string.diag_gps),
                    value = stringResource(if (report.gpsOn) R.string.summary_on else R.string.summary_off),
                    ok = report.gpsOn,
                )
                DiagRow(
                    label = stringResource(R.string.diag_network_location),
                    value = stringResource(if (report.networkLocationOn) R.string.summary_on else R.string.summary_off),
                    ok = report.networkLocationOn,
                )
                if (report.batteryPercent in 0..100) {
                    DiagRow(
                        label = stringResource(R.string.diag_battery),
                        value = stringResource(
                            if (report.charging) R.string.diag_battery_charging else R.string.diag_battery_value,
                            report.batteryPercent,
                        ),
                        ok = report.charging || report.batteryPercent >= 20,
                    )
                }
                if (report.appVersionCode > 0) {
                    DiagRow(
                        label = stringResource(R.string.diag_version),
                        value = stringResource(R.string.diag_version_value, report.appVersionName, report.appVersionCode),
                        ok = report.appVersionCode >= BuildConfig.VERSION_CODE,
                    )
                }
                if (report.updateError.isNotBlank()) {
                    DiagRow(
                        label = stringResource(R.string.diag_update_error),
                        value = remoteResultLabel(context, report.updateError),
                        ok = report.updateError == "waiting_parent",
                    )
                }
                if (report.suspendFailures.isNotEmpty()) {
                    DiagRow(
                        label = stringResource(R.string.diag_suspend_failures),
                        value = report.suspendFailures.joinToString(),
                        ok = false,
                    )
                }
                if (report.logLines.isNotEmpty()) {
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(
                            if (showLog) {
                                stringResource(R.string.diag_hide_log)
                            } else {
                                stringResource(R.string.diag_show_log, report.logLines.size)
                            },
                        )
                    }
                    if (showLog) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                report.logLines.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                modifier = Modifier.padding(spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LocationCard(
    customized: Boolean,
    onSetCustomized: (Boolean) -> Unit,
    intervalMinutes: Int,
    onSetInterval: (Int) -> Unit,
    historyEnabled: Boolean,
    onSetHistory: (Boolean) -> Unit,
    hasDevice: Boolean,
    locating: Boolean,
    onLocateNow: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.location_section_title), style = MaterialTheme.typography.titleMedium)
            // Children inherit the family's location defaults; the switch snapshots them
            // into a per-child override, mirroring the other override rows.
            Row(
                Modifier.fillMaxWidth().padding(top = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (customized) R.string.location_customized else R.string.location_inherited,
                        if (intervalMinutes == 0) {
                            stringResource(R.string.tracking_off)
                        } else {
                            stringResource(R.string.tracking_minutes_fmt, intervalMinutes)
                        },
                        stringResource(
                            if (historyEnabled) R.string.location_history_on else R.string.location_history_off,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = customized, onCheckedChange = onSetCustomized)
            }
            if (customized) {
                Text(
                    stringResource(R.string.tracking_periodic_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = spacing.sm),
                )
                TrackingIntervalChips(selected = intervalMinutes, onSelect = onSetInterval)
                Text(
                    stringResource(R.string.tracking_battery_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.location_history_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.location_history_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    Switch(checked = historyEnabled, onCheckedChange = onSetHistory)
                }
            }
            if (hasDevice) {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    OutlinedButton(onClick = onLocateNow, enabled = !locating, modifier = Modifier.weight(1f)) {
                        if (locating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(spacing.xs))
                            Text(stringResource(R.string.locate_in_progress))
                        } else {
                            Text(stringResource(R.string.locate_now))
                        }
                    }
                    Button(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.view_on_map))
                    }
                }
            }
        }
    }
}

/**
 * Per-child Wi-Fi-only-updates override. A single boolean, so the override is expressed as a
 * "customize" switch that snapshots the family value; once customized, a second switch sets
 * this child's value, and "follow family" clears it back to inheriting.
 */
@Composable
private fun UpdateWifiOverrideCard(
    override: Boolean?,
    familyValue: Boolean,
    onSetOverride: (Boolean?) -> Unit,
) {
    val spacing = Tokens.spacing
    val customized = override != null
    val value = override ?: familyValue
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_wifi_only_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (customized) {
                            stringResource(R.string.override_customized_hint)
                        } else {
                            stringResource(
                                R.string.update_wifi_following_family,
                                stringResource(if (familyValue) R.string.summary_on else R.string.summary_off),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Customize snapshots the current resolved value; turning it off re-inherits.
                Switch(checked = customized, onCheckedChange = { on -> onSetOverride(if (on) value else null) })
            }
            if (customized) {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.update_wifi_only_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = value, onCheckedChange = { onSetOverride(it) })
                }
            }
        }
    }
}

@Composable
private fun OverrideSwitchRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                // Make the "snapshot" nature of overrides explicit: once on, it stops tracking
                // later family edits (resolveForChild replaces the whole field).
                if (checked) {
                    Text(
                        stringResource(R.string.override_customized_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onEdit != null) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_child)) },
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
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun QrCard(bitmap: androidx.compose.ui.graphics.ImageBitmap?) {
    val spacing = Tokens.spacing
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White, tonalElevation = 2.dp) {
            Box(Modifier.padding(spacing.lg).size(200.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(200.dp))
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
