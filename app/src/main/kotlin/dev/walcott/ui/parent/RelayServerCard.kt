package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.RelayServer
import dev.walcott.sync.SyncManager
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * The relay this family's phones talk through, and — when it is refusing them — the reason to
 * care about it at all.
 *
 * Editable only before the first child is enrolled, which is what makes it safe: a child learns
 * the relay from its pairing QR and nowhere else (see [SyncManager.setRelayServer]).
 */
@Composable
fun RelayServerCard(viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()
    val server by viewModel.relayServer.collectAsStateWithLifecycle()
    val health by viewModel.publishHealth.collectAsStateWithLifecycle()
    val tooLarge by viewModel.policyTooLarge.collectAsStateWithLifecycle()
    val migration by viewModel.relayMigration.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    WalcottCard(onClick = { editing = true }) {
        Column(Modifier.padding(spacing.lg)) {
            Text(stringResource(R.string.relay_title), style = MaterialTheme.typography.titleSmall)
            Text(
                server,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            Text(
                stringResource(R.string.relay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
            // The rules being too big to send is reported HERE, next to the relay, because that is
            // what it looks like from the outside: a channel that refuses everything. It is also
            // the one cause the parent cannot fix by waiting, so it gets said first.
            // A move in flight, and who has not followed yet. The one thing worth saying on this
            // card while it lasts: until every phone has moved, the old relay is still carrying
            // this family, and a phone that never moves has to be noticed rather than assumed.
            migration?.let {
                Text(
                    stringResource(R.string.relay_migrating, it.to, it.moved, it.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
            if (tooLarge || health.failing) {
                val color = MaterialTheme.colorScheme.error
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        stringResource(
                            when {
                                tooLarge -> R.string.relay_policy_too_large
                                health.rateLimited -> R.string.relay_rate_limited
                                else -> R.string.relay_failing
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
        }
    }

    if (editing) {
        RelayServerDialog(
            current = server,
            onDismiss = { editing = false },
            onSave = { typed -> viewModel.setRelayServer(typed) },
            onMigrate = { typed -> viewModel.migrateRelay(typed) },
            scopeLaunch = { block -> scope.launch { block() } },
        )
    }
}

@Composable
private fun RelayServerDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> SyncManager.RelayChangeResult,
    onMigrate: suspend (String) -> SyncManager.RelayChangeResult,
    scopeLaunch: (suspend () -> Unit) -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Set when the plain change is refused because children are enrolled: the move is possible,
    // but it is a migration of the whole family and it is asked for in those words.
    var offerMigration by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relay_title)) },
        text = {
            Column {
                Text(stringResource(R.string.relay_dialog_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; error = null },
                    label = { Text(stringResource(R.string.relay_field_label)) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().padding(top = Tokens.spacing.md),
                )
                error?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Tokens.spacing.xs),
                    )
                }
                TextButton(
                    onClick = { typed = RelayServer.DEFAULT; error = null },
                    modifier = Modifier.padding(top = Tokens.spacing.xs),
                ) {
                    Text(stringResource(R.string.relay_use_default))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scopeLaunch {
                        when (onSave(typed)) {
                            SyncManager.RelayChangeResult.OK -> onDismiss()
                            SyncManager.RelayChangeResult.INVALID -> error = R.string.relay_error_invalid
                            // Not a dead end any more: the family CAN move, it just has to take
                            // its children with it, which is a different button.
                            SyncManager.RelayChangeResult.HAS_CHILDREN -> offerMigration = true
                            SyncManager.RelayChangeResult.MIGRATION_RUNNING ->
                                error = R.string.relay_error_migrating
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (offerMigration) {
        AlertDialog(
            onDismissRequest = { offerMigration = false },
            title = { Text(stringResource(R.string.relay_migrate_title)) },
            text = { Text(stringResource(R.string.relay_migrate_body, typed)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scopeLaunch {
                            when (onMigrate(typed)) {
                                SyncManager.RelayChangeResult.OK -> {
                                    offerMigration = false
                                    onDismiss()
                                }
                                SyncManager.RelayChangeResult.INVALID -> error = R.string.relay_error_invalid
                                SyncManager.RelayChangeResult.MIGRATION_RUNNING ->
                                    error = R.string.relay_error_migrating
                                SyncManager.RelayChangeResult.HAS_CHILDREN -> error = R.string.relay_error_locked
                            }
                            if (error != null) offerMigration = false
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.relay_migrate_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { offerMigration = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
