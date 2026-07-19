package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.AppCategory
import dev.walcott.R
import dev.walcott.ui.theme.Tokens

/** Target + minutes picker for granting unsolicited bonus time to a child device. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BonusDialog(onDismiss: () -> Unit, onGrant: (String, Int) -> Unit) {
    val spacing = Tokens.spacing
    // Target key: "all apps" is the simple default; categories are the optional power tool.
    var target by remember { mutableStateOf(dev.walcott.rules.ExtraTime.ALL_APPS) }
    var minutes by remember { mutableIntStateOf(15) }
    val allApps = stringResource(R.string.request_all_apps)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.give_bonus)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = target == dev.walcott.rules.ExtraTime.ALL_APPS,
                        onClick = { target = dev.walcott.rules.ExtraTime.ALL_APPS },
                        label = { Text(allApps) },
                    )
                    AppCategory.entries.forEach { c ->
                        FilterChip(selected = target == c.id, onClick = { target = c.id }, label = { Text(stringResource(c.nameRes)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(stringResource(R.string.extra_minutes, m)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onGrant(target, minutes) }) { Text(stringResource(R.string.action_grant)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
