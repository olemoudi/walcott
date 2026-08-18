package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One reason something on the child's phone is shut right now, and what would open it.
 *
 * The parent could always read the rules, one editor at a time, and work out which of them was
 * biting — a bedtime here, a screen-free window there, an allowance spent hours ago. Nowhere did
 * anything say "this is what is stopping them, now". That is the question a parent actually
 * arrives with, and answering it is the difference between a screen of settings and a screen
 * that explains a phone.
 */
data class ActiveBlock(
    val kind: Kind,
    /** The app this is about; "" for the rules that close the whole phone. */
    val packageName: String = "",
    /** The window this block is, for the kinds that are windows: when it started and when it lets go. */
    val from: LocalTime? = null,
    val until: LocalTime? = null,
    /** For [Kind.BUDGET]: how long it was allowed today, and how long it got used for. */
    val allowance: Duration? = null,
    val used: Duration? = null,
    /**
     * For [Kind.BUDGET]: the day's budget before extra time was added, when a grant made the two
     * differ. Null means the allowance IS the budget — saying "1h of 1h" while a grant is
     * silently inside the number is how a parent comes to believe the rules are not being applied.
     */
    val budget: Duration? = null,
    /** Whether the budget behind this block is the family default rather than one set for the app. */
    val fromDefaultBudget: Boolean = false,
) {
    enum class Kind {
        /** The parent paused this phone; it opens again at [until] (see [TodayException]). */
        PAUSED,

        /** The family's bedtime is running. */
        BEDTIME,

        /** A family-wide screen-free window is running. */
        SCREEN_FREE,

        /** A window set for this app alone is running. */
        APP_WINDOW,

        /** This app's time for today is spent. More minutes are the thing that ends this one. */
        BUDGET,

        /**
         * This app is blocked outright today: its limit is zero, so there is no time to run out
         * of and nothing ends this at any hour.
         *
         * Its own kind rather than a budget of zero, because they are different facts and the
         * parent reads them as different sentences. A blocked app reported as a spent budget
         * says "used 0s of 0s" — which looks like a bug, or like a child who somehow exhausted
         * an allowance without opening the app — and offers "give time" as the way out of a rule
         * whose whole point is that there is no time.
         */
        APP_BLOCKED,
    }
}

/**
 * Everything shutting something on this phone at [now], in the order a parent should read it:
 * the rules that close the whole phone first, then the ones about single apps.
 *
 * [packages] is what this device can actually block — the list the child publishes, which is
 * already only the apps its enforcement loop will touch. Anything outside it has no business
 * here: a limit that cannot be imposed is not blocking anything, however spent it looks.
 *
 * A device-wide window does NOT swallow the app rows underneath it. During bedtime the engine
 * reports every app as blocked by bedtime, which is true and useless: the parent lifting bedtime
 * needs to know that YouTube will still be out of time on the other side of it. So budgets are
 * judged on their own terms here, whatever else is running.
 */
fun RuleEngine.activeBlocks(
    config: FamilyConfig,
    packages: Collection<String>,
    now: LocalDateTime,
    usageToday: Map<String, Duration> = emptyMap(),
    extraTime: Map<String, Duration> = emptyMap(),
    /** False when the child's counters aren't today's, so no budget can be judged from them. */
    usageIsToday: Boolean = true,
): List<ActiveBlock> {
    val dayType = config.calendar.dayTypeOf(now)
    val time = now.toLocalTime()
    val specialDay = dayType == DayType.HOLIDAY
    val blocks = mutableListOf<ActiveBlock>()

    // First, because it is the one thing here the parent did on purpose a minute ago — and the
    // only one they end by changing their mind rather than by changing a rule.
    config.todayException.pauseUntil?.takeIf { now.isBefore(it) }?.let {
        blocks += ActiveBlock(ActiveBlock.Kind.PAUSED, until = it.toLocalTime())
    }
    config.bedtimeAt(now)?.takeIf { time in it }?.let {
        blocks += ActiveBlock(ActiveBlock.Kind.BEDTIME, from = it.start, until = it.end)
    }
    config.blockedWindows[dayType].orEmpty()
        .filter { it.appliesAt(now, specialDay) }
        .forEach { blocks += ActiveBlock(ActiveBlock.Kind.SCREEN_FREE, from = it.start, until = it.end) }

    // Sorted so the same rules always read in the same order: a list that reshuffles itself
    // between two glances is one nobody trusts.
    packages.filterNot { it in config.essentialPackages }.sorted().forEach { pkg ->
        config.perAppPolicies[pkg]?.blockedWindows?.get(dayType).orEmpty()
            .filter { it.appliesAt(now, specialDay) }
            .forEach { blocks += ActiveBlock(ActiveBlock.Kind.APP_WINDOW, pkg, from = it.start, until = it.end) }

        val allowance = config.allowanceFor(pkg, dayType, extraTime) ?: return@forEach
        val budget = config.budgetFor(pkg, dayType)
        val onDefault = config.usesDefaultBudget(pkg)
        // A zero allowance is not a spent one: nothing was ever available, so it is reported
        // whatever the counters say — including on a device whose counters are not today's,
        // where "blocked all day" is still exactly as true as it was this morning.
        if (allowance.isZero || allowance.isNegative) {
            blocks += ActiveBlock(
                ActiveBlock.Kind.APP_BLOCKED,
                pkg,
                allowance = allowance,
                fromDefaultBudget = onDefault,
            )
            return@forEach
        }
        if (!usageIsToday) return@forEach
        val used = usageToday[pkg] ?: Duration.ZERO
        if (used >= allowance) {
            blocks += ActiveBlock(
                ActiveBlock.Kind.BUDGET,
                pkg,
                allowance = allowance,
                used = used,
                // Only when a grant actually widened it; otherwise the two numbers are one.
                budget = budget?.takeIf { it != allowance },
                fromDefaultBudget = onDefault,
            )
        }
    }
    return blocks
}
