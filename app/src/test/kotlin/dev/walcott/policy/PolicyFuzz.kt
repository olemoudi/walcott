package dev.walcott.policy

import dev.walcott.data.AppPolicyDto
import dev.walcott.data.ChildEntry
import dev.walcott.data.ChildOverrides
import dev.walcott.data.PolicySettings
import dev.walcott.data.VacationDto
import dev.walcott.data.WindowDto
import dev.walcott.data.withHolidayMirroringWeekend
import dev.walcott.rules.DayType
import dev.walcott.rules.FamilyConfig
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Random

/**
 * A deterministic generator of whole parent policies, and the exact path one takes to become
 * the rules a child device enforces.
 *
 * Every other test in the suite either builds a [FamilyConfig] by hand (proving the engine) or
 * checks one field of [PolicySettings] (proving the mapping). Neither covers the seam between
 * them, which is where this app keeps its complexity: a budget that is mirrored into the
 * holiday slot, resolved through a child's overrides, filtered by a day-of-week mask, and only
 * then compared against a clock. This generator exists so invariants can be asserted over
 * hundreds of those combinations instead of the handful anyone thinks to write down.
 *
 * Seeded per policy index: a failure prints the index, and that index alone reproduces it.
 */
object PolicyFuzz {

    const val OWN_PACKAGE = "dev.walcott"
    val CATEGORY_IDS = listOf("games", "social", "video", "education", "creative", "other")

    /** Apps a generated policy draws from; [UNCLASSIFIED_APP] is never assigned on purpose. */
    val APPS = listOf("com.game", "com.chat", "com.video", "com.school", "com.draw")
    const val UNCLASSIFIED_APP = "com.brand.new"
    val MANAGED: Set<String> = (APPS + UNCLASSIFIED_APP).toSet()

    /** A Monday, so weekday/weekend/holiday all sit within one week of it. */
    val MONDAY: LocalDate = LocalDate.of(2026, 3, 2)
    val FRIDAY: LocalDate = MONDAY.plusDays(4)
    val SATURDAY: LocalDate = MONDAY.plusDays(5)
    val SUNDAY: LocalDate = MONDAY.plusDays(6)

    /**
     * Instants chosen for their edges, not their spread: either side of a weekend cut, either
     * side of midnight, and a mid-afternoon that most windows straddle.
     */
    val INSTANTS: List<LocalDateTime> = listOf(
        MONDAY.atTime(0, 0),
        MONDAY.atTime(9, 0),
        MONDAY.atTime(13, 0),
        MONDAY.atTime(18, 30),
        MONDAY.atTime(22, 15),
        MONDAY.plusDays(1).atTime(18, 0), // the Tuesday a generated policy may mark special
        FRIDAY.atTime(13, 59),
        FRIDAY.atTime(14, 0),
        FRIDAY.atTime(23, 30),
        SATURDAY.atTime(1, 0),
        SATURDAY.atTime(12, 0),
        SUNDAY.atTime(19, 59),
        SUNDAY.atTime(20, 0),
    )

    /** The Tuesday a policy may declare a holiday, so a special day lands inside [INSTANTS]. */
    val SPECIAL_DAY: LocalDate = MONDAY.plusDays(1)

    /**
     * One generated policy. [index] is the seed: same index, same policy, forever — which is
     * what makes a shrunk failure worth pasting into a bug report.
     */
    fun policy(index: Int): PolicySettings {
        val rnd = Random(index.toLong() * 1_000_003L)

        val assignments = APPS
            .filter { rnd.nextInt(10) > 0 } // ~10% of apps stay unclassified, i.e. blocked
            .associateWith { CATEGORY_IDS[rnd.nextInt(CATEGORY_IDS.size)] }

        val budgets = CATEGORY_IDS
            .filter { rnd.nextBoolean() }
            .associateWith { perDayBudget(rnd) }
            .filterValues { it.isNotEmpty() }

        val appPolicies = APPS
            .filter { rnd.nextInt(3) == 0 }
            .associateWith {
                AppPolicyDto(
                    budgets = if (rnd.nextBoolean()) perDayBudget(rnd) else emptyMap(),
                    blockedWindows = if (rnd.nextBoolean()) perDayWindows(rnd) else emptyMap(),
                )
            }
            .filterValues { !it.isEmpty }

        val bedtime = if (rnd.nextInt(3) > 0) {
            // Usually crossing midnight, like a real one; occasionally an evening-only block.
            val start = WindowDto(21 * 60 + rnd.nextInt(4) * 15, if (rnd.nextBoolean()) 7 * 60 else 23 * 60)
            DayType.entries.associate { it.name to start }
        } else {
            emptyMap()
        }

        val children = List(1 + rnd.nextInt(2)) { i ->
            ChildEntry(
                childId = "c$i",
                name = "Child$i",
                overrides = ChildOverrides(
                    // A child either inherits everything or replaces a whole field, which is
                    // the only shape resolveForChild supports.
                    budgets = if (rnd.nextInt(3) == 0) {
                        CATEGORY_IDS.take(2).associateWith { perDayBudget(rnd) }.filterValues { it.isNotEmpty() }
                    } else {
                        null
                    },
                    bedtime = if (rnd.nextInt(4) == 0) DayType.entries.associate { it.name to WindowDto(20 * 60, 6 * 60) } else null,
                ),
                addedAtMs = 1L,
            )
        }

        return PolicySettings(
            version = 1 + index.toLong(),
            budgets = budgets,
            blockedWindows = CATEGORY_IDS.filter { rnd.nextInt(4) == 0 }.associateWith { perDayWindows(rnd) },
            bedtime = bedtime,
            allAppsBlockedWindows = if (rnd.nextInt(3) == 0) perDayWindows(rnd) else emptyMap(),
            holidays = if (rnd.nextInt(3) == 0) setOf(SPECIAL_DAY.toEpochDay()) else emptySet(),
            vacations = if (rnd.nextInt(5) == 0) {
                listOf(VacationDto(SUNDAY.toEpochDay(), SUNDAY.plusDays(3).toEpochDay()))
            } else {
                emptyList()
            },
            weekendStartsFridayAtMinute = if (rnd.nextInt(3) == 0) 14 * 60 else null,
            weekendEndsSundayAtMinute = if (rnd.nextInt(4) == 0) 20 * 60 else null,
            specialDaysOwnRules = rnd.nextInt(4) == 0,
            assignments = assignments,
            appPolicies = appPolicies,
            children = children,
        )
    }

