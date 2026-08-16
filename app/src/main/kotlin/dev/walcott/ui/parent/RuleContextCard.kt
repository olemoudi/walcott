package dev.walcott.ui.parent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.rules.DayTypeChange
import dev.walcott.rules.RuleContext
import dev.walcott.rules.WindowStatus
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.labelRes
import dev.walcott.ui.theme.Tokens
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Where this child stands in the rules right now, stated as what is NOT running.
 *
 * Everything else on this screen reports something happening. The hours when nothing is
 * happening are most of a child's day, and they were the one state the app never described — so
 * "is it bedtime yet?", "does the weekend count already?", "is today one of the special days?"
 * could only be answered by opening three editors and doing the arithmetic against a clock.
 *
 * Each line therefore carries both halves: that the rule is not running, and when that stops
 * being true. "Not bedtime" alone would be the same silence in more words.
 */
@Composable
fun RuleContextCard(context: RuleContext, now: LocalDateTime) {
    val spacing = Tokens.spacing
    WalcottCard {
        Column(Modifier.padding(spacing.lg)) {
            // Which day the rules think it is — not which day the calendar says, because a
            // Friday afternoon can already be the weekend and a Tuesday can be a special day.
            ContextRow(
                icon = Icons.Outlined.CalendarMonth,
                text = stringResource(context.dayType.labelRes()),
                supporting = context.nextDayType?.let { change ->
                    stringResource(
                        R.string.now_daytype_change,
                        stringResource(change.to.labelRes()),
                        whenText(change, now),
                    )
                },
                active = true,
            )
            ContextRow(
                icon = Icons.Outlined.AutoAwesome,
                text = stringResource(
                    if (context.specialDay) R.string.now_special_day else R.string.now_not_special_day,
                ),
                active = context.specialDay,
            )
            ContextRow(
                icon = Icons.Filled.Bedtime,
                text = when (val bedtime = context.bedtime) {
                    is WindowStatus.Running -> stringResource(R.string.now_bedtime_running, bedtime.until.hhmm())
                    is WindowStatus.Later -> stringResource(R.string.now_bedtime_later, bedtime.from.hhmm())
                    is WindowStatus.None ->
                        if (bedtime.configuredToday) {
                            stringResource(R.string.now_bedtime_over)
                        } else {
                            stringResource(R.string.now_bedtime_none)
                        }
                },
                active = context.bedtime is WindowStatus.Running,
            )
            ContextRow(
                icon = Icons.Outlined.DoNotDisturbOn,
                text = when (val screenFree = context.screenFree) {
                    is WindowStatus.Running ->
                        stringResource(R.string.now_screenfree_running, screenFree.until.hhmm())
                    is WindowStatus.Later ->
                        stringResource(R.string.now_screenfree_later, screenFree.from.hhmm(), screenFree.to.hhmm())
                    is WindowStatus.None ->
                        if (screenFree.configuredToday) {
                            stringResource(R.string.now_screenfree_over)
                        } else {
                            stringResource(R.string.now_screenfree_none)
                        }
                },
                active = context.screenFree is WindowStatus.Running,
            )
        }
    }
}

/**
 * "today at 21:30", "tomorrow at 00:00", "Friday at 14:00" — the change said the way a parent
 * would say it. A bare weekday for something happening in four hours reads as next week.
 */
@Composable
private fun whenText(change: DayTypeChange, now: LocalDateTime): String {
    val time = change.at.toLocalTime().hhmm()
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), change.at.toLocalDate())
    return when (days) {
        0L -> stringResource(R.string.now_when_today, time)
        1L -> stringResource(R.string.now_when_tomorrow, time)
        else -> stringResource(
            R.string.now_when_weekday,
            change.at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            time,
        )
    }
}

@Composable
private fun ContextRow(icon: ImageVector, text: String, active: Boolean, supporting: String? = null) {
    val spacing = Tokens.spacing
    val ink = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            // Running rules are the exception here and read as the exception; the quiet ones are
            // the point of the card and must not shout.
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = ink)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
