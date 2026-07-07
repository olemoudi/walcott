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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.DeviceMode
import dev.walcott.ui.child.ChildStatusScreen
import dev.walcott.ui.parent.AppAssignScreen
import dev.walcott.ui.parent.BudgetsScreen
import dev.walcott.ui.parent.CalendarScreen
import dev.walcott.ui.parent.ChildDetailScreen
import dev.walcott.ui.parent.ChildrenScreen
import dev.walcott.ui.parent.DeviceProtectionScreen
import dev.walcott.ui.parent.EarnRulesScreen
import dev.walcott.ui.parent.FamiliesScreen
import dev.walcott.ui.parent.ParentHomeScreen
import dev.walcott.ui.parent.PinGateScreen
import dev.walcott.ui.parent.WebFilterScreen
import dev.walcott.ui.parent.WeeklyReportScreen

private enum class Screen {
    MODE_SELECT, CHILD, GATE, FAMILIES, FAMILY, CHILD_DETAIL,
    APPS, BUDGETS, CHILDREN, EARN, CALENDAR, REPORT, WEBFILTER, PROTECTION,
}

@Composable
fun WalcottApp(viewModel: WalcottViewModel, deviceOwner: Boolean) {
    val bootMode by viewModel.bootMode.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
    val parentMode = identity.effectiveMode == DeviceMode.PARENT

    fun back() {
        screen = when (screen) {
            Screen.APPS, Screen.BUDGETS, Screen.CHILDREN,
            Screen.EARN, Screen.CALENDAR, Screen.REPORT, Screen.WEBFILTER, Screen.PROTECTION,
            -> Screen.FAMILY
            Screen.CHILD_DETAIL -> Screen.FAMILIES
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
                        onParentCreated = { screen = Screen.GATE },
                        onChildSelected = { screen = Screen.CHILD },
                    )
                    Screen.CHILD -> ChildStatusScreen(viewModel, onOpenParent = { screen = Screen.GATE })
                    Screen.GATE -> PinGateScreen(
                        viewModel,
                        onUnlocked = { screen = if (parentMode) Screen.FAMILIES else Screen.FAMILY },
                        onBack = ::back,
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
                        ChildDetailScreen(viewModel, childId, onBack = ::back)
                    }
                    Screen.FAMILY -> ParentHomeScreen(
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
                        onChangeMode = {
                            viewModel.resetDeviceMode()
                            screen = Screen.MODE_SELECT
                        },
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
                }
            }
        }
    }
}
