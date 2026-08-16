package dev.walcott.ui.parent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.data.BlockKinds
import dev.walcott.sync.BlockLedger
import dev.walcott.sync.BlockReports
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens
import java.time.LocalDate

/**
 * The periods blocks can be read over. All time is the odd one out and the reason the ledger
 * has an archive: it is not a longer window, it is everything that ever left the window.
 */
private enum class BlockRange(val days: Int?, val labelRes: Int) {
    TODAY(1, R.string.report_range_today),
    WEEK(7, R.string.report_range_week),
    MONTH(30, R.string.report_range_month),
    ALL(null, R.string.report_range_all),
}

/**
 * What the filter and the rules actually stopped, over the period the parent picks.
 *
 * Two questions on one screen, because they are two halves of the same one. The web filter's
 * count says what the phone was reaching for — which is mostly not the child's doing, and is
 * exactly why the tracker list is interesting. The rules' count says where the child met a wall
 * they can feel: an app out of time, bedtime, a screen-free stretch.
 *
 * Read from the parent's own ledger (see [BlockLedger]), never from the live snapshot: a
 * snapshot carries today, and the month is something only this phone remembers.
 */
@Composable
fun BlockStatsScreen(viewModel: WalcottViewModel, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val ledgers by viewModel.blockLedgers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshots by viewModel.children.collectAsStateWithLifecycle()
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    val labels = remember(snapshots) {
        snapshots.flatMap { it.apps }.associate { it.packageName to it.label }
    }

    var range by rememberSaveable { mutableStateOf(BlockRange.WEEK) }
    // Which child, or the whole family. Keyed like the ledger itself (childId, or the deviceId
    // a legacy child files under).
    var childKey by rememberSaveable { mutableStateOf<String?>(null) }
    val today = LocalDate.now().toEpochDay()

    val ledger = remember(ledgers, childKey) {
        val chosen = if (childKey == null) ledgers.values else listOfNotNull(ledgers[childKey])
        if (chosen.isEmpty()) BlockLedger.Ledger() else BlockLedger.combine(chosen)
    }
    val totals = remember(ledger, range, today) { BlockLedger.totals(ledger, today, range.days) }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_blocks_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    BlockRange.entries.forEach { option ->
                        ChoiceChip(
                            selected = range == option,
                            onClick = { range = option },
                            label = stringResource(option.labelRes),
                        )
                    }
                }
            }
            // One child or all of them. Only worth a row when there is more than one.
            if (settings.children.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        ChoiceChip(
                            selected = childKey == null,
                            onClick = { childKey = null },
                            label = stringResource(R.string.blocks_all_children),
                        )
                        settings.children.forEach { child ->
                            ChoiceChip(
                                selected = childKey == child.childId,
                                onClick = { childKey = child.childId },
                                label = child.name,
                            )
                        }
                    }
                }
            }

            if (totals.isEmpty) {
                item {
                    Text(
                        stringResource(
                            if (settings.hasWebFilter()) R.string.blocks_empty else R.string.blocks_empty_no_filter,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                }
                return@LazyColumn
            }

            // --- The web filter ---
            item {
                SectionHeader(
                    stringResource(R.string.blocks_net_title),
                    icon = Icons.Outlined.Language,
                    accent = SectionAccent.RULES,
                    supporting = pluralStringResource(
                        R.plurals.blocks_net_total,
                        totals.net.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        totals.net,
                    ),
                )
            }
            if (totals.domains.isNotEmpty()) {
                item { BlockBreakdown(stringResource(R.string.blocks_by_domain), totals.domains, labels, viewModel, iconRefresh, icons = false) }
            }
            if (totals.netApps.isNotEmpty()) {
                item { BlockBreakdown(stringResource(R.string.blocks_by_app), totals.netApps, labels, viewModel, iconRefresh, icons = true) }
            }

            // --- The rules ---
            item {
                SectionHeader(
                    stringResource(R.string.blocks_rule_title),
                    icon = Icons.Outlined.Block,
                    accent = SectionAccent.RULES,
                    supporting = pluralStringResource(
                        R.plurals.blocks_rule_total,
                        totals.rule.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        totals.rule,
                    ),
                )
            }
            if (totals.ruleApps.isNotEmpty()) {
                item { BlockBreakdown(null, totals.ruleApps, labels, viewModel, iconRefresh, icons = true) }
            }

            item {
                Text(
                    stringResource(
                        if (range == BlockRange.ALL) R.string.blocks_all_time_note else R.string.blocks_range_note,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.xl),
                )
            }
        }
    }
}

/**
 * One breakdown as rows with a proportional bar. The bar is relative to the biggest row rather
 * than to the total: with a long tail folded into one bucket, a percentage-of-total bar would
 * render every real row as an invisible sliver.
 */
@Composable
private fun BlockBreakdown(
    title: String?,
    counts: Map<String, Long>,
    labels: Map<String, String>,
    viewModel: WalcottViewModel,
    iconRefresh: Int,
    icons: Boolean,
) {
    val spacing = Tokens.spacing
    val rows = counts.entries.sortedByDescending { it.value }
    val top = rows.firstOrNull()?.value ?: 1L
    Column {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
        }
        dev.walcott.ui.components.CardGroup {
            rows.forEachIndexed { index, entry ->
                BlockRow(
                    key = entry.key,
                    count = entry.value,
                    fraction = (entry.value.toDouble() / top).toFloat().coerceIn(0f, 1f),
                    label = labelFor(entry.key, labels),
                    showIcon = icons && !entry.key.startsWith("__"),
                    viewModel = viewModel,
                    iconRefresh = iconRefresh,
                    position = cardPosition(index, rows.size),
                )
            }
        }
    }
}

/** The reserved keys are not packages or domains; they are the sentences they stand for. */
@Composable
private fun labelFor(key: String, labels: Map<String, String>): String = when (key) {
    BlockReports.OTHER -> stringResource(R.string.blocks_key_other)
    BlockKinds.UNKNOWN_APP -> stringResource(R.string.blocks_key_unknown)
    BlockKinds.DEVICE_BEDTIME -> stringResource(R.string.bedtime_title)
    BlockKinds.DEVICE_SCREEN_FREE -> stringResource(R.string.all_apps_windows_title)
    else -> labels[key] ?: key
}

@Composable
private fun BlockRow(
    key: String,
    count: Long,
    fraction: Float,
    label: String,
    showIcon: Boolean,
    viewModel: WalcottViewModel,
    iconRefresh: Int,
    position: CardPosition,
) {
    val spacing = Tokens.spacing
    val width by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(Tokens.motion.medium, easing = Tokens.motion.emphasized),
        label = "blockBar",
    )
    WalcottCard(position = position) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                dev.walcott.ui.components.AppIcon(
                    packageName = key,
                    inventory = viewModel.repository.inventory,
                    size = 28.dp,
                    remoteLoader = { viewModel.childAppIcon(it) },
                    refreshKey = iconRefresh,
                    label = label,
                )
                Spacer(Modifier.width(spacing.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier.fillMaxWidth(width).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
