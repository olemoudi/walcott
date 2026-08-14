package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime

/** Something the rules are about to close, and how long is left of it. */
data class ClosingSoon(
    val reason: BlockReason,
    /** The app this is about; empty when it is the whole device (bedtime, screen-free time). */
    val packageName: String,
    val left: Duration,
)

/**
 * How long until the rules close what the child is using, so they can be told beforehand
 * instead of the screen dying mid-game. Pure and stateless like the rest of the engine: it
 * reads no clock, and what to do with the answer (warn once, warn twice, stay quiet) is the
 * caller's business.
 *
 * Time rules are read by walking the clock forward a minute at a time and asking [RuleEngine]
 * what it would say. Deliberately naive: bedtime, family screen-free windows and an app's own
 * windows each have their own rules about day types, weekday filters and special days, and
 * restating any of that here to work out "when does the next one start" is how a warning and
 * the block it warns about end up disagreeing.
 */
object CloseWatch {

    /**
     * How long before a close the child hears about it.
     *
     * Three rungs, descending: the half hour is a heads-up, the five minutes are "find a save
     * point", and the last minute is the one that stops the screen dying mid-sentence. The last
     * one matters most and was the one missing — five minutes of warning is plenty of time to
     * forget you were warned, and the complaint it answers is never "nobody told me half an hour
     * ago", it is "it just closed".
     *
     * Each rung is announced at most once per countdown ([dev.walcott.enforcement.TimeWarnings]),
     * and the enforcement loop samples every two seconds while a limited app is in the
     * foreground, so a one-minute rung is comfortably inside what it can see.
     */
    val WARN_MINUTES = listOf(30, 5, 1)

    /**
     * How little has to be left before Walcott starts saying anything about it at all.
     *
     * The top rung, named: under half an hour the warnings engage — the timed ones as the app is
     * used ([WARN_MINUTES]), and the one shown on opening it ([worthAnnouncingOnOpen]). Above
     * this the app says nothing, because "nine hours left" is not news and a phone that reports
     * numbers nobody needed teaches its owner to stop reading them.
     */
    val WARN_FROM: Duration = Duration.ofMinutes(WARN_MINUTES.max().toLong())

    private val HORIZON: Duration = WARN_FROM

    /**
     * What will close [packageName] first — its own time running out, or a rule about the
     * clock — within the warning horizon. Null when nothing does, or when it is closed already.
     */
    fun nextClose(
        config: FamilyConfig,
        packageName: String,
        now: LocalDateTime,
        usageToday: Map<String, Duration> = emptyMap(),
        extraTime: Map<String, Duration> = emptyMap(),
    ): ClosingSoon? {
        // Already closed: there is nothing to warn about, and the child can see it.
        val verdict = RuleEngine.evaluate(config, packageName, now, usageToday, extraTime)
        if (verdict is Verdict.Blocked) return null

        val budget = (verdict as? Verdict.AllowedWithBudget)?.remaining
            ?.takeIf { it <= HORIZON }
            ?.let { ClosingSoon(BlockReason.BUDGET_EXHAUSTED, packageName, it) }

        // Asked with no usage on purpose: the budget's countdown is the arithmetic above, and
        // letting it answer here too would report every app as closing the minute it runs out.
        val timed = firstMinute(now) { at ->
            (RuleEngine.evaluate(config, packageName, at, emptyMap(), emptyMap()) as? Verdict.Blocked)
                ?.reason
                ?.takeIf { it == BlockReason.BEDTIME || it == BlockReason.BLOCKED_WINDOW }
        }?.let { (reason, left) -> ClosingSoon(reason, packageName, left) }

        return listOfNotNull(budget, timed).minByOrNull { it.left }
    }

    /**
     * When the whole device closes next (bedtime or a family screen-free window), for a child
     * using something Walcott doesn't limit: their app isn't going anywhere, but the phone is.
     */
    fun nextDeviceWideClose(config: FamilyConfig, now: LocalDateTime): ClosingSoon? {
        if (RuleEngine.deviceWideBlock(config, now) != null) return null
        return firstMinute(now) { at -> RuleEngine.deviceWideBlock(config, at) }
            ?.let { (reason, left) -> ClosingSoon(reason, "", left) }
    }

    /** The warning [left] has earned — the smallest threshold it has reached — or null. */
    fun thresholdFor(left: Duration): Int? =
        WARN_MINUTES.filter { left <= Duration.ofMinutes(it.toLong()) }.minOrNull()

    /**
     * Whether opening an app is worth telling the child what is left in it.
     *
     * The same threshold as every other warning, so a child meets one rule rather than several:
     * under half an hour Walcott speaks, above it stays quiet. Null means the app has no limit —
     * nothing to report — and a blocked app cannot be opened to be told anything.
     */
    fun worthAnnouncingOnOpen(remaining: Duration?): Boolean =
        remaining != null && remaining <= WARN_FROM

    /**
     * How little has to be left of an app's time before it is worth the child's attention.
     *
     * One threshold, doing two jobs that must not disagree: which apps earn a place on the
     * child's home, and which cards offer the shortcut to ask for more. Two numbers would put a
     * card on screen that says "running out" and offers nothing to do about it, which is the
     * exact shape of the problem this replaced.
     *
     * Comfortably above the five-minute warning rung, so by the time the banner fires the card
     * the child reaches for is already there.
     */
    val RUNNING_LOW_BELOW: Duration = Duration.ofMinutes(10)

    /**
     * Whether an app is close enough to the end to be shown, and offered more time. [remaining]
     * is null once the app is blocked, which is the clearest possible yes.
     *
     * The shortcut used to appear only after the app had already closed, which put the one
     * moment a child most wants it — watching the last minute tick away — in a dead zone: the
     * card read "1m left" and offered nothing, and the only way through was to go back into the
     * app, be shut out of it, and come back. Asking BEFORE the screen dies is the entire point
     * of knowing it is about to.
     */
    fun runningLow(remaining: Duration?, blocked: Boolean): Boolean =
        blocked || (remaining != null && remaining <= RUNNING_LOW_BELOW)

    /** The first minute after [now], within the horizon, where [test] answers something. */
    private inline fun <T : Any> firstMinute(
        now: LocalDateTime,
        test: (LocalDateTime) -> T?,
    ): Pair<T, Duration>? {
        for (minute in 1..HORIZON.toMinutes()) {
            val found = test(now.plusMinutes(minute)) ?: continue
            return found to Duration.ofMinutes(minute)
        }
        return null
    }
}
