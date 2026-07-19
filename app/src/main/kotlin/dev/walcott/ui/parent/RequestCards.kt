package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.sync.ChildRequest
import dev.walcott.sync.SyncManager
import dev.walcott.ui.theme.Tokens

/**
 * Pending child request cards, shared between the parent home ([FamiliesScreen]) and the
 * children hub ([ChildrenScreen]) so approve/deny looks and behaves identically in both.
 */

@Composable
fun ExtraTimeRequestCard(pending: SyncManager.PendingRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val spacing = Tokens.spacing
    val key = pending.request.categoryId
    val category = AppCategory.byId(key)
    // The target can be a category, a single app, or "all apps" — name it the way the child chose.
    val targetName = when {
        key == dev.walcott.rules.ExtraTime.ALL_APPS -> stringResource(R.string.request_all_apps)
        category != null -> stringResource(category.nameRes)
        pending.request.targetLabel.isNotBlank() -> pending.request.targetLabel
        else -> key
    }

    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.request_summary, targetName, pending.request.minutes),
                color = category?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pending.request.reason.isNotBlank()) {
                Text(
                    "“${pending.request.reason}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ApproveDenyRow(onApprove, onDeny)
        }
    }
}

@Composable
fun AskRequestCard(pending: SyncManager.PendingAsk, onApprove: () -> Unit, onDeny: () -> Unit) {
    val spacing = Tokens.spacing
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(pending.childName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (pending.ask.kind == ChildRequest.KIND_APP) R.string.ask_summary_app else R.string.ask_summary_other,
                    pending.ask.text,
                ),
            )
            if (pending.ask.kind == ChildRequest.KIND_APP) {
                Text(
                    stringResource(R.string.ask_approve_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ApproveDenyRow(onApprove, onDeny)
        }
    }
}

@Composable
private fun ApproveDenyRow(onApprove: () -> Unit, onDeny: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.approve)) }
        OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.deny)) }
    }
}
