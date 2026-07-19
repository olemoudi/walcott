package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun config(
        budgets: Map<String, Map<DayType, Duration>> = emptyMap(),
        bedtime: Map<DayType, TimeWindow> = emptyMap(),
    ) = FamilyConfig(
        version = 1,
        assignments = mapOf(game to "games", chat to "social", edu to "education"),
        policies = budgets.mapValues { (_, perDay) -> CategoryPolicy(dailyBudget = perDay) },
        bedtime = bedtime,
    )

    @Test
    fun `unclassified-free config with room blocks nothing`() {
        val cfg = config(budgets = mapOf("games" to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday, usageToday = emptyMap())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `only the exhausted category is blocked`() {
        val cfg = config(budgets = mapOf("games" to mapOf(DayType.SCHOOL to Duration.ofHours(1))))
        val out = RuleEngine.blockedPackages(
            cfg, managed, monday, usageToday = mapOf("games" to Duration.ofHours(1)),
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
        val cfg = config(budgets = mapOf("games" to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
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
        val cfg = config(budgets = mapOf("games" to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        val out = RuleEngine.blockedPackages(cfg, managed, monday, usageCountingAvailable = true)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty managed set is always empty`() {
        val cfg = config(budgets = mapOf("games" to mapOf(DayType.SCHOOL to Duration.ofHours(2))))
        assertTrue(RuleEngine.blockedPackages(cfg, emptySet(), monday, usageCountingAvailable = false).isEmpty())
    }
}
