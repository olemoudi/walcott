package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
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
import dev.walcott.R
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.theme.Tokens

/**
 * Target + minutes picker for granting unsolicited bonus time to a child device: every app, or
 * one of the ones that child actually has.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BonusDialog(
    /** The child's apps (package -> label), so a grant can name one of them. */
    apps: List<Pair<String, String>> = emptyList(),
    /**
     * What it opens on. The general button means every app; opened from the one that has just
     * run out, it means that one — a dialog that lands on "all apps" after the parent tapped a
     * row about Roblox is asking a question they already answered.
     */
    initialTarget: String = dev.walcott.rules.ExtraTime.ALL_APPS,
    onDismiss: () -> Unit,
    onGrant: (String, Int) -> Unit,
) {
    val spacing = Tokens.spacing
    var target by remember(initialTarget) { mutableStateOf(initialTarget) }
    var minutes by remember { mutableIntStateOf(15) }
    val allApps = stringResource(R.string.request_all_apps)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.give_bonus)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ChoiceChip(
                        selected = target == dev.walcott.rules.ExtraTime.ALL_APPS,
                        onClick = { target = dev.walcott.rules.ExtraTime.ALL_APPS },
                        label = allApps,
                    )
                    apps.forEach { (pkg, label) ->
                        ChoiceChip(selected = target == pkg, onClick = { target = pkg }, label = label)
                    }
                }
                dev.walcott.ui.components.MinutesChips(value = minutes, onSelect = { minutes = it })
            }
        },
        confirmButton = { TextButton(onClick = { onGrant(target, minutes) }) { Text(stringResource(R.string.action_grant)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
