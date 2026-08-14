package dev.walcott.ui.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.rules.TimeWindow
import dev.walcott.ui.AppStatusUi
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import java.time.Duration
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything the rules say about this phone today, for the child living under them.
 *
 * The home was rebuilt around what is about to run out, which is the right thing for it to be
 * and left nowhere to answer the other question a child has: what are my rules, and how much do
 * I have in the apps that are NOT about to close? That question does not deserve a place on the
 * home — asking it is rare next to being told — but it does deserve an answer, and until now it
 * had none: the only complete list lived inside the "ask for more time" picker, which is a place
 * you go to ask for something, not to look something up.
 *
 * Read-only on purpose. A child cannot change any of this, and a screen that looked editable
 * would be offering something it cannot deliver.
 */
@Composable
fun ChildRulesScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val state by viewModel.childState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.child_rules_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { SectionHeader(stringResource(R.string.child_rules_general)) }

            // The standing rules, in the order they bite: the whole phone first, then per app.
            val bedtime = state.bedtimeTonight
            if (bedtime == null && state.screenFreeToday.isEmpty() && state.defaultBudget == null) {
                item {
                    Text(
                        stringResource(R.string.child_rules_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            bedtime?.let { window ->
                item {
                    RuleCard(
                        icon = Icons.Filled.Bedtime,
                        title = stringResource(R.string.bedtime_title),
                        detail = stringResource(R.string.window_range, window.start.hhmm(), window.end.hhmm()),
                    )
                }
            }
            items(state.screenFreeToday, key = { "sf-${it.start}-${it.end}-${it.days}" }) { window ->
                RuleCard(
                    icon = Icons.Outlined.DoNotDisturbOn,
                    title = stringResource(R.string.all_apps_windows_title),
                    detail = stringResource(R.string.window_range, window.start.hhmm(), window.end.hhmm()),
                    // Which days, spelled out: a window that only bites on school days is a very
                    // different rule from one that bites every day, and the times alone hide that.
                    footnote = daysLabel(window),
                )
            }
            state.defaultBudget?.let { budget ->
                item {
                    RuleCard(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.default_budget_title),
                        detail = stringResource(R.string.home_limit_default, budget.humanize()),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.child_rules_apps)) }
            if (state.apps.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.child_rules_no_apps),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Every app with a limit today, not only the ones running out — this list IS the
            // answer the home deliberately stopped giving.
            items(state.apps, key = { "app-${it.packageName}" }) { app ->
                AppLimitRow(app, viewModel)
            }
            item { Spacer(Modifier.navigationBarsPadding().padding(bottom = spacing.xl)) }
        }
    }
}

/** One standing rule: what it is, when it applies, and — when it matters — on which days. */
@Composable
private fun RuleCard(icon: ImageVector, title: String, detail: String, footnote: String? = null) {
    val spacing = Tokens.spacing
    WalcottCard {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                footnote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * One app's limit and where it stands: "45m of 1h", plus what is left.
 *
 * Both halves, because either alone leaves a question. The limit without the spend does not say
 * whether there is any left; the time left without the limit does not say whether that is a lot.
 */
@Composable
private fun AppLimitRow(app: AppStatusUi, viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    WalcottCard {
        Row(
            Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName, viewModel.repository.inventory, size = 32.dp)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // "20m of 1h used" — where the total is the allowance ACTUALLY in force, which
                // is the base limit plus anything a parent has granted today. Showing the base
                // limit instead reads as a contradiction the moment there is extra time: a child
                // with an hour's limit, an hour granted and 3h20m spent saw "3h 20m of 1h used"
                // beside "9h 14m left". Blocked apps drop the total: they have spent whatever
                // they had, and the pill beside says so.
                val allowance = app.remaining?.let { app.used + it }
                Text(
                    if (allowance == null) {
                        stringResource(R.string.child_rules_used, app.used.humanize())
                    } else {
                        stringResource(R.string.child_rules_used_of, app.used.humanize(), allowance.humanize())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            if (app.blocked) {
                Text(
                    stringResource(R.string.status_blocked),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    (app.remaining ?: Duration.ZERO).humanize(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** "Mon, Tue, Wed" — or nothing at all when the window applies every day and there is no news. */
@Composable
private fun daysLabel(window: TimeWindow): String? {
    if (window.days.isEmpty()) return null
    val locale = Locale.getDefault()
    return window.days.sortedBy { it.value }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
}
