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
            if (health.failing) {
                val color = MaterialTheme.colorScheme.error
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        stringResource(
                            if (health.rateLimited) R.string.relay_rate_limited else R.string.relay_failing,
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
            scopeLaunch = { block -> scope.launch { block() } },
        )
    }
}

@Composable
private fun RelayServerDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> SyncManager.RelayChangeResult,
    scopeLaunch: (suspend () -> Unit) -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

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
                            SyncManager.RelayChangeResult.HAS_CHILDREN -> error = R.string.relay_error_locked
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
}
