package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Whole-day, minute-tick simulations of a child device against the pure engines — the
 * closest thing to "boot a phone and watch the rules" that runs in milliseconds. The clock
 * is virtual: one tick = one simulated minute, so bedtime boundaries, budget exhaustion and
 * idle-earn caps are exercised across full days with zero real waiting.
 *
 * These are interaction tests: each engine is unit-tested elsewhere; here we verify the
 * *sequence* a device would actually produce over a day (precedence at boundaries, usage
 * only accruing while allowed, earned time widening budgets, day-type flips at midnight).
 */
class DaySimulationTest {

    private companion object {
        const val GAMES = "games"
        const val GAME_APP = "com.example.game"
        /** A Monday, so the calendar resolves SCHOOL; weekend tests start on a Friday. */
        val MONDAY: LocalDate = LocalDate.of(2026, 3, 2)
        val FRIDAY: LocalDate = LocalDate.of(2026, 3, 6)
    }

    /**
     * Minute-by-minute device: at each tick it evaluates the rule engine and, when the
     * verdict allows and the child is "using the app", credits one minute of usage —
     * mirroring the enforcement loop's credit-only-while-foreground rule.
     */
    private class SimDevice(val config: FamilyConfig, start: LocalDateTime) {
        var now: LocalDateTime = start
            private set
        val usage = mutableMapOf<String, Duration>()
        val extra = mutableMapOf<String, Duration>()

        fun verdict(pkg: String): Verdict = RuleEngine.evaluate(config, pkg, now, usage, extra)

        /** Ticks one minute with the child glued to [pkg]; usage accrues only if allowed. */
        fun tickUsing(pkg: String): Verdict {
            val v = verdict(pkg)
            if (v !is Verdict.Blocked) {
                val categoryId = config.assignments[pkg]
                if (categoryId != null) usage.merge(categoryId, Duration.ofMinutes(1), Duration::plus)
                if (pkg in config.perAppPolicies) usage.merge(pkg, Duration.ofMinutes(1), Duration::plus)
            }
            now = now.plusMinutes(1)
            return v
        }

        fun tickIdle() {
            now = now.plusMinutes(1)
        }

        fun jumpTo(time: LocalTime) {
            require(!now.toLocalTime().isAfter(time)) { "cannot jump backwards" }
            now = LocalDateTime.of(now.toLocalDate(), time)
        }

        fun grantExtra(categoryId: String, minutes: Long) {
            extra.merge(categoryId, Duration.ofMinutes(minutes), Duration::plus)
        }
    }

