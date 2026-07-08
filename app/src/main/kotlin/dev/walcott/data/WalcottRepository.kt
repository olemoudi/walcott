package dev.walcott.data

import dev.walcott.rules.EarnEngine
import dev.walcott.rules.FamilyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate

/**
 * Single facade over persistence (Room + DataStore) and inventory. The UI consumes reactive
 * flows; the enforcement service uses the snapshot functions (`*Now`).
 */
class WalcottRepository(
    private val db: WalcottDatabase,
    private val settingsStore: SettingsStore,
    val inventory: AppInventory,
    private val ownPackage: String,
) {
    private fun today(): Long = LocalDate.now().toEpochDay()
    private val essentials: Set<String> get() = setOf(ownPackage)

    val settingsFlow: Flow<PolicySettings> = settingsStore.settings

    /** App -> categoryId assignments, now sourced from the synced policy. */
    val assignmentsFlow: Flow<Map<String, String>> = settingsFlow.map { it.assignments }

    val familyConfigFlow: Flow<FamilyConfig> =
        settingsFlow.map { it.toFamilyConfig(essentials) }

    /** Today's usage per category, reactive (pinned to the subscription day). */
    val usageTodayFlow: Flow<Map<String, Duration>> = db.usage().observeDay(today())
        .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }

    val extraTodayFlow: Flow<Map<String, Duration>> = db.usage().observeExtraDay(today())
        .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }

    /** Extra earned today from earn-rules (kept separate so the UI can show the bonus). */
    val earnedTodayFlow: Flow<Map<String, Duration>> =
        combine(settingsFlow, usageTodayFlow) { settings, usage ->
            EarnEngine.computeEarned(settings.toEarnRules(), usage)
        }

    /** Total extra applied to budgets today: manually granted + earned by use. */
    val effectiveExtraTodayFlow: Flow<Map<String, Duration>> =
        combine(extraTodayFlow, earnedTodayFlow) { granted, earned -> sumDurations(granted, earned) }

    // --- Snapshots for the service (always recompute "today") ---

    suspend fun configNow(): FamilyConfig =
        settingsStore.current().toFamilyConfig(essentials)

    suspend fun usageNow(): Map<String, Duration> =
        db.usage().getDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    suspend fun extraNow(): Map<String, Duration> =
        db.usage().getExtraDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    /** Granted + earned extra, as the enforcement service applies it. */
    suspend fun effectiveExtraNow(): Map<String, Duration> {
        val earned = EarnEngine.computeEarned(settingsStore.current().toEarnRules(), usageNow())
        return sumDurations(extraNow(), earned)
    }

    /** Usage for the last 7 days: epochDay -> (categoryId -> duration). */
    suspend fun weeklyUsage(): Map<Long, Map<String, Duration>> {
        val end = today()
        return db.usage().getRange(end - 6, end)
            .groupBy { it.epochDay }
            .mapValues { (_, rows) -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }
    }

    private fun sumDurations(a: Map<String, Duration>, b: Map<String, Duration>): Map<String, Duration> {
        val out = a.toMutableMap()
        b.forEach { (k, v) -> out[k] = (out[k] ?: Duration.ZERO) + v }
        return out
    }

    suspend fun managedPackagesNow(): Set<String> =
        inventory.managedPackages(settingsStore.current().assignments)

    suspend fun addUsageSeconds(categoryId: String, seconds: Long) =
        db.usage().addSeconds(categoryId, today(), seconds)

    suspend fun grantExtraMinutes(categoryId: String, minutes: Long) =
        db.usage().addExtraSeconds(categoryId, today(), minutes * 60)

    // --- Assignments (in the synced policy; changes republish to children) ---

    suspend fun assign(packageName: String, categoryId: String) =
        updateSettings { it.copy(assignments = it.assignments + (packageName to categoryId)) }

    suspend fun unassign(packageName: String) =
        updateSettings { it.copy(assignments = it.assignments - packageName) }

    /**
     * One-time migration of legacy Room assignments into the policy. Idempotent and a no-op
     * on children (their table is empty) and once assignments already live in the policy.
     */
    suspend fun migrateLocalAssignmentsToSettings() {
        if (settingsStore.current().assignments.isNotEmpty()) return
        val legacy = db.assignments().getAll().associate { it.packageName to it.categoryId }
        if (legacy.isEmpty()) return
        updateSettings { it.withLegacyAssignments(legacy) }
    }

    // --- Parent PIN ---

    suspend fun hasPin(): Boolean = settingsStore.current().pinHash != null

    suspend fun setPin(pin: String) {
        val hashed = Pin.hash(pin)
        settingsStore.update { it.copy(pinHash = hashed.hash, pinSalt = hashed.salt) }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val s = settingsStore.current()
        val hash = s.pinHash ?: return false
        val salt = s.pinSalt ?: return false
        return Pin.verify(pin, hash, salt)
    }

    // --- Rule editing (parent mode) ---

    suspend fun updateSettings(transform: (PolicySettings) -> PolicySettings) {
        // Every rule change bumps the version (relevant for Phase 2 sync).
        settingsStore.update { current ->
            transform(current).copy(version = current.version + 1)
        }
    }
}
