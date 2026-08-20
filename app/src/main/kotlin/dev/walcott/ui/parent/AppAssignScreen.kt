package dev.walcott.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.AppRow
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.AppIcon
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAssignScreen(
    viewModel: WalcottViewModel,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    // Set = this is one child's app list, opened from their rules: only their apps, and the
    // per-app editor behind each row writes that child's override (see AppDetailScreen).
    childId: String? = null,
    childName: String? = null,
    /**
     * Opens one member's own rules, for the note that says who is not following a family
     * rule. Null on a phone with nowhere to send them (see [OverriddenNote]).
     */
    onOpenMemberRules: ((String) -> Unit)? = null,
) {
    val spacing = Tokens.spacing
    val allRows by viewModel.appRows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val iconRefresh by viewModel.iconRefresh.collectAsStateWithLifecycle()
    val ledgers by viewModel.usageByApp.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // Alphabetical is how you FIND an app you already have in mind; by use is how you find the
    // one that needs a limit, which is the reason a parent opens this screen at all and was the
    // one order it did not offer. The numbers are already on this phone (see UsageLedger).
    var byUsage by rememberSaveable { mutableStateOf(false) }
    val rows = remember(allRows, childId) {
        if (childId == null) allRows else allRows.filter { row -> row.owners.any { it.id == childId } }
    }
    // Badges must reflect what THIS scope actually gets: the child's resolved policy when
    // scoped, the family policy otherwise.
    val effective = remember(settings, childId) { if (childId == null) settings else settings.resolveForChild(childId) }
    // Per-child filter (null = everyone). Only offered once there are two+ children to
    // tell apart — a single-child family gains nothing from the extra chrome. Never in
    // child scope, where the whole list already belongs to one child.
    var ownerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val allOwners = remember(rows) {
        rows.flatMap { it.owners }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
    val showOwners = childId == null && allOwners.size > 1

    // Seconds per app over the last week, for whoever the list is currently about: this child in
    // child scope, the filtered child if one is picked, otherwise everybody.
    val usageWeek = remember(ledgers, childId, ownerFilter) {
        val scope = childId ?: ownerFilter
        dev.walcott.sync.UsageLedger.totalsByApp(
            dev.walcott.sync.UsageLedger.mergeAcross(
                ledgers.filterKeys { scope == null || it == scope }.values,
            ),
            java.time.LocalDate.now().toEpochDay(),
            days = 7,
        )
    }

    val filtered = remember(rows, query, ownerFilter, byUsage, usageWeek) {
        rows.filter { row ->
            (query.isBlank() || row.app.label.contains(query, ignoreCase = true)) &&
                (ownerFilter == null || row.owners.any { it.id == ownerFilter })
        }.let { list ->
            // Ties fall back to the alphabet rather than to whatever order they arrived in: half
            // this list has no time on it at all, and a block of apps that reshuffles between two
            // glances is one nobody can use.
            if (byUsage) list.sortedWith(
                compareByDescending<AppRow> { usageWeek[it.app.packageName] ?: 0L }
                    .thenBy { it.app.label.lowercase() },
            ) else list
        }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.nav_apps_title), onBack)
        if (childId != null) {
            Column(Modifier.padding(horizontal = spacing.screen)) {
                OverrideScopeBanner(
                    childName.orEmpty(),
                    onOpenMemberRules = childId?.let { id -> onOpenMemberRules?.let { open -> { open(id) } } },
                )
            }
        } else {
            // One note for the whole list, not one per app: the override takes the entire
            // per-app map, so a member who has customized it ignores the family's limit on
            // every app at once rather than on the ones they happen to have.
            Column(Modifier.padding(horizontal = spacing.screen)) {
                OverriddenNote(settings, dev.walcott.data.FamilyRule.APP_LIMITS, onOpenMemberRules = onOpenMemberRules)
            }
        }
        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(spacing.screen),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.apps_no_remote),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_app)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ChoiceChip(
                    selected = !byUsage,
                    onClick = { byUsage = false },
                    label = stringResource(R.string.apps_sort_alpha),
                )
                ChoiceChip(
                    selected = byUsage,
                    onClick = { byUsage = true },
                    label = stringResource(R.string.apps_sort_usage),
                )
            }
            if (showOwners) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    ChoiceChip(
                        selected = ownerFilter == null,
                        onClick = { ownerFilter = null },
                        label = stringResource(R.string.apps_filter_all),
                    )
                    allOwners.forEach { owner ->
                        ChoiceChip(
                            selected = ownerFilter == owner.id,
                            onClick = { ownerFilter = if (ownerFilter == owner.id) null else owner.id },
                            label = owner.name,
                        )
                    }
                }
            }
            Spacer(Modifier.width(spacing.sm))
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = spacing.sm),
                contentPadding = PaddingValues(vertical = spacing.sm),
            ) {
                items(filtered, key = { it.app.packageName }) { row ->
                    AppAssignRow(
                        viewModel,
                        row,
                        restrictions = appRestrictions(effective, row.app.packageName),
                        limitLabel = appLimitLabel(effective, row.app.packageName),
                        usedThisWeek = usageWeek[row.app.packageName] ?: 0L,
                        showOwners = showOwners,
                        iconRefresh = iconRefresh,
                        onClick = { onOpenApp(row.app.packageName) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppAssignRow(
    viewModel: WalcottViewModel,
    row: AppRow,
    /** The app's own restrictions, badged with the icons that title them in its own screen. */
    restrictions: List<AppRestriction>,
    /** "45 min a day", "No limit", "Blocked" — the one thing there is to say about an app. */
    limitLabel: String,
    /** Seconds spent on it over the last week; 0 hides the line rather than printing a zero. */
    usedThisWeek: Long,
    showOwners: Boolean,
    iconRefresh: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.app.label) },
        supportingContent = {
            Column {
                // What this app's day looks like — its own limit, the family default, or
                // nothing — then a badge per rule it carries of its own.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The limit, then what the week actually did with it. The second half is what
                    // makes the first half a decision rather than a guess.
                    Text(
                        if (usedThisWeek > 0) {
                            limitLabel + "  ·  " + stringResource(
                                R.string.apps_used_week,
                                java.time.Duration.ofSeconds(usedThisWeek).humanize(),
                            )
                        } else {
                            limitLabel
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The badges are the row's other half and must not be measured into
                        // whatever this leaves behind: unweighted, this line took the width and
                        // squeezed them to nothing, so an app's own rules simply vanished off
                        // the list whenever its limit had a week's usage printed after it.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AppRestrictionBadges(restrictions, Modifier.padding(start = Tokens.spacing.sm))
                }
                // Who has it: one small tag per child (only in multi-child families).
                if (showOwners && row.owners.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        row.owners.forEach { owner ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    owner.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        leadingContent = {
            AppIcon(
                row.app.packageName,
                viewModel.repository.inventory,
                size = 40.dp,
                remoteLoader = { viewModel.childAppIcon(it) },
                refreshKey = iconRefresh,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}
