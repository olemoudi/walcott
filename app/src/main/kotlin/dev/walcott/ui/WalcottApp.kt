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
import dev.walcott.ui.child.ChildStatusScreen
import dev.walcott.ui.parent.AppAssignScreen
import dev.walcott.ui.parent.BudgetsScreen
import dev.walcott.ui.parent.CalendarScreen
import dev.walcott.ui.parent.ChildSetupScreen
import dev.walcott.ui.parent.ChildrenScreen
import dev.walcott.ui.parent.EarnRulesScreen
import dev.walcott.ui.parent.ParentHomeScreen
import dev.walcott.ui.parent.PinGateScreen
import dev.walcott.ui.parent.WeeklyReportScreen

private enum class Screen {
    CHILD, GATE, PARENT_HOME, APPS, BUDGETS, CHILD_SETUP, CHILDREN, EARN, CALENDAR, REPORT
}

@Composable
fun WalcottApp(viewModel: WalcottViewModel, deviceOwner: Boolean) {
    var screen by remember { mutableStateOf(Screen.CHILD) }

    fun back() {
        screen = when (screen) {
            Screen.APPS, Screen.BUDGETS, Screen.CHILD_SETUP, Screen.CHILDREN,
            Screen.EARN, Screen.CALENDAR, Screen.REPORT,
            -> Screen.PARENT_HOME
            else -> Screen.CHILD
        }
    }

    BackHandler(enabled = screen != Screen.CHILD) { back() }

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
                    Screen.CHILD -> ChildStatusScreen(viewModel, onOpenParent = { screen = Screen.GATE })
                    Screen.GATE -> PinGateScreen(
                        viewModel,
                        onUnlocked = { screen = Screen.PARENT_HOME },
                        onBack = { screen = Screen.CHILD },
                    )
                    Screen.PARENT_HOME -> ParentHomeScreen(
                        deviceOwner = deviceOwner,
                        onOpenApps = { screen = Screen.APPS },
                        onOpenBudgets = { screen = Screen.BUDGETS },
                        onOpenChildren = { screen = Screen.CHILDREN },
                        onOpenChildSetup = { screen = Screen.CHILD_SETUP },
                        onOpenEarn = { screen = Screen.EARN },
                        onOpenCalendar = { screen = Screen.CALENDAR },
                        onOpenReport = { screen = Screen.REPORT },
                        onBack = { screen = Screen.CHILD },
                    )
                    Screen.APPS -> AppAssignScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                    Screen.BUDGETS -> BudgetsScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                    Screen.CHILD_SETUP -> ChildSetupScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                    Screen.CHILDREN -> ChildrenScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                    Screen.EARN -> EarnRulesScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                    Screen.CALENDAR -> CalendarScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                    Screen.REPORT -> WeeklyReportScreen(viewModel, onBack = { screen = Screen.PARENT_HOME })
                }
            }
        }
    }
}
