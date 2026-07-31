package dev.walcott.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.sync.DeviceMode
import dev.walcott.sync.SyncNotifications
import dev.walcott.ui.child.ChildStatusScreen
import dev.walcott.ui.child.PanicScreen
import dev.walcott.data.PolicySettings
import dev.walcott.ui.parent.AppAssignScreen
import dev.walcott.ui.parent.AppDetailScreen
import dev.walcott.ui.parent.ActivityScreen
import dev.walcott.ui.parent.AppSettingsScreen
import dev.walcott.ui.parent.LocationSettingsScreen
import dev.walcott.ui.parent.BudgetsScreen
import dev.walcott.ui.parent.CalendarScreen
import dev.walcott.ui.parent.ChildDetailScreen
import dev.walcott.ui.parent.ChildrenScreen
import dev.walcott.ui.parent.DebugLogScreen
import dev.walcott.ui.parent.DeviceProtectionScreen
import dev.walcott.ui.parent.EarnRulesScreen
import dev.walcott.ui.parent.FamiliesScreen
import dev.walcott.ui.parent.DomainMonitorScreen
import dev.walcott.ui.parent.DomainReviewScreen
import dev.walcott.ui.parent.HealthReportsScreen
import dev.walcott.ui.parent.MapScreen
import dev.walcott.ui.parent.ParentHomeScreen
import dev.walcott.ui.parent.PinGateScreen
import dev.walcott.ui.parent.SetupPreset
import dev.walcott.ui.parent.SetupPresetChooserScreen
import dev.walcott.ui.parent.SetupWizardScreen
import dev.walcott.ui.parent.WebFilterScreen
import dev.walcott.ui.parent.WeeklyReportScreen

/** Child name for the override-scope banner, or null when editing the family policy. */
private fun overrideChildName(settings: PolicySettings, childId: String?): String? =
    childId?.let { id -> settings.children.firstOrNull { it.childId == id }?.name }

private enum class Screen {
    MODE_SELECT, CHILD, GATE, FAMILIES, SETUP_PRESETS, SETUP_WIZARD, FAMILY, CHILD_DETAIL, CHILD_MAP,
    APPS, APP_DETAIL, BUDGETS, CHILDREN, EARN, CALENDAR, REPORT, WEBFILTER, PROTECTION, LOCATION,
    APP_SETTINGS, DEBUG_LOGS, PANIC, ACTIVITY, CHILD_HEALTH, DOMAIN_MONITOR, DOMAIN_REVIEW,
}

