package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.sync.LiveTracking
import dev.walcott.ui.components.ChoiceChip
import dev.walcott.ui.components.CustomValueChip
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens

// Close tracking is offered in two places — the parent's quick-actions sheet and the child's
// location card — and these are the pieces both need. Shared rather than copied because they are
// the two halves of one promise: what the session will cost, and what the phone has left to pay
// it with.

/**
 * The close-tracking confirmation: what it will do, what it costs, and for how long.
 *
 * The duration is the whole point of the dialog. A mode this expensive must be bought for a
 * stated length of time rather than switched on, so there is no "off" to forget: the presets are
 * the answers people actually want, the stepper covers the rest in quarter hours, and four hours
 * is the ceiling because past that it has stopped being about right now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveTrackingDialog(
    name: String,
    ordinaryIntervalMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val spacing = Tokens.spacing
    var minutes by remember { mutableIntStateOf(LiveTracking.DEFAULT_MINUTES) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(
                    // A family with periodic tracking off has no "instead of" to compare against.
                    // The two percentages come from the rules rather than the copy, so the
                    // sentence cannot drift away from what the session will actually do.
                    if (ordinaryIntervalMinutes > 0) {
                        stringResource(
                            R.string.live_dialog_body,
                            name,
                            ordinaryIntervalMinutes,
                            LiveTracking.THROTTLE_FROM_PERCENT,
                            LiveTracking.BATTERY_FLOOR_PERCENT,
                        )
                    } else {
                        stringResource(
                            R.string.live_dialog_body_no_interval,
                            name,
                            LiveTracking.THROTTLE_FROM_PERCENT,
                            LiveTracking.BATTERY_FLOOR_PERCENT,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.live_dialog_duration), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    LiveTracking.PRESET_MINUTES.forEach { preset ->
                        ChoiceChip(
                            selected = minutes == preset,
                            onClick = { minutes = preset },
                            label = java.time.Duration.ofMinutes(preset.toLong()).humanize(),
                        )
                    }
                    CustomValueChip(
                        selected = minutes !in LiveTracking.PRESET_MINUTES,
                        customLabel = java.time.Duration.ofMinutes(minutes.toLong()).humanize()
                            .takeIf { minutes !in LiveTracking.PRESET_MINUTES },
                        dialogTitle = stringResource(R.string.live_dialog_duration),
                        initial = minutes,
                        onConfirm = { minutes = LiveTracking.clampMinutes(it) },
                    )
                }
                Text(
                    stringResource(R.string.live_dialog_visible, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(minutes) }) {
                Text(stringResource(R.string.live_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * What the phone has left, next to the button that would spend it.
 *
 * Close tracking is the one mode whose price is the battery, and the number is only useful where
 * the decision is taken: a parent about to follow a phone across town needs to know it is on 12%
 * BEFORE tapping, not from a session that quietly throttles itself ten minutes later.
 *
 * The warning threshold is [LiveTracking.THROTTLE_FROM_PERCENT] rather than a number written
 * here, because that is exactly where the session stops running flat out and starts stretching
 * its interval — so the mark on the screen and the behaviour on the phone cannot disagree.
 */
@Composable
internal fun LiveBatteryTag(batteryPercent: Int, charging: Boolean) {
    if (batteryPercent !in 0..100) return // a legacy child reports -1; say nothing rather than "0%"
    val spacing = Tokens.spacing
    val low = batteryPercent < LiveTracking.THROTTLE_FROM_PERCENT
    val color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when {
                low -> Icons.Outlined.BatteryAlert
                charging -> Icons.Outlined.BatteryChargingFull
                else -> Icons.Outlined.BatteryStd
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "  " + stringResource(
                if (charging) R.string.child_battery_charging else R.string.child_battery,
                batteryPercent,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
    if (low) {
        // The alarm is the number; this only explains it. Both in red would shout twice and make
        // a slower session read as a fault.
        Text(
            stringResource(R.string.live_battery_low_note, LiveTracking.THROTTLE_FROM_PERCENT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
}
