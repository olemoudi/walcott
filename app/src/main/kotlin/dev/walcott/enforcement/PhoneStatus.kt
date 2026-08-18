package dev.walcott.enforcement

import dev.walcott.rules.AppState
import dev.walcott.rules.BlockReason
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.appStatus
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the phone's own notification has to say about the rules, right now.
 *
 * Walcott has to keep one notification on the child's phone for as long as it is enforcing
 * anything — Android requires it of a foreground service — and for four versions it said
 * "Usage rules are active", which is true, useless, and the only permanently visible sentence
 * this app writes. Everything a child actually wants to know from it is already computed on
 * every tick of the enforcement loop: how long is left of what they are using, and when the
 * phone opens again if it is shut.
 *
 * That turns an obligation into the answer to the question that otherwise costs them opening the
 * app: "how long have I got?". It also removes an argument — a number on the phone itself is
 * harder to disbelieve than one somebody remembers being told.
 */
sealed interface PhoneStatus {

    /** A parent paused the phone; it opens again at [until]. */
    data class Paused(val until: LocalTime) : PhoneStatus

    /** Bedtime is running until [until]. */
    data class Bedtime(val until: LocalTime) : PhoneStatus

    /** A screen-free window is running until [until]. */
    data class ScreenFree(val until: LocalTime) : PhoneStatus

    /** [packageName] is in use and has [left] on today's limit. */
    data class AppRemaining(val packageName: String, val left: Duration) : PhoneStatus

    /** The rules can't be trusted (no usage counter, or a clock this phone can't rely on). */
    data object FailClosed : PhoneStatus

    /** Nothing worth a line: nothing is shut, and what is in use has no limit today. */
    data object Quiet : PhoneStatus
}

object StatusLine {

    /**
     * The one thing worth saying at [now], in the order it matters: the phone being shut outranks
     * anything about a single app, because it is the answer to "why is nothing opening".
     *
     * [foreground] is what is on screen (null when the screen is off or it is Walcott itself) and
     * [managed] is what this device can actually block — an app outside it has a counter but no
     * wall, and promising one is the lie this deliberately does not tell.
     */
    fun of(
        config: FamilyConfig,
        foreground: String?,
        managed: Set<String>,
        now: LocalDateTime,
        usageToday: Map<String, Duration> = emptyMap(),
        extraTime: Map<String, Duration> = emptyMap(),
        failClosed: Boolean = false,
    ): PhoneStatus {
        if (failClosed) return PhoneStatus.FailClosed
        when (RuleEngine.deviceWideBlock(config, now)) {
            BlockReason.PAUSED ->
                return config.todayException.pauseUntil?.let { PhoneStatus.Paused(it.toLocalTime()) }
                    ?: PhoneStatus.Quiet
            BlockReason.BEDTIME ->
                return config.bedtimeAt(now)?.let { PhoneStatus.Bedtime(it.end) } ?: PhoneStatus.Quiet
            BlockReason.BLOCKED_WINDOW -> {
                val dayType = config.calendar.dayTypeOf(now)
                val window = config.blockedWindows[dayType].orEmpty()
                    .firstOrNull { it.appliesAt(now, dayType == dev.walcott.rules.DayType.HOLIDAY) }
                return window?.let { PhoneStatus.ScreenFree(it.end) } ?: PhoneStatus.Quiet
            }
            else -> Unit
        }
        // Nothing in the foreground to report on: the screen is off, or the child is in Walcott,
        // or in something this phone does not limit.
        if (foreground == null || foreground !in managed) return PhoneStatus.Quiet
        val status = RuleEngine.appStatus(config, foreground, now, usageToday, extraTime)
        // A blocked app is not reported: it cannot be in the foreground for more than the moment
        // it takes to be suspended, and the child is looking at the block screen, not at this.
        return if (status.state == AppState.BUDGETED && status.remaining != null) {
            PhoneStatus.AppRemaining(foreground, status.remaining!!)
        } else {
            PhoneStatus.Quiet
        }
    }
}