@Composable
fun WalcottApp(
    viewModel: WalcottViewModel,
    deviceOwner: Boolean,
    startDest: String? = null,
    onDestConsumed: () -> Unit = {},
) {
    val bootMode by viewModel.bootMode.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installExemption by viewModel.installExemption.collectAsStateWithLifecycle()

    // Hold rendering until the persisted identity loads, so existing installs
    // don't flash the mode selector.
    val loadedMode = bootMode
    if (loadedMode == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    var screen by remember {
        mutableStateOf(
            when (loadedMode) {
                DeviceMode.PARENT -> Screen.FAMILIES
                DeviceMode.CHILD -> Screen.CHILD
                DeviceMode.UNSET -> Screen.MODE_SELECT
            },
        )
    }
    var childDetailId by remember { mutableStateOf<String?>(null) }
    // Which domain request the parent is reviewing (DOMAIN_REVIEW screen).
    var domainBatchId by remember { mutableStateOf<String?>(null) }
    // Where "Special days" was opened from, so Back lands on the screen that sent the parent
    // there instead of the hub. Null = reached from the hub itself.
    var calendarReturnTo by remember { mutableStateOf<Screen?>(null) }
    // Same for "Limits and schedules" and the child map: both are reachable straight from the
    // home now, and Back must land where the parent came from. Null = the historical origin
    // (the hub for budgets, the child detail for the map).
    var budgetsReturnTo by remember { mutableStateOf<Screen?>(null) }
    var mapReturnTo by remember { mutableStateOf<Screen?>(null) }
    // Which guided-setup preset is running (SETUP_WIZARD screen).
    var wizardPreset by remember { mutableStateOf<SetupPreset?>(null) }
    // When set, EARN/WEBFILTER/PROTECTION edit this child's override instead of the family
    // policy, and back returns to the child detail.
    var overrideChildId by remember { mutableStateOf<String?>(null) }
    // Selected package for the per-app detail screen (Apps & categories).
    var appDetailPkg by remember { mutableStateOf<String?>(null) }
    // Only the parent's own initial setup may CREATE a PIN at the gate; a child never can.
    var gateAllowCreate by remember { mutableStateOf(false) }
    val parentMode = identity.effectiveMode == DeviceMode.PARENT

    // A notification deep-link (e.g. "new app installed" -> Apps). Honored in parent mode only;
    // the child's settings live behind the PIN gate, so we never jump a child straight in.
    // Keyed on settings too: a child-detail dest may arrive before the registry has loaded.
    LaunchedEffect(startDest, parentMode, settings) {
        if (startDest == SyncNotifications.DEST_APPS && parentMode) {
            screen = Screen.APPS
            onDestConsumed()
        }
        if (startDest == SyncNotifications.DEST_APP_SETTINGS && parentMode) {
            screen = Screen.APP_SETTINGS
            onDestConsumed()
        }
        // Health alerts land on the affected child, so the message behind the notification
        // (and the matching feed entry) is right there instead of a bare home screen.
        if (startDest?.startsWith(SyncNotifications.DEST_CHILD_PREFIX) == true && parentMode) {
            val childId = startDest.removePrefix(SyncNotifications.DEST_CHILD_PREFIX)
            if (settings.children.any { it.childId == childId }) {
                childDetailId = childId
                screen = Screen.CHILD_DETAIL
                onDestConsumed()
            }
        }
    }

    // Parent app lock: gate the whole app behind the PIN/biometrics on open and re-lock
    // when it leaves the foreground. Toggling the setting on mid-session must not lock
    // the current session, so an unlocked state is assumed whenever the lock is off.
    val appLockOn = parentMode && identity.appLock
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(appLockOn) { if (!appLockOn) unlocked = true }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-key on appLockOn so the observer captures the current value, not the one from
    // the composition where it was first registered.
    DisposableEffect(lifecycleOwner, appLockOn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appLockOn) unlocked = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A child device's PIN-gated screens (the local settings hub and everything behind it)
    // must not survive a trip to the background: the parent unlocks, hands the phone back,
    // and reopening the app would land the child straight in the settings. Snap back to the
    // child home on ON_STOP, so the gate asks for the PIN again.
    val childDevice = identity.effectiveMode == DeviceMode.CHILD
    DisposableEffect(lifecycleOwner, childDevice) {
        val observer = LifecycleEventObserver { _, event ->
            // PANIC is exempt: it is the one child screen that is NOT behind the PIN, so
            // there is nothing to protect by kicking the child out of it.
            if (event == Lifecycle.Event.ON_STOP && childDevice &&
                screen != Screen.CHILD && screen != Screen.MODE_SELECT && screen != Screen.PANIC
            ) {
                screen = Screen.CHILD
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (appLockOn && !unlocked) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                AppLockScreen(viewModel, onUnlocked = { unlocked = true })
            }
        }
        return
    }

    fun back() {
        screen = when (screen) {
            Screen.WEBFILTER, Screen.PROTECTION ->
                if (overrideChildId != null) {
                    overrideChildId = null
                    Screen.CHILD_DETAIL
                } else {
                    Screen.FAMILY
                }
            Screen.CALENDAR -> calendarReturnTo?.also { calendarReturnTo = null } ?: Screen.FAMILY
            Screen.BUDGETS -> budgetsReturnTo?.also { budgetsReturnTo = null } ?: Screen.FAMILY
            Screen.APPS, Screen.CHILDREN, Screen.EARN,
            Screen.REPORT, Screen.LOCATION,
            -> Screen.FAMILY
            // Reached from the home gear on the parent, from the device hub on a child.
            Screen.APP_SETTINGS -> if (parentMode) Screen.FAMILIES else Screen.FAMILY
            Screen.DEBUG_LOGS -> Screen.APP_SETTINGS
            Screen.APP_DETAIL -> Screen.APPS
            Screen.SETUP_PRESETS -> Screen.FAMILIES
            Screen.SETUP_WIZARD -> Screen.SETUP_PRESETS
            Screen.CHILD_DETAIL -> Screen.FAMILIES
            Screen.CHILD_MAP -> mapReturnTo?.also { mapReturnTo = null } ?: Screen.CHILD_DETAIL
            Screen.CHILD_HEALTH -> Screen.CHILD_DETAIL
            Screen.DOMAIN_MONITOR -> Screen.FAMILY
            Screen.DOMAIN_REVIEW -> Screen.FAMILIES
            Screen.PANIC -> Screen.CHILD
            Screen.ACTIVITY -> Screen.FAMILIES
            Screen.FAMILY, Screen.GATE -> if (parentMode) Screen.FAMILIES else Screen.CHILD
            else -> screen
        }
    }

    val isHome = screen == Screen.MODE_SELECT || screen == Screen.CHILD || screen == Screen.FAMILIES
    BackHandler(enabled = !isHome) { back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(220)) { w -> dir * w / 6 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(220)) { w -> -dir * w / 6 } + fadeOut(tween(140)))
                },
                label = "nav",
            ) { current ->
                when (current) {
                    Screen.MODE_SELECT -> ModeSelectScreen(
                        viewModel,
                        onParentCreated = { gateAllowCreate = true; screen = Screen.GATE },
                        onChildSelected = { screen = Screen.CHILD },
                    )
                    Screen.CHILD -> ChildStatusScreen(
                        viewModel,
                        deviceOwner = deviceOwner,
                        onOpenParent = { gateAllowCreate = false; screen = Screen.GATE },
                        onOpenPanic = { screen = Screen.PANIC },
                    )
                    // Reachable without the PIN on purpose: it exists for the family that lost
                    // both the parent phone and the PIN (see PanicProtocol).
                    Screen.PANIC -> PanicScreen(viewModel, onBack = { screen = Screen.CHILD })
                    Screen.GATE -> PinGateScreen(
                        viewModel,
                        onUnlocked = {
                            screen = when {
                                !parentMode -> Screen.FAMILY
                                // Fresh family: offer the guided setup right after the PIN.
                                gateAllowCreate -> Screen.SETUP_PRESETS
                                else -> Screen.FAMILIES
                            }
                        },
                        onBack = ::back,
                        allowCreate = gateAllowCreate,
                    )
                    Screen.FAMILIES -> FamiliesScreen(
                        viewModel,
                        onOpenFamily = { screen = Screen.FAMILY },
                        onOpenChild = { childId ->
                            childDetailId = childId
                            screen = Screen.CHILD_DETAIL
                        },
                        onOpenAppSettings = { screen = Screen.APP_SETTINGS },
                        onOpenApps = { screen = Screen.APPS },
                        onOpenBudgets = { budgetsReturnTo = Screen.FAMILIES; screen = Screen.BUDGETS },
                        onOpenGuidedSetup = { screen = Screen.SETUP_PRESETS },
                        onOpenActivity = { screen = Screen.ACTIVITY },
                        onOpenDomainRequest = { batchId ->
                            domainBatchId = batchId
                            screen = Screen.DOMAIN_REVIEW
                        },
                        onOpenCalendar = { calendarReturnTo = Screen.FAMILIES; screen = Screen.CALENDAR },
                        onOpenMap = { childId ->
                            childDetailId = childId
                            mapReturnTo = Screen.FAMILIES
                            screen = Screen.CHILD_MAP
                        },
                    )
                    Screen.ACTIVITY -> ActivityScreen(
                        viewModel,
                        onOpenChild = { childId ->
                            childDetailId = childId
                            screen = Screen.CHILD_DETAIL
                        },
                        onBack = ::back,
                    )
                    Screen.SETUP_PRESETS -> SetupPresetChooserScreen(
                        onPick = { preset ->
                            wizardPreset = preset
                            screen = Screen.SETUP_WIZARD
                        },
                        onSkip = { screen = Screen.FAMILIES },
                    )
                    Screen.SETUP_WIZARD -> wizardPreset?.let { preset ->
                        SetupWizardScreen(
                            viewModel,
                            preset = preset,
                            onDone = { screen = Screen.FAMILIES },
                            onExit = { screen = Screen.FAMILIES },
                        )
                    }
                    Screen.CHILD_DETAIL -> childDetailId?.let { childId ->
                        ChildDetailScreen(
                            viewModel,
                            childId,
                            onBack = ::back,
                            onOpenMap = {
                                childDetailId = it
                                screen = Screen.CHILD_MAP
                            },
                            onOpenHealthReports = { screen = Screen.CHILD_HEALTH },
                            onEditWebFilter = { overrideChildId = childId; screen = Screen.WEBFILTER },
                            onEditProtection = { overrideChildId = childId; screen = Screen.PROTECTION },
                            onOpenSpecialDays = {
                                calendarReturnTo = Screen.CHILD_DETAIL
                                screen = Screen.CALENDAR
                            },
                        )
                    }
                    Screen.CHILD_MAP -> childDetailId?.let { childId ->
                        MapScreen(viewModel, childId, onBack = ::back)
                    }
                    Screen.CHILD_HEALTH -> childDetailId?.let { childId ->
                        HealthReportsScreen(viewModel, childId, onBack = ::back)
                    }
                    Screen.DOMAIN_MONITOR -> DomainMonitorScreen(viewModel, onBack = ::back)
                    Screen.DOMAIN_REVIEW -> domainBatchId?.let { batchId ->
                        DomainReviewScreen(viewModel, batchId, onBack = ::back)
                    }
                    Screen.FAMILY -> ParentHomeScreen(
                        viewModel = viewModel,
                        title = if (parentMode) {
                            settings.familyName.ifBlank { stringResource(R.string.family_default_name) }
                        } else {
                            stringResource(R.string.device_settings_title)
                        },
                        deviceOwner = deviceOwner,
                        childDevice = !parentMode,
                        onOpenApps = { screen = Screen.APPS },
                        onOpenBudgets = { screen = Screen.BUDGETS },
                        onOpenChildren = { screen = Screen.CHILDREN },
                        onOpenEarn = { overrideChildId = null; screen = Screen.EARN },
                        onOpenCalendar = { screen = Screen.CALENDAR },
                        onOpenReport = { screen = Screen.REPORT },
                        onOpenWebFilter = { overrideChildId = null; screen = Screen.WEBFILTER },
                        onOpenDomainMonitor = { screen = Screen.DOMAIN_MONITOR },
                        onOpenProtection = { overrideChildId = null; screen = Screen.PROTECTION },
                        onOpenLocation = { screen = Screen.LOCATION },
                        onOpenAppSettings = { screen = Screen.APP_SETTINGS },
                        onOpenGuidedSetup = { screen = Screen.SETUP_PRESETS },
                        onBack = ::back,
                    )
                    Screen.APPS -> AppAssignScreen(
                        viewModel,
                        onBack = { screen = Screen.FAMILY },
                        onOpenApp = { pkg -> appDetailPkg = pkg; screen = Screen.APP_DETAIL },
                    )
                    Screen.APP_DETAIL -> appDetailPkg?.let { pkg ->
                        AppDetailScreen(
                            viewModel,
                            packageName = pkg,
                            onBack = ::back,
                            onOpenWebFilter = { overrideChildId = null; screen = Screen.WEBFILTER },
                            onOpenSpecialDays = { calendarReturnTo = Screen.APP_DETAIL; screen = Screen.CALENDAR },
                        )
                    }
                    Screen.BUDGETS -> BudgetsScreen(
                        viewModel,
                        onBack = ::back,
                        onOpenSpecialDays = { calendarReturnTo = Screen.BUDGETS; screen = Screen.CALENDAR },
                    )
                    Screen.CHILDREN -> ChildrenScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.EARN -> EarnRulesScreen(
                        viewModel,
                        onBack = { screen = Screen.FAMILY },
                        onOpenSpecialDays = { calendarReturnTo = Screen.EARN; screen = Screen.CALENDAR },
                    )
                    Screen.CALENDAR -> CalendarScreen(viewModel, onBack = ::back)
                    Screen.REPORT -> WeeklyReportScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.WEBFILTER -> WebFilterScreen(
                        viewModel, onBack = ::back,
                        childId = overrideChildId, childName = overrideChildName(settings, overrideChildId),
                    )
                    Screen.PROTECTION -> DeviceProtectionScreen(
                        viewModel, onBack = ::back,
                        childId = overrideChildId, childName = overrideChildName(settings, overrideChildId),
                    )
                    Screen.LOCATION -> LocationSettingsScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.APP_SETTINGS -> AppSettingsScreen(
                        viewModel = viewModel,
                        deviceOwner = deviceOwner,
                        childDevice = !parentMode,
                        installsBlocked = DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions,
                        installExemptionUntil = installExemption,
                        onAllowInstalls = { durationMs -> viewModel.allowInstallsFor(durationMs) },
                        onEndInstallWindow = { viewModel.endInstallExemption() },
                        onOpenDebugLogs = { screen = Screen.DEBUG_LOGS },
                        onChangeMode = {
                            viewModel.resetDeviceMode()
                            screen = Screen.MODE_SELECT
                        },
                        onReleased = { screen = Screen.MODE_SELECT },
                        onBack = ::back,
                    )
                    Screen.DEBUG_LOGS -> DebugLogScreen(onBack = ::back)
                }
            }
        }
    }
}
