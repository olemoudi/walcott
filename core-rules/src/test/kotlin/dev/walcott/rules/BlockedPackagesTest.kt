package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The single control the enforcement loop acts on: which managed apps get suspended right
 * now, fail-closed included. Kept here (pure) so the fail-closed branch — the one that stops
 * a child from buying unlimited time by revoking usage access — is covered by a unit test,
 * not only by driving a device.
 */
class BlockedPackagesTest {

    private val monday: LocalDateTime = LocalDateTime.of(2026, 3, 2, 15, 0)
    private val game = "com.game"
    private val chat = "com.chat"
    private val edu = "com.edu"
    private val managed = setOf(game, chat, edu)

    /** [budgets] is package -> per-day-type budget, the only shape budgets have now. */
    private fun config(
        budgets: Map<String, Map<DayType, Duration>> = emptyMap(),
        bedtime: Map<DayType, TimeWindow> = emptyMap(),
        defaultBudget: Map<DayType, Duration> = emptyMap(),
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = defaultBudget,
        perAppPolicies = budgets.mapValues { (_, perDay) -> AppPolicy(dailyBudget = perDay) },
        bedtime = bedtime,
    )

    @Test
    fun `a config with room blocks nothing`() {
        val cfg = config(budgets = mapOf(game to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday, usageToday = emptyMap())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `only the exhausted app is blocked`() {
        val cfg = config(budgets = mapOf(game to mapOf(DayType.SCHOOL to Duration.ofHours(1))))
        val out = RuleEngine.blockedPackages(
            cfg, managed, monday, usageToday = mapOf(game to Duration.ofHours(1)),
        )
        assertEquals(setOf(game), out)
    }

    @Test
    fun `the family default blocks each app on its own counter`() {
        // What replaced the shared category pot: one app burning its hour leaves the others
        // with theirs. The old model blocked all three the moment the pot ran dry.
        val cfg = config(defaultBudget = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        val out = RuleEngine.blockedPackages(
            cfg, managed, monday, usageToday = mapOf(game to Duration.ofHours(1)),
        )
        assertEquals(setOf(game), out)
    }

    @Test
    fun `bedtime blocks every managed app`() {
        val cfg = config(bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday)
        assertEquals(managed, out)
    }

    // --- Fail-closed ---

    @Test
    fun `revoked usage access with budgets blocks everything managed`() {
        val cfg = config(budgets = mapOf(game to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday, usageCountingAvailable = false)
        assertEquals(managed, out)
    }

    @Test
    fun `revoked usage access without budgets keeps normal enforcement`() {
        // Only bedtime — no counter needed, so a revoked counter must NOT fail closed.
        val cfg = config(bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(21, 0), LocalTime.of(7, 0))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday, usageCountingAvailable = false)
        assertTrue(out.isEmpty()) // 15:00 is outside bedtime; nothing blocked
    }

    @Test
    fun `granted usage access ignores the fail-closed branch`() {
        val cfg = config(budgets = mapOf(game to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday, usageCountingAvailable = true)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty managed set is always empty`() {
        val cfg = config(budgets = mapOf(game to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        assertTrue(RuleEngine.blockedPackages(cfg, emptySet(), monday, usageCountingAvailable = false).isEmpty())
    }

    // --- Fail-closed: the clock ---

    @Test
    fun `an untrusted clock with time rules blocks everything managed`() {
        // Every rule here is a rule about *when*, so a clock the child moved forward walks
        // past bedtime and hands back a fresh budget. Failing closed makes that pointless.
        val cfg = config(bedtime = mapOf(DayType.SCHOOL to TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0))))
        assertTrue(RuleEngine.blockedPackages(cfg, managed, monday).isEmpty()) // 15:00, outside bedtime
        assertEquals(managed, RuleEngine.blockedPackages(cfg, managed, monday, clockTrusted = false))
    }

    @Test
    fun `an untrusted clock with a budget blocks everything managed`() {
        val cfg = config(budgets = mapOf(game to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        assertEquals(managed, RuleEngine.blockedPackages(cfg, managed, monday, clockTrusted = false))
    }

    @Test
    fun `an untrusted clock changes nothing when no rule depends on time`() {
        // A family with no budgets, windows or bedtime can't be cheated by moving the clock,
        // so a wrong clock must not lock a child out of a device nobody was limiting.
        val cfg = config()
        assertTrue(RuleEngine.blockedPackages(cfg, managed, monday, clockTrusted = false).isEmpty())
    }

    // --- The two fail-closed guards, one clause at a time ---
    //
    // Both are OR-chains, so a config that trips an early clause proves nothing about the
    // later ones: each rule a parent can configure ALONE has to trip the guard by itself, or
    // that rule is walkable by revoking a permission or moving the clock.

    private val window = TimeWindow(LocalTime.of(9, 0), LocalTime.of(14, 0))
    private val bare = FamilyConfig(version = 1)

    @Test
    fun `the family default budget alone requires the usage counter`() {
        val cfg = bare.copy(defaultAppBudget = mapOf(DayType.SCHOOL to Duration.ofHours(1)))
        assertTrue(RuleEngine.requiresUsageCounting(cfg))
        assertEquals(managed, RuleEngine.blockedPackages(cfg, managed, monday, usageCountingAvailable = false))
    }

    @Test
    fun `a per-app budget alone requires the usage counter`() {
        // The bypass this closes: capping only individual apps left the counter "optional",
        // and a budget that can't count down never runs out.
        val cfg = bare.copy(
            perAppPolicies = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)))),
        )
        assertTrue(RuleEngine.requiresUsageCounting(cfg))
        assertEquals(managed, RuleEngine.blockedPackages(cfg, managed, monday, usageCountingAvailable = false))
    }

    @Test
    fun `windows and bedtime alone do not require the usage counter`() {
        // They read the clock, not the counter: still enforceable with usage access revoked,
        // so failing closed there would punish a family for nothing.
        assertFalse(RuleEngine.requiresUsageCounting(bare.copy(bedtime = mapOf(DayType.SCHOOL to window))))
        assertFalse(RuleEngine.requiresUsageCounting(bare.copy(blockedWindows = mapOf(DayType.SCHOOL to listOf(window)))))
        assertFalse(
            RuleEngine.requiresUsageCounting(
                bare.copy(perAppPolicies = mapOf(game to AppPolicy(blockedWindows = mapOf(DayType.SCHOOL to listOf(window))))),
            ),
        )
        assertFalse(RuleEngine.requiresUsageCounting(bare))
    }

    @Test
    fun `every kind of time rule alone requires a trusted clock`() {
        assertFalse(RuleEngine.requiresTrustedClock(bare))
        assertTrue(RuleEngine.requiresTrustedClock(bare.copy(bedtime = mapOf(DayType.SCHOOL to window))))
        assertTrue(RuleEngine.requiresTrustedClock(bare.copy(blockedWindows = mapOf(DayType.SCHOOL to listOf(window)))))
        assertTrue(
            RuleEngine.requiresTrustedClock(bare.copy(defaultAppBudget = mapOf(DayType.SCHOOL to Duration.ofHours(1)))),
        )
        assertTrue(
            RuleEngine.requiresTrustedClock(
                bare.copy(perAppPolicies = mapOf(game to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30))))),
            ),
        )
        assertTrue(
            RuleEngine.requiresTrustedClock(
                bare.copy(perAppPolicies = mapOf(game to AppPolicy(blockedWindows = mapOf(DayType.SCHOOL to listOf(window))))),
            ),
        )
    }

    @Test
    fun `an empty rule slot is not a rule`() {
        // The editors leave keys behind: an app entry whose budget map is empty, a windows list
        // emptied of its last window. Neither is a reason to lock a device down.
        val emptyish = bare.copy(
            perAppPolicies = mapOf(game to AppPolicy()),
            blockedWindows = mapOf(DayType.SCHOOL to emptyList()),
        )
        assertFalse(RuleEngine.requiresUsageCounting(emptyish))
        assertFalse(RuleEngine.requiresTrustedClock(emptyish))
    }

    @Test
    fun `an app set free of the default is not blocked when the default runs out`() {
        val cfg = config(defaultBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)))
            .let { it.copy(perAppPolicies = it.perAppPolicies + (edu to AppPolicy(unlimited = true))) }
        val burned = mapOf(
            game to Duration.ofMinutes(30),
            chat to Duration.ofMinutes(30),
            edu to Duration.ofMinutes(30),
        )
        assertEquals(setOf(game, chat), RuleEngine.blockedPackages(cfg, managed, monday, usageToday = burned))
    }
}
