package dev.walcott.ui.parent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.UsageEntry
import dev.walcott.sync.UsageLedger
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The periods the report can be read over. Today is a period too — it is the one in progress. */
private enum class Range(val days: Int, val labelRes: Int) {
    TODAY(1, R.string.report_range_today),
    WEEK(7, R.string.report_range_week),
    MONTH(30, R.string.report_range_month),
}

/**
 * Where the family's screen time went, app by app, over the period the parent picks.
 *
 * One shape for all three periods, because "today" and "this month" are the same question asked
 * over different lengths — and answering them differently (a per-app list here, a bare total
 * there) was what made the month unavailable at all. It reads the parent's own ledger rather
 * than the children's live snapshots: a snapshot carries seven days, so a month app by app
 * exists nowhere else (see [UsageLedger.mergeByApp]).
 */
@Composable
fun WeeklyReportScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    /** Straight from "where the time went" to "then give it a limit" (see AppDetailScreen). */
    onOpenApp: (String) -> Unit,
) {
    val spacing = Tokens.spacing
    val ledgers by viewModel.usageByApp.collectAsStateWithLifecycle()
    val childSnapshots by viewModel.children.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Packages are what the counters are keyed by; the children's own app lists name them.
    val apps = remember(childSnapshots) { childSnapshots.flatMap { it.apps }.distinctBy { it.packageName } }

    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    // Null = the whole family. Worth asking only where the answer can differ: with one child,
    // "the family" and "that child" are the same column of numbers.
    var childFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val children = settings.children
    val today = LocalDate.now().toEpochDay()

    // The selected ledgers, added together. Keyed by childId (see UsageLedger.keyOf), so the
    // filter is a lookup rather than a join — and a child with no ledger yet reads as an empty
    // report rather than as the family's.
    val family = remember(ledgers, childFilter) {
        UsageLedger.mergeAcross(ledgers.filterKeys { childFilter == null || it == childFilter }.values)
    }
    val totals = remember(family, range, today) { UsageLedger.totalsByApp(family, today, range.days) }
    val covered = remember(family, range, today) { UsageLedger.daysCovered(family, today, range.days) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_report_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Range.entries.forEach { option ->
                        dev.walcott.ui.components.ChoiceChip(
                            selected = range == option,
                            onClick = { range = option },
                            label = stringResource(option.labelRes),
                        )
                    }
                }
            }
            // Whose time this is. The family's total is the honest default — it is what the
            // screen is titled — but "which of them?" is the next question anybody asks of it,
            // and until now the only answer was the per-child page, which cannot go back a month.
            if (children.size >= 2) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        dev.walcott.ui.components.ChoiceChip(
                            selected = childFilter == null,
                            onClick = { childFilter = null },
                            label = stringResource(R.string.blocks_all_children),
                        )
                        children.forEach { child ->
                            dev.walcott.ui.components.ChoiceChip(
                                selected = childFilter == child.childId,
                                onClick = {
                                    childFilter = child.childId.takeIf { it != childFilter }
                                },
                                label = child.name,
                            )
                        }
                    }
                }
            }

            if (totals.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.report_no_data),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                }
                return@LazyColumn
            }

            // The shape of the period, skipped for today — one bar is not a chart.
            if (range != Range.TODAY) {
                item { DayChart(family, today, range.days) }
            }

            item {
                SectionHeader(
                    stringResource(R.string.report_by_app),
                    supporting = pluralStringResource(R.plurals.report_days_covered, covered, covered),
                    icon = Icons.Outlined.InsertChart,
                    accent = SectionAccent.ACTIVITY,
                )
            }
            val rows = totals.entries.sortedByDescending { it.value }.take(USAGE_ROWS)
            item {
                Column {
                    rows.forEachIndexed { index, (pkg, seconds) ->
                        // Tappable, because the row is half an answer: a parent reading "YouTube,
                        // 6h" is already deciding what to do about YouTube, and the limit for it
                        // was four screens away — through a hub, a list and a search field.
                        // The OTHER bucket is not an app and leads nowhere.
                        val openable = pkg != dev.walcott.sync.UsageReport.OTHER
                        WalcottCard(
                            position = cardPosition(index, rows.size),
                            onClick = if (openable) ({ onOpenApp(pkg) }) else null,
                        ) {
                            Box(Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs)) {
                                UsageRow(UsageEntry(pkg, seconds), apps, viewModel)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(spacing.xl)) }
        }
    }
}

/**
 * One bar per day of the period, tallest day full height. Weekday initials only when there is
 * room for them to be read — a month of labels is a smudge, and the shape is the point.
 */
@Composable
private fun DayChart(family: Map<Long, Map<String, Long>>, todayEpochDay: Long, days: Int) {
    val spacing = Tokens.spacing
    val range = (todayEpochDay - days + 1)..todayEpochDay
    val totals = range.map { day -> family[day]?.values?.sum() ?: 0L }
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    WalcottCard(position = CardPosition.Single) {
        Row(
            Modifier.fillMaxWidth().height(180.dp).padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(if (days > 10) 2.dp else spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            range.forEachIndexed { i, day ->
                DayBar(
                    fraction = totals[i].toFloat() / max,
                    label = if (days > 10) {
                        ""
                    } else {
                        LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayBar(fraction: Float, label: String, modifier: Modifier) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "bar")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(130.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(animated.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}
