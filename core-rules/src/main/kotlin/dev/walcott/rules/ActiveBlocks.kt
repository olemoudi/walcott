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
    /** When the window lets go, for the two kinds that are windows. */
    val until: LocalTime? = null,
    /** For [Kind.BUDGET]: how long it was allowed, and how long it got used for. */
    val allowance: Duration? = null,
    val used: Duration? = null,
) {
    enum class Kind {
        /** The family's bedtime is running. */
        BEDTIME,

        /** A family-wide screen-free window is running. */
        SCREEN_FREE,

        /** A window set for this app alone is running. */
        APP_WINDOW,

        /** This app's time for today is spent. More minutes are the thing that ends this one. */
        BUDGET,
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

    config.bedtime[dayType]?.takeIf { time in it }?.let {
        blocks += ActiveBlock(ActiveBlock.Kind.BEDTIME, until = it.end)
    }
    config.blockedWindows[dayType].orEmpty()
        .filter { it.appliesAt(now, specialDay) }
        .forEach { blocks += ActiveBlock(ActiveBlock.Kind.SCREEN_FREE, until = it.end) }

    // Sorted so the same rules always read in the same order: a list that reshuffles itself
    // between two glances is one nobody trusts.
    packages.filterNot { it in config.essentialPackages }.sorted().forEach { pkg ->
        config.perAppPolicies[pkg]?.blockedWindows?.get(dayType).orEmpty()
            .filter { it.appliesAt(now, specialDay) }
            .forEach { blocks += ActiveBlock(ActiveBlock.Kind.APP_WINDOW, pkg, until = it.end) }

        if (!usageIsToday) return@forEach
        val allowance = config.allowanceFor(pkg, dayType, extraTime) ?: return@forEach
        val used = usageToday[pkg] ?: Duration.ZERO
        if (used >= allowance) {
            blocks += ActiveBlock(ActiveBlock.Kind.BUDGET, pkg, allowance = allowance, used = used)
        }
    }
    return blocks
}
