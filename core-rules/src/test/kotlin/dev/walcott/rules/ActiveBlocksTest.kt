package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "What is stopping them right now" — the question the parent's screen exists to answer, and
 * the one the rules could only be read one editor at a time to guess at.
 */
class ActiveBlocksTest {

    private val game = "com.game"
    private val chat = "com.chat"
    private val installed = listOf(game, chat)

    private fun config(
        bedtime: TimeWindow? = null,
        screenFree: List<TimeWindow> = emptyList(),
        perApp: Map<String, AppPolicy> = emptyMap(),
        default: Duration? = null,
    ) = FamilyConfig(
        version = 1,
        defaultAppBudget = default?.let { DayType.entries.associateWith { _ -> it } }.orEmpty(),
        perAppPolicies = perApp,
        bedtime = bedtime?.let { mapOf(DayType.SCHOOL to it) }.orEmpty(),
        blockedWindows = if (screenFree.isEmpty()) emptyMap() else mapOf(DayType.SCHOOL to screenFree),
    )

    /** A Monday, well inside the school day. */
    private val monday = LocalDateTime.of(2026, 3, 2, 17, 0)

    @Test
    fun `a quiet phone reports nothing at all`() {
        val blocks = RuleEngine.activeBlocks(config(default = Duration.ofHours(1)), installed, monday)
        assertEquals(emptyList<ActiveBlock>(), blocks)
    }

