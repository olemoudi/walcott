package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class PerAppPolicyTest {

    private val schoolAfternoon = LocalDateTime.of(2026, 3, 3, 16, 0) // a Tuesday

    private fun config(
        categoryBudget: Map<DayType, Duration> = emptyMap(),
        categoryWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
        appBudget: Map<DayType, Duration> = emptyMap(),
        appWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
    ) = FamilyConfig(
        version = 1,
        assignments = mapOf("com.chat" to "social"),
        policies = mapOf("social" to CategoryPolicy(categoryBudget, categoryWindows)),
        perAppPolicies = if (appBudget.isEmpty() && appWindows.isEmpty()) {
            emptyMap()
        } else {
            mapOf("com.chat" to CategoryPolicy(appBudget, appWindows))
        },
        essentialPackages = setOf("dev.walcott"),
    )

    @Test
    fun `per-app budget blocks earlier than the category budget`() {
        // Social has 60 min; com.chat is sub-capped at 20.
        val cfg = config(
            categoryBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)),
            appBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(20)),
        )
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            usageToday = mapOf("social" to Duration.ofMinutes(25), "com.chat" to Duration.ofMinutes(25)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `remaining is the tighter of category and per-app`() {
        val cfg = config(
            categoryBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)),
            appBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(20)),
        )
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            usageToday = mapOf("social" to Duration.ofMinutes(10), "com.chat" to Duration.ofMinutes(10)),
        )
        // category left = 50, app left = 10 -> min = 10.
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(10)), verdict)
    }

    @Test
    fun `category budget still blocks even if the app has its own smaller cap unmet`() {
        val cfg = config(
            categoryBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(30)),
            appBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(90)),
        )
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            usageToday = mapOf("social" to Duration.ofMinutes(30), "com.chat" to Duration.ofMinutes(5)),
        )
        // App has 85 left, but the category is exhausted -> blocked. Per-app never loosens.
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `per-app blocked window blocks even when the category allows`() {
        val cfg = config(
            appWindows = mapOf(DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(15, 0), LocalTime.of(17, 0)))),
        )
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(cfg, "com.chat", schoolAfternoon),
        )
    }

    @Test
    fun `an app with no per-app policy behaves exactly as before`() {
        val cfg = config(categoryBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(60)))
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            usageToday = mapOf("social" to Duration.ofMinutes(20)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(40)), verdict)
    }

    @Test
    fun `per-app budget applies even when the category is unrestricted`() {
        val cfg = config(appBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(15)))
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            usageToday = mapOf("com.chat" to Duration.ofMinutes(15)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `a zero per-app budget blocks that day type even with category room and no usage`() {
        // "Block this app completely on school days" = a 0-minute per-app cap, even though
        // the category is wide open and nothing has been used yet.
        val cfg = config(
            categoryBudget = mapOf(DayType.SCHOOL to Duration.ofHours(3)),
            appBudget = mapOf(DayType.SCHOOL to Duration.ZERO),
        )
        val verdict = RuleEngine.evaluate(cfg, "com.chat", schoolAfternoon)
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `blocking one day type leaves the others untouched`() {
        // School blocked (0), weekend has no entry -> weekend behaves as if no per-app cap.
        val saturdayAfternoon = LocalDateTime.of(2026, 3, 7, 16, 0)
        val cfg = config(appBudget = mapOf(DayType.SCHOOL to Duration.ZERO))
        assertEquals(
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED),
            RuleEngine.evaluate(cfg, "com.chat", schoolAfternoon),
        )
        // Weekend: no app cap, category unrestricted -> allowed.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(cfg, "com.chat", saturdayAfternoon))
    }

    @Test
    fun `a zero per-app cap is a hard block that extra time cannot lift`() {
        val cfg = config(appBudget = mapOf(DayType.SCHOOL to Duration.ZERO))
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            extraTime = mapOf("social" to Duration.ofHours(2)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `per-app extra time does not widen the per-app sub-cap`() {
        // extraTime is keyed by category; it must not loosen the per-app package budget.
        val cfg = config(appBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(15)))
        val verdict = RuleEngine.evaluate(
            cfg, "com.chat", schoolAfternoon,
            usageToday = mapOf("com.chat" to Duration.ofMinutes(15)),
            extraTime = mapOf("social" to Duration.ofMinutes(60)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }
}
