package dev.walcott.ui.parent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * The whole activity feed, newest first — everything the home only teases. The log is bounded
 * on both axes (see [dev.walcott.sync.SyncState.pruneEvents]), so this is "the last week",
 * never an unbounded history.
 */
@Composable
fun ActivityScreen(viewModel: WalcottViewModel, onOpenChild: (String) -> Unit, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val events by viewModel.recentEvents.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Minute tick so the relative ages ("8 minutes ago") stay honest while the screen is open.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }

    // Whose wall this is. Only asked where the answer can differ: with one member every line is
    // already theirs, and a filter with one option is furniture.
    var childFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val children = settings.children

    // Identical adjacent entries (a replayed ack, a repeated grant) fold into one ×N line.
    // Filtered BEFORE collapsing, so one child's repeats fold together even when another child's
    // lines were interleaved between them.
    val feed = dev.walcott.sync.ParentEvent.collapseRepeats(
        events.filter { eventRenderable(it) && (childFilter == null || it.childId == childFilter) },
    )

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.timeline_title), onBack)
        if (children.size >= 2) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.xs),
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
                        onClick = { childFilter = child.childId.takeIf { it != childFilter } },
                        label = child.name,
                    )
                }
            }
        }
        if (feed.isEmpty()) {
            Text(
                stringResource(R.string.timeline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(spacing.xxl),
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text(
                    stringResource(R.string.timeline_window),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.xs),
                )
            }
            itemsIndexed(
                feed,
                key = { _, (event, _) ->
                    "ev-" + event.id.ifBlank { "${event.atMs}-${event.type}-${event.childId}" }
                },
            ) { index, (event, times) ->
                // A date only where the day changes. Without them the feed is one column of
                // "3 hours ago", "yesterday", "2 days ago" — relative ages that are exact for the
                // top few lines and unreadable as soon as you are looking for something.
                val previous = feed.getOrNull(index - 1)?.first
                if (previous == null || !sameDay(previous.atMs, event.atMs)) {
                    DayHeader(event.atMs, nowMs)
                }
                val name = settings.children.firstOrNull { it.childId == event.childId }?.name
                    ?: event.childName.ifBlank { stringResource(R.string.family_default_name) }
                val target = event.childId.takeIf { id -> settings.children.any { it.childId == id } }
                EventRow(event, name, nowMs, onClick = target?.let { id -> { onOpenChild(id) } }, repeat = times)
            }
            item { Text("", Modifier.padding(bottom = spacing.xl)) }
        }
    }
}

/** Whether two instants fall on the same local day. */
private fun sameDay(a: Long, b: Long): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

/**
 * "Today", "Yesterday", then the date. Left to Android rather than written out: it already knows
 * what those two words are in the phone's language, and what a date looks like there.
 */
@Composable
private fun DayHeader(atMs: Long, nowMs: Long) {
    val label = android.text.format.DateUtils.getRelativeTimeSpanString(
        atMs,
        maxOf(atMs, nowMs),
        android.text.format.DateUtils.DAY_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_SHOW_DATE,
    ).toString()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.spacing.md, bottom = Tokens.spacing.xs),
    )
}
