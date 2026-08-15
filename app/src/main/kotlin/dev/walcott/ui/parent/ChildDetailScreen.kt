package dev.walcott.ui.parent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.BuildConfig
import dev.walcott.Distribution
import dev.walcott.R
import dev.walcott.data.ChildEntry
import dev.walcott.data.withBudget
import dev.walcott.provisioning.DeviceOwnerProvisioning
import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.ClockGuard
import dev.walcott.sync.EnforcementStatus
import dev.walcott.sync.PairingPayload
import dev.walcott.sync.PanicProtocol
import dev.walcott.sync.PanicRequest
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.Role
import dev.walcott.sync.SyncNotifications
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.FoldCard
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.cardPosition
import dev.walcott.rules.ActiveBlock
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.activeBlocks
import dev.walcott.ui.format.hhmm
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
    onOpenHealthReports: () -> Unit,
    onEditWebFilter: () -> Unit,
    onEditProtection: () -> Unit,
    onEditApps: () -> Unit,
    /** The family's own limits and schedules — where an inherited rule is actually changed. */
    onOpenFamilyLimits: () -> Unit,
    /** The family's app list, for the same reason. */
    onOpenFamilyApps: () -> Unit,
    onOpenSpecialDays: () -> Unit,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val pendingOps by viewModel.pendingOps.collectAsStateWithLifecycle()
    val parentVersion by viewModel.parentVersion.collectAsStateWithLifecycle()
    val policyConfirmedAt by viewModel.policyConfirmedAt.collectAsStateWithLifecycle()
    val diagHistory by viewModel.diagHistory.collectAsStateWithLifecycle()
    val lastSeen by viewModel.lastSeen.collectAsStateWithLifecycle()
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

    // The child's day, as the child's own clock has it: its counters are keyed to its calendar
    // day, and the time matters too because a weekend edge flips the day type mid-day and with
    // it every rule below. Hoisted out of the cards that need it so the dashboard and the
    // "what is shut right now" list can never disagree about what "now" is.
    val parentNow = java.time.LocalDateTime.now()
    val childNow = dev.walcott.data.ChildStats.localNow(snapshot?.tzOffsetMinutes, nowMs, parentNow)
    val reportedToday = snapshot != null && dev.walcott.data.ChildStats
        .reportsCurrentDay(snapshot.epochDay, snapshot.tzOffsetMinutes, nowMs, parentNow)
    val childSettings = remember(settings, childId) { settings.resolveForChild(childId) }
    val childConfig = remember(childSettings) { childSettings.toFamilyConfig(emptySet()) }
    // The family's own rules, unresolved, so every override row can say what it is overriding
    // instead of only that it is overriding something.
    val familyConfig = remember(settings) { settings.toFamilyConfig(emptySet()) }
    val childDayType = childConfig.calendar.dayTypeOf(childNow)
    // Its own calendar: a child can have special days the family doesn't (see childHolidays),
    // so the day type the family's rules answer to today is not always the same one.
    val familyDayType = familyConfig.calendar.dayTypeOf(childNow)
    val usageToday = if (reportedToday) {
        snapshot!!.usage.associate { it.categoryId to Duration.ofSeconds(it.seconds) }
    } else {
        emptyMap()
    }
    val extraToday = if (reportedToday) {
        snapshot!!.extra.associate { it.categoryId to Duration.ofSeconds(it.seconds) }
    } else {
        emptyMap()
    }
    // Computed here rather than reported by the child: the parent holds the rules and the
    // child's day, so this is the same verdict its enforcement loop is reaching, a minute of
    // tick behind at worst — and it stays honest about a device that hasn't checked in today
    // (windows still read; a budget judged from yesterday's counters would not).
    val blockingNow = remember(childConfig, snapshot?.apps, childNow.withSecond(0), usageToday, extraToday, reportedToday) {
        RuleEngine.activeBlocks(
            config = childConfig,
            packages = snapshot?.apps?.map { it.packageName }.orEmpty(),
            now = childNow,
            usageToday = usageToday,
            extraTime = extraToday,
            usageIsToday = reportedToday,
        )
    }

    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    // Which app the bonus dialog opens on: the all-apps sentinel from the general button, the
    // package itself when it was opened from the app that has just run out. Null = closed.
    var bonusTarget by remember { mutableStateOf<String?>(null) }
    var showInheritAll by remember { mutableStateOf(false) }
    var showCode by rememberSaveable { mutableStateOf(false) }
    // The technical tail (remote fixes, live health, update transport) folded away: it is
    // rarely what the parent came for, and it used to push location and limits off-screen.
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    // Same for the per-child rule overrides: an all-inherited child used to open on a wall
    // of greyed-out rules, which read as "settings to fix" instead of "nothing customized".
    // Starts open only when something IS customized, so active state is never hidden.
    var showRules by rememberSaveable { mutableStateOf(entry.overrides.customRuleCount > 0) }
    // Sending the parent to a rule of this child's own means opening the fold it lives in — and
    // that fold is near the bottom of a long screen, so expanding it alone looks like the button
    // did nothing. Bumping this asks the list to travel there, once the rows exist to travel to.
    val listState = rememberLazyListState()
    var goToRules by remember { mutableIntStateOf(0) }
    LaunchedEffect(goToRules) {
        if (goToRules == 0) return@LaunchedEffect
        // Second from the end, always: the rules are one item and "Additional settings" — itself
        // collapsed on the way past — is the last. Landing on the section's own header puts the
        // rule that was tapped directly under it.
        val target = (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)
        listState.animateScrollToItem(target)
    }
    fun openChildRules() {
        showRules = true
        showAdvanced = false
        goToRules++
    }


    // Location earns a prominent slot only when it can actually show something: tracking or
    // history on for this child, or a trail already reported. Otherwise the card (which is
    // also where it gets switched on) lives under "Additional settings".
    val resolvedForLocation = settings.resolveForChild(childId)
    val locationActive = resolvedForLocation.trackingIntervalMinutes > 0 ||
        resolvedForLocation.locationHistoryEnabled || snapshot?.locations?.isNotEmpty() == true

    val locationCard: @Composable () -> Unit = {
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

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(
            title = entry.name,
            onBack = onBack,
            onRename = { showRename = true },
            onRemove = { showRemove = true },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            // Breathing room at the bottom as padding rather than a trailing spacer item: the
            // jump to the rules counts back from the end of the list, and an item that exists
            // only to be blank would make "the end of the list" mean something else.
            contentPadding = PaddingValues(bottom = spacing.xl),
        ) {
            // --- Enrollment ---
            if (snapshot == null || showCode) {
                item {
                    EnrollmentSection(
                        entry = entry,
                        viewModel = viewModel,
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
                        rulesConfirmedAtMs = policyConfirmedAt[snapshot.deviceId] ?: 0L,
                        onShowCode = { showCode = true },
                    )
                }
            }

            // --- Dashboard: the child's day at a glance, plus their recent events ---
            if (snapshot != null) {
                item {
                    val today = childNow.toLocalDate().toEpochDay()
                    val ledger = ledgers[dev.walcott.sync.UsageLedger.keyOf(snapshot.childId, snapshot.deviceId)].orEmpty()
                    ChildDashboardCard(
                        childName = entry.name,
                        usedToday = Duration.ofSeconds(usageToday.values.sumOf { it.seconds }),
                        avg7 = dev.walcott.sync.UsageLedger.averageDaily(ledger, today, days = 7),
                        avg30 = dev.walcott.sync.UsageLedger.averageDaily(ledger, today, days = 30),
                        defaultBudget = dev.walcott.data.ChildStats.defaultBudgetToday(childConfig, childNow),
                        events = dev.walcott.sync.ParentEvent
                            .collapseRepeats(events.filter { it.childId == childId && eventRenderable(it) })
                            .take(3),
                        nowMs = nowMs,
                    )
                }
            }

            // --- Emergency release: the child is asking to be let go (see PanicProtocol) ---
            // Above every other warning on purpose: it ends with the device leaving the family,
            // and the parent's refusal is only possible while the countdown is running.
            snapshot?.panic?.let { request ->
                item {
                    PanicRequestCard(
                        request = request,
                        onDeny = { viewModel.denyPanic(snapshot.deviceId, request.id) },
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

            // --- Apps that appeared unapproved: suspended there, waiting for the parent's call ---
            if (snapshot != null) {
                items(snapshot.unauthorized, key = { "unauth${it.pkg}" }) { entry ->
                    UnauthorizedAppCard(
                        entry = entry,
                        onRemove = { viewModel.removeChildApp(snapshot.deviceId, entry.pkg) },
                        onAllow = { viewModel.allowChildApp(snapshot.deviceId, entry.pkg) },
                    )
                }
            }

            // --- Web filter asked for but not running (consent, or another VPN app) ---
            if (snapshot != null && dev.walcott.sync.FamilyHealth.webFilterDown(snapshot)) {
                item { WebFilterDownCard() }
            }

            // --- Clock tamper (device clock far off the sync server's) ---
            if (snapshot != null && ClockGuard.isTampered(snapshot.clockSkewMs)) {
                item { ClockTamperCard(snapshot.clockSkewMs) }
            }

            // --- Wrong-PIN attempts (someone is trying to guess the parent PIN on the child) ---
            if (snapshot != null && snapshot.pinWrongTotal > 0) {
                item { WrongPinCard(snapshot.pinWrongTotal, snapshot.lastWrongPinMs) }
            }

            // --- What is shut right now, and the one thing that opens it ---
            // Under the alarms above and over everything else: those say the rules are not being
            // applied as written, and this says what the rules are doing. A parent arrives here
            // because a child said "it won't let me", and until now the answer was to open four
            // editors and work it out — the rules were all visible and which of them was biting
            // was not.
            if (snapshot != null && blockingNow.isNotEmpty()) {
                item {
                    SectionHeader(
                        stringResource(R.string.blocking_now_title),
                        supporting = stringResource(R.string.blocking_now_hint),
                    )
                }
                item {
                    CardGroup {
                        blockingNow.forEachIndexed { index, block ->
                            BlockingRow(
                                block = block,
                                appLabel = snapshot.apps.firstOrNull { it.packageName == block.packageName }
                                    ?.label?.ifBlank { null } ?: block.packageName,
                                position = cardPosition(index, blockingNow.size),
                                onAct = {
                                    when (block.kind) {
                                        // More minutes is the only thing that ends a spent
                                        // budget, and it is what the child's own screen asks
                                        // for — the same grant, from the side that can give it.
                                        ActiveBlock.Kind.BUDGET -> bonusTarget = block.packageName
                                        // Otherwise: the rule itself. Where it lives depends on
                                        // whose it is — this child's own copy, or the family's.
                                        ActiveBlock.Kind.BEDTIME ->
                                            if (entry.overrides.bedtime != null) openChildRules() else onOpenFamilyLimits()
                                        ActiveBlock.Kind.SCREEN_FREE ->
                                            if (entry.overrides.allAppsBlockedWindows != null) openChildRules() else onOpenFamilyLimits()
                                        ActiveBlock.Kind.APP_WINDOW ->
                                            if (entry.overrides.appPolicies != null) onEditApps() else onOpenFamilyApps()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // --- Location, right under the day-at-a-glance while it's in use ---
            if (locationActive) {
                item { locationCard() }
            }

            // --- Stats ---
            if (snapshot != null) {
                item { SectionHeader(stringResource(R.string.child_section_activity)) }
                item {
                    CardGroup {
                        val hasHistory = snapshot.history.isNotEmpty()
                        UsageTodayCard(
                            snapshot,
                            viewModel,
                            position = if (hasHistory) CardPosition.First else CardPosition.Single,
                            onGiveBonus = { bonusTarget = dev.walcott.rules.ExtraTime.ALL_APPS },
                        )
                        if (hasHistory) {
                            HistoryCard(snapshot, position = CardPosition.Last)
                        }
                    }
                }
            }

            // --- Per-child overrides, behind a fold ---
            // One list item, not nine: it is the only way a LazyColumn can be asked to travel to
            // this section (see openChildRules) — an index is all it understands, and the index of
            // one item that is always second from the end is a fact, where a count of nine that
            // grows whenever somebody adds a rule is a bug waiting to be written.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    // The rules this fold actually owns. `budgets` used to be counted here and is
                    // the pre-0.35 category map, blanked by the migration and null on every install
                    // since — so the count was one short whenever the one rule most likely to be
                    // customized, this child's own daily limit, was the customized one.
                    val customized = entry.overrides.customRuleCount
                    FoldCard(
                        icon = Icons.Outlined.Rule,
                        title = stringResource(R.string.override_section_title),
                        subtitle = if (customized > 0) {
                            pluralStringResource(R.plurals.override_fold_customized, customized, customized)
                        } else {
                            stringResource(R.string.override_inherited_hint)
                        },
                        expanded = showRules,
                        onToggle = { showRules = !showRules },
                    )
                    if (showRules) {
                        // Each override is a connected pair: the switch that owns the rule on top, the
                        // rule itself (always rendered, refused while inherited) attached below it.
                        CardGroup {
                            OverrideSwitchRow(
                                title = stringResource(R.string.override_bedtime_title),
                                checked = entry.overrides.bedtime != null,
                                position = CardPosition.First,
                                childValue = windowValue(childConfig.bedtime[childDayType]),
                                familyValue = windowValue(familyConfig.bedtime[familyDayType]),
                                onToggle = { on ->
                                    viewModel.setChildOverrides(
                                        childId,
                                        entry.overrides.copy(bedtime = if (on) settings.bedtime else null),
                                    )
                                },
                            )
                            BedtimeCard(
                                bedtime = entry.overrides.bedtime ?: settings.bedtime,
                                enabled = entry.overrides.bedtime != null,
                                position = CardPosition.Last,
                                specialDaysOwnRules = settings.specialDaysOwnRules,
                                onOpenSpecialDays = onOpenSpecialDays,
                                onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                            ) { updated ->
                                viewModel.setChildOverrides(childId, entry.overrides.copy(bedtime = updated))
                            }
                        }
                        // Family-wide screen-free windows, per child: the one field that used to be
                        // truly family-only — a laxer sibling couldn't opt out of "no screens at
                        // dinner" until this row existed.
                        CardGroup {
                            OverrideSwitchRow(
                                title = stringResource(R.string.override_windows_title),
                                checked = entry.overrides.allAppsBlockedWindows != null,
                                position = CardPosition.First,
                                childValue = windowsValue(childConfig.blockedWindows[childDayType].orEmpty()),
                                familyValue = windowsValue(familyConfig.blockedWindows[familyDayType].orEmpty()),
                                onToggle = { on ->
                                    viewModel.setChildOverrides(
                                        childId,
                                        entry.overrides.copy(
                                            allAppsBlockedWindows = if (on) settings.allAppsBlockedWindows else null,
                                        ),
                                    )
                                },
                            )
                            BlockedWindowsCard(
                                title = stringResource(R.string.all_apps_windows_title),
                                hint = stringResource(R.string.all_apps_windows_hint),
                                windowsByDay = entry.overrides.allAppsBlockedWindows ?: settings.allAppsBlockedWindows,
                                enabled = entry.overrides.allAppsBlockedWindows != null,
                                position = CardPosition.Last,
                                specialDaysOwnRules = settings.specialDaysOwnRules,
                                onOpenSpecialDays = onOpenSpecialDays,
                                onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                                onChange = { windows ->
                                    // The whole schedule in one write, same list under every day
                                    // type (see WalcottViewModel.setAllAppsWindows). An empty list
                                    // clears the override's map rather than storing empty ones.
                                    viewModel.setChildOverrides(
                                        childId,
                                        entry.overrides.copy(
                                            allAppsBlockedWindows = if (windows.isEmpty()) {
                                                emptyMap()
                                            } else {
                                                dev.walcott.rules.DayType.entries.associate { it.name to windows }
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                        // This child's own version of the family default: the same one number, so
                        // "Ana gets two hours an app, her brother one" needs no new concept.
                        val budget = entry.overrides.defaultAppBudget ?: settings.defaultAppBudget
                        CardGroup {
                            OverrideSwitchRow(
                                title = stringResource(R.string.override_budgets_title),
                                checked = entry.overrides.defaultAppBudget != null,
                                position = CardPosition.First,
                                childValue = budgetValue(childConfig.defaultAppBudget[childDayType]),
                                familyValue = budgetValue(familyConfig.defaultAppBudget[familyDayType]),
                                onToggle = { on ->
                                    viewModel.setChildOverrides(
                                        childId,
                                        entry.overrides.copy(
                                            defaultAppBudget = if (on) settings.defaultAppBudget else null,
                                        ),
                                    )
                                },
                            )
                            DailyBudgetCard(
                                title = stringResource(R.string.default_budget_title),
                                icon = Icons.Outlined.Apps,
                                perDay = budget,
                                enabled = entry.overrides.defaultAppBudget != null,
                                position = CardPosition.Last,
                                specialDaysOwnRules = settings.specialDaysOwnRules,
                                onOpenSpecialDays = onOpenSpecialDays,
                                onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                                onSetBudget = { dayType, minutes ->
                                    viewModel.setDefaultBudget(dayType, minutes, childId)
                                },
                            )
                        }
                        // Per-app limits for this child's own apps. The Edit door only exists while
                        // the override is on: the scoped Apps screens always write the override.
                        OverrideSwitchRow(
                            title = stringResource(R.string.override_apps_title),
                            checked = entry.overrides.appPolicies != null,
                            childValue = countValue(childSettings.appPolicies.count { !it.value.isEmpty }, R.plurals.override_value_apps),
                            familyValue = countValue(settings.appPolicies.count { !it.value.isEmpty }, R.plurals.override_value_apps),
                            onToggle = { on ->
                                viewModel.setChildOverrides(
                                    childId,
                                    entry.overrides.copy(appPolicies = if (on) settings.appPolicies else null),
                                )
                            },
                            onEdit = if (entry.overrides.appPolicies != null) onEditApps else null,
                        )
                        OverrideSwitchRow(
                            title = stringResource(R.string.override_webfilter_title),
                            checked = entry.overrides.blockedDomains != null,
                            childValue = countValue(childSettings.blockedDomains.size, R.plurals.override_value_domains),
                            familyValue = countValue(settings.blockedDomains.size, R.plurals.override_value_domains),
                            onToggle = { on ->
                                viewModel.setChildOverrides(
                                    childId,
                                    entry.overrides.copy(blockedDomains = if (on) settings.blockedDomains else null),
                                )
                            },
                            onEdit = onEditWebFilter,
                            editable = entry.overrides.blockedDomains != null,
                        )
                        OverrideSwitchRow(
                            title = stringResource(R.string.override_protection_title),
                            checked = entry.overrides.deviceRestrictions != null,
                            childValue = countValue(childSettings.deviceRestrictions.size, R.plurals.override_value_locks),
                            familyValue = countValue(settings.deviceRestrictions.size, R.plurals.override_value_locks),
                            onToggle = { on ->
                                viewModel.setChildOverrides(
                                    childId,
                                    entry.overrides.copy(deviceRestrictions = if (on) settings.deviceRestrictions else null),
                                )
                            },
                            onEdit = onEditProtection,
                            editable = entry.overrides.deviceRestrictions != null,
                        )
                        // The way back. Undoing this child's rules meant finding every switch that was
                        // on — including the ones in other sections — and remembering which those were,
                        // which is exactly what a parent who wants to start over has lost track of.
                        if (!entry.overrides.isEmpty) {
                            OutlinedButton(
                                onClick = { showInheritAll = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.override_inherit_all)) }
                        }
                    }
                }
            }
            // --- Additional settings: the technical tail, folded until asked for ---
            item {
                FoldCard(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.child_more_title),
                    subtitle = stringResource(R.string.child_more_subtitle),
                    expanded = showAdvanced,
                    onToggle = { showAdvanced = !showAdvanced },
                )
            }
            if (showAdvanced) {
                // Remote fixes and live health are only meaningful once a device is linked.
                if (snapshot != null) {
                    item { SectionHeader(stringResource(R.string.child_section_device)) }
                    item {
                        CardGroup {
                            RemoteFixCard(
                                snapshot = snapshot,
                                position = CardPosition.First,
                                onCommand = { action -> viewModel.sendRemoteCommand(snapshot.deviceId, action) },
                            )
                            LiveHealthCard(
                                snapshot = snapshot,
                                lastSeenMs = lastSeen[snapshot.deviceId] ?: 0L,
                                nowMs = nowMs,
                                reportCount = diagHistory[snapshot.deviceId]?.size ?: 0,
                                position = CardPosition.Last,
                                onOpenReports = onOpenHealthReports,
                            )
                        }
                    }
                }
                // Location switched off lives here: still reachable to turn it on, but not
                // spending a prominent slot on a feature the family isn't using.
                if (!locationActive) {
                    item { locationCard() }
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
            }

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
    bonusTarget?.let { target ->
        if (snapshot == null) return@let
        BonusDialog(
            apps = snapshot.apps.map { it.packageName to it.label },
            initialTarget = target,
            onDismiss = { bonusTarget = null },
            onGrant = { categoryId, minutes ->
                viewModel.giveBonus(snapshot.deviceId, categoryId, minutes)
                bonusTarget = null
            },
        )
    }
    if (showInheritAll) {
        AlertDialog(
            onDismissRequest = { showInheritAll = false },
            title = { Text(stringResource(R.string.override_inherit_all_title)) },
            text = { Text(stringResource(R.string.override_inherit_all_body, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showInheritAll = false
                    viewModel.setChildOverrides(childId, dev.walcott.data.ChildOverrides())
                }) { Text(stringResource(R.string.override_inherit_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showInheritAll = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * A rule as one short line, for the override rows: what it says TODAY.
 *
 * Today rather than the whole week because that is the version a parent is looking at the child
 * about, and a row that tried to print every day type would be a table, not a line.
 */
@Composable
private fun windowValue(window: dev.walcott.rules.TimeWindow?): String =
    window?.let { "${it.start.hhmm()} – ${it.end.hhmm()}" } ?: stringResource(R.string.override_value_none)

@Composable
private fun windowsValue(windows: List<dev.walcott.rules.TimeWindow>): String = when (windows.size) {
    0 -> stringResource(R.string.override_value_none)
    // One window is worth naming; several are worth counting, and the editor is one tap away.
    1 -> windowValue(windows.first())
    else -> pluralStringResource(R.plurals.override_value_windows, windows.size, windows.size)
}

@Composable
private fun budgetValue(budget: Duration?): String =
    budget?.humanize() ?: stringResource(R.string.override_value_no_limit)

@Composable
private fun countValue(count: Int, plural: Int): String =
    if (count == 0) stringResource(R.string.override_value_none) else pluralStringResource(plural, count, count)

/**
 * One rule that is shutting something right now, and the single thing that would end it.
 *
 * One action per row on purpose: a spent budget ends with minutes and a window ends by being
 * changed, and offering both on every row would make the parent choose between them each time
 * over a difference the rule already settles.
 */
@Composable
private fun BlockingRow(
    block: ActiveBlock,
    appLabel: String,
    position: CardPosition,
    onAct: () -> Unit,
) {
    val spacing = Tokens.spacing
    val (icon, title) = when (block.kind) {
        ActiveBlock.Kind.BEDTIME -> Icons.Filled.Bedtime to stringResource(R.string.bedtime_title)
        ActiveBlock.Kind.SCREEN_FREE -> Icons.Outlined.DoNotDisturbOn to stringResource(R.string.screen_free_title)
        ActiveBlock.Kind.APP_WINDOW -> Icons.Outlined.Schedule to appLabel
        ActiveBlock.Kind.BUDGET -> Icons.Outlined.HourglassEmpty to appLabel
    }
    val detail = when (block.kind) {
        ActiveBlock.Kind.BUDGET -> stringResource(
            R.string.blocking_out_of_time,
            (block.used ?: Duration.ZERO).humanize(),
            (block.allowance ?: Duration.ZERO).humanize(),
        )
        // The hour it lets go, which is the parent's actual question about a window.
        else -> block.until?.let { stringResource(R.string.blocking_until, it.hhmm()) }.orEmpty()
    }
    val action = stringResource(
        if (block.kind == ActiveBlock.Kind.BUDGET) R.string.blocking_give_time else R.string.action_edit,
    )
    WalcottCard(position = position) {
        Row(
            Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            TextButton(onClick = onAct) { Text(action) }
        }
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
private fun EnrollmentSection(entry: ChildEntry, pairingText: String?, viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    // Device Owner is the strong path (full blocking); the fallback works without a factory reset.
    var mode by remember { mutableStateOf(EnrollMode.DEVICE_OWNER) }
    // Two-step wizard: only one QR is ever on screen at a time, so the child's camera can't
    // lock onto the wrong code when two are shown together.
    var step by rememberSaveable { mutableStateOf(0) }
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }

    // The PIN gate. A child enrolled into a family that has no PIN is a device whose emergency
    // release can never be authorised — every attempt is rejected, because there is nothing to
    // check against — leaving the 24-hour countdown as the only way back if the parent phone is
    // lost. That is a trap to walk into, not a preference, so the code that creates it is not
    // handed out until the family has the key to undo it.
    if (pairingText != null && !hasPin) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(stringResource(R.string.enroll_needs_pin_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.enroll_needs_pin_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { settingPin = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.parent_pin_create))
            }
        }
        if (settingPin) ChangePinDialog(viewModel) { settingPin = false }
        return
    }

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
private fun LinkedCard(
    snapshot: ChildSnapshot,
    rulesSyncing: Boolean,
    rulesConfirmedAtMs: Long,
    onShowCode: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard {
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
                // Rules in flight, or the positive counterpart. Saying "up to date, confirmed at
                // X" matters as much as the warning: a healthy child used to be shown by the
                // ABSENCE of a line, which is also exactly what a child that has never reported
                // anything looks like — so the one state a parent most wants to confirm was the
                // one the screen said nothing about.
                if (rulesSyncing) {
                    Text(
                        stringResource(R.string.detail_rules_syncing),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB26A00),
                    )
                } else if (rulesConfirmedAtMs > 0) {
                    Text(
                        stringResource(
                            R.string.detail_rules_current,
                            android.text.format.DateUtils.getRelativeTimeSpanString(rulesConfirmedAtMs).toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * The child's day at a glance: screen time so far, budget left, and the week/month averages
 * from the parent-side ledger — plus this child's slice of the activity feed. Sits at the top
 * of the detail so the answer to "how are they doing?" needs no scrolling.
 */
@Composable
private fun ChildDashboardCard(
    childName: String,
    usedToday: Duration,
    avg7: dev.walcott.sync.UsageLedger.Average?,
    avg30: dev.walcott.sync.UsageLedger.Average?,
    /** Budget left today across categories; null = nothing has a budget today ("no limit"). */
    defaultBudget: Duration?,
    /** Collapsed feed entries: each with how many identical lines it stands for. */
    events: List<Pair<dev.walcott.sync.ParentEvent, Int>>,
    nowMs: Long,
) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            Row(Modifier.fillMaxWidth()) {
                StatTile(
                    value = usedToday.humanize(),
                    label = stringResource(R.string.dashboard_used_today),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = defaultBudget?.humanize() ?: stringResource(R.string.no_limit),
                    label = stringResource(R.string.dashboard_default_limit),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(spacing.md))
            Row(Modifier.fillMaxWidth()) {
                AvgTile(avg7, R.string.dashboard_avg7, Modifier.weight(1f))
                AvgTile(avg30, R.string.dashboard_avg30, Modifier.weight(1f))
            }
            if (events.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = spacing.md))
                events.forEach { (event, times) -> EventLine(event, childName, nowMs, repeat = times) }
            }
        }
    }
}

/** A [StatTile] for a ledger average, captioned with how many days actually back it. */
@Composable
private fun AvgTile(average: dev.walcott.sync.UsageLedger.Average?, labelRes: Int, modifier: Modifier = Modifier) {
    StatTile(
        value = average?.let { Duration.ofSeconds(it.seconds).humanize() } ?: "—",
        label = stringResource(labelRes),
        caption = average?.let {
            pluralStringResource(R.plurals.dashboard_avg_days, it.daysCounted, it.daysCounted)
        },
        modifier = modifier,
    )
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
private fun UsageTodayCard(
    snapshot: ChildSnapshot,
    viewModel: dev.walcott.ui.WalcottViewModel,
    position: CardPosition = CardPosition.Single,
    onGiveBonus: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.usage_today), style = MaterialTheme.typography.titleMedium)
            if (snapshot.usage.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                HorizontalDivider(Modifier.padding(vertical = spacing.sm))
                // Usage is per app now; the child's own reported app list names each package,
                // and only the busiest few are worth a row.
                snapshot.usage.sortedByDescending { it.seconds }.take(USAGE_ROWS).forEach { entry ->
                    UsageRow(entry, snapshot.apps, viewModel)
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
private fun HistoryCard(snapshot: ChildSnapshot, position: CardPosition = CardPosition.Single) {
    val spacing = Tokens.spacing
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val days = snapshot.history.sortedByDescending { it.epochDay }.take(7)
    WalcottCard(position = position) {
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
    WalcottCard(color = color.copy(alpha = 0.12f)) {
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
    WalcottCard(color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Text(stringResource(R.string.usage_access_off_child), style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

/**
 * The rules ask for a DNS filter and the child's tunnel isn't up, so every blocked domain is
 * resolving normally. Same silent-failure class as the self-test gap: the child looks healthy
 * from every other angle, because publishing never depended on the tunnel.
 */
@Composable
private fun WebFilterDownCard() {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(spacing.md))
            Text(
                stringResource(R.string.web_filter_down_text),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

/**
 * An app that appeared on the child device without being approved.
 *
 * It is already suspended there and its removal is already being retried, so the card reports
 * rather than asks — except for the one decision that is the parent's: letting it stay. The
 * removal button is here too, because a retry that keeps failing needs a way to be pushed again.
 */
@Composable
private fun UnauthorizedAppCard(
    entry: dev.walcott.sync.UnauthorizedApp,
    onRemove: () -> Unit,
    onAllow: () -> Unit,
) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    var answered by remember(entry.pkg) { mutableStateOf(false) }
    WalcottCard(color = color.copy(alpha = 0.12f)) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.unauthorized_app_card_title, entry.label.ifBlank { entry.pkg }),
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                    )
                    Text(
                        entry.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(
                    // Suspension is the promise being made on this screen, so a device that
                    // could not deliver it says so instead of implying the app is harmless now.
                    if (entry.suspended) R.string.unauthorized_app_card_state
                    else R.string.unauthorized_app_card_state_unsuspended,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (answered) {
                Text(
                    stringResource(R.string.remote_command_sent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Button(
                        onClick = { answered = true; onRemove() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.unauthorized_app_remove)) }
                    OutlinedButton(
                        onClick = { answered = true; onAllow() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.unauthorized_app_allow)) }
                }
            }
        }
    }
}

/** The self-test caught apps that should be suspended but aren't — the silent failure class. */
@Composable
private fun EnforcementGapCard(count: Int) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    WalcottCard(color = color.copy(alpha = 0.12f)) {
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

/**
 * The child started an emergency release: unless the parent refuses, the device frees itself
 * once the countdown completes. Shows how much is left and what refusing costs the child, so
 * the decision is made with the facts (see [dev.walcott.sync.PanicProtocol]).
 */
@Composable
private fun PanicRequestCard(request: PanicRequest, onDeny: () -> Unit) {
    val spacing = Tokens.spacing
    val color = MaterialTheme.colorScheme.error
    val remaining = PanicProtocol.remainingCheckpoints(request)
    val hoursLeft = (remaining * PanicProtocol.CHECKPOINT_INTERVAL_SEC / 3600).toInt()
    var confirming by remember { mutableStateOf(false) }

    WalcottCard(color = color.copy(alpha = 0.12f)) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(spacing.md))
                Text(
                    stringResource(R.string.panic_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                )
            }
            Text(
                if (hoursLeft > 0) {
                    pluralStringResource(R.plurals.panic_card_left, hoursLeft, hoursLeft)
                } else {
                    stringResource(R.string.panic_card_done)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            Text(
                stringResource(R.string.panic_card_explain),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            if (hoursLeft > 0) {
                Button(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.panic_deny_action)) }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.panic_deny_action)) },
            text = { Text(stringResource(R.string.panic_deny_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDeny() }) {
                    Text(stringResource(R.string.panic_deny_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The child's clock disagrees with the sync server far beyond drift — a limits bypass. */
@Composable
private fun ClockTamperCard(skewMs: Long) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.error
    WalcottCard(color = color.copy(alpha = 0.12f)) {
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
    WalcottCard(color = color.copy(alpha = 0.12f)) {
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
private fun RemoteFixCard(snapshot: ChildSnapshot, position: CardPosition = CardPosition.Single, onCommand: (String) -> Unit) {
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

    WalcottCard(position = position) {
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
                // The wrong-app removal names the exact package that was tried; everything
                // else maps through the generic detail table.
                val detailLabel = if (ack.detail == RemoteAction.DETAIL_WRONG_APP_REMOVED && ack.arg.isNotBlank()) {
                    stringResource(R.string.remote_result_wrong_app_pkg, ack.arg)
                } else {
                    remoteResultLabel(context, ack.detail)
                }
                Text(
                    stringResource(
                        if (ack.ok) R.string.remote_command_ok else R.string.remote_command_failed,
                        detailLabel,
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
internal fun remoteResultLabel(context: android.content.Context, detail: String): String = when (detail) {
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
    "wrong_app_removed" -> context.getString(R.string.remote_result_wrong_app)
    "removing" -> context.getString(R.string.remote_result_removing)
    "not_installed" -> context.getString(R.string.remote_result_not_installed)
    "allowed" -> context.getString(R.string.remote_result_allowed)
    "waiting_parent" -> context.getString(R.string.remote_result_waiting_parent)
    "diag_sent" -> context.getString(R.string.remote_result_diag_sent)
    else -> if (detail.contains('_')) context.getString(R.string.remote_result_notified) else detail
}

/**
 * The child's health as of its last check-in — live, not a report. Every row comes from the
 * snapshot the device publishes on every heartbeat, so nothing here carries a date that can go
 * stale while still looking like the truth. The on-demand reports, which are snapshots of a
 * moment and age badly, live behind [dev.walcott.ui.parent.HealthReportsScreen].
 */
@Composable
private fun LiveHealthCard(
    snapshot: ChildSnapshot,
    lastSeenMs: Long,
    nowMs: Long,
    reportCount: Int,
    position: CardPosition = CardPosition.Single,
    onOpenReports: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.health_section), style = MaterialTheme.typography.titleMedium)
            Text(
                if (lastSeenMs > 0) {
                    stringResource(
                        R.string.health_as_of,
                        android.text.format.DateUtils
                            .getRelativeTimeSpanString(
                                lastSeenMs,
                                // A snapshot that lands between ticks is newer than this
                                // screen's clock, and would read as the future (see ageReference).
                                dev.walcott.ui.format.ageReference(lastSeenMs, nowMs),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                            )
                            .toString(),
                    )
                } else {
                    stringResource(R.string.health_never_seen)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            DiagRow(
                label = stringResource(R.string.diag_enforcement),
                value = stringResource(
                    when (snapshot.enforcement) {
                        EnforcementStatus.DEVICE_OWNER -> R.string.diag_enforcement_do
                        EnforcementStatus.ACCESSIBILITY -> R.string.diag_enforcement_accessibility
                        else -> R.string.diag_enforcement_none
                    },
                ),
                ok = snapshot.enforcement == EnforcementStatus.DEVICE_OWNER,
            )
            DiagRow(
                label = stringResource(R.string.diag_usage_access),
                value = stringResource(if (snapshot.usageAccessOn) R.string.summary_on else R.string.summary_off),
                ok = snapshot.usageAccessOn,
            )
            DiagRow(
                label = stringResource(R.string.diag_network_location),
                value = stringResource(if (snapshot.networkLocationOn) R.string.summary_on else R.string.summary_off),
                ok = snapshot.networkLocationOn,
            )
            // Only when the rules ask for one: a family that filters nothing has no tunnel to
            // miss, and a row reading "off" would read as something broken.
            if (snapshot.webFilterExpected) {
                DiagRow(
                    label = stringResource(R.string.diag_web_filter),
                    value = stringResource(if (snapshot.webFilterOn) R.string.summary_on else R.string.summary_off),
                    ok = snapshot.webFilterOn,
                )
            }
            if (snapshot.batteryPercent in 0..100) {
                DiagRow(
                    label = stringResource(R.string.diag_battery),
                    value = stringResource(
                        if (snapshot.charging) R.string.diag_battery_charging else R.string.diag_battery_value,
                        snapshot.batteryPercent,
                    ),
                    ok = snapshot.charging || snapshot.batteryPercent >= LOW_BATTERY_PERCENT,
                )
            }
            if (snapshot.appVersionCode > 0) {
                DiagRow(
                    label = stringResource(R.string.diag_version),
                    value = stringResource(
                        R.string.diag_version_value,
                        snapshot.appVersionName,
                        snapshot.appVersionCode,
                    ),
                    // Live, so today's build IS the right yardstick — unlike a dated report.
                    ok = snapshot.appVersionCode >= BuildConfig.VERSION_CODE,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = spacing.sm))
            Surface(
                onClick = onOpenReports,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(vertical = spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (reportCount > 0) {
                                pluralStringResource(R.plurals.health_reports_count, reportCount, reportCount)
                            } else {
                                stringResource(R.string.diag_section)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.health_reports_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
    WalcottCard {
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
    WalcottCard {
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
                // Always shown, so the inherited value is visible as the child actually gets it;
                // only customizing hands over the control.
                Switch(checked = value, enabled = customized, onCheckedChange = { onSetOverride(it) })
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
    /** Whether [onEdit] opens the rules to change them, or just to look at what is inherited. */
    editable: Boolean = checked,
    position: CardPosition = CardPosition.Single,
    /** What this child gets today, and what the family says. The row states both. */
    childValue: String? = null,
    familyValue: String? = null,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Row(Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                // The values, not a description of the mechanism. A switch labelled
                // "Customized" told the parent that something differed and never what — so
                // the only way to see what this child was actually getting was to turn the
                // override off and watch the numbers change, which changes the rules.
                //
                // An override is a snapshot, not a link: once on it stops following later
                // family edits. That matters most in the case that otherwise reads as
                // harmless — identical values — so that is where it is said out loud.
                val subtitle = when {
                    childValue == null || familyValue == null -> stringResource(
                        if (checked) R.string.override_customized_hint else R.string.override_inherited_row_hint,
                    )
                    !checked -> stringResource(R.string.override_row_inherited, familyValue)
                    childValue == familyValue -> stringResource(R.string.override_row_same, childValue)
                    else -> stringResource(R.string.override_row_custom, childValue, familyValue)
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onEdit != null) {
                TextButton(onClick = onEdit) {
                    Text(stringResource(if (editable) R.string.action_edit else R.string.action_view))
                }
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
