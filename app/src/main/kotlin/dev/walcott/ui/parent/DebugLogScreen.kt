package dev.walcott.ui.parent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.walcott.R
import dev.walcott.debug.DebugLog
import dev.walcott.debug.LogEntry
import dev.walcott.ui.components.WalcottTopBar
import dev.walcott.ui.theme.Tokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())

/**
 * In-app view of [DebugLog], reachable from the settings hub in both parent and child mode
 * (behind the PIN gate on the child). Meant for diagnosing self-update / enforcement issues on
 * devices with no adb access — copy or share the buffer to get the traces off the device.
 */
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val copiedMsg = stringResource(R.string.debug_copied)
    val shareSubject = stringResource(R.string.debug_share_subject)

    // Keep the newest line in view as traces stream in.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        WalcottTopBar(stringResource(R.string.debug_logs_title), onBack)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                copyToClipboard(context, DebugLog.format())
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.debug_copy)) }
            OutlinedButton(onClick = { shareText(context, DebugLog.format(), shareSubject) }) {
                Text(stringResource(R.string.debug_share))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { DebugLog.clear() }) { Text(stringResource(R.string.debug_clear)) }
        }

        if (entries.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(spacing.screen),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.debug_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = spacing.screen),
                contentPadding = PaddingValues(vertical = spacing.sm),
            ) {
                items(entries) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        'E' -> MaterialTheme.colorScheme.error
        'W' -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${TIME.format(Instant.ofEpochMilli(entry.epochMillis))} ${entry.level}/${entry.tag}: ${entry.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Walcott debug logs", text))
}

private fun shareText(context: Context, text: String, subject: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
