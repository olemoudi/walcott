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

    /** How long before a close the child hears about it. */
    val WARN_MINUTES = listOf(30, 5)

    private val HORIZON: Duration = Duration.ofMinutes(WARN_MINUTES.max().toLong())

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
