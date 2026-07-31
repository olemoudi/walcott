package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

    // Identical adjacent entries (a replayed ack, a repeated grant) fold into one ×N line.
    val feed = dev.walcott.sync.ParentEvent.collapseRepeats(events.filter(::eventRenderable))

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.timeline_title), onBack)
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
            items(feed, key = { (event, _) -> "ev-" + event.id.ifBlank { "${event.atMs}-${event.type}-${event.childId}" } }) { (event, times) ->
                val name = settings.children.firstOrNull { it.childId == event.childId }?.name
                    ?: event.childName.ifBlank { stringResource(R.string.family_default_name) }
                val target = event.childId.takeIf { id -> settings.children.any { it.childId == id } }
                EventRow(event, name, nowMs, onClick = target?.let { id -> { onOpenChild(id) } }, repeat = times)
            }
            item { Text("", Modifier.padding(bottom = spacing.xl)) }
        }
    }
}