    @Test
    fun `bedtime is reported once, with the hour it ends`() {
        val cfg = config(bedtime = TimeWindow(LocalTime.of(16, 0), LocalTime.of(7, 30)))
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday)
        assertEquals(1, blocks.size, "bedtime closes the phone once, not once per app")
        assertEquals(ActiveBlock.Kind.BEDTIME, blocks[0].kind)
        assertEquals(LocalTime.of(7, 30), blocks[0].until)
    }

    @Test
    fun `a screen-free window is reported with its own end`() {
        val cfg = config(screenFree = listOf(TimeWindow(LocalTime.of(16, 30), LocalTime.of(18, 0))))
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday)
        assertEquals(listOf(ActiveBlock.Kind.SCREEN_FREE), blocks.map { it.kind })
        assertEquals(LocalTime.of(18, 0), blocks[0].until)
    }

    @Test
    fun `a window that is not running now is not a block`() {
        val cfg = config(
            bedtime = TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30)),
            screenFree = listOf(TimeWindow(LocalTime.of(13, 0), LocalTime.of(14, 0))),
        )
        assertEquals(emptyList<ActiveBlock>(), RuleEngine.activeBlocks(cfg, installed, monday))
    }

    @Test
    fun `an app out of time is named, with what it was allowed and what it spent`() {
        val cfg = config(default = Duration.ofMinutes(30))
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(45)),
        )
        assertEquals(listOf(ActiveBlock.Kind.BUDGET), blocks.map { it.kind })
        assertEquals(game, blocks[0].packageName)
        assertEquals(Duration.ofMinutes(30), blocks[0].allowance)
        assertEquals(Duration.ofMinutes(45), blocks[0].used)
    }

    @Test
    fun `extra time already granted counts towards the allowance`() {
        // The parent gave twenty minutes an hour ago; the app is not out of time any more, and
        // a screen still saying so would send them to give the same twenty minutes again.
        val cfg = config(default = Duration.ofMinutes(30))
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(45)),
            extraTime = mapOf(game to Duration.ofMinutes(20)),
        )
        assertEquals(emptyList<ActiveBlock>(), blocks)
    }

    @Test
    fun `bedtime does not hide an app that has also run out`() {
        // The parent lifting bedtime needs to know the app is still spent on the other side of
        // it. The engine reports every app as "blocked by bedtime", which is true and useless.
        val cfg = config(
            bedtime = TimeWindow(LocalTime.of(16, 0), LocalTime.of(7, 30)),
            default = Duration.ofMinutes(30),
        )
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(45)),
        )
        assertEquals(
            listOf(ActiveBlock.Kind.BEDTIME, ActiveBlock.Kind.BUDGET),
            blocks.map { it.kind },
            "the phone-wide rule reads first, the app's own fact still reads",
        )
    }

    @Test
    fun `an app's own window is reported against that app`() {
        val cfg = config(
            perApp = mapOf(
                game to AppPolicy(
                    blockedWindows = mapOf(
                        DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(16, 0), LocalTime.of(19, 0))),
                    ),
                ),
            ),
        )
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday)
        assertEquals(listOf(ActiveBlock.Kind.APP_WINDOW), blocks.map { it.kind })
        assertEquals(game, blocks[0].packageName)
        assertEquals(LocalTime.of(19, 0), blocks[0].until)
    }

    @Test
    fun `an app blocked outright is not an app that ran out of time`() {
        // A limit of zero is how "Blocked" is written, and reporting it as a spent budget said
        // "0s used of 0s" — which reads as a bug, and offered more minutes as the way out of a
        // rule whose entire content is that there are none.
        val cfg = config(perApp = mapOf(game to AppPolicy(dailyBudget = DayType.entries.associateWith { Duration.ZERO })))
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday)
        assertEquals(listOf(ActiveBlock.Kind.APP_BLOCKED), blocks.map { it.kind })
        assertEquals(game, blocks[0].packageName)
        assertEquals(Duration.ZERO, blocks[0].allowance)
        assertTrue(blocks[0].used == null, "nothing was used, and nothing is what it should say")
        assertTrue(!blocks[0].fromDefaultBudget, "this one was set for the app itself")
    }

    @Test
    fun `a family default of zero blocks every app it reaches, and says so`() {
        val blocks = RuleEngine.activeBlocks(config(default = Duration.ZERO), installed, monday)
        assertEquals(listOf(ActiveBlock.Kind.APP_BLOCKED, ActiveBlock.Kind.APP_BLOCKED), blocks.map { it.kind })
        assertTrue(blocks.all { it.fromDefaultBudget }, "it is the family's default doing this")
    }

    @Test
    fun `extra time turns a blocked app into one with time to spend`() {
        // Zero plus a grant is a real allowance, so the app is not blocked outright any more —
        // and once that is spent it is a budget, not a block.
        val cfg = config(perApp = mapOf(game to AppPolicy(dailyBudget = DayType.entries.associateWith { Duration.ZERO })))
        val granted = RuleEngine.activeBlocks(
            cfg, installed, monday,
            extraTime = mapOf(game to Duration.ofMinutes(20)),
        )
        assertEquals(emptyList<ActiveBlock>(), granted)

        val spent = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(25)),
            extraTime = mapOf(game to Duration.ofMinutes(20)),
        )
        assertEquals(listOf(ActiveBlock.Kind.BUDGET), spent.map { it.kind })
        assertEquals(Duration.ofMinutes(20), spent[0].allowance)
        assertEquals(Duration.ZERO, spent[0].budget, "the day's own limit, beside what a grant added")
    }

    @Test
    fun `a budget nobody widened does not pretend a grant is inside it`() {
        val blocks = RuleEngine.activeBlocks(
            config(default = Duration.ofMinutes(30)), installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(45)),
        )
        assertTrue(blocks[0].budget == null, "budget is only stated when a grant made it differ")
        assertTrue(blocks[0].fromDefaultBudget, "and this one comes from the family default")
    }

    @Test
    fun `a blocked app reports even while the counters are stale`() {
        // There is no counter to disbelieve: nothing was allowed today whatever the phone
        // last managed to report.
        val cfg = config(perApp = mapOf(game to AppPolicy(dailyBudget = DayType.entries.associateWith { Duration.ZERO })))
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday, usageIsToday = false)
        assertEquals(listOf(ActiveBlock.Kind.APP_BLOCKED), blocks.map { it.kind })
    }

    @Test
    fun `windows carry both ends, so the rule can be named and not just its exit`() {
        val cfg = config(
            bedtime = TimeWindow(LocalTime.of(16, 0), LocalTime.of(7, 30)),
            screenFree = listOf(TimeWindow(LocalTime.of(16, 30), LocalTime.of(18, 0))),
        )
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday)
        assertEquals(LocalTime.of(16, 0), blocks[0].from)
        assertEquals(LocalTime.of(16, 30), blocks[1].from)
    }

    @Test
    fun `counters that are not today's produce no budget verdict`() {
        // A device that hasn't checked in since yesterday reports real numbers for the wrong
        // day. Quoting them as "out of time today" would be worse than saying nothing.
        val cfg = config(default = Duration.ofMinutes(30))
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(45)),
            usageIsToday = false,
        )
        assertEquals(emptyList<ActiveBlock>(), blocks)
    }

    @Test
    fun `a window still reports while the counters are stale`() {
        // Windows are the clock against the policy: nothing about them needs the child's day.
        val cfg = config(bedtime = TimeWindow(LocalTime.of(16, 0), LocalTime.of(7, 30)))
        val blocks = RuleEngine.activeBlocks(cfg, installed, monday, usageIsToday = false)
        assertEquals(listOf(ActiveBlock.Kind.BEDTIME), blocks.map { it.kind })
    }

    @Test
    fun `an app the phone cannot block is never reported`() {
        // The parent's list is what the child says it can enforce. A limit on anything else is
        // bookkeeping, and reporting it as a block would be the screen inventing a wall.
        val cfg = config(default = Duration.ofMinutes(30))
        val blocks = RuleEngine.activeBlocks(
            cfg, packages = listOf(chat), now = monday,
            usageToday = mapOf(game to Duration.ofHours(3)),
        )
        assertEquals(emptyList<ActiveBlock>(), blocks)
    }

    @Test
    fun `an app that can always be reached is never a block`() {
        val cfg = FamilyConfig(
            version = 1,
            defaultAppBudget = DayType.entries.associateWith { Duration.ofMinutes(30) },
            essentialPackages = setOf(chat),
        )
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(chat to Duration.ofHours(3), game to Duration.ofHours(3)),
        )
        assertEquals(listOf(game), blocks.map { it.packageName })
    }

    @Test
    fun `an app set free of limits cannot run out`() {
        val cfg = config(default = Duration.ofMinutes(30), perApp = mapOf(game to AppPolicy(unlimited = true)))
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofHours(5)),
        )
        assertTrue(blocks.none { it.packageName == game })
    }

    @Test
    fun `spending exactly the allowance is out of time`() {
        // The boundary the engine uses: remaining > 0 is allowed, so zero left is blocked.
        val cfg = config(default = Duration.ofMinutes(30))
        val blocks = RuleEngine.activeBlocks(
            cfg, installed, monday,
            usageToday = mapOf(game to Duration.ofMinutes(30)),
        )
        assertEquals(listOf(game), blocks.map { it.packageName })
    }

    @Test
    fun `the order of the apps does not depend on the order they were installed`() {
        val cfg = config(default = Duration.ofMinutes(30))
        val usage = mapOf(game to Duration.ofHours(1), chat to Duration.ofHours(1))
        assertEquals(
            RuleEngine.activeBlocks(cfg, listOf(game, chat), monday, usage).map { it.packageName },
            RuleEngine.activeBlocks(cfg, listOf(chat, game), monday, usage).map { it.packageName },
        )
    }
}
