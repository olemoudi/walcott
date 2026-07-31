package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.Tokens

@Composable
fun BudgetsScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    onOpenSpecialDays: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_limits_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { SectionHeader(stringResource(R.string.budgets_section_schedules)) }
            item {
                CardGroup {
                    BedtimeCard(
                        bedtime = settings.bedtime,
                        position = CardPosition.First,
                        specialDaysOwnRules = settings.specialDaysOwnRules,
                        onOpenSpecialDays = onOpenSpecialDays,
                        onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                        onChange = viewModel::setBedtime,
                    )
                    BlockedWindowsCard(
                        title = stringResource(R.string.all_apps_windows_title),
                        hint = stringResource(R.string.all_apps_windows_hint),
                        windowsByDay = settings.allAppsBlockedWindows,
                        position = CardPosition.Last,
                        specialDaysOwnRules = settings.specialDaysOwnRules,
                        onOpenSpecialDays = onOpenSpecialDays,
                        onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                        onChange = viewModel::setAllAppsWindows,
                    )
                }
            }
            // The optional default: one number, applied to each app on its own counter. Off
            // unless the family asks for it, which is what keeps a newly installed app free of
            // limits nobody chose for it.
            item {
                SectionHeader(
                    stringResource(R.string.daily_budget_header),
                    supporting = stringResource(R.string.default_budget_hint),
                )
            }
            item {
                DailyBudgetCard(
                    title = stringResource(R.string.default_budget_title),
                    icon = Icons.Outlined.Apps,
                    perDay = settings.defaultAppBudget,
                    specialDaysOwnRules = settings.specialDaysOwnRules,
                    onOpenSpecialDays = onOpenSpecialDays,
                    onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                    onSetBudget = { dayType, minutes -> viewModel.setDefaultBudget(dayType, minutes) },
                )
            }
            // Per-app limits are the everyday instrument, so the way to them is on this screen
            // rather than buried in the settings hub.
            item {
                NavCard(
                    Icons.Outlined.Apps,
                    stringResource(R.string.nav_apps_title),
                    stringResource(R.string.nav_apps_subtitle),
                    onOpenApps,
                )
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}
