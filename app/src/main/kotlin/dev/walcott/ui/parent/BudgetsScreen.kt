package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.Tokens

@Composable
fun BudgetsScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
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
                    BedtimeCard(settings.bedtime, position = CardPosition.First, onChange = viewModel::setBedtime)
                    BlockedWindowsCard(
                        title = stringResource(R.string.all_apps_windows_title),
                        hint = stringResource(R.string.all_apps_windows_hint),
                        windows = settings.allAppsBlockedWindows[dev.walcott.rules.DayType.SCHOOL.name].orEmpty(),
                        position = CardPosition.Last,
                        onChange = viewModel::setAllAppsWindows,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.daily_budget_header)) }
            item {
                val categories = AppCategory.entries.toList()
                CardGroup {
                    categories.forEachIndexed { index, category ->
                        CategoryBudgetCard(
                            category = category,
                            perDay = settings.budgets[category.id].orEmpty(),
                            position = cardPosition(index, categories.size),
                            onSetBudget = { dayType, minutes -> viewModel.setBudget(category.id, dayType, minutes) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}
