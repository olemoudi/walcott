package dev.walcott.data

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

    val assignmentsFlow: Flow<Map<String, String>> = db.assignments().observeAll()
        .map { rows -> rows.associate { it.packageName to it.categoryId } }

    val familyConfigFlow: Flow<FamilyConfig> =
        combine(settingsFlow, assignmentsFlow) { settings, assignments ->
            settings.toFamilyConfig(assignments, essentials)
        }

    /** Today's usage per category, reactive (pinned to the subscription day). */
    val usageTodayFlow: Flow<Map<String, Duration>> = db.usage().observeDay(today())
        .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }

    val extraTodayFlow: Flow<Map<String, Duration>> = db.usage().observeExtraDay(today())
        .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }

    // --- Snapshots for the service (always recompute "today") ---

    suspend fun configNow(): FamilyConfig {
        val settings = settingsStore.current()
        val assignments = db.assignments().getAll().associate { it.packageName to it.categoryId }
        return settings.toFamilyConfig(assignments, essentials)
    }

    suspend fun usageNow(): Map<String, Duration> =
        db.usage().getDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    suspend fun extraNow(): Map<String, Duration> =
        db.usage().getExtraDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    suspend fun managedPackagesNow(): Set<String> {
        val assignments = db.assignments().getAll().associate { it.packageName to it.categoryId }
        return inventory.managedPackages(assignments)
    }

    suspend fun addUsageSeconds(categoryId: String, seconds: Long) =
        db.usage().addSeconds(categoryId, today(), seconds)

    suspend fun grantExtraMinutes(categoryId: String, minutes: Long) =
        db.usage().addExtraSeconds(categoryId, today(), minutes * 60)

    // --- Assignments ---

    suspend fun assign(packageName: String, categoryId: String) =
        db.assignments().upsert(AppAssignmentEntity(packageName, categoryId))

    suspend fun unassign(packageName: String) = db.assignments().delete(packageName)

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
