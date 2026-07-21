package dev.walcott.ui.parent

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.sync.ParentEvent
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.SyncNotifications
import dev.walcott.ui.format.humanize
import dev.walcott.ui.theme.Tokens
import java.time.Duration

/**
 * The activity feed ("wall"): the durable, tappable record behind every parent alert, so a
 * dismissed notification is never a lost message. Renders raw [ParentEvent] data in the
 * device's current locale; types this build doesn't know are skipped (forward compat).
 */

/** True when this build can render [event] (unknown types from newer builds are hidden). */
internal fun eventRenderable(event: ParentEvent): Boolean = event.type in RENDERABLE_TYPES

private val RENDERABLE_TYPES = setOf(
    ParentEvent.TYPE_UNPROTECTED, ParentEvent.TYPE_PROTECTION_DEGRADED, ParentEvent.TYPE_USAGE_ACCESS_OFF,
    ParentEvent.TYPE_MOCK_LOCATION, ParentEvent.TYPE_LOW_BATTERY, ParentEvent.TYPE_ENFORCEMENT_GAP,
    ParentEvent.TYPE_ENFORCEMENT_GAP_CLEARED, ParentEvent.TYPE_CLOCK_TAMPER, ParentEvent.TYPE_INDOOR_LOCATION_OFF,
    ParentEvent.TYPE_NEW_APP, ParentEvent.TYPE_WRONG_PIN, ParentEvent.TYPE_STALE, ParentEvent.TYPE_NEVER_REPORTED,
    ParentEvent.TYPE_TIME_REQUEST, ParentEvent.TYPE_ASK, ParentEvent.TYPE_REQUEST_APPROVED,
    ParentEvent.TYPE_REQUEST_DENIED, ParentEvent.TYPE_BONUS, ParentEvent.TYPE_REMOTE_DONE,
)

