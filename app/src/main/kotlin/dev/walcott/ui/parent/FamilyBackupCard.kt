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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The family's disaster-recovery card: the copy that lives somewhere OTHER than this phone, for
 * the day the phone is lost, stolen or broken. Two ways out, both credential-less by design —
 * save the encrypted file through the system picker (Drive and friends show up there), or hand
 * it to the share sheet (mail it to yourself) — plus the reminders that nag when it goes stale.
 *
 * Deliberately manual. It used to offer to keep a chosen file refreshed by itself, which read as
 * "backups are handled" while only ever writing to this same phone — exactly what the nightly
 * on-device copies now do properly, under the parent PIN and with no setup at all. Two automatic
 * local backups is one too many, and the confusing one was this.
 */
@Composable
internal fun FamilyBackupCard(viewModel: WalcottViewModel) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastBackupAtMs by viewModel.lastBackupAtMs.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()

    // null = no dialog; SAVE/SHARE pick what happens after the passphrase is chosen.
    var dialogMode by remember { mutableStateOf<BackupMode?>(null) }
    // The SEALED file, waiting for the picker to say where it goes.
    //
    // Saveable, and it is the ciphertext rather than the passphrase for two reasons. The picker is
    // another activity, so this one can be recreated while it is open — a rotation was enough —
    // and a plain `remember` came back empty, at which point the save silently did nothing at all.
    // And a passphrase is the one thing that must not be handed to saved-instance state, whereas
    // an encrypted backup is exactly as safe there as it is in the file it is about to become.
    var pendingFile by rememberSaveable { mutableStateOf("") }
    var sealing by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingFile
        pendingFile = ""
        if (uri == null) return@rememberLauncherForActivityResult
        if (text.isEmpty()) {
            Toast.makeText(context, R.string.backup_save_failed, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = runCatching {
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
                // "Backed up" is recorded from the sheet's own callback, not from the fact that we
                // opened it. Recording it here marked the family as safe the instant the sheet
                // appeared — back out of it and the card said "backed up just now" about a file
                // that had gone nowhere, and the reminder ladder stayed quiet for a month over it.
                context.startActivity(
                    Intent.createChooser(
                        send,
                        context.getString(R.string.backup_share),
                        BackupSharedReceiver.callback(context, viewModel.familyId),
                    ),
                )
            }.onFailure {
                Toast.makeText(context, R.string.backup_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    WalcottCard {
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

            // Honest status line: when this copy was last taken, or that it never was.
            val statusText = when {
                lastBackupAtMs > 0 -> {
                    val stamp = remember(lastBackupAtMs) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                            .format(Date(lastBackupAtMs))
                    }
                    stringResource(R.string.backup_last, stamp)
                }
                else -> stringResource(R.string.backup_never)
            }
            val statusIsError = lastBackupAtMs == 0L
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )

            // The nightly on-device copies. Stated plainly, and stated as NOT covering the case
            // this card is really about: a phone that is lost or broken takes them with it.
            //
            // And stated CONDITIONALLY, because they are conditional. They are sealed with the
            // family PIN, so until one is set AND entered once there are none at all — and when a
            // night's write fails, nothing anywhere said so: the flag was recorded and never read.
            // Either way this line used to promise copies that did not exist, about the one
            // disaster it exists to cover.
            val syncState by viewModel.syncState.collectAsStateWithLifecycle()
            val localKeyed = syncState.localBackupKeyB64.isNotBlank()
            val localFailing = syncState.localBackupError
            Text(
                when {
                    !localKeyed -> stringResource(R.string.backup_local_needs_pin)
                    localFailing -> stringResource(R.string.backup_local_failed, dev.walcott.sync.LocalBackupStore.FOLDER)
                    else -> stringResource(R.string.backup_local_note, dev.walcott.sync.LocalBackupStore.FOLDER)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (localKeyed && !localFailing) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = spacing.sm),
            )

            // The nudge notifications; the notification's own action can also turn this off.
            Row(
                Modifier.fillMaxWidth().padding(top = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.backup_reminders_switch),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = identity.backupReminders,
                    onCheckedChange = { viewModel.setBackupReminders(it) },
                )
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
            busy = sealing,
            onDismiss = { if (!sealing) dialogMode = null },
            onConfirm = { passphrase ->
                when (mode) {
                    // Sealed BEFORE the picker opens, so the only thing that has to survive the
                    // trip through another activity is ciphertext. Key derivation is 600k
                    // rounds and takes a moment, hence the spinner rather than a frozen dialog.
                    BackupMode.SAVE -> scope.launch {
                        sealing = true
                        val text = runCatching { viewModel.createBackup(passphrase.toCharArray()) }.getOrNull()
                        sealing = false
                        dialogMode = null
                        if (text == null) {
                            Toast.makeText(context, R.string.backup_save_failed, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        pendingFile = text
                        saveLauncher.launch("walcott-family-backup.json")
                    }
                    BackupMode.SHARE -> {
                        dialogMode = null
                        shareBackup(passphrase)
                    }
                }
            },
        )
    }
}

private enum class BackupMode { SAVE, SHARE }

/** Choose (and confirm) the backup passphrase. There is no reset — the dialog says so. */
@Composable
private fun BackupPassphraseDialog(
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String) -> Unit,
) {
    val spacing = Tokens.spacing
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooShort && repeat == passphrase && !busy,
                onClick = { onConfirm(passphrase) },
            ) {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.action_continue))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}


/**
 * Fires when the parent actually picks a target in the share sheet, and only then records the
 * family as backed up (see [android.content.Intent.createChooser] with an [android.content.IntentSender]).
 *
 * The signal is the callback itself rather than anything in it: the PendingIntent is immutable, so
 * the chooser cannot attach `EXTRA_CHOSEN_COMPONENT`, and an immutable one still delivers. What is
 * knowable is "the file was handed to an app", which is as far as Android will ever let this go —
 * `ACTION_SEND` has no result, and most targets report cancelled even after sending.
 */
class BackupSharedReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: android.content.Context, intent: Intent) {
        val familyId = intent.getStringExtra(EXTRA_FAMILY).orEmpty()
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as dev.walcott.WalcottApplication
                val family = runCatching { app.hub.scopeOf(familyId) }.getOrNull() ?: app.hub.own
                family.syncManager.recordBackupSaved()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SHARED = "dev.walcott.action.BACKUP_SHARED"
        private const val EXTRA_FAMILY = "family_id"

        /** The chooser's "a target was picked" callback, aimed at [familyId]'s scope. */
        fun callback(context: android.content.Context, familyId: String): android.content.IntentSender =
            android.app.PendingIntent.getBroadcast(
                context,
                familyId.hashCode(),
                Intent(context, BackupSharedReceiver::class.java)
                    .setAction(ACTION_SHARED)
                    .putExtra(EXTRA_FAMILY, familyId),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            ).intentSender
    }
}
