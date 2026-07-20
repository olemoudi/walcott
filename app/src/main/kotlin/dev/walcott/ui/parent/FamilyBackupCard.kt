package dev.walcott.ui.parent

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.sync.FamilyBackup
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The family's disaster-recovery card. Two ways out, both credential-less by design:
 * save the encrypted file through the system picker (Drive and friends show up there), or
 * hand it to the share sheet (mail it to yourself). Saving can also turn on fire-and-forget
 * mode: the file is rewritten automatically on every rule change, so a backup made once at
 * setup never goes stale.
 */
@Composable
internal fun FamilyBackupCard(viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastBackupAtMs by viewModel.lastBackupAtMs.collectAsStateWithLifecycle()
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()

    // null = no dialog; SAVE/SHARE pick what happens after the passphrase is chosen.
    var dialogMode by remember { mutableStateOf<BackupMode?>(null) }
    // Held between the passphrase dialog and the file picker's async result.
    var pendingPassphrase by remember { mutableStateOf("") }
    var pendingAutoUpdate by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val passphrase = pendingPassphrase
        val autoUpdate = pendingAutoUpdate
        pendingPassphrase = ""
        if (uri == null || passphrase.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = viewModel.createBackup(passphrase.toCharArray())
                withContext(Dispatchers.IO) {
                    // "wt" truncates when overwriting a previous backup; some providers only
                    // accept the default mode, so fall back rather than fail the save.
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(uri)
                    checkNotNull(stream) { "no output stream" }.use { it.write(text.toByteArray()) }
                }
            }.isSuccess
            if (!ok) {
                Toast.makeText(context, R.string.backup_save_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            viewModel.recordBackupSaved()
            if (autoUpdate) {
                // Fire-and-forget needs the grant to outlive this process and reboots.
                val persisted = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.isSuccess
                if (persisted) {
                    viewModel.enableAutoBackup(uri.toString(), passphrase.toCharArray())
                } else {
                    Toast.makeText(context, R.string.backup_auto_unsupported, Toast.LENGTH_LONG).show()
                }
            }
            Toast.makeText(context, R.string.backup_saved, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareBackup(passphrase: String) {
        scope.launch {
            runCatching {
                val text = viewModel.createBackup(passphrase.toCharArray())
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "backups").apply { mkdirs() }
                        .resolve("walcott-family-backup.json")
                        .apply { writeText(text) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.backup_share_subject))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_share)))
                viewModel.recordBackupSaved()
            }.onFailure {
                Toast.makeText(context, R.string.backup_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SettingsBackupRestore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_card_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.backup_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Honest status line: never backed up (red), auto-updating (calm), or auto failing.
            val statusText = when {
                autoBackup.enabled && autoBackup.failing -> stringResource(R.string.backup_auto_error)
                lastBackupAtMs > 0 -> {
                    val stamp = remember(lastBackupAtMs) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                            .format(Date(lastBackupAtMs))
                    }
                    if (autoBackup.enabled) {
                        stringResource(R.string.backup_auto_on, stamp)
                    } else {
                        stringResource(R.string.backup_last, stamp)
                    }
                }
                else -> stringResource(R.string.backup_never)
            }
            val statusIsError = (autoBackup.enabled && autoBackup.failing) || lastBackupAtMs == 0L
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )

            if (autoBackup.enabled) {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.backup_auto_switch),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = true, onCheckedChange = { viewModel.disableAutoBackup() })
                }
            }

            Spacer(Modifier.size(spacing.sm))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(onClick = { dialogMode = BackupMode.SAVE }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_save_file))
                }
                OutlinedButton(onClick = { dialogMode = BackupMode.SHARE }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_share))
                }
            }
        }
    }

    dialogMode?.let { mode ->
        BackupPassphraseDialog(
            showAutoOption = mode == BackupMode.SAVE,
            onDismiss = { dialogMode = null },
            onConfirm = { passphrase, autoUpdate ->
                dialogMode = null
                when (mode) {
                    BackupMode.SAVE -> {
                        pendingPassphrase = passphrase
                        pendingAutoUpdate = autoUpdate
                        saveLauncher.launch("walcott-family-backup.json")
                    }
                    BackupMode.SHARE -> shareBackup(passphrase)
                }
            },
        )
    }
}

private enum class BackupMode { SAVE, SHARE }

/** Choose (and confirm) the backup passphrase. There is no reset — the dialog says so. */
@Composable
private fun BackupPassphraseDialog(
    showAutoOption: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, autoUpdate: Boolean) -> Unit,
) {
    val spacing = Tokens.spacing
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var autoUpdate by remember { mutableStateOf(true) }
    val tooShort = passphrase.length < FamilyBackup.MIN_PASSPHRASE_CHARS
    val mismatch = repeat.isNotEmpty() && repeat != passphrase

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_pass_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_pass_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_pass_label)) },
                    supportingText = {
                        if (tooShort && passphrase.isNotEmpty()) {
                            Text(stringResource(R.string.backup_pass_short, FamilyBackup.MIN_PASSPHRASE_CHARS))
                        }
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                )
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text(stringResource(R.string.backup_pass_repeat)) },
                    isError = mismatch,
                    supportingText = { if (mismatch) Text(stringResource(R.string.backup_pass_mismatch)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showAutoOption) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.xs)) {
                        Checkbox(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                        Text(
                            stringResource(R.string.backup_auto_checkbox),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && repeat == passphrase,
                onClick = { onConfirm(passphrase, showAutoOption && autoUpdate) },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
