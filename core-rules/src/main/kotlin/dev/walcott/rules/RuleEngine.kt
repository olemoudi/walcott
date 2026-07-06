package dev.walcott.rules

import java.time.Duration
import java.time.LocalDateTime

/**
 * Evaluador determinista y sin estado: no lee reloj ni almacenamiento — todo entra
 * por parámetros. El servicio de enforcement lo invoca con el estado real; los tests,
 * con el que quieran reproducir.
 *
 * Precedencia: esencial > bedtime > sin clasificar > ventana bloqueada > presupuesto.
 */
object RuleEngine {

    fun evaluate(
        config: FamilyConfig,
        packageName: String,
        now: LocalDateTime,
        /** Uso acumulado hoy por categoría (categoryId -> duración). */
        usageToday: Map<String, Duration> = emptyMap(),
        /** Tiempo extra concedido hoy por categoría (aprobaciones, ledger gastado…). */
        extraTime: Map<String, Duration> = emptyMap(),
    ): Verdict {
        if (packageName in config.essentialPackages) return Verdict.Allowed

        val dayType = config.calendar.dayTypeOf(now.toLocalDate())
        val time = now.toLocalTime()

        config.bedtime[dayType]?.let { window ->
            if (time in window) return Verdict.Blocked(BlockReason.BEDTIME)
        }

        val categoryId = config.assignments[packageName]
            ?: return Verdict.Blocked(BlockReason.UNCLASSIFIED)
        val policy = config.policies[categoryId] ?: return Verdict.Allowed

        if (policy.blockedWindows[dayType].orEmpty().any { time in it }) {
            return Verdict.Blocked(BlockReason.BLOCKED_WINDOW)
        }

        val budget = policy.dailyBudget[dayType] ?: return Verdict.Allowed
        val allowedTotal = budget + (extraTime[categoryId] ?: Duration.ZERO)
        val remaining = allowedTotal - (usageToday[categoryId] ?: Duration.ZERO)
        return if (remaining > Duration.ZERO) {
            Verdict.AllowedWithBudget(remaining)
        } else {
            Verdict.Blocked(BlockReason.BUDGET_EXHAUSTED)
        }
    }
}