@Composable
internal fun EventRow(event: ParentEvent, childName: String, nowMs: Long, onClick: (() -> Unit)?) {
    val spacing = Tokens.spacing
    val text = eventText(event, childName) ?: return
    val (icon, tint) = eventBadge(event)
    val age = DateUtils.getRelativeTimeSpanString(event.atMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            Modifier.padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    age,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Compact, non-card feed line for embedding inside another card (the child dashboard). */
@Composable
internal fun EventLine(event: ParentEvent, childName: String, nowMs: Long) {
    val text = eventText(event, childName) ?: return
    val (icon, tint) = eventBadge(event)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Tokens.spacing.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Tokens.spacing.sm))
        Text(
            DateUtils.getRelativeTimeSpanString(event.atMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun eventBadge(event: ParentEvent): Pair<ImageVector, Color> {
    val warn = Color(0xFFB26A00)
    val error = MaterialTheme.colorScheme.error
    val good = MaterialTheme.colorScheme.secondary
    val neutral = MaterialTheme.colorScheme.primary
    return when (event.type) {
        ParentEvent.TYPE_UNPROTECTED -> Icons.Filled.Warning to error
        ParentEvent.TYPE_PROTECTION_DEGRADED -> Icons.Filled.Warning to warn
        ParentEvent.TYPE_USAGE_ACCESS_OFF -> Icons.Filled.Warning to error
        ParentEvent.TYPE_MOCK_LOCATION -> Icons.Outlined.LocationOff to warn
        ParentEvent.TYPE_LOW_BATTERY -> Icons.Outlined.BatteryAlert to warn
        ParentEvent.TYPE_ENFORCEMENT_GAP -> Icons.Filled.Warning to error
        ParentEvent.TYPE_ENFORCEMENT_GAP_CLEARED -> Icons.Filled.CheckCircle to good
        ParentEvent.TYPE_CLOCK_TAMPER -> Icons.Outlined.Schedule to error
        ParentEvent.TYPE_INDOOR_LOCATION_OFF -> Icons.Outlined.LocationOff to warn
        ParentEvent.TYPE_NEW_APP -> Icons.Outlined.InstallMobile to neutral
        ParentEvent.TYPE_WRONG_PIN -> Icons.Outlined.Key to warn
        ParentEvent.TYPE_STALE -> Icons.Outlined.CloudOff to error
        ParentEvent.TYPE_NEVER_REPORTED -> Icons.Outlined.CloudOff to warn
        ParentEvent.TYPE_TIME_REQUEST -> Icons.Outlined.Timer to neutral
        ParentEvent.TYPE_ASK -> Icons.AutoMirrored.Outlined.Chat to neutral
        ParentEvent.TYPE_REQUEST_APPROVED -> Icons.Filled.CheckCircle to good
        ParentEvent.TYPE_REQUEST_DENIED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
        ParentEvent.TYPE_BONUS -> Icons.Outlined.Redeem to good
        else -> Icons.Outlined.Build to neutral
    }
}

/** The localized one-liner for [event], or null when the type is unknown to this build. */
@Composable
private fun eventText(event: ParentEvent, name: String): String? = when (event.type) {
    ParentEvent.TYPE_UNPROTECTED -> stringResource(R.string.enforcement_off_title, name)
    ParentEvent.TYPE_PROTECTION_DEGRADED -> stringResource(R.string.enforcement_degraded_title, name)
    ParentEvent.TYPE_USAGE_ACCESS_OFF -> stringResource(R.string.usage_access_off_title, name)
    ParentEvent.TYPE_MOCK_LOCATION -> stringResource(R.string.mock_location_title, name)
    ParentEvent.TYPE_LOW_BATTERY -> stringResource(R.string.event_low_battery, name, event.count)
    ParentEvent.TYPE_ENFORCEMENT_GAP ->
        pluralStringResource(R.plurals.event_enforcement_gap, event.count, name, event.count)
    ParentEvent.TYPE_ENFORCEMENT_GAP_CLEARED -> stringResource(R.string.event_enforcement_gap_cleared, name)
    ParentEvent.TYPE_CLOCK_TAMPER -> stringResource(
        R.string.event_clock_tamper, name,
        SyncNotifications.formatSkew(LocalContext.current, event.detail.toLongOrNull() ?: 0L),
    )
    ParentEvent.TYPE_INDOOR_LOCATION_OFF -> stringResource(R.string.net_location_off_title, name)
    ParentEvent.TYPE_NEW_APP ->
        if (event.count > 0) {
            stringResource(R.string.event_new_app_more, name, event.detail, event.count)
        } else {
            stringResource(R.string.event_new_app, name, event.detail)
        }
    ParentEvent.TYPE_WRONG_PIN -> pluralStringResource(R.plurals.event_wrong_pin, event.count, name, event.count)
    ParentEvent.TYPE_STALE -> stringResource(
        R.string.event_stale, name, Duration.ofMillis(event.detail.toLongOrNull() ?: 0L).humanize(),
    )
    ParentEvent.TYPE_NEVER_REPORTED -> stringResource(R.string.never_reported_title, name)
    ParentEvent.TYPE_TIME_REQUEST -> stringResource(R.string.event_time_request, name, event.count)
    ParentEvent.TYPE_ASK -> stringResource(R.string.event_ask, name, event.detail)
    ParentEvent.TYPE_REQUEST_APPROVED ->
        if (event.count > 0) {
            stringResource(R.string.event_request_approved, name, event.count)
        } else {
            stringResource(R.string.event_request_approved_ask, name)
        }
    ParentEvent.TYPE_REQUEST_DENIED -> stringResource(R.string.event_request_denied, name)
    ParentEvent.TYPE_BONUS -> stringResource(R.string.event_bonus, name, event.count)
    ParentEvent.TYPE_REMOTE_DONE -> stringResource(
        if (event.count > 0) R.string.event_remote_ok else R.string.event_remote_failed,
        name, remoteActionLabel(event.detail),
    )
    else -> null
}

@Composable
private fun remoteActionLabel(action: String): String = when (action) {
    RemoteAction.UPDATE_NOW -> stringResource(R.string.remote_update_now)
    RemoteAction.REAPPLY_POLICY -> stringResource(R.string.remote_reapply)
    RemoteAction.REQUEST_PERMISSIONS -> stringResource(R.string.remote_ask_permissions)
    RemoteAction.INSTALL_APP -> stringResource(R.string.install_share_title)
    RemoteAction.DIAGNOSE -> stringResource(R.string.diag_section)
    else -> action
}
