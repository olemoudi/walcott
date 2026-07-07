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

/** Category + minutes picker for granting unsolicited bonus time to a child device. */
@Composable
internal fun BonusDialog(onDismiss: () -> Unit, onGrant: (String, Int) -> Unit) {
    val spacing = Tokens.spacing
    var category by remember { mutableStateOf(AppCategory.GAMES) }
    var minutes by remember { mutableIntStateOf(15) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.give_bonus)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AppCategory.GAMES, AppCategory.VIDEO, AppCategory.SOCIAL).forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(stringResource(c.nameRes)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(stringResource(R.string.extra_minutes, m)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onGrant(category.id, minutes) }) { Text(stringResource(R.string.action_grant)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
