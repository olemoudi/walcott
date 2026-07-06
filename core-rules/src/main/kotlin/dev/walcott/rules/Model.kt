package dev.walcott.rules

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

enum class DayType { SCHOOL, WEEKEND, HOLIDAY }

/** Festivos y vacaciones editables por el padre; decide el tipo de día. */
data class SchoolCalendar(
    val holidays: Set<LocalDate> = emptySet(),
    val vacations: List<ClosedRange<LocalDate>> = emptyList(),
) {
    fun dayTypeOf(date: LocalDate): DayType = when {
        date in holidays || vacations.any { date in it } -> DayType.HOLIDAY
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> DayType.WEEKEND
        else -> DayType.SCHOOL
    }
}

/** Ventana horaria [start, end); puede cruzar medianoche (p. ej. 21:30–07:30). */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    operator fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end
        else time >= start || time < end
}

data class CategoryPolicy(
    /** Presupuesto diario por tipo de día; sin entrada = sin límite de tiempo ese día. */
    val dailyBudget: Map<DayType, Duration> = emptyMap(),
    /** Ventanas de bloqueo total por tipo de día (p. ej. horario escolar). */
    val blockedWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
)

data class FamilyConfig(
    /** Versión monótona del emisor; el sync usa last-write-wins sobre ella. */
    val version: Long,
    /** package -> categoryId. Los paquetes no listados están sin clasificar (bloqueados). */
    val assignments: Map<String, String>,
    /** categoryId -> política. Una categoría sin política es de uso libre. */
    val policies: Map<String, CategoryPolicy>,
    /** Ventana de sueño por tipo de día: bloquea todo lo no esencial. */
    val bedtime: Map<DayType, TimeWindow> = emptyMap(),
    /** Nunca se bloquean: teléfono, contactos, la propia app… */
    val essentialPackages: Set<String> = emptySet(),
    val calendar: SchoolCalendar = SchoolCalendar(),
)

sealed interface Verdict {
    /** Permitida sin límite de tiempo aplicable en este momento. */
    data object Allowed : Verdict

    /** Permitida; a su categoría le queda este presupuesto hoy. */
    data class AllowedWithBudget(val remaining: Duration) : Verdict

    data class Blocked(val reason: BlockReason) : Verdict
}

enum class BlockReason { UNCLASSIFIED, BEDTIME, BLOCKED_WINDOW, BUDGET_EXHAUSTED }
