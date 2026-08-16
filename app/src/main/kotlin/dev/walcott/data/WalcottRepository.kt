package dev.walcott.data

import dev.walcott.rules.FamilyConfig
import dev.walcott.sync.LocationPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    /**
     * Apps no rule of ours may ever touch: Walcott itself, the phone, and contacts.
     *
     * Reaching a person is not a convenience — a child has to be able to call at any hour,
     * including the middle of bedtime, and especially to call the parent who set the rules;
     * and a number they cannot look up is a call they cannot make. Both are asked of the
     * system (see [AppInventory.alwaysReachablePackages]) rather than assumed to be system
     * apps, so a device where either is an ordinary installed app is covered too.
     */
    private val essentials: Set<String>
        get() = setOf(ownPackage) + inventory.alwaysReachablePackages()

    val settingsFlow: Flow<PolicySettings> = settingsStore.settings

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

    /**
     * Today's counters exactly as stored — per-app package counters included — reactive across
     * midnight rollovers.
     *
     * What the enforcement loop watches instead of querying. It used to call [usageNow] and
     * [extraNow] on every tick, which is two database reads every two seconds while a child is
     * using a limited app; Room's invalidation tracker pushes the same numbers for free the
     * moment they change, and the loop's own writes are what change them.
     */
    val usageTodayAllFlow: Flow<Map<String, Duration>> = todayFlow.flatMapLatest { day ->
        db.usage().observeDay(day)
            .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }
    }

    // usageTodayFlow used to sit here: the same map with every key containing a dot removed,
    // which since limits went per app in 0.35 meant every counter there was. It was left over
    // from the category era and read like a reasonable default, so it quietly caught two
    // callers that needed the opposite — the child's own home (which then showed time left it
    // was not counting down) and the accessibility blocker (whose budgets therefore never
    // fired at all). Deleted rather than fixed: there is no caller that wants usage with the
    // usage taken out, and leaving it would only wait for a third.

    val extraTodayFlow: Flow<Map<String, Duration>> = todayFlow.flatMapLatest { day ->
        db.usage().observeExtraDay(day)
            .map { rows -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }
    }

    /**
     * Total extra applied to budgets today. Idle-earned time is granted straight into
     * [extraTodayFlow] by the enforcement service (see [dev.walcott.sync.SyncManager]), so this
     * is simply the granted extra — no separate earn recomputation, no double counting.
     */
    val effectiveExtraTodayFlow: Flow<Map<String, Duration>> = extraTodayFlow

    // --- Snapshots for the service (always recompute "today") ---

    suspend fun configNow(): FamilyConfig =
        settingsStore.current().toFamilyConfig(essentials)

    /** The idle-earn config right now, or null when the feature is off. */
    suspend fun idleEarnConfigNow(): dev.walcott.rules.IdleEarnConfig? =
        settingsStore.current().idleEarn?.toConfig()

    /**
     * All of today's usage counters, keyed by categoryId AND by package (per-app budgets are
     * counted under the package name — which always contains a dot, so it never collides with a
     * category id). The enforcement engine needs both; reports to the parent use [reportedUsageNow].
     */
    suspend fun usageNow(): Map<String, Duration> =
        db.usage().getDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    /**
     * Today's counters as reported to the parent.
     *
     * This used to strip every key containing a dot, which was right while counters were keyed by
     * CATEGORY: it removed the per-app detail and left the category totals. When limits became
     * per app the counters started being keyed by package name — and package names always contain
     * a dot — so the same filter stopped removing the detail and started removing everything. The
     * parent has been shown, and has been accumulating into its ledger, nothing but zeros since.
     */
    suspend fun reportedUsageNow(): Map<String, Duration> = usageNow()

    suspend fun extraNow(): Map<String, Duration> =
        db.usage().getExtraDay(today()).associate { it.categoryId to Duration.ofSeconds(it.seconds) }

    /** Extra applied to budgets (manual grants + idle-earned, which is granted into extra_time). */
    suspend fun effectiveExtraNow(): Map<String, Duration> = extraNow()

    /** Usage for the last 7 days: epochDay -> (package -> duration). */
    suspend fun weeklyUsage(): Map<Long, Map<String, Duration>> = usageBetween(today() - 6, today())

    /**
     * Usage per day between two epoch days, inclusive: epochDay -> (package -> duration).
     *
     * Room keeps ninety days on this device, so a month of app-by-app history is a query rather
     * than something to accumulate — the child's own numbers never left their phone.
     */
    suspend fun usageBetween(startDay: Long, endDay: Long): Map<Long, Map<String, Duration>> {
        return db.usage().getRange(startDay, endDay)
            .groupBy { it.epochDay }
            .mapValues { (_, rows) -> rows.associate { it.categoryId to Duration.ofSeconds(it.seconds) } }
    }


    /** Every user-installed app on this device: with no categories, they are all managed. */
    suspend fun managedPackagesNow(): Set<String> = inventory.managedPackages()

    /** What screen time is counted for — wider than the managed set (see [AppInventory.trackedPackages]). */
    suspend fun trackedPackagesNow(): Set<String> = inventory.trackedPackages()

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

    /** One-time: turn the recommended anti-tamper restrictions on by default (parent edits sync down). */
    suspend fun seedHardeningIfNeeded() {
        if (settingsStore.current().hardeningSeeded) return
        updateSettings { it.seedRestrictions(dev.walcott.enforcement.DeviceRestrictions.RECOMMENDED_DEFAULTS) }
    }

    // --- Parent PIN ---

    suspend fun hasPin(): Boolean = settingsStore.current().pinHash != null

    // PBKDF2 at 120k iterations takes long enough to freeze a frame or twenty — always
    // derive off the main thread; the PIN screens show a progress state meanwhile.
    suspend fun setPin(pin: String) {
        val hashed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Pin.hash(pin) }
        // Through updateSettings, so the policy version bumps. The hash travels to the children
        // inside the policy (they verify the emergency release locally), and a child only adopts
        // a snapshot strictly newer than the one it applied (SyncEngine.adoptsPolicy) — written
        // straight to the store, a changed PIN would silently never reach them.
        updateSettings { it.copy(pinHash = hashed.hash, pinSalt = hashed.salt) }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val s = settingsStore.current()
        val hash = s.pinHash ?: return false
        val salt = s.pinSalt ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Pin.verify(pin, hash, salt) }
    }

    // --- Rule editing (parent mode) ---

    suspend fun updateSettings(transform: (PolicySettings) -> PolicySettings) {
        // Every rule change bumps the version (relevant for Phase 2 sync). The holiday mirror
        // keeps the wire's HOLIDAY slot equal to WEEKEND on every write — the UI only edits
        // weekdays/weekends, and calendar special days behave like weekends (see
        // withHolidayMirroringWeekend for why the key itself must keep travelling).
        settingsStore.update { current ->
            transform(current).withHolidayMirroringWeekend().copy(version = current.version + 1)
        }
    }

    /** Drops usage/extra counters older than [USAGE_RETENTION_DAYS] (see [WatchdogWorker]). */
    suspend fun pruneOldUsage() {
        val cutoff = today() - USAGE_RETENTION_DAYS
        db.usage().deleteUsageBefore(cutoff)
        db.usage().deleteExtraBefore(cutoff)
        db.blocks().deleteBefore(today() - BLOCK_RETENTION_DAYS)
    }

    // --- Block counters (child side) ---

    /**
     * Writes what the DNS and enforcement loops have counted since the last flush.
     *
     * Called on a timer and before every publish, never per event: see [BlockCounters] for why
     * the hot path stays in memory. Compaction runs here too, so a day that saw an unusual
     * number of distinct domains is folded back under its cap at the first opportunity rather
     * than at the end of the day, when the rows already exist.
     */
    suspend fun flushBlockCounters() {
        val drained = BlockCounters.drain()
        if (drained.isEmpty()) return
        val day = today()
        for ((slot, count) in drained) {
            db.blocks().add(day, slot.first, slot.second, count)
        }
        for (kind in drained.keys.mapTo(mutableSetOf()) { it.first }) {
            if (db.blocks().keyCount(day, kind) > BlockKinds.MAX_KEYS_PER_DAY) compactBlocks(day, kind)
        }
    }

    /**
     * Folds everything past the [BlockKinds.KEEP_ON_COMPACT] biggest keys of a (day, kind) into
     * one OTHER row. The day's total is unchanged by construction — the tail is added up, not
     * discarded — so only the breakdown loses its long tail, which is the part nobody reads.
     */
    private suspend fun compactBlocks(day: Long, kind: String) {
        val rows = db.blocks().getDayKind(day, kind)
        val tail = rows.filterNot { it.key == BlockKinds.OTHER }.drop(BlockKinds.KEEP_ON_COMPACT)
        if (tail.isEmpty()) return
        db.blocks().add(day, kind, BlockKinds.OTHER, tail.sumOf { it.count })
        db.blocks().deleteKeys(day, kind, tail.map { it.key })
    }

    /** One day's counters for [kind], biggest first. */
    suspend fun blockCounts(kind: String, day: Long = today()): List<BlockCounterEntity> =
        db.blocks().getDayKind(day, kind)

    /** Per-day totals over a closed range of days, for the catch-up a report carries. */
    suspend fun blockTotals(from: Long, to: Long): List<BlockDayTotal> = db.blocks().totalsBetween(from, to)

    /**
     * Emergency release ([dev.walcott.enforcement.PanicRelease]): forgets the rules and every
     * local record they produced (usage, extra time, location trail), so what's left looks
     * like a fresh install rather than a device that was once enrolled.
     */
    suspend fun wipeLocalData() {
        settingsStore.update { PolicySettings() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.clearAllTables()
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

        /**
         * How many days of per-day counters to keep. The weekly report reads 7; the rest is
         * kept as slack for a device whose clock jumped around, and pruned so an enrollment
         * that lasts years doesn't grow a row per app per day without end.
         */
        private const val USAGE_RETENTION_DAYS = 90L

        /**
         * How many days of block counters the child keeps. Short on purpose: the parent is where
         * the history lives (see BlockLedger), and this only has to cover today plus the days a
         * report carries for a device that was offline.
         */
        private const val BLOCK_RETENTION_DAYS = 10L
    }
}
