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
import dev.walcott.ui.child.ChildStatusScreen
import dev.walcott.ui.parent.AppAssignScreen
import dev.walcott.ui.parent.BudgetsScreen
import dev.walcott.ui.parent.CalendarScreen
import dev.walcott.ui.parent.ChildDetailScreen
import dev.walcott.ui.parent.ChildrenScreen
import dev.walcott.ui.parent.DebugLogScreen
import dev.walcott.ui.parent.DeviceProtectionScreen
import dev.walcott.ui.parent.EarnRulesScreen
import dev.walcott.ui.parent.FamiliesScreen
import dev.walcott.ui.parent.MapScreen
import dev.walcott.ui.parent.ParentHomeScreen
import dev.walcott.ui.parent.PinGateScreen
import dev.walcott.ui.parent.WebFilterScreen
import dev.walcott.ui.parent.WeeklyReportScreen

private enum class Screen {
    MODE_SELECT, CHILD, GATE, FAMILIES, FAMILY, CHILD_DETAIL, CHILD_MAP,
    APPS, BUDGETS, CHILDREN, EARN, CALENDAR, REPORT, WEBFILTER, PROTECTION, DEBUG_LOGS,
}

@Composable
fun WalcottApp(viewModel: WalcottViewModel, deviceOwner: Boolean) {
    val bootMode by viewModel.bootMode.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installExemption by viewModel.installExemption.collectAsStateWithLifecycle()

    // Hold rendering until the persisted identity loads, so existing installs
    // don't flash the mode selector.
    val loadedMode = bootMode
    if (loadedMode == null) {
        Surface(Modifier.fillMaxSize()) {}
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
    // Only the parent's own initial setup may CREATE a PIN at the gate; a child never can.
    var gateAllowCreate by remember { mutableStateOf(false) }
    val parentMode = identity.effectiveMode == DeviceMode.PARENT

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
    if (appLockOn && !unlocked) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                AppLockScreen(viewModel, onUnlocked = { unlocked = true })
            }
        }
        return
    }

    fun back() {
        screen = when (screen) {
            Screen.APPS, Screen.BUDGETS, Screen.CHILDREN,
            Screen.EARN, Screen.CALENDAR, Screen.REPORT, Screen.WEBFILTER, Screen.PROTECTION,
            Screen.DEBUG_LOGS,
            -> Screen.FAMILY
            Screen.CHILD_DETAIL -> Screen.FAMILIES
            Screen.CHILD_MAP -> Screen.CHILD_DETAIL
            Screen.FAMILY, Screen.GATE -> if (parentMode) Screen.FAMILIES else Screen.CHILD
            else -> screen
        }
    }

    val isHome = screen == Screen.MODE_SELECT || screen == Screen.CHILD || screen == Screen.FAMILIES
    BackHandler(enabled = !isHome) { back() }

    Surface(Modifier.fillMaxSize()) {
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
                    )
                    Screen.GATE -> PinGateScreen(
                        viewModel,
                        onUnlocked = { screen = if (parentMode) Screen.FAMILIES else Screen.FAMILY },
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
                    )
                    Screen.CHILD_DETAIL -> childDetailId?.let { childId ->
                        ChildDetailScreen(
                            viewModel,
                            childId,
                            onBack = ::back,
                            onOpenMap = {
                                childDetailId = it
                                screen = Screen.CHILD_MAP
                            },
                        )
                    }
                    Screen.CHILD_MAP -> childDetailId?.let { childId ->
                        MapScreen(viewModel, childId, onBack = ::back)
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
                        onOpenEarn = { screen = Screen.EARN },
                        onOpenCalendar = { screen = Screen.CALENDAR },
                        onOpenReport = { screen = Screen.REPORT },
                        onOpenWebFilter = { screen = Screen.WEBFILTER },
                        onOpenProtection = { screen = Screen.PROTECTION },
                        onOpenDebugLogs = { screen = Screen.DEBUG_LOGS },
                        onChangeMode = {
                            viewModel.resetDeviceMode()
                            screen = Screen.MODE_SELECT
                        },
                        installsBlocked = DeviceRestrictions.KEY_INSTALLS in settings.deviceRestrictions,
                        installExemptionUntil = installExemption,
                        onAllowInstalls = { viewModel.allowInstallsTemporarily() },
                        onBack = ::back,
                    )
                    Screen.APPS -> AppAssignScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.BUDGETS -> BudgetsScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.CHILDREN -> ChildrenScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.EARN -> EarnRulesScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.CALENDAR -> CalendarScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.REPORT -> WeeklyReportScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.WEBFILTER -> WebFilterScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.PROTECTION -> DeviceProtectionScreen(viewModel, onBack = { screen = Screen.FAMILY })
                    Screen.DEBUG_LOGS -> DebugLogScreen(onBack = { screen = Screen.FAMILY })
                }
            }
        }
    }
}
