package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.data.EarnRuleDto
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens

@Composable
fun EarnRulesScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_earn_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (settings.earnRules.isEmpty()) {
                item { Text(stringResource(R.string.earn_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(settings.earnRules) { index, rule ->
                EarnRuleCard(rule, onDelete = { viewModel.removeEarnRule(index) })
            }
            item { AddEarnRuleCard(onAdd = { viewModel.addEarnRule(it) }) }
        }
    }
}

@Composable
private fun EarnRuleCard(rule: EarnRuleDto, onDelete: () -> Unit) {
    val spacing = Tokens.spacing
    val source = AppCategory.byId(rule.sourceCategoryId)
    val target = AppCategory.byId(rule.targetCategoryId)
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        R.string.earn_rule_summary,
                        rule.sourceMinutesPerReward,
                        source?.let { stringResource(it.nameRes) } ?: rule.sourceCategoryId,
                        rule.rewardMinutes,
                        target?.let { stringResource(it.nameRes) } ?: rule.targetCategoryId,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.earn_cap, rule.dailyCapMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddEarnRuleCard(onAdd: (EarnRuleDto) -> Unit) {
    val spacing = Tokens.spacing
    var source by remember { mutableStateOf(AppCategory.EDUCATION) }
    var target by remember { mutableStateOf(AppCategory.GAMES) }
    var per by remember { mutableStateOf("10") }
    var reward by remember { mutableStateOf("5") }
    var cap by remember { mutableStateOf("30") }

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(stringResource(R.string.earn_source), style = MaterialTheme.typography.labelLarge)
            CategoryChips(selected = source, onSelect = { source = it })
            Text(stringResource(R.string.earn_target), style = MaterialTheme.typography.labelLarge)
            CategoryChips(selected = target, onSelect = { target = it })

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NumberField(stringResource(R.string.earn_per), per, Modifier.weight(1f)) { per = it }
                NumberField(stringResource(R.string.earn_reward), reward, Modifier.weight(1f)) { reward = it }
                NumberField(stringResource(R.string.earn_daily_cap), cap, Modifier.weight(1f)) { cap = it }
            }
            Button(
                onClick = {
                    onAdd(
                        EarnRuleDto(
                            sourceCategoryId = source.id,
                            targetCategoryId = target.id,
                            sourceMinutesPerReward = per.toIntOrNull() ?: 10,
                            rewardMinutes = reward.toIntOrNull() ?: 5,
                            dailyCapMinutes = cap.toIntOrNull() ?: 30,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.earn_add_rule)) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(selected: AppCategory, onSelect: (AppCategory) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(stringResource(category.nameRes)) },
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
