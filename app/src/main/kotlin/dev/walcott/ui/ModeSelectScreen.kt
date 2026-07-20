package dev.walcott.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SupervisorAccount
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.sync.DeviceMode
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * First-launch choice between parent and child mode. The parent card expands into the
 * family creation form; the child card just persists the mode — enrollment happens on
 * the child home by scanning a per-child QR.
 */
@Composable
fun ModeSelectScreen(
    viewModel: WalcottViewModel,
    onParentCreated: () -> Unit,
    onChildSelected: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scope = rememberCoroutineScope()
    var parentExpanded by rememberSaveable { mutableStateOf(false) }
    var familyName by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val defaultFamilyName = stringResource(R.string.family_default_name)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.mode_select_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.mode_select_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm, bottom = spacing.xl),
        )

        ModeCard(
            icon = Icons.Outlined.SupervisorAccount,
            title = stringResource(R.string.mode_parent_card_title),
            description = stringResource(R.string.mode_parent_card_desc),
            selected = parentExpanded,
            onClick = { parentExpanded = !parentExpanded },
        )
        AnimatedVisibility(parentExpanded) {
            Column(Modifier.padding(top = spacing.md)) {
                OutlinedTextField(
                    value = familyName,
                    onValueChange = { familyName = it },
                    label = { Text(stringResource(R.string.family_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        creating = true
                        scope.launch {
                            viewModel.becomeParent(familyName.trim().ifBlank { defaultFamilyName })
                            onParentCreated()
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                ) {
                    // Creating the family generates keys in the Keystore — worth a beat of feedback.
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Text(stringResource(R.string.action_continue))
                    }
                }
            }
        }

        Spacer(Modifier.height(spacing.md))

        ModeCard(
            icon = Icons.Outlined.Face,
            title = stringResource(R.string.mode_child_card_title),
            description = stringResource(R.string.mode_child_card_desc),
            selected = false,
            onClick = {
                viewModel.setMode(DeviceMode.CHILD)
                onChildSelected()
            },
        )

        Spacer(Modifier.height(spacing.md))

        // Disaster recovery: a replaced parent phone loads the family backup file and the
        // whole family comes back — children keep obeying without being touched.
        RestoreBackupCard(viewModel, onRestored = onParentCreated)
    }
}

@Composable
private fun RestoreBackupCard(viewModel: WalcottViewModel, onRestored: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var backupText by remember { mutableStateOf<String?>(null) }
    val readFailed = stringResource(R.string.backup_read_failed)

    // Accept any type: cloud providers often serve the .json as octet-stream or text.
    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                }.getOrNull()
            }
            if (text == null) {
                android.widget.Toast.makeText(context, readFailed, android.widget.Toast.LENGTH_LONG).show()
            } else {
                backupText = text
            }
        }
    }

    ModeCard(
        icon = Icons.Outlined.SettingsBackupRestore,
        title = stringResource(R.string.restore_card_title),
        description = stringResource(R.string.restore_card_desc),
        selected = false,
        onClick = { openLauncher.launch(arrayOf("*/*")) },
    )

    backupText?.let { text ->
        RestorePassphraseDialog(
            onDismiss = { backupText = null },
            onRestore = { passphrase, onError ->
                scope.launch {
                    if (viewModel.restoreBackup(text, passphrase.toCharArray())) {
                        backupText = null
                        onRestored()
                    } else {
                        onError()
                    }
                }
            },
        )
    }
}

/** Passphrase prompt for a picked backup file; stays open with an error on a wrong one. */
@Composable
private fun RestorePassphraseDialog(onDismiss: () -> Unit, onRestore: (String, onError: () -> Unit) -> Unit) {
    val spacing = Tokens.spacing
    var passphrase by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text(stringResource(R.string.restore_pass_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; failed = false },
                    label = { Text(stringResource(R.string.backup_pass_label)) },
                    isError = failed,
                    supportingText = { if (failed) Text(stringResource(R.string.restore_failed)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = passphrase.isNotEmpty() && !restoring,
                onClick = {
                    restoring = true
                    onRestore(passphrase) { failed = true; restoring = false }
                },
            ) {
                if (restoring) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.restore_action))
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !restoring) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
