package dev.walcott.ui.parent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.walcott.data.PolicyDiff
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.DeviceMode
import dev.walcott.sync.EnforcementStatus
import dev.walcott.sync.PanicProtocol
import dev.walcott.sync.PanicRequest
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.Staleness
import dev.walcott.sync.SyncEngine
import dev.walcott.sync.UsageLedger
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.ModeBadge
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.PendingChip
import dev.walcott.ui.components.PermissionFixRow
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

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
    onOpenBudgets: () -> Unit,
    onOpenGuidedSetup: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenDomainRequest: (String) -> Unit,
    // Day-to-day shortcuts: limits/schedules and special days are the two screens a settled
    // family keeps coming back to, so they're one tap from home instead of behind the hub.
    onOpenCalendar: () -> Unit,
    onOpenMap: (String) -> Unit,
    /** The chooser: switching between families, adding one, letting one go. */
    onOpenFamilies: () -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Rule edits still sitting on this phone, waiting out the coalescing window (see PolicyPush).
    val pendingKeys by viewModel.pendingPolicyKeys.collectAsStateWithLifecycle()
    // What this parent's OWN phone still needs switched on (see DeviceSetup).
    val deviceSetup = dev.walcott.ui.setup.rememberDeviceSetup()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val lastSeen by viewModel.lastSeen.collectAsStateWithLifecycle()
    val requests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val asks by viewModel.pendingAsks.collectAsStateWithLifecycle()
    val domainRequests by viewModel.domainRequests.collectAsStateWithLifecycle()
    val pendingOps by viewModel.pendingOps.collectAsStateWithLifecycle()
    val parentVersion by viewModel.parentVersion.collectAsStateWithLifecycle()
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    val ledgers by viewModel.usageLedgers.collectAsStateWithLifecycle()
    var showAddChild by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var removingDevice by remember { mutableStateOf<ChildSnapshot?>(null) }
    val needsBackupPin by viewModel.localBackupNeedsPin.collectAsStateWithLifecycle()
    var showBackupPin by remember { mutableStateOf(false) }
    val families by viewModel.familySummaries.collectAsStateWithLifecycle()
    val multiFamily = families.size > 1

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
                // The home IS this family's home: its name is the title. (A future multi-family
                // build puts a chooser in front of this screen; the layout already assumes one.)
                Text(
                    settings.familyName.ifBlank { stringResource(R.string.family_default_name) },
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

        // The way out of this family: to the chooser when there are others, straight to
        // "add one" when this is still the only family the phone manages.
        item {
            OutlinedButton(onClick = onOpenFamilies, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (multiFamily) Icons.Outlined.SwapHoriz else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(spacing.xs))
                Text(
                    if (multiFamily) {
                        stringResource(R.string.switch_family, families.size)
                    } else {
                        stringResource(R.string.add_family)
                    },
                )
            }
        }

        // A child asking to be released outranks everything else on this screen: ignore it for
        // 24 hours and the device leaves the family (see PanicProtocol).
        items(snapshots.filter { it.panic != null }, key = { "panic-${it.deviceId}" }) { child ->
            PanicHomeRow(
                childName = childNameFor(child.deviceId, settings.children, snapshots),
                request = child.panic!!,
                onOpen = { if (child.childId in registryIds) onOpenChild(child.childId) },
            )
        }

        // Everything a child is waiting on an answer for, always at the very top: a pending
        // request is the one thing on this screen with a person on the other end of it.
        if (requests.isNotEmpty() || asks.isNotEmpty() || domainRequests.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.pending_requests)) }
            items(requests, key = { "req-" + it.request.requestId }) { pending ->
                // animateItem: resolved requests slide out instead of popping (and new ones in).
                Box(Modifier.animateItem()) {
                    ExtraTimeRequestCard(
                        pending = pending,
                        settings = settings,
                        viewModel = viewModel,
                        onResolve = { approved, minutes ->
                            viewModel.resolveRequest(pending.request.requestId, approved, minutes)
                        },
                    )
                }
            }
            items(asks, key = { "ask-" + it.ask.requestId }) { pending ->
                Box(Modifier.animateItem()) {
                    AskCard(pending, viewModel)
                }
            }
            // A domain selection a parent went and gathered on the child's phone: the parent is
            // standing there having just done the work, so this is the moment worth answering.
            items(domainRequests, key = { "domains-" + it.batchId }) { request ->
                Box(Modifier.animateItem()) {
                    DomainRequestHomeRow(
                        childName = childNameFor(request.deviceId, settings.children, snapshots),
                        appLabel = request.label.ifBlank { request.packageName },
                        count = request.domains()?.size ?: 0,
                        onOpen = { onOpenDomainRequest(request.batchId) },
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

        // The parent's own phone silently breaking the app: notifications turned off, or battery
        // optimisation deferring the catch-up poll. Not a setup step that gets ticked once — both
        // can be revoked months later — so it re-checks itself on every resume, and dismissing
        // moves it to Settings → Device setup rather than deleting it (see DeviceSetup).
        items(deviceSetup.toNag, key = { it.key }) { requirement ->
            dev.walcott.ui.setup.SetupNudgeCard(
                requirement = requirement,
                onFixed = deviceSetup::refreshNow,
                onDismiss = { deviceSetup.dismiss(requirement) },
            )
        }

        // Onboarding coach: a brand-new family enforces nothing until apps are classified and
        // limits set. Show the remaining steps until they're done, then it disappears.
        // Classifying apps is no longer a step: new apps land in General, usable under the
        // general limit — categories are an optional refinement, not setup.
        val childDone = settings.children.isNotEmpty()
        // Any limit at all counts: the family default, or a limit on one app. (It reads the
        // new fields — `budgets` is the pre-0.35 category map and is always empty now.)
        val limitsDone = settings.defaultAppBudget.isNotEmpty() ||
            settings.appPolicies.values.any { it.budgets.isNotEmpty() } ||
            settings.bedtime.isNotEmpty()
        val bedtimeDone = settings.bedtime.isNotEmpty()
        // Not a rule but the family's spare key: without a PIN nobody can authorise the
        // emergency release on a child's phone, so a lost parent phone means a 24-hour
        // countdown instead of thirty seconds. Families set up before this was asked for are
        // exactly the ones that need telling, which is why an otherwise-finished setup shows
        // the list again for this one step.
        val pinDone = settings.pinHash != null
        val rulesIncomplete = !(childDone && limitsDone && bedtimeDone)
        if (rulesIncomplete) {
            // The guided wizards, front and center until the family is fully set up (they
            // stay reachable afterwards from the family rules hub).
            item { GuidedSetupCard(onOpenGuidedSetup) }
        }
        if (rulesIncomplete || !pinDone) {
            item {
                SetupChecklistCard(
                    steps = listOf(
                        SetupStep(stringResource(R.string.setup_step_child), childDone) { showAddChild = true },
                        SetupStep(stringResource(R.string.setup_step_limits), limitsDone, onOpenBudgets),
                        SetupStep(stringResource(R.string.setup_step_bedtime), bedtimeDone, onOpenBudgets),
                        SetupStep(stringResource(R.string.setup_step_pin), pinDone) { showSetPin = true },
                    ),
                )
            }
        }

        // This family's general settings, one row, before the children: the home reads
        // top-down as "the family → its rules → its kids".
        item { SectionHeader(stringResource(R.string.home_section_manage)) }
        item {
            CardGroup {
                // Each row says whether what it leads to is waiting to be sent, so a parent can
                // see from here that a change of theirs is still on this phone.
                NavCard(
                    Icons.Outlined.Schedule,
                    stringResource(R.string.nav_limits_title),
                    stringResource(R.string.nav_limits_subtitle),
                    onOpenBudgets,
                    position = CardPosition.First,
                    pending = pendingKeys.any {
                        it == PolicyDiff.DEFAULT_BUDGET || it == PolicyDiff.BEDTIME ||
                            it == PolicyDiff.SCREEN_FREE || it.startsWith("app:")
                    },
                )
                NavCard(
                    Icons.Outlined.CalendarMonth,
                    stringResource(R.string.nav_calendar_title),
                    stringResource(R.string.nav_calendar_subtitle),
                    onOpenCalendar,
                    position = CardPosition.Middle,
                    pending = PolicyDiff.CALENDAR in pendingKeys,
                )
                NavCard(
                    Icons.Outlined.Groups,
                    stringResource(R.string.nav_family_settings_title),
                    stringResource(R.string.nav_family_settings_subtitle),
                    onOpenFamily,
                    position = CardPosition.Last,
                    // The family hub is everything that isn't the two rows above.
                    pending = pendingKeys.any {
                        it in setOf(
                            PolicyDiff.WEB_FILTER, PolicyDiff.RESTRICTIONS, PolicyDiff.EARN,
                            PolicyDiff.LOCATION, PolicyDiff.NEW_APP_ALERTS, PolicyDiff.FAMILY_NAME,
                        )
                    },
                )
            }
        }

        // The children of THIS family, with the day's numbers at a glance.
        item { SectionHeader(stringResource(R.string.children_section)) }
        if (settings.children.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.children_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            CardGroup {
                val parentNow = LocalDateTime.now()
                settings.children.forEachIndexed { index, entry ->
                    val snapshot = snapshots.firstOrNull { it.childId == entry.childId }
                    // Averages come from the parent-side ledger, dated by the child's clock
                    // (a child in another timezone is on another calendar day).
                    val ledger = snapshot?.let { ledgers[UsageLedger.keyOf(it.childId, it.deviceId)].orEmpty() }
                    val childToday = snapshot?.let {
                        dev.walcott.data.ChildStats.localNow(it.tzOffsetMinutes, nowMs, parentNow)
                            .toLocalDate().toEpochDay()
                    }
                    // The map is only worth a slot when it can show something: tracking or
                    // history on for this child, or a trail already reported.
                    val resolved = settings.resolveForChild(entry.childId)
                    val locationOn = resolved.trackingIntervalMinutes > 0 || resolved.locationHistoryEnabled ||
                        snapshot?.locations?.isNotEmpty() == true
                    ChildRow(
                        entry = entry,
                        snapshot = snapshot,
                        lastSeenMs = snapshot?.let { lastSeen[it.deviceId] },
                        nowMs = nowMs,
                        parentVersion = parentVersion,
                        avg7 = ledger?.let { UsageLedger.averageDaily(it, childToday!!, days = 7) },
                        avg30 = ledger?.let { UsageLedger.averageDaily(it, childToday!!, days = 30) },
                        showMap = snapshot != null && locationOn,
                        pending = PolicyDiff.childKey(entry.childId) in pendingKeys,
                        position = cardPosition(index, settings.children.size),
                        onClick = { onOpenChild(entry.childId) },
                        onOpenMap = { onOpenMap(entry.childId) },
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = { showAddChild = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.add_child))
            }
        }

        // The wall, in digest form: the last few lines in one compact card, with the full feed
        // one tap away. A notification can be swiped away and lost; its message survives there.
        val renderable = events.filter(::eventRenderable)
        if (renderable.isNotEmpty()) {
            item {
                RecentActivityCard(
                    events = dev.walcott.sync.ParentEvent.collapseRepeats(renderable).take(HOME_FEED_COUNT),
                    childName = { event ->
                        settings.children.firstOrNull { it.childId == event.childId }?.name
                            ?: event.childName.ifBlank { stringResource(R.string.family_default_name) }
                    },
                    nowMs = nowMs,
                    onSeeAll = onOpenActivity,
                )
            }
        }

        // Everything sent to a child device that hasn't finished: queued remote fixes, pushed
        // installs waiting for their tap in Play, location requests. Visible so the parent
        // doesn't re-send blindly, and cancellable while still queued.
        if (pendingOps.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.pending_ops_title),
                    supporting = stringResource(R.string.pending_ops_hint),
                )
            }
            items(pendingOps, key = { "op-" + it.deviceId + it.action + it.arg + it.sentAtMs }) { op ->
                Box(Modifier.animateItem()) {
                    PendingOpRow(
                        op = op,
                        childName = childNameFor(op.deviceId, settings.children, snapshots),
                        nowMs = nowMs,
                        onCancel = when {
                            // Delivered installs can be dismissed: the child may simply never
                            // finish in Play, and the parent shouldn't stare at it for a week.
                            op.delivered -> {
                                { viewModel.dismissPendingOp(op.id) }
                            }
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

        // A family that existed before the on-device copies did has no key for them yet, and
        // there is no reliable moment when it would appear: app lock is off by default, and with
        // biometrics on the PIN is never typed at all. Left to a settings screen this would be
        // the same opt-in nobody switches on that the copies exist to replace.
        if (needsBackupPin) {
            item { EnableLocalBackupCard(onEnable = { showBackupPin = true }) }
        }


        if (legacyDevices.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.legacy_devices_header)) }
            item {
                CardGroup {
                    legacyDevices.forEachIndexed { index, device ->
                        LegacyDeviceRow(
                            device,
                            position = cardPosition(index, legacyDevices.size),
                            onRemove = { removingDevice = device },
                        )
                    }
                }
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

    if (showBackupPin) {
        LocalBackupPinDialog(
            viewModel = viewModel,
            onDismiss = { showBackupPin = false },
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

    if (showSetPin) ChangePinDialog(viewModel) { showSetPin = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChildRow(
    entry: ChildEntry,
    snapshot: ChildSnapshot?,
    lastSeenMs: Long?,
    nowMs: Long,
    parentVersion: Long,
    avg7: UsageLedger.Average?,
    avg30: UsageLedger.Average?,
    /** Show the map shortcut (location on for this child, or a trail already reported). */
    showMap: Boolean,
    /** This child's own rules were edited here and haven't been sent yet (see PolicyDiff). */
    pending: Boolean,
    position: CardPosition = CardPosition.Single,
    onClick: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val spacing = Tokens.spacing
    // Dated by the child's clock, not the parent's: a child in another timezone is on another
    // calendar day, and matched against the parent's its usage would read as zero all day.
    val usageToday = snapshot
        ?.takeIf {
            dev.walcott.data.ChildStats.reportsCurrentDay(it.epochDay, it.tzOffsetMinutes, nowMs, LocalDateTime.now())
        }
        ?.usage?.sumOf { it.seconds } ?: 0L
    // A quiet child is almost always just a phone at rest (Doze) — say so neutrally, and
    // save the red for silences longer than any benign gap (see Staleness).
    val tier = if (snapshot == null) Staleness.Tier.FRESH else Staleness.tierOf(lastSeenMs, nowMs)

    WalcottCard(onClick = onClick, position = position) {
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
                if (snapshot == null) {
                    Text(
                        stringResource(R.string.device_not_linked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Today plus the week/month averages: the home answers "how much?" at a
                    // glance, without opening the detail. FlowRow for the same reason as the
                    // chips: long values in the long locale must wrap, never crush a column.
                    FlowRow(
                        Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
                    ) {
                        MiniStat(stringResource(R.string.stat_today), Duration.ofSeconds(usageToday).humanize())
                        MiniStat(
                            stringResource(R.string.stat_avg7),
                            avg7?.let { Duration.ofSeconds(it.seconds).humanize() } ?: "—",
                        )
                        MiniStat(
                            stringResource(R.string.stat_avg30),
                            avg30?.let { Duration.ofSeconds(it.seconds).humanize() } ?: "—",
                        )
                    }
                }
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
                // Edited here and not sent yet, then sent and not confirmed yet: two states,
                // in the order they happen.
                if (pending) PendingChip(Modifier.padding(top = Tokens.spacing.xs))
                if (snapshot != null) StatusChips(snapshot, parentVersion)
            }
            if (showMap) {
                IconButton(onClick = onOpenMap) {
                    Icon(
                        Icons.Outlined.Map,
                        contentDescription = stringResource(R.string.view_on_map),
                        tint = MaterialTheme.colorScheme.primary,
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

/** One number with its label under it, sized to sit three-in-a-row inside a child row. */
@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, softWrap = false)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
        )
    }
}

/**
 * The home's activity digest: the newest few lines, compact, in one card. The wall used to be
 * eight full-size rows and dominated the screen — the family, not the log, is what the home is
 * for. Everything else lives behind "see all" ([ActivityScreen]).
 */
@Composable
private fun RecentActivityCard(
    /** Collapsed feed entries: each with how many identical lines it stands for. */
    events: List<Pair<dev.walcott.sync.ParentEvent, Int>>,
    childName: @Composable (dev.walcott.sync.ParentEvent) -> String,
    nowMs: Long,
    onSeeAll: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard(modifier = Modifier.padding(top = spacing.sm)) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(spacing.sm))
            events.forEach { (event, times) -> EventLine(event, childName(event), nowMs, repeat = times) }
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier.align(Alignment.End).padding(top = spacing.xs),
            ) { Text(stringResource(R.string.timeline_see_all)) }
        }
    }
}

/**
 * A child's domain selection waiting to be turned into rules. Highlighted in the primary colour
 * rather than the panic red: this is an invitation, not an alarm.
 */
@Composable
private fun DomainRequestHomeRow(childName: String, appLabel: String, count: Int, onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.primary
    WalcottCard(onClick = onOpen, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Language, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.domains_home_title, childName),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
                Text(
                    pluralStringResource(R.plurals.domains_home_sub, count, count, appLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A child's pending emergency release, at the very top of the parent's home. The child detail
 * screen holds the actual refusal button; this is the "you cannot miss this" line.
 */
@Composable
private fun PanicHomeRow(childName: String, request: PanicRequest, onOpen: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    val hoursLeft = (PanicProtocol.remainingCheckpoints(request) * PanicProtocol.CHECKPOINT_INTERVAL_SEC / 3600).toInt()
    WalcottCard(onClick = onOpen, color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.panic_alert_title, childName),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
                Text(
                    if (hoursLeft > 0) {
                        pluralStringResource(R.plurals.panic_card_left, hoursLeft, hoursLeft)
                    } else {
                        stringResource(R.string.panic_card_done)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun GuidedSetupCard(onClick: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onClick, color = MaterialTheme.colorScheme.primaryContainer) {
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
    WalcottCard(color = MaterialTheme.colorScheme.secondaryContainer) {
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
@OptIn(ExperimentalLayoutApi::class)
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
    // FlowRow, not Row: with the longer locale and several warnings at once the chips outgrow
    // the column, and a plain Row squeezes the last one into a zero-width, letter-per-line
    // tower that stretches the whole card. Overflowing chips wrap to the next line instead.
    FlowRow(
        Modifier.padding(top = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        chips.forEach { (icon, label, color) ->
            Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
                Row(
                    Modifier.padding(horizontal = spacing.sm, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun LegacyDeviceRow(device: ChildSnapshot, position: CardPosition = CardPosition.Single, onRemove: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
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
private const val HOME_FEED_COUNT = 4

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

    WalcottCard {
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

/**
 * Asks for the PIN once so the nightly on-device copies can start (see [dev.walcott.sync.LocalBackupStore]).
 * Only shown while there is no key: it disappears for good the moment one is derived.
 */
@Composable
private fun EnableLocalBackupCard(onEnable: () -> Unit) {
    val spacing = Tokens.spacing
    WalcottCard(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                stringResource(R.string.local_backup_enable_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.local_backup_enable_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            androidx.compose.material3.TextButton(
                onClick = onEnable,
                modifier = Modifier.padding(top = spacing.xs),
            ) { Text(stringResource(R.string.local_backup_enable_action)) }
        }
    }
}

/** Takes the PIN once and derives the on-device backup key from it; wrong PINs keep it open. */
@Composable
private fun LocalBackupPinDialog(viewModel: WalcottViewModel, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.local_backup_enable_title)) },
        text = {
            Column {
                Text(stringResource(R.string.local_backup_pin_prompt), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; failed = false },
                    label = { Text(stringResource(R.string.restore_pin_label)) },
                    isError = failed,
                    supportingText = { if (failed) Text(stringResource(R.string.pin_incorrect)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = Tokens.spacing.sm),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        // Verified first: deriving from a wrong PIN would seal every copy with a
                        // key nobody can reproduce, and the failure would only show up at restore.
                        if (viewModel.verifyPin(pin) is dev.walcott.data.PinResult.Ok) {
                            viewModel.enableLocalBackup(pin)
                            onDismiss()
                        } else {
                            failed = true
                        }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.local_backup_enable_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
