package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SchoolCalendarTest {

    private val calendar = SchoolCalendar(
        holidays = setOf(LocalDate.of(2026, 10, 12)),
        vacations = listOf(LocalDate.of(2026, 6, 22)..LocalDate.of(2026, 9, 9)),
    )

    @Test
    fun `laborable es dia lectivo`() {
        assertEquals(DayType.SCHOOL, calendar.dayTypeOf(LocalDate.of(2026, 3, 2))) // lunes
    }

    @Test
    fun `sabado y domingo son fin de semana`() {
        assertEquals(DayType.WEEKEND, calendar.dayTypeOf(LocalDate.of(2026, 3, 7)))
        assertEquals(DayType.WEEKEND, calendar.dayTypeOf(LocalDate.of(2026, 3, 8)))
    }

    @Test
    fun `festivo puntual es holiday aunque caiga en lunes`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `rango de vacaciones es holiday, incluidos los extremos`() {
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 6, 22)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 7, 15)))
        assertEquals(DayType.HOLIDAY, calendar.dayTypeOf(LocalDate.of(2026, 9, 9)))
        assertEquals(DayType.SCHOOL, calendar.dayTypeOf(LocalDate.of(2026, 9, 10))) // jueves
    }
}

class TimeWindowTest {

    @Test
    fun `ventana normal - inicio inclusivo, fin exclusivo`() {
        val school = TimeWindow(LocalTime.of(8, 30), LocalTime.of(14, 30))
        assertTrue(LocalTime.of(8, 30) in school)
        assertTrue(LocalTime.of(12, 0) in school)
        assertFalse(LocalTime.of(14, 30) in school)
        assertFalse(LocalTime.of(20, 0) in school)
    }

    @Test
    fun `ventana que cruza medianoche`() {
        val bedtime = TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30))
        assertTrue(LocalTime.of(23, 0) in bedtime)
        assertTrue(LocalTime.of(3, 0) in bedtime)
        assertTrue(LocalTime.of(21, 30) in bedtime)
        assertFalse(LocalTime.of(7, 30) in bedtime)
        assertFalse(LocalTime.of(12, 0) in bedtime)
    }
}

class RuleEngineTest {

    private val config = FamilyConfig(
        version = 1,
        assignments = mapOf(
            "com.game.fortnite" to "games",
            "org.duolingo" to "edu",
            "com.whatsapp" to "social",
        ),
        policies = mapOf(
            "games" to CategoryPolicy(
                dailyBudget = mapOf(
                    DayType.SCHOOL to Duration.ofMinutes(30),
                    DayType.WEEKEND to Duration.ofHours(2),
                ),
                blockedWindows = mapOf(
                    DayType.SCHOOL to listOf(TimeWindow(LocalTime.of(8, 30), LocalTime.of(14, 30))),
                ),
            ),
            // "edu" sin política: uso libre
            "social" to CategoryPolicy(
                dailyBudget = mapOf(DayType.SCHOOL to Duration.ofMinutes(45)),
            ),
        ),
        bedtime = mapOf(
            DayType.SCHOOL to TimeWindow(LocalTime.of(21, 30), LocalTime.of(7, 30)),
        ),
        essentialPackages = setOf("com.android.dialer", "dev.walcott"),
    )

    // Lunes lectivo y sábado, fuera de ventanas conflictivas salvo que se indique.
    private val schoolAfternoon = LocalDateTime.of(2026, 3, 2, 17, 0)
    private val schoolMorning = LocalDateTime.of(2026, 3, 2, 10, 0)
    private val schoolNight = LocalDateTime.of(2026, 3, 2, 22, 0)
    private val saturdayMorning = LocalDateTime.of(2026, 3, 7, 10, 0)

    @Test
    fun `app esencial permitida siempre, incluso en bedtime`() {
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "com.android.dialer", schoolNight))
    }

    @Test
    fun `bedtime bloquea lo no esencial, incluso apps de uso libre`() {
        assertEquals(
            Verdict.Blocked(BlockReason.BEDTIME),
            RuleEngine.evaluate(config, "org.duolingo", schoolNight),
        )
    }

    @Test
    fun `app sin clasificar queda bloqueada por defecto`() {
        assertEquals(
            Verdict.Blocked(BlockReason.UNCLASSIFIED),
            RuleEngine.evaluate(config, "com.random.newapp", schoolAfternoon),
        )
    }

    @Test
    fun `categoria sin politica es de uso libre`() {
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "org.duolingo", schoolAfternoon))
    }

    @Test
    fun `ventana bloqueada aplica en dia lectivo pero no en fin de semana`() {
        assertEquals(
            Verdict.Blocked(BlockReason.BLOCKED_WINDOW),
            RuleEngine.evaluate(config, "com.game.fortnite", schoolMorning),
        )
        assertEquals(
            Verdict.AllowedWithBudget(Duration.ofHours(2)),
            RuleEngine.evaluate(config, "com.game.fortnite", saturdayMorning),
        )
    }

    @Test
    fun `presupuesto descuenta el uso del dia`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(10)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(20)), verdict)
    }

    @Test
    fun `presupuesto agotado bloquea`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
        )
        assertEquals(Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED), verdict)
    }

    @Test
    fun `tiempo extra aprobado amplia el presupuesto`() {
        val verdict = RuleEngine.evaluate(
            config, "com.game.fortnite", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofMinutes(30)),
            extraTime = mapOf("games" to Duration.ofMinutes(15)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(15)), verdict)
    }

    @Test
    fun `dia sin entrada de presupuesto es ilimitado para esa categoria`() {
        // "social" solo define presupuesto para dias lectivos.
        assertEquals(Verdict.Allowed, RuleEngine.evaluate(config, "com.whatsapp", saturdayMorning))
    }

    @Test
    fun `el uso de una categoria no afecta a otra`() {
        val verdict = RuleEngine.evaluate(
            config, "com.whatsapp", schoolAfternoon,
            usageToday = mapOf("games" to Duration.ofHours(5)),
        )
        assertEquals(Verdict.AllowedWithBudget(Duration.ofMinutes(45)), verdict)
    }
}
