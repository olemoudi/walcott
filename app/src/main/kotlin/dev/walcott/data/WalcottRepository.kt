package dev.walcott.data

import dev.walcott.rules.EarnEngine
import dev.walcott.rules.FamilyConfig
import dev.walcott.sync.LocationPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate

/**
 * Single facade over persistence (Room + DataStore) and inventory. The UI consumes reactive
 * flows; the enforcement service uses the snapshot functions (`*Now`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * The current epoch day, re-checked once a minute. On an always-on child device the
     * process lives across midnight, so anything keyed to "today" must re-subscribe when
     * the day changes — a flow pinned to the construction-time day would show yesterday's
     * usage forever after the first rollover.
     */
    private val todayFlow: Flow<Long> = flow {
        while (true) {
            emit(LocalDate.now().toEpochDay())
            delay(DAY_CHECK_MILLIS)
        }
    }.distinctUntilChanged()

    /** Today's usage per category, reactive across midnight rollovers (per-app counters stripped). */
    val usageTodayFlow: Flow<Map<String, Duration>> = todayFlow.flatMapLatest { day ->
        db.usage().observeDay(day)
            .map { rows -> rows.filterNot { it.categoryId.contains('.') }.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }
    }

    val extraTodayFlow: Flow<Map<String, Duration>> = todayFlow.flatMapLatest { day ->
        db.usage().observeExtraDay(day)
            .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }
    }

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

    /**
     * All of today's usage counters, keyed by categoryId AND by package (per-app budgets are
     * counted under the package name — which always contains a dot, so it never collides with a
     * category id). The enforcement engine needs both; reports to the parent use [reportedUsageNow].
     */
    suspend fun usageNow(): Map<String, Duration> =
        db.usage().getDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    /** Today's per-category usage only (strips per-app package counters), for the parent report. */
    suspend fun reportedUsageNow(): Map<String, Duration> = usageNow().filterKeys { !it.contains('.') }

    suspend fun extraNow(): Map<String, Duration> =
        db.usage().getExtraDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    /** Granted + earned extra, as the enforcement service applies it. */
    suspend fun effectiveExtraNow(): Map<String, Duration> {
        val earned = EarnEngine.computeEarned(settingsStore.current().toEarnRules(), usageNow())
        return sumDurations(extraNow(), earned)
    }

    /** Usage for the last 7 days: epochDay -> (categoryId -> duration). Per-app counters stripped. */
    suspend fun weeklyUsage(): Map<Long, Map<String, Duration>> {
        val end = today()
        return db.usage().getRange(end - 6, end)
            .filterNot { it.categoryId.contains('.') }
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

    // --- Location history (child device only) ---

    /** Stores a fix and prunes anything older than the retention window. */
    suspend fun recordLocation(point: LocationPoint) {
        db.locations().insert(
            LocationPointEntity(
                epochMs = point.epochMs, lat = point.lat, lng = point.lng, accuracyM = point.accuracyM, mock = point.mock,
            ),
        )
        db.locations().deleteOlderThan(System.currentTimeMillis() - LOCATION_RETENTION_MS)
    }

    /** The last [LOCATION_RETENTION_MS] of fixes, oldest first, for the parent's map. */
    suspend fun recentLocations(): List<LocationPoint> =
        db.locations().getSince(System.currentTimeMillis() - LOCATION_RETENTION_MS).map { it.toPoint() }

    /**
     * Just the current position, for children whose parent hasn't enabled location history.
     * History is always retained locally, so switching the option on shows the past 48h
     * immediately instead of starting from empty.
     */
    suspend fun latestLocation(): List<LocationPoint> =
        listOfNotNull(db.locations().getLatestSince(System.currentTimeMillis() - LOCATION_RETENTION_MS)?.toPoint())

    private fun LocationPointEntity.toPoint() =
        LocationPoint(lat = lat, lng = lng, epochMs = epochMs, accuracyM = accuracyM, mock = mock)

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

    /** One-time: turn the recommended anti-tamper restrictions on by default (parent edits sync down). */
    suspend fun seedHardeningIfNeeded() {
        if (settingsStore.current().hardeningSeeded) return
        updateSettings { it.seedRestrictions(dev.walcott.enforcement.DeviceRestrictions.RECOMMENDED_DEFAULTS) }
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

    companion object {
        /**
         * Location history retention shown on the parent map. Matches the trail window the
         * child publishes, so the timeline never runs past the data it has.
         */
        const val LOCATION_RETENTION_MS = dev.walcott.sync.LocationTrail.WINDOW_MS
        /** How often the "today" flows re-check the date (cheap; rollover lands within a minute). */
        private const val DAY_CHECK_MILLIS = 60_000L
    }
}
