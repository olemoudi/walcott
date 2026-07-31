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
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.FoldCard
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.Tokens

@Composable
fun BudgetsScreen(viewModel: WalcottViewModel, onBack: () -> Unit, onOpenSpecialDays: () -> Unit) {
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
            // The general daily limit first: it covers every app without a category of its
            // own, which under the general-first posture is most of the phone.
            item {
                SectionHeader(
                    stringResource(R.string.daily_budget_header),
                    supporting = stringResource(R.string.general_budget_hint),
                )
            }
            item {
                CategoryBudgetCard(
                    category = AppCategory.OTHER,
                    perDay = settings.budgets[AppCategory.OTHER.id].orEmpty(),
                    specialDaysOwnRules = settings.specialDaysOwnRules,
                    onOpenSpecialDays = onOpenSpecialDays,
                    onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                    onSetBudget = { dayType, minutes -> viewModel.setBudget(AppCategory.OTHER.id, dayType, minutes) },
                )
            }
            // Category limits are the opt-in refinement: folded away unless the family
            // already uses them, so the screen doesn't read as six mandatory decisions.
            item {
                val categories = AppCategory.entries.filterNot { it == AppCategory.OTHER }
                var showCategories by rememberSaveable {
                    mutableStateOf(categories.any { !settings.budgets[it.id].isNullOrEmpty() })
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    FoldCard(
                        icon = Icons.Outlined.Apps,
                        title = stringResource(R.string.budgets_categories_fold_title),
                        subtitle = stringResource(R.string.budgets_categories_fold_subtitle),
                        expanded = showCategories,
                        onToggle = { showCategories = !showCategories },
                    )
                    if (showCategories) {
                        CardGroup {
                            categories.forEachIndexed { index, category ->
                                CategoryBudgetCard(
                                    category = category,
                                    perDay = settings.budgets[category.id].orEmpty(),
                                    position = cardPosition(index, categories.size),
                                    specialDaysOwnRules = settings.specialDaysOwnRules,
                                    onOpenSpecialDays = onOpenSpecialDays,
                                    onSetSpecialDaysOwnRules = viewModel::setSpecialDaysOwnRules,
                                    onSetBudget = { dayType, minutes ->
                                        viewModel.setBudget(category.id, dayType, minutes)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}
