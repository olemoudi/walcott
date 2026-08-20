package dev.walcott.ui.parent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.ui.theme.Tokens

/**
 * The reminder that sits next to anything setting rules FOR special days: which days those are
 * is decided elsewhere, on the special-days screen. Shown wherever a special-day row can appear,
 * because a limit for a day type the family never populated silently does nothing.
 *
 * The line is kept short enough to read in place; the info button opens the full explanation with
 * a shortcut to that screen, and [onOpenCalendar] is expected to come back here on Back so the
 * round trip costs two taps.
 */
@Composable
internal fun SpecialDaysNote(onOpenCalendar: () -> Unit) {
    val spacing = Tokens.spacing
    var explaining by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(top = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(spacing.xs))
        Text(
            stringResource(R.string.special_days_note_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { explaining = true }, contentPadding = ComfortableTextButtonPadding) {
            Text(stringResource(R.string.special_days_note_action), style = MaterialTheme.typography.labelLarge)
        }
    }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.nav_calendar_title)) },
            text = { Text(stringResource(R.string.special_days_note_long)) },
            confirmButton = {
                TextButton(onClick = { explaining = false; onOpenCalendar() }) {
                    Text(stringResource(R.string.special_days_note_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/**
 * The special-days section of a time-based editor: the switch that claims them, and — only once
 * it is on — the rules themselves, right underneath it.
 *
 * Ordering is the whole point of it being a component. The switch comes first and the row it
 * governs unfolds below it, so the control always reads before the thing it controls. Rendering
 * the row greyed above the switch instead (the first attempt) put the answer before the question.
 *
 * It is deliberately one family-wide switch shown in many places rather than a switch per screen.
 * A parent editing an app's limit should not have to remember that the row they want was enabled
 * on a different screen — nor discover that flipping it here left the other editors behind.
 *
 * The info button lives on the switch, because "which days are special" is a question about the
 * switch itself, not about any one row below it.
 */
@Composable
internal fun SpecialDaysSection(
    on: Boolean,
    onOpenCalendar: () -> Unit,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val spacing = Tokens.spacing
    androidx.compose.material3.HorizontalDivider(Modifier.padding(top = spacing.sm))
    androidx.compose.foundation.layout.Column(Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.special_days_own_rules_title),
                        style = MaterialTheme.typography.bodyMedium,
                        // Weighted, so the title wraps rather than crushing the button that
                        // explains it. This row already gives half its width to a Switch, and
                        // unweighted the title took the rest: the ⓘ was measured into what was
                        // left of a line that had nothing left.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SpecialDaysInfoButton(onOpenCalendar)
                }
                Text(
                    stringResource(
                        if (on) R.string.special_days_own_rules_on else R.string.special_days_own_rules_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Turning it on seeds every special-day slot from the weekend's, so nothing changes
            // until the parent moves a value (see withSpecialDaysOwnRules).
            androidx.compose.material3.Switch(checked = on, enabled = enabled, onCheckedChange = onChange)
        }
        if (on) content()
    }
}

/** Compact but still tappable, so the note stays one line next to a row label. */
private val ComfortableTextButtonPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/** The same note, as the trailing info button alone — for rows with no room for the line. */
@Composable
internal fun SpecialDaysInfoButton(onOpenCalendar: () -> Unit) {
    var explaining by remember { mutableStateOf(false) }
    IconButton(onClick = { explaining = true }, modifier = Modifier.size(28.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.special_days_note_action),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.nav_calendar_title)) },
            text = { Text(stringResource(R.string.special_days_note_long)) },
            confirmButton = {
                TextButton(onClick = { explaining = false; onOpenCalendar() }) {
                    Text(stringResource(R.string.special_days_note_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}