    /** Budgets across day types: sometimes absent, sometimes a hard zero, sometimes generous. */
    private fun perDayBudget(rnd: Random): Map<String, Int> =
        DayType.entries
            .filter { rnd.nextInt(4) > 0 }
            .associate {
                it.name to when (rnd.nextInt(6)) {
                    0 -> 0 // the "Blocked" chip: a zero cap is a hard block, not an absence
                    1 -> 15
                    2 -> 60
                    3 -> 120
                    else -> 30 * (1 + rnd.nextInt(8))
                }
            }

    /** 1–2 windows per day type, with the day masks and special-day opt-out the editor writes. */
    private fun perDayWindows(rnd: Random): Map<String, List<WindowDto>> {
        val windows = List(1 + rnd.nextInt(2)) {
            val start = rnd.nextInt(24) * 60
            val span = listOf(0, 30, 120, 9 * 60)[rnd.nextInt(4)] // 0 = the degenerate empty window
            WindowDto(
                startMinute = start,
                endMinute = (start + span) % (24 * 60),
                days = if (rnd.nextBoolean()) emptyList() else (1..7).filter { rnd.nextBoolean() }.ifEmpty { listOf(3) },
                skipSpecialDays = rnd.nextInt(3) == 0,
            )
        }
        return DayType.entries.associate { it.name to windows }
    }

    /**
     * The rules a child actually enforces, down the real path: the parent's write mirrors the
     * holiday slot, the child's slice resolves its overrides, and only then does it become a
     * [FamilyConfig]. Skipping any of those three is how a test passes while a phone misbehaves.
     */
    fun configFor(settings: PolicySettings, childId: String?): FamilyConfig =
        settings.withHolidayMirroringWeekend().resolveForChild(childId).toFamilyConfig(setOf(OWN_PACKAGE))

    /** Every (policy, child) pair worth evaluating, as the harness iterates them. */
    fun cases(count: Int): List<Case> = (0 until count).flatMap { index ->
        val settings = policy(index)
        settings.children.map { Case(index, settings, it.childId) }
    }

    /**
     * Usage counters worth evaluating against: none, half of every budget, and every budget
     * spent. Without them a harness only ever sees fresh mornings — and a daily budget that
     * counts down is the one rule this app is really about.
     */
    fun usageProfiles(config: FamilyConfig, now: LocalDateTime): List<Map<String, Duration>> {
        val dayType = config.calendar.dayTypeOf(now)
        val full = buildMap<String, Duration> {
            config.policies.forEach { (id, policy) -> policy.dailyBudget[dayType]?.let { put(id, it) } }
            config.perAppPolicies.forEach { (pkg, policy) -> policy.dailyBudget[dayType]?.let { put(pkg, it) } }
        }
        return listOf(
            emptyMap(),
            full.mapValues { it.value.dividedBy(2) },
            // A minute past the cap, so "exactly at the budget" and "over it" are both covered
            // (the engine blocks at zero remaining, not below it).
            full.mapValues { it.value.plusMinutes(1) },
        )
    }

    data class Case(val index: Int, val settings: PolicySettings, val childId: String) {
        val config: FamilyConfig get() = configFor(settings, childId)
        override fun toString() = "policy #$index / $childId"
    }
}
