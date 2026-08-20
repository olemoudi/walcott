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
import androidx.compose.material.icons.outlined.Schedule
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
import dev.walcott.data.FamilyRule
import dev.walcott.data.RuleOverrides
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens

@Composable
fun BudgetsScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    onOpenSpecialDays: () -> Unit,
    onOpenApps: () -> Unit,
    /**
     * Opens one member's own rules, for the note that says who is not following a family
     * rule. Null on a phone with nowhere to send them (see [OverriddenNote]).
     */
    onOpenMemberRules: ((String) -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_limits_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                SectionHeader(
                    stringResource(R.string.budgets_section_schedules),
                    icon = Icons.Outlined.Schedule,
                    accent = SectionAccent.RULES,
                )
            }
            // Two tall cards, deliberately NOT connected. The card language joins rows that
            // belong to one thing (2dp apart, corners flattened towards each other); at the
            // height of a whole editor that hairline gap disappears and two separate rules —
            // when the phone sleeps, and when it is put down — read as one wall of controls.
            item {
                BedtimeCard(
                    bedtime = settings.bedtime,
                    specialDaysOwnRules = settings.specialDaysOwnRules,
                    onOpenSpecialDays = onOpenSpecialDays,
                    onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                    overriddenBy = RuleOverrides.namedMembersOverriding(settings, FamilyRule.BEDTIME),
                    onOpenMemberRules = onOpenMemberRules,
                    onChange = viewModel::setBedtime,
                )
            }
            item {
                BlockedWindowsCard(
                    title = stringResource(R.string.all_apps_windows_title),
                    hint = stringResource(R.string.all_apps_windows_hint),
                    windowsByDay = settings.allAppsBlockedWindows,
                    specialDaysOwnRules = settings.specialDaysOwnRules,
                    onOpenSpecialDays = onOpenSpecialDays,
                    onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                    overriddenBy = RuleOverrides.namedMembersOverriding(settings, FamilyRule.SCREEN_FREE),
                    onOpenMemberRules = onOpenMemberRules,
                    onChange = viewModel::setAllAppsWindows,
                )
            }
            // The optional default: one number, applied to each app on its own counter. Off
            // unless the family asks for it, which is what keeps a newly installed app free of
            // limits nobody chose for it.
            item {
                SectionHeader(
                    stringResource(R.string.daily_budget_header),
                    icon = Icons.Outlined.Apps,
                    accent = SectionAccent.RULES,
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
                    overriddenBy = RuleOverrides.namedMembersOverriding(settings, FamilyRule.DEFAULT_BUDGET),
                    onOpenMemberRules = onOpenMemberRules,
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
            item { OverriddenNote(settings, FamilyRule.APP_LIMITS, onOpenMemberRules = onOpenMemberRules) }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}
