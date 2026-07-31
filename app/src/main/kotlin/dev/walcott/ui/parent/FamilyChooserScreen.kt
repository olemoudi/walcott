package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.FamilyHub
import dev.walcott.FamilySummary
import dev.walcott.R
import dev.walcott.ui.OpenBackupDocument
import dev.walcott.ui.RestorePassphraseDialog
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.components.CardGroup
import dev.walcott.ui.components.NavCard
import dev.walcott.ui.components.SectionHeader
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.components.cardPosition
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * The families this phone manages, and the way in and out of the set.
 *
 * Only reachable when it is worth reaching: with a single family the home IS that family, and
 * this screen exists solely as the door to adding a second one.
 */
@Composable
fun FamilyChooserScreen(
    viewModel: WalcottViewModel,
    onOpenFamily: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val families by viewModel.familySummaries.collectAsStateWithLifecycle()
    val activeId by viewModel.activeFamilyId.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.family_default_name)

    var creating by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FamilySummary?>(null) }
    var backupText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val readFailed = stringResource(R.string.backup_read_failed)
    val duplicateFamily = stringResource(R.string.add_family_duplicate)

    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(OpenBackupDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()
            }
            if (text == null) {
                android.widget.Toast.makeText(context, readFailed, android.widget.Toast.LENGTH_LONG).show()
            } else {
                backupText = text
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.families_title), onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item { SectionHeader(stringResource(R.string.families_section), supporting = stringResource(R.string.families_subtitle)) }
            itemsIndexed(families, key = { _, family -> family.id }) { index, family ->
                FamilyCard(
                    family = family,
                    fallbackName = defaultName,
                    active = family.id == activeId,
                    canRemove = families.size > 1,
                    position = cardPosition(index, families.size),
                    onOpen = {
                        viewModel.switchFamily(family.id)
                        onOpenFamily()
                    },
                    onRemove = { removing = family },
                )
            }

            item { SectionHeader(stringResource(R.string.add_family_section)) }
            item {
                CardGroup {
                    NavCard(
                        Icons.Outlined.PersonAdd,
                        stringResource(R.string.add_family_new),
                        stringResource(R.string.add_family_new_desc),
                        { creating = true },
                        position = dev.walcott.ui.components.CardPosition.First,
                    )
                    NavCard(
                        Icons.Outlined.SettingsBackupRestore,
                        stringResource(R.string.add_family_restore),
                        stringResource(R.string.add_family_restore_desc),
                        { openLauncher.launch(arrayOf("*/*")) },
                        position = dev.walcott.ui.components.CardPosition.Last,
                    )
                }
            }
            item { Spacer(Modifier.size(spacing.xl)) }
        }
    }

    if (creating) {
        NewFamilyDialog(
            busy = busy,
            onDismiss = { if (!busy) creating = false },
            onCreate = { name ->
                busy = true
                scope.launch {
                    viewModel.createFamily(name)
                    busy = false
                    creating = false
                    onOpenFamily()
                }
            },
        )
    }

    backupText?.let { text ->
        RestorePassphraseDialog(
            fromPin = dev.walcott.sync.FamilyBackup.keySourceOf(text) == dev.walcott.sync.FamilyBackup.SOURCE_PIN,
            onDismiss = { backupText = null },
            onRestore = { passphrase, onError ->
                scope.launch {
                    when (viewModel.addFamilyFromBackup(text, passphrase.toCharArray())) {
                        FamilyHub.AddResult.OK -> {
                            backupText = null
                            onOpenFamily()
                        }
                        // Already here: not a wrong passphrase, so say so instead of blaming it.
                        FamilyHub.AddResult.ALREADY_HERE -> {
                            backupText = null
                            android.widget.Toast
                                .makeText(context, duplicateFamily, android.widget.Toast.LENGTH_LONG)
                                .show()
                        }
                        FamilyHub.AddResult.BAD_FILE -> onError()
                    }
                }
            },
        )
    }

    removing?.let { family ->
        RemoveFamilyDialog(
            name = family.name.ifBlank { defaultName },
            onDismiss = { removing = null },
            onConfirm = {
                viewModel.removeFamily(family.id)
                removing = null
            },
        )
    }
}

@Composable
private fun FamilyCard(
    family: FamilySummary,
    fallbackName: String,
    active: Boolean,
    canRemove: Boolean,
    position: dev.walcott.ui.components.CardPosition,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard(onClick = onOpen, position = position) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(family.name.ifBlank { fallbackName }, style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.family_children_count, family.children, family.children),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The two numbers a parent picks a family by: who is waiting on them, and
                // whether anything is broken. Zero alerts is stated, not left blank — "no
                // avisos" is the answer they came for.
                Text(
                    buildString {
                        if (family.pending > 0) {
                            append(
                                pluralStringResource(
                                    R.plurals.family_pending_count, family.pending, family.pending,
                                ),
                            )
                            append(" · ")
                        }
                        append(pluralStringResource(R.plurals.family_alerts_count, family.alerts, family.alerts))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (family.alerts > 0 || family.pending > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (active) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.family_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.remove_family_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewFamilyDialog(busy: Boolean, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val fallback = stringResource(R.string.family_default_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_family_new)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.add_family_new_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Tokens.spacing.md))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.family_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onCreate(name.trim().ifBlank { fallback }) }) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Removing a family is not undoable and does not free anybody: its children keep enforcing the
 * last rules they received, with nobody able to change them. The dialog says exactly that.
 */
@Composable
private fun RemoveFamilyDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_family_title, name)) },
        text = { Text(stringResource(R.string.remove_family_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remove_family_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
