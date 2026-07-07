package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
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
            item { BedtimeCard(settings.bedtime, onChange = viewModel::setBedtime) }
            item {
                Text(
                    stringResource(R.string.daily_budget_header),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            items(AppCategory.entries.toList(), key = { it.id }) { category ->
                CategoryBudgetCard(
                    category = category,
                    perDay = settings.budgets[category.id].orEmpty(),
                    onSetBudget = { dayType, minutes -> viewModel.setBudget(category.id, dayType, minutes) },
                )
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }
}