    private fun schoolDayConfig() = FamilyConfig(
        version = 1,
        assignments = mapOf(GAME_APP to GAMES),
        policies = mapOf(
            GAMES to CategoryPolicy(
                dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(240), DayType.WEEKEND to Duration.ofMinutes(300)),
                blockedWindows = mapOf(
                    DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(9, 0), LocalTime.of(14, 0))),
                ),
            ),
        ),
        bedtime = mapOf(
            DayType.SCHOOL to TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30)),
            DayType.WEEKEND to TimeWindow(LocalTime.of(23, 0), LocalTime.of(9, 30)),
        ),
    )

    // --- A full school day, minute by minute ---

    @Test
    fun `a school day produces the exact verdict sequence a parent configured`() {
        val device = SimDevice(schoolDayConfig(), LocalDateTime.of(MONDAY, LocalTime.of(7, 0)))
        // Record each STATE change while the child tries to play all day. The budget verdict
        // counts as one state (its remaining ticks down every minute); what we assert is the
        // verdict at the moment each state was entered.
        fun stateOf(v: Verdict) = when (v) {
            is Verdict.Blocked -> v.reason.name
            else -> "ALLOWED"
        }

        val transitions = mutableListOf<Pair<LocalTime, Verdict>>()
        var last: String? = null
        while (device.now.toLocalDate() == MONDAY) {
            val at = device.now.toLocalTime()
            val v = device.tickUsing(GAME_APP)
            if (stateOf(v) != last) {
                transitions += at to v
                last = stateOf(v)
            }
        }

        val expected = listOf(
            // Waking up inside school bedtime (21:30–07:30): still blocked.
            LocalTime.of(7, 0) to Verdict.Blocked(BlockReason.BEDTIME),
            // 07:30 bedtime lifts; full 240m budget ahead.
            LocalTime.of(7, 30) to Verdict.AllowedWithBudget(Duration.ofMinutes(240)),
            // 09:00 school block window, regardless of remaining budget.
            LocalTime.of(9, 0) to Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            // 14:00 window ends; 90m were consumed 07:30–09:00, so 150m remain.
            LocalTime.of(14, 0) to Verdict.AllowedWithBudget(Duration.ofMinutes(150)),
            // 16:30 the last budgeted minute ran out.
            LocalTime.of(16, 30) to Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            // 21:30 bedtime again (asserted below to also outrank any grant).
            LocalTime.of(21, 30) to Verdict.Blocked(BlockReason.BEDTIME),
        )
        assertEquals(expected, transitions)
    }

    @Test
    fun `remaining budget counts down only while the child is actually playing`() {
        val device = SimDevice(schoolDayConfig(), LocalDateTime.of(MONDAY, LocalTime.of(14, 0)))
        repeat(30) { device.tickUsing(GAME_APP) } // 30m of play
        repeat(60) { device.tickIdle() } // an hour off the phone
        val verdict = device.verdict(GAME_APP)
        // Only the played minutes count: 240 − 30, the idle hour costs nothing.
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(210)), verdict)
    }

    @Test
    fun `an extra-time grant revives an exhausted budget and runs out again`() {
        val device = SimDevice(schoolDayConfig(), LocalDateTime.of(MONDAY, LocalTime.of(14, 0)))
        repeat(240) { device.tickUsing(GAME_APP) } // burn the whole budget → 18:00
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), device.verdict(GAME_APP))

        device.grantExtra(GAMES, 30)
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(30)), device.verdict(GAME_APP))

        repeat(30) { device.tickUsing(GAME_APP) }
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), device.verdict(GAME_APP))
    }

    @Test
    fun `bedtime outranks granted extra time`() {
        val device = SimDevice(schoolDayConfig(), LocalDateTime.of(MONDAY, LocalTime.of(21, 30)))
        device.grantExtra(GAMES, 120)
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), device.verdict(GAME_APP))
    }

    // --- Midnight and day-type boundaries ---

    @Test
    fun `friday night into saturday flips bedtime and budget to the weekend rules`() {
        val device = SimDevice(schoolDayConfig(), LocalDateTime.of(FRIDAY, LocalTime.of(23, 30)))
        // Friday 23:30: Friday is SCHOOL; its 21:30–07:30 bedtime is in force.
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), device.verdict(GAME_APP))

        // Saturday 00:30: the day is now WEEKEND, whose own bedtime (23:00–09:30) applies.
        var saturday = SimDevice(schoolDayConfig(), LocalDateTime.of(FRIDAY.plusDays(1), LocalTime.of(0, 30)))
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), saturday.verdict(GAME_APP))

        // Saturday 09:29 still asleep; 09:30 wakes into the WEEKEND budget (300m, not 240m).
        saturday = SimDevice(schoolDayConfig(), LocalDateTime.of(FRIDAY.plusDays(1), LocalTime.of(9, 29)))
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), saturday.verdict(GAME_APP))
        saturday.tickIdle()
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(300)), saturday.verdict(GAME_APP))
    }

    @Test
    fun `a holiday on a monday uses holiday rules, not school rules`() {
        val config = schoolDayConfig().let {
            it.copy(
                calendar = SchoolCalendar(holidays = setOf(MONDAY)),
                policies = mapOf(
                    GAMES to it.policies.getValue(GAMES).copy(
                        dailyBudget = it.policies.getValue(GAMES).dailyBudget + (DayType.HOLIDAY to Duration.ofMinutes(180)),
                    ),
                ),
            )
        }
        val device = SimDevice(config, LocalDateTime.of(MONDAY, LocalTime.of(10, 0)))
        // 10:00 on a school day would be inside the 09:00–14:00 school block; on the
        // holiday there is no block and the HOLIDAY budget applies.
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(180)), device.verdict(GAME_APP))
    }

    // --- Per-app sub-cap over a day ---

    @Test
    fun `the per-app sub-cap bites before the category budget and ignores extra time`() {
        val config = schoolDayConfig().copy(
            perAppPolicies = mapOf(
                GAME_APP to CategoryPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(45))),
            ),
        )
        val device = SimDevice(config, LocalDateTime.of(MONDAY, LocalTime.of(14, 0)))
        repeat(45) { device.tickUsing(GAME_APP) }
        // Category still has 195m, but this app's own 45m cap is spent.
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), device.verdict(GAME_APP))
        // Extra time widens the CATEGORY only; the per-app cap is a hard ceiling.
        device.grantExtra(GAMES, 60)
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), device.verdict(GAME_APP))
    }

    // --- Idle-earn across a day and a week ---

    /**
     * Idle-earn sim: banks a minute of idle per tick inside the earn windows (mirroring the
     * enforcement loop), converts through [IdleEarnEngine] on demand, and stamps grants with
     * the virtual epoch so the rolling caps see simulated hours, not real ones.
     */
    private class EarnSim(val config: IdleEarnConfig, val calendar: SchoolCalendar, start: LocalDateTime) {
        var now: LocalDateTime = start
            private set
        private val origin = start
        var bankMinutes = 0L
            private set
        val ledger = mutableListOf<EarnGrant>()

        private fun epochMs() = Duration.between(origin, now).toMillis()

        fun idle(minutes: Int) = repeat(minutes) {
            val earning = IdleEarnEngine.isEarningTime(config, calendar.dayTypeOf(now.toLocalDate()), now.toLocalTime())
            if (earning) bankMinutes++
            now = now.plusMinutes(1)
        }

        fun advance(minutes: Long) {
            now = now.plusMinutes(minutes)
        }

        /** Converts what the caps allow right now; returns the granted minutes. */
        fun convert(): Int {
            val grant = IdleEarnEngine.grantableMinutes(config, ledger, bankMinutes, epochMs())
            if (grant > 0) {
                bankMinutes -= IdleEarnEngine.idleConsumedFor(config, grant)
                ledger += EarnGrant(epochMs(), grant)
            }
            return grant
        }
    }

    private val earnConfig = IdleEarnConfig(
        targetCategoryId = GAMES,
        minutesIdlePerReward = 30, // half an hour off the phone…
        rewardMinutes = 10, // …earns 10 minutes of games
        windowHours = 2,
        windowCapMinutes = 20,
        weeklyCapMinutes = 40,
        earnWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(16, 0), LocalTime.of(20, 0)))),
    )

    @Test
    fun `idle outside the earn window banks nothing`() {
        val sim = EarnSim(earnConfig, SchoolCalendar(), LocalDateTime.of(MONDAY, LocalTime.of(8, 0)))
        sim.idle(60) // 08:00–09:00, window is 16:00–20:00
        assertEquals(0, sim.bankMinutes)
        assertEquals(0, sim.convert())
    }

    @Test
    fun `an afternoon of idle earns up to the rolling-window cap, then resumes when it slides`() {
        val sim = EarnSim(earnConfig, SchoolCalendar(), LocalDateTime.of(MONDAY, LocalTime.of(16, 0)))

        sim.idle(60) // 16:00–17:00 banked
        // 60 idle = 2 reward blocks = 20m, exactly the 2h-window cap.
        assertEquals(20, sim.convert())
        assertEquals(0, sim.bankMinutes)

        sim.idle(90) // 17:00–18:30, bank refills
        // Window cap already earned in the last 2h: nothing more yet.
        assertEquals(0, sim.convert())

        // 19:01: the 17:00 grant has slid out of the 2h window; caps free up again
        // (conversion is time-based; the earn window only gates the BANKING of idle).
        sim.advance(31)
        assertEquals(20, sim.convert())
        assertEquals(30, sim.bankMinutes) // 90 banked − 60 consumed for the two blocks
    }

    @Test
    fun `the weekly cap stops earning across days and frees up after seven`() {
        val sim = EarnSim(earnConfig, SchoolCalendar(), LocalDateTime.of(MONDAY, LocalTime.of(16, 0)))
        sim.idle(60) // 16:00–17:00, inside the earn window
        assertEquals(20, sim.convert())
        sim.idle(120) // 17:00–19:00, still inside; bank refills while the window cap is hot
        sim.advance(1) // 19:01: the 17:00 grant slides out of the 2h rolling window
        assertEquals(20, sim.convert()) // weekly total now 40 = cap

        // Tuesday afternoon: plenty banked and the rolling window is clear — but the week is full.
        sim.advance(20 * 60 + 59) // 19:01 → 16:00 next day
        sim.idle(120)
        assertTrue(sim.bankMinutes >= 60)
        assertEquals(0, sim.convert())

        // A week after those grants they expire from the ledger; earning resumes.
        sim.advance(7 * 24 * 60)
        assertEquals(20, sim.convert())
    }

    @Test
    fun `earned minutes actually widen the game budget in the rule engine`() {
        val sim = EarnSim(earnConfig, SchoolCalendar(), LocalDateTime.of(MONDAY, LocalTime.of(16, 0)))
        sim.idle(60)
        val earned = sim.convert()
        assertEquals(20, earned)

        val device = SimDevice(schoolDayConfig(), LocalDateTime.of(MONDAY, LocalTime.of(17, 0)))
        repeat(240) { device.tickUsing(GAME_APP) } // category budget fully spent
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), device.verdict(GAME_APP))
        device.grantExtra(GAMES, earned.toLong())
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(20)), device.verdict(GAME_APP))
    }

    // --- The unclassified default over a day ---

    @Test
    fun `an app the parent never classified stays blocked all day except never during essential use`() {
        val config = schoolDayConfig().copy(essentialPackages = setOf("com.android.dialer"))
        val stranger = "com.random.new.app"
        val device = SimDevice(config, LocalDateTime.of(MONDAY, LocalTime.of(12, 0)))
        // Midday, evening, night: always blocked as unclassified (bedtime shows through at night).
        assertEquals(Verdict.Blocked(BlockReason.UNCLASSIFIED), device.verdict(stranger))
        device.jumpTo(LocalTime.of(18, 0))
        assertEquals(Verdict.Blocked(BlockReason.UNCLASSIFIED), device.verdict(stranger))
        device.jumpTo(LocalTime.of(22, 0))
        assertEquals(Verdict.Blocked(BlockReason.BEDTIME), device.verdict(stranger))
        // The phone app is untouchable at any hour, bedtime included.
        assertEquals(Verdict.Allowed, device.verdict("com.android.dialer"))
    }
}
