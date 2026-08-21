package dev.walcott.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.walcott.MainActivity
import dev.walcott.R
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.location.LocationAlarm
import dev.walcott.location.LocationPolicy
import dev.walcott.location.LocationSampler
import dev.walcott.net.VpnController
import dev.walcott.rules.RuleEngine
import dev.walcott.sync.LiveTracking
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.update.UpdateCheckOutcome
import dev.walcott.update.Updater
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Persistent service running the enforcement loop: samples the foreground app, accumulates
 * per-category usage, evaluates the rule engine, and suspends/unsuspends apps. All work
 * happens off the UI thread.
 */
class EnforcementService : LifecycleService() {

    private lateinit var enforcer: Enforcer
    private lateinit var sampler: UsageSampler
    private lateinit var power: PowerManager

    /** Tracks the screen so the loop can sleep with zero wakeups while it's off. */
    private val screenOn = MutableStateFlow(true)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn.value = true
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn.value = false
                    // Checkpoint publish as the phone goes to rest: the parent's "last
                    // signal" then matches the moment usage stopped, not up to a re-emit
                    // interval earlier. Throttled so screen toggling doesn't spam.
                    val app = application as WalcottApplication
                    lifecycleScope.launch {
                        runCatching { app.syncManager.publishHeartbeatIfStale(SCREEN_OFF_PUBLISH_MIN_MS) }
                    }
                }
            }
        }
    }

    /**
     * Today's counters, followed rather than polled.
     *
     * The loop needs both on every tick, and reading them was two SQLite queries every two
     * seconds. Room already knows when they change — the loop's own writes are what change the
     * usage half — so it pushes, and the tick just reads memory. Seeded from the database at
     * start-up so the first ticks aren't decided on an empty map.
     */
    @Volatile private var usageToday: Map<String, java.time.Duration> = emptyMap()
    @Volatile private var extraToday: Map<String, java.time.Duration> = emptyMap()

    /** Set when a package is (un)installed, so the managed-set cache refreshes immediately. */
    @Volatile private var inventoryDirty = true
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            inventoryDirty = true
            val app = application as WalcottApplication
            // The app list itself is cached now, and this is the event it is cached against.
            app.repository.inventory.invalidate()
            val realChange = intent?.action in
                setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED) &&
                intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == false
            if (realChange) {
                // A genuinely NEW install (not an app self-update) closes any pushed-install
                // window and is then judged against what was approved: the approved app is
                // acknowledged, anything else is quarantined (see SyncManager.reconcileInstalls).
                // A removal is reconciled too — it is how a quarantine case closes.
                val added = if (intent?.action == Intent.ACTION_PACKAGE_ADDED) {
                    intent.data?.schemeSpecificPart
                } else {
                    null
                }
                lifecycleScope.launch {
                    runCatching { app.syncManager.onPackageChanged(added) }
                    // The parent's app list should follow reality in seconds, not at the next
                    // heartbeat: a new (or removed) app publishes now, throttled so a batch of
                    // installs during an open window costs one message a minute, not one each.
                    runCatching { app.syncManager.publishHeartbeatIfStale(PACKAGE_PUBLISH_MIN_MS) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        enforcer = Enforcer(this)
        sampler = UsageSampler(this)
        power = getSystemService(PowerManager::class.java)
        screenOn.value = power.isInteractive
        // Explicit NOT_EXPORTED keeps registration valid under the Android 14 receiver-flag rule.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Package changes invalidate the managed-set cache (see runLoop). System broadcast,
        // so NOT_EXPORTED is fine under the Android 14 receiver-flag rule.
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Grant location before startForeground so the service can claim the location FGS type.
        LocationPolicy.ensureEnforced(this)
        // Same idea, for the permission every warning and every answer has to pass through:
        // a child device should not be able to end up enforcing rules it cannot explain.
        NotificationPolicy.ensureGranted(this)
        startForegroundCompat()
        observeCounters()
        lifecycleScope.launch { runLoopResilient() }
        observeWebFilter()
        observeBlocklists()
        observeAssistance()
        observeDeviceRestrictions()
        scheduleUpdateChecks()
        scheduleLocationSampling()
        observeLiveTracking()
        observeUpdateWindow()
        // Catch up on whatever happened while this service wasn't running. The package receiver
        // lives in this process, so a device that was off — or a service an OEM battery saver
        // killed — witnesses no install at all; without this pass, that is exactly when an app
        // could arrive unseen.
        lifecycleScope.launch {
            runCatching { (application as WalcottApplication).syncManager.reconcileInstalls() }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        ringerGuard?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    /**
     * Runs the update check from this always-on foreground service, so on the child device the
     * process is guaranteed to be alive to download and (silently) install a new version.
     */
    private fun scheduleUpdateChecks() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            while (currentCoroutineContext().isActive) {
                // Report the outcome to the parent: a child silently stuck on an old build
                // is otherwise only diagnosable by picking the device up.
                runCatching { Updater(applicationContext).checkAndUpdate() }
                    .onSuccess { outcome ->
                        app.syncManager.recordUpdateError(
                            when (outcome) {
                                UpdateCheckOutcome.TRANSIENT_FAILURE -> "download_failed"
                                UpdateCheckOutcome.INSTALL_FAILURE -> "install_failed"
                                // Not a failure, but the parent should see WHY the child is
                                // behind: it is deliberately waiting for the canary.
                                UpdateCheckOutcome.WAITING_FOR_PARENT -> "waiting_parent"
                                // Nor is this one: the family asked for Wi-Fi-only updates and
                                // this phone has not seen Wi-Fi. Without it the child reports
                                // nothing at all and sits months behind looking perfectly well.
                                UpdateCheckOutcome.WAITING_FOR_WIFI -> "waiting_wifi"
                                else -> ""
                            },
                        )
                    }
                delay(UPDATE_CHECK_MILLIS)
            }
        }
    }

    /**
     * Periodic GPS sampling driven by the child's resolved tracking interval (0 = off).
     *
     * All this does now is arm an alarm. The sampling itself used to be a `delay()` loop in this
     * service, on the belief that an always-on FGS gives near-exact cadence at any interval; it
     * does not, and [LocationAlarm] documents exactly why. A timer that stops counting whenever
     * the phone is in somebody's pocket is a timer that fails in the one situation the whole
     * feature exists for.
     */
    private fun scheduleLocationSampling() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            app.repository.settingsFlow
                .map { it.trackingIntervalMinutes }
                .distinctUntilChanged()
                .collect { minutes ->
                    DebugLog.i(LOC_TAG, "tracking interval resolved: $minutes min")
                    // The permission may only have landed after startForeground ran, and without
                    // the location FGS type nothing below can get a fix at all.
                    claimLocationType()
                    if (minutes <= 0) {
                        LocationAlarm.cancel(this@EnforcementService)
                    } else {
                        // Soon rather than in a full period: a phone that has just booted, or
                        // whose family has just changed the interval, should say where it is
                        // while somebody is still looking. The cycle arms the real interval.
                        LocationAlarm.schedule(this@EnforcementService, FIRST_FIX_DELAY_MILLIS)
                    }
                }
        }
    }

    /**
     * Close tracking: while the parent's session runs, hold the CPU awake and take a fix a
     * minute (see [LiveTracking]).
     *
     * A wakelock rather than an alarm chain, and this is the one place in the app that takes one.
     * At a fix a minute the alarm approach would be sixty wakeups an hour fighting Doze for
     * permission to fire, and the whole point of this mode is that the cadence is guaranteed
     * rather than best-effort. The cost is real, which is why nothing here is open-ended: the
     * session is bounded by the parent's deadline, by the battery floor, and by the wakelock's
     * own timeout, and the parent was told what it costs before it started.
     */
    private fun observeLiveTracking() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            app.syncManager.liveTrackingUntilElapsed
                .collectLatest { untilElapsed ->
                    if (!LiveTracking.isRunning(untilElapsed, SystemClock.elapsedRealtime())) return@collectLatest
                    runLiveSession(untilElapsed)
                }
        }
    }

    private suspend fun runLiveSession(untilElapsedMs: Long) {
        val app = application as WalcottApplication
        val sampler = LocationSampler(this)
        // Bounded by what is actually left, plus a little slack for the fix in flight when the
        // deadline passes. A wakelock without a timeout is how a phone dies overnight.
        val budget = LiveTracking.remainingMs(untilElapsedMs, SystemClock.elapsedRealtime()) +
            LIVE_WAKELOCK_SLACK_MILLIS
        val lock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walcott:live-tracking")
                .apply { setReferenceCounted(false); acquire(budget) }
        }.getOrNull()
        DebugLog.w(LOC_TAG, "close tracking started, ${budget / 60_000} min of wakelock")
        setLiveTrackingBanner(true)
        var lastPublishAt = 0L
        var stoppedForBattery = false
        try {
            while (currentCoroutineContext().isActive &&
                LiveTracking.isRunning(untilElapsedMs, SystemClock.elapsedRealtime())
            ) {
                // The failure this prevents is the one that matters: a session that flattens the
                // phone leaves the parent with NO location at all, which is the opposite of what
                // they switched it on for.
                val battery = batteryPercent()
                val charging = batteryCharging()
                if (LiveTracking.batteryTooLow(battery, charging)) {
                    DebugLog.w(LOC_TAG, "close tracking stopping: battery below the floor")
                    stoppedForBattery = true
                    break
                }
                // Re-read every cycle, so a session that started on a full battery slows down by
                // itself as it drains rather than running flat out into the floor.
                val sampleEvery = LiveTracking.sampleIntervalMs(battery, charging)
                runCatching {
                    val fix = sampler.currentFix(maxCacheAgeMs = LIVE_CACHE_MAX_AGE_MILLIS)
                    if (fix != null) app.repository.recordLocation(fix)
                    // Publishing is deliberately coarser than sampling: each publish is a whole
                    // snapshot over the relay, and one per fix would be sixty messages an hour.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPublishAt >= LiveTracking.publishIntervalMs(sampleEvery)) {
                        lastPublishAt = now
                        app.syncManager.publishLocationUpdate()
                    }
                }.onFailure { DebugLog.e(LOC_TAG, "close tracking cycle failed", it) }
                delay(sampleEvery)
            }
        } finally {
            runCatching { lock?.release() }
            setLiveTrackingBanner(false)
            DebugLog.w(LOC_TAG, "close tracking ended (battery=$stoppedForBattery)")
            // Outside the cancellable body: a session ending because the rules changed under it
            // must still tidy up and tell the parent where the phone finished.
            withContext(NonCancellable) {
                // Scoped to THIS session: a parent who extended it wrote a new deadline, which
                // is what cancelled this loop, and clearing that would undo their own tap.
                runCatching { app.syncManager.endLiveTracking(stoppedForBattery, untilElapsedMs) }
            }
        }
    }

    private fun batteryPercent(): Int = runCatching {
        getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    private fun batteryCharging(): Boolean = runCatching {
        getSystemService(android.os.BatteryManager::class.java)?.isCharging ?: false
    }.getOrDefault(false)

    /** Keeps [usageToday] / [extraToday] current, so the tick never has to query for them. */
    private fun observeCounters() {
        val repo = (application as WalcottApplication).repository
        lifecycleScope.launch {
            // Seeded before the flows have emitted, so the very first ticks decide on real
            // numbers rather than on "nothing has been used today".
            runCatching { usageToday = repo.usageNow() }
            runCatching { extraToday = repo.effectiveExtraNow() }
        }
        lifecycleScope.launch { repo.usageTodayAllFlow.collect { usageToday = it } }
        lifecycleScope.launch { repo.effectiveExtraTodayFlow.collect { extraToday = it } }
    }

    /** Starts/stops the DNS filter VPN as web-filter rules appear or disappear. */
    private fun observeWebFilter() {
        val repo = (application as WalcottApplication).repository
        lifecycleScope.launch {
            // Any of three reasons keeps the tunnel up: rules to enforce, a parent watching which
            // domains an app resolves, or a phone that is supposed to be shut with something on it
            // that cannot be suspended (see Curfew). Each drops back by itself — the session
            // expires, the window closes — so nothing here has to be turned off by hand.
            kotlinx.coroutines.flow.combine(
                repo.settingsFlow,
                dev.walcott.net.DomainMonitor.state,
                dev.walcott.net.NetworkCurfew.packages,
            ) { settings, monitor, curfew ->
                VpnController.wanted(settings, monitor.isActive(System.currentTimeMillis()), curfew)
            }
                .distinctUntilChanged()
                .collect { enabled -> VpnController.apply(this@EnforcementService, enabled) }
        }
    }

    /**
     * Keeps this device's copy of the public blocklists in step with the rules.
     *
     * Fires on the rules changing rather than on a timer, because a parent who has just switched
     * "Adult content" on is standing there looking at the phone: a filter whose 494 000 domains
     * arrive tomorrow reads as a filter that does not work. The periodic pass behind it exists for
     * the other 99% of the time, on the interval the family chose.
     */
    private fun observeBlocklists() {
        val repo = (application as WalcottApplication).repository
        lifecycleScope.launch {
            repo.settingsFlow
                .map { Triple(it.enabledBlocklists, it.blocklistRefreshHours, it.updateWifiOnly) }
                .distinctUntilChanged()
                .collect { (_, hours, wifiOnly) ->
                    dev.walcott.net.BlocklistWorker.schedule(this@EnforcementService, hours, wifiOnly)
                    // Unconditionally, including when the family has just switched their last
                    // list off: the filter stops enforcing it immediately either way (it is read
                    // from the rules, not from the cache), but this is also what deletes the
                    // cached file and stops the child reporting domains it no longer uses.
                    dev.walcott.net.BlocklistWorker.runNow(this@EnforcementService)
                }
        }
    }

    /** The ringer-mode receiver while the rules ask for it (see [AudioGuard]); null otherwise. */
    private var ringerGuard: BroadcastReceiver? = null

    /** The volume floor the receiver reads — it outlives any single policy emission. */
    @Volatile private var ringerFloor: Int = dev.walcott.data.PolicySettings.DEFAULT_RING_VOLUME_PERCENT

    /**
     * The three things this device does for a person being helped rather than limited: keep the
     * ringer audible, keep the lock-screen escape hatch armed, and keep a notification log only
     * while the rules ask for one.
     *
     * All three are driven from the rules and all three are re-derived on every change, because the
     * one that matters most is the negative: a family switching the notification log OFF has to
     * mean the rows go away, and a family switching the ringer guard off has to mean this device
     * stops fighting its owner over the volume.
     */
    private fun observeAssistance() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            app.repository.settingsFlow
                .map {
                    Triple(it.keepRingerAudible, it.minRingVolumePercent, it.notificationLogEnabled)
                }
                .distinctUntilChanged()
                .collect { (keepAudible, minPercent, logEnabled) ->
                    ringerFloor = minPercent
                    ringerGuard = registerRingerGuard(keepAudible, ringerGuard)
                    if (keepAudible) {
                        AudioGuard.liftDoNotDisturb(this@EnforcementService)
                        if (AudioGuard.enforce(this@EnforcementService, minPercent)) {
                            app.syncManager.recordRingerRestore()
                        }
                    }
                    // Armed here rather than at enrollment: a device that becomes Device Owner
                    // later, or one whose token the platform forgot, gets another chance on every
                    // rule change instead of only ever having had one.
                    runCatching { app.syncManager.armLockReset() }
                    if (!logEnabled) {
                        dev.walcott.notifications.NotificationLog.forget(app.repository.notifications)
                    }
                }
        }
    }

    /** Registers or drops the ringer-mode receiver, returning what is registered now. */
    private fun registerRingerGuard(wanted: Boolean, current: BroadcastReceiver?): BroadcastReceiver? {
        if (wanted == (current != null)) return current
        if (!wanted) {
            runCatching { unregisterReceiver(current) }
            return null
        }
        val app = application as WalcottApplication
        val receiver = AudioGuard.RingerReceiver(
            minPercent = { ringerFloor },
            onRestored = { lifecycleScope.launch { app.syncManager.recordRingerRestore() } },
        )
        return runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                AudioGuard.RingerReceiver.FILTER,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver
        }.getOrNull()
    }

    /**
     * Keeps the nightly update window's alarm in step with the policy.
     *
     * Here rather than at each place a policy can change, because there are several — a parent's
     * edit, a restore, the setup wizard — and an alarm that quietly stopped matching the rules
     * is the kind of thing nobody notices until a phone has gone a month without updates.
     */
    private fun observeUpdateWindow() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            app.repository.settingsFlow
                .map {
                    listOf(
                        it.installMode,
                        it.updateWindowEnabled.toString(),
                        it.updateWindowFollowsBedtime.toString(),
                        it.updateWindowHour.toString(),
                        it.updateWindowMinutes.toString(),
                        (DeviceRestrictions.KEY_INSTALLS in it.deviceRestrictions).toString(),
                        // The bedtime too, because the window follows it by default: a family that
                        // moves bedtime an hour later has moved the window with it, and an alarm
                        // still armed for the old hour would open the block while they are up.
                        it.bedtime.toString(),
                    )
                }
                .distinctUntilChanged()
                .collectLatest { AppUpdateWindowAlarm.sync(this@EnforcementService) }
        }
    }

    /** Keeps the Device Owner user restrictions in sync with the policy. */
    private fun observeDeviceRestrictions() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            combine(
                app.repository.settingsFlow.map { it.restrictionKeysToApply() },
                app.syncManager.installExemption,
            ) { keys, exemptUntil -> keys to exemptUntil }
                .distinctUntilChanged()
                .collectLatest { (keys, exemptUntil) ->
                    DeviceRestrictions.apply(this@EnforcementService, keys, exemptUntil)
                    // Re-arm the install block when the exemption window closes. Two ways to
                    // notice, because neither is enough on its own: an alarm, which is the only
                    // clock that ticks on a sleeping phone (the nightly update window ends on
                    // one by design), and this countdown, which is the precise one while the
                    // phone is awake — an inexact alarm may run minutes late, and a ten-minute
                    // window that becomes fourteen is a promise broken to whoever typed the PIN.
                    val untilExpiry = exemptUntil - System.currentTimeMillis()
                    if (untilExpiry > 0 && DeviceRestrictions.KEY_INSTALLS in keys) {
                        InstallBlockAlarm.arm(this@EnforcementService, exemptUntil)
                        delay(untilExpiry + 1_000)
                        app.syncManager.rearmInstallBlock()
                    } else {
                        InstallBlockAlarm.cancel(this@EnforcementService)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Anything that has just repaired this device's permissions can ask for the location
        // type to be claimed again, without needing a handle on the running service.
        if (intent?.action == ACTION_RECHECK) claimLocationType()
        return START_STICKY
    }

    /**
     * Keeps [runLoop] alive across unexpected exceptions. A throw from any tick — an OEM
     * PackageManager quirk, a DataStore read error — would otherwise kill the loop coroutine
     * for good: the service stays "running", so the watchdog's start() is a no-op and nothing
     * ever revives it, leaving the child unprotected until the process is killed. Restarting
     * the loop (losing only the tiny in-memory tick state, which is recomputed) is the fix.
     */
    private suspend fun runLoopResilient() {
        while (currentCoroutineContext().isActive) {
            try {
                runLoop()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                DebugLog.e(TAG, "enforcement loop crashed; restarting", t)
                delay(TICK_IDLE_MILLIS)
            }
        }
    }

    private suspend fun runLoop() {
        val app = application as WalcottApplication
        val repo = app.repository
        // The apps that reach a person, resolved once at start-up and logged: they are exempt
        // from every rule, so "why is this one never blocked" has to be answerable from the
        // child's own debug log rather than by guessing at the OEM's packaging.
        DebugLog.i(TAG, "phone and contacts (never limited): ${repo.inventory.alwaysReachablePackages()}")
        var lastTick = SystemClock.elapsedRealtime()
        var lastForeground: String? = null
        // Tracks how long the child has been away from each app, so opening one after a real
        // gap can be told apart from switching between two (see AppOpeningBanner).
        val openingBanner = AppOpeningBanner()
        var lastUsageAccess: Boolean? = null
        var lastClockTrusted: Boolean? = null
        // Usage access, re-read at most every USAGE_ACCESS_TTL_MILLIS (an AppOps binder call).
        var usageAccessCached = true
        var usageAccessCheckedAt = 0L
        // Last direct database read of the counters (see the resync below).
        var lastCounterResyncAt = 0L
        // Last time what the filter and the rules blocked was written down (see BlockCounters).
        var lastBlockFlushAt = 0L
        // Managed-set cache: enumerating PackageManager (launchable apps + labels) on every
        // 2s tick was pure binder churn — the set only changes on (un)installs and
        // classification edits, both of which invalidate it explicitly below.
        var managed: Set<String> = emptySet()
        // What screen time is COUNTED for: wider than `managed`, which is what may be blocked.
        var tracked: Set<String> = emptySet()
        var managedFetchedAt = 0L
        // Suspension is only re-asserted when the target state changes (or periodically as
        // a self-heal), instead of N isPackageSuspended binder calls per tick.
        var lastAppliedBlocked: Set<String>? = null
        // What was blocking the whole device last tick, so entering bedtime is one line on the
        // parent's wall instead of one per app. Null until the first tick has been seen: a
        // service that restarts mid-bedtime must not announce it again.
        var lastDeviceBlock: dev.walcott.rules.BlockReason? = null
        var deviceBlockSeen = false
        // What the child has already been warned about, so a 2-second loop says each thing once.
        val warnings = TimeWarnings()
        var lastAppliedManaged: Set<String>? = null
        // Apps quarantined by the install guard: suspended regardless of what the rules say,
        // because they are not supposed to be on this phone at all.
        var lastAppliedQuarantine: Set<String>? = null
        var lastApplyAt = 0L
        // Idle-earn: idle seconds are batched locally and flushed to the store ~once a minute,
        // so a child idling all evening doesn't hammer DataStore. Screen-off counts as idle.
        var idleAccumSeconds = 0L
        var lastEarnFlushAt = SystemClock.elapsedRealtime()
        // The apps this device cannot suspend but can silence (see Curfew): every browser on the
        // phone, and how long each app has outstayed its welcome inside the current window.
        var browsers: Set<String> = emptySet()
        var lingerSeconds: Map<String, Long> = emptyMap()
        // Null until the first tick, for the same reason lastDeviceBlock is: a service restarting
        // mid-bedtime must not announce a curfew it merely re-derived.
        var lastCutOff: Set<String>? = null

        while (currentCoroutineContext().isActive) {
            val config = repo.configNow()
            val idleCfg = repo.idleEarnConfigNow()
            val nowForEarn = LocalDateTime.now()
            val earningNow = idleCfg != null &&
                dev.walcott.rules.IdleEarnEngine.isEarningTime(
                    idleCfg, config.calendar.dayTypeOf(nowForEarn), nowForEarn.toLocalTime(),
                )

            // Screen off: blocked apps stay suspended. With idle-earn off we park with zero
            // wakeups; with it on we wake every few minutes so "putting the phone down" earns
            // (screen off = not using managed apps = idle).
            if (!screenOn.value) {
                lastForeground = null
                val parkStart = SystemClock.elapsedRealtime()
                // Only wake periodically to accrue idle when earning is actually possible now
                // (feature on AND inside an earn window); otherwise park with zero wakeups.
                if (idleCfg == null || !earningNow) {
                    screenOn.first { it }
                } else {
                    kotlinx.coroutines.withTimeoutOrNull(IDLE_STEP_MILLIS) { screenOn.first { it } }
                }
                val offSeconds = (SystemClock.elapsedRealtime() - parkStart) / 1000
                if (earningNow && offSeconds > 0) idleAccumSeconds += offSeconds.coerceAtMost(MAX_IDLE_STEP_SECONDS)
                lastTick = SystemClock.elapsedRealtime()
                if (idleCfg != null && idleAccumSeconds > 0 &&
                    SystemClock.elapsedRealtime() - lastEarnFlushAt >= EARN_FLUSH_MILLIS
                ) {
                    app.syncManager.accrueAndConvertIdle(idleAccumSeconds, idleCfg)
                    idleAccumSeconds = 0
                    lastEarnFlushAt = SystemClock.elapsedRealtime()
                }
                if (!screenOn.value) continue // still off: keep accruing in the next step
            }

            val nowClock = SystemClock.elapsedRealtime()
            val deltaSeconds = (nowClock - lastTick) / 1000
            lastTick = nowClock

            val foreground = sampler.currentForeground()
            // Fresh clock for rule evaluation: a screen-off park above can span minutes.
            val now = LocalDateTime.now()

            // Read from memory, kept current by observeCounters(). Room pushes the change the
            // moment addUsageSeconds below writes it, so the next tick already sees it.
            //
            // Re-read straight from the database once a minute anyway. Everything else in this
            // loop fails closed; this is the one place where a subscription that quietly stopped
            // delivering would fail OPEN — frozen counters mean budgets that never run out, which
            // is the failure a child would least mind and most notice. One query a minute against
            // thirty is still the whole saving, and it makes the freeze self-correcting.
            if (nowClock - lastCounterResyncAt > COUNTER_RESYNC_MILLIS) {
                usageToday = repo.usageNow()
                extraToday = repo.effectiveExtraNow()
                lastCounterResyncAt = nowClock
            }
            val usage = usageToday
            val extra = extraToday // manually granted + idle-earned
            if (inventoryDirty || nowClock - managedFetchedAt > INVENTORY_TTL_MILLIS) {
                managed = repo.managedPackagesNow()
                tracked = repo.trackedPackagesNow()
                // Read on the same event as the rest: a browser arrives and leaves by being
                // installed and uninstalled, which is exactly what invalidates this block.
                browsers = repo.inventory.browserPackages()
                managedFetchedAt = nowClock
                inventoryDirty = false
            }

            // Credit time only on consecutive sightings of the same app, so the slow idle tick
            // can't attribute time actually spent elsewhere.
            //
            // Against `tracked`, not `managed`: counting is not enforcing. The managed set is
            // non-system apps only, so on a phone whose browser and video app ship as system apps
            // — most of them — the hours that actually went somewhere were the ones the parent
            // could not see. A parent decides to set a limit BECAUSE they saw the time; making
            // the limit a precondition for seeing it had that backwards.
            val creditedUsage = foreground != null && foreground == lastForeground &&
                foreground in tracked && deltaSeconds in 1..MAX_CREDIT_SECONDS
            // One counter per app, always: every limit is now an app's own, and an app with no
            // limit today may be given one tomorrow — a counter that only started then would
            // hand back a day the child already spent.
            if (creditedUsage) repo.addUsageSeconds(foreground!!, deltaSeconds)
            // Idle-earn: screen on but not on a managed app, inside an earning window.
            if (idleCfg != null && earningNow && !creditedUsage && deltaSeconds in 1..MAX_IDLE_STEP_SECONDS) {
                idleAccumSeconds += deltaSeconds
            }
            // Which app, if any, the child has just come back to after a real gap. Decided
            // here because this is where the foreground transition is known; acted on further
            // down, where the verdict and the fail-closed state are.
            val justOpened: String? = when {
                foreground == null -> null
                foreground != lastForeground -> foreground.takeIf { openingBanner.opened(it, nowClock) }
                else -> null.also { openingBanner.stillOpen(foreground, nowClock) }
            }
            lastForeground = foreground

            // Fail CLOSED when the config needs the usage counter but usage access is revoked:
            // without it budgets never count down, so a child could disable the toggle for
            // unlimited time. Suspending everything managed makes revoking it self-defeating —
            // the apps come back the moment the permission does. A Device Owner can't grant or
            // pin usage access (it's an AppOp, out of setPermissionGrantState's reach), so this
            // is the strongest enforcement available.
            //
            // Cached for a few seconds: it is an AppOps binder round trip, and this loop runs
            // every two seconds while a child is using a limited app. (The accessibility backend
            // was given this same cache in 0.37 on the belief that this loop already had one — it
            // did not.) Staleness costs nothing here: a revocation is still caught within the
            // window, and the periodic re-assert applies the consequence either way.
            if (nowClock - usageAccessCheckedAt > USAGE_ACCESS_TTL_MILLIS) {
                usageAccessCached = UsageAccess.grantedForEnforcement(this)
                usageAccessCheckedAt = nowClock
            }

            // Block counting is a map increment on two hot paths (the DNS loop and this one);
            // this is where it reaches disk. A minute of counts is what a process death costs,
            // which for statistics is cheaper than a write per blocked lookup.
            if (nowClock - lastBlockFlushAt > BLOCK_FLUSH_MILLIS && !dev.walcott.data.BlockCounters.isEmpty()) {
                lastBlockFlushAt = nowClock
                lifecycleScope.launch { runCatching { repo.flushBlockCounters() } }
            }
            val usageAccessOk = usageAccessCached
            if (usageAccessOk != lastUsageAccess) {
                if (lastUsageAccess != null) {
                    DebugLog.w(TAG, "usage access changed: granted=$usageAccessOk")
                    // Tell the parent right away instead of waiting for the next re-emit.
                    lifecycleScope.launch { runCatching { app.syncManager.publishHealthUpdate() } }
                }
                lastUsageAccess = usageAccessOk
            }

            // Every rule here is a rule about *when*, so a clock the child moved is as good as
            // no rules at all. ClockGuard measures the drift against the sync server's stamps;
            // beyond its threshold the engine fails closed, exactly like a revoked usage access.
            val clockTrusted = !dev.walcott.sync.ClockGuard.isTampered(app.syncManager.state.value.clockSkewMs)
            if (clockTrusted != lastClockTrusted) {
                if (lastClockTrusted != null) DebugLog.w(TAG, "clock trusted=$clockTrusted")
                lastClockTrusted = clockTrusted
            }
            // The single control decision (fail-closed included) lives in the tested rule engine.
            val blocked = RuleEngine.blockedPackages(
                config, managed, now, usage, extra,
                usageCountingAvailable = usageAccessOk,
                clockTrusted = clockTrusted,
            )
            // What the rules just did, for the parent's activity wall. Skipped entirely while
            // failing closed: that already has its own alert, and it would otherwise report
            // every managed app as having run out of time at the same instant.
            val failingClosed = (!usageAccessOk && RuleEngine.requiresUsageCounting(config)) ||
                (!clockTrusted && RuleEngine.requiresTrustedClock(config))

            // --- What the suspension cannot reach (see Curfew) ---
            //
            // Bedtime suspends the managed apps, and the managed set is the non-system ones, so
            // the browser that ships with the phone was never in it. The window closes everything
            // the child installed and leaves open the one thing that was already there — plus
            // whatever else holds a WebView. Those keep their icons and lose their DNS.
            //
            // Deliberately NOT extended to the fail-closed state above. That one suspends every
            // managed app too, but it can last for days on a revoked permission, and a browser
            // that resolves nothing for days — with no rule on any screen that would explain it —
            // is a worse failure than the one it would be covering.
            val deviceBlock = RuleEngine.deviceWideBlock(config, now)
            val curfewWindow = deviceBlock != null
            // The same guard the usage counters use: consecutive sightings only, so the slow tick
            // cannot charge one app for time spent in another. `creditedUsage` also carries
            // `in tracked`, which is what keeps the launcher and Walcott itself out of this.
            lingerSeconds = dev.walcott.rules.Curfew.accrue(
                lingerSeconds,
                foreground.takeIf { creditedUsage },
                deltaSeconds,
                windowOpen = curfewWindow,
            )
            val lingering = dev.walcott.rules.Curfew.lingering(lingerSeconds)
            val cutOff = dev.walcott.rules.Curfew.cutOff(
                windowOpen = curfewWindow,
                browsers = browsers,
                lingering = lingering,
                // Never the phone and contacts: reaching a person is the one promise that
                // outranks every rule here, and half of reaching them is resolving a name.
                spared = config.essentialPackages,
            )
            if (cutOff != lastCutOff) {
                dev.walcott.net.NetworkCurfew.set(cutOff)
                // Only the lingering ones are news. A browser losing its DNS at bedtime is the
                // rule working as written; an app that kept going for two minutes inside a phone
                // that is supposed to be shut is the thing this watch exists to notice, and the
                // parent is the one who can decide whether it is a limit they want to set.
                val noticed = (cutOff - lastCutOff.orEmpty()).filter { it in lingering && it !in browsers }
                if (noticed.isNotEmpty()) {
                    DebugLog.w(TAG, "still going inside a closed window: ${noticed.joinToString()}")
                    val stampedAt = System.currentTimeMillis()
                    app.syncManager.recordRuleEvents(
                        noticed.sorted().map { pkg ->
                            dev.walcott.sync.ChildEvent(
                                id = java.util.UUID.randomUUID().toString(),
                                atMs = stampedAt,
                                kind = dev.walcott.sync.ChildEvent.KIND_CURFEW_CUT,
                                pkg = pkg,
                                label = repo.inventory.label(pkg) ?: pkg,
                            )
                        },
                    )
                }
                lastCutOff = cutOff
            }

            if (!failingClosed) {
                // On the first tick there is no "before", so nothing is new.
                val newlyBlocked = blocked - (lastAppliedBlocked ?: blocked)
                val budgetOut = newlyBlocked.filter {
                    val verdict = RuleEngine.evaluate(config, it, now, usage, extra)
                    (verdict as? dev.walcott.rules.Verdict.Blocked)?.reason ==
                        dev.walcott.rules.BlockReason.BUDGET_EXHAUSTED
                }
                val kinds = if (deviceBlockSeen) {
                    RuleEvents.kindsFor(lastDeviceBlock, deviceBlock, budgetOut)
                } else {
                    emptyList()
                }
                lastDeviceBlock = deviceBlock
                deviceBlockSeen = true
                if (kinds.isNotEmpty()) {
                    val stampedAt = System.currentTimeMillis()
                    // The same transitions, counted. A device-wide block is counted under its own
                    // key rather than against every app it closed, for the reason RuleEvents
                    // collapses it in the first place: one thing happened, not forty.
                    kinds.forEach { (kind, pkg) ->
                        dev.walcott.data.BlockCounters.recordRuleBlock(
                            when (kind) {
                                dev.walcott.sync.ChildEvent.KIND_BEDTIME -> dev.walcott.data.BlockKinds.DEVICE_BEDTIME
                                dev.walcott.sync.ChildEvent.KIND_SCREEN_FREE ->
                                    dev.walcott.data.BlockKinds.DEVICE_SCREEN_FREE
                                else -> pkg
                            },
                        )
                    }
                    app.syncManager.recordRuleEvents(
                        kinds.map { (kind, pkg) ->
                            dev.walcott.sync.ChildEvent(
                                id = java.util.UUID.randomUUID().toString(),
                                atMs = stampedAt,
                                kind = kind,
                                pkg = pkg,
                                // Named here, where the app is installed: the parent may never
                                // have heard of the package.
                                label = if (pkg.isEmpty()) "" else repo.inventory.label(pkg) ?: pkg,
                            )
                        },
                    )
                }
            }

            // "12m left", as the app opens rather than as it runs out. The closing warnings
            // below arrive when it is too late to plan around them; this is the moment the
            // number is worth something. Only for an app that has a limit AND time left on it:
            // a blocked one cannot be opened, and an unlimited one has nothing to say.
            // `in managed` for the same reason the closing warnings below check it: screen time
            // is counted for a wider set than this device can block, so an app outside it has a
            // budget that is only ever bookkeeping. Announcing "12m left" over one of them
            // promised a wall that was never going to arrive.
            if (justOpened != null && justOpened in managed && !failingClosed) {
                val left = (RuleEngine.evaluate(config, justOpened, now, usage, extra)
                    as? dev.walcott.rules.Verdict.AllowedWithBudget)?.remaining
                // Only once the app is inside the warning horizon. "9h 54m left" is not news,
                // and a banner that fires on every opening regardless is one a child learns to
                // look past — including on the openings where it mattered.
                if (dev.walcott.rules.CloseWatch.worthAnnouncingOnOpen(left)) {
                    runCatching {
                        TimeWarningNotifications.notifyOnOpen(
                            this, justOpened, repo.inventory.label(justOpened) ?: justOpened, left!!,
                        )
                    }.onFailure { DebugLog.w(TAG, "opening banner failed", it) }
                }
            }

            // Heads-up before the door closes, and only while the phone is being used: a warning
            // nobody is there to read is just a notification waiting to confuse them later.
            // Skipped while failing closed, where "when does this end" has no honest answer.
            if (!failingClosed && foreground != null && foreground != packageName) {
                val closing = if (foreground in managed) {
                    dev.walcott.rules.CloseWatch.nextClose(config, foreground, now, usage, extra)
                } else {
                    // Nothing Walcott limits, so only the rules that close the whole phone apply.
                    dev.walcott.rules.CloseWatch.nextDeviceWideClose(config, now)
                }
                warnings.due(closing, System.currentTimeMillis() / 60_000)?.let {
                    TimeWarningNotifications.notify(
                        this, closing!!,
                        // What is actually left, rounded up — not the threshold that triggered
                        // it. A child who picks the phone up 22 minutes before bedtime crosses
                        // the 30-minute mark on the first look, and "in 30 minutes" would be
                        // a lie the clock disproves.
                        minutes = ((closing.left.seconds + 59) / 60).toInt().coerceAtLeast(1),
                        appLabel = repo.inventory.label(closing.packageName) ?: closing.packageName,
                    )
                }
            }

            // Re-assert on change, plus periodically so external state drift self-heals.
            //
            // The quarantine rides along on both sides of the call: on the managed side so a
            // package without a launcher icon is still reachable by the reconciliation, and on
            // the blocked side because no rule will ever ask for it — an app nobody approved
            // has no policy, only a verdict.
            val quarantined = app.syncManager.quarantined.value
            if (blocked != lastAppliedBlocked || managed != lastAppliedManaged ||
                quarantined != lastAppliedQuarantine || nowClock - lastApplyAt > REASSERT_MILLIS
            ) {
                // An app that LEAVES the managed set while suspended would stay suspended for
                // ever: the reconciliation only ever looks at what is managed now, and nothing
                // else on the device unsuspends anything. It is a narrow case — an update that
                // drops an app's launcher activity, a package the system stops listing — but its
                // failure mode is an app blocked with no rule to explain it and no way back.
                val leftManaged = (lastAppliedManaged.orEmpty() + lastAppliedQuarantine.orEmpty()) -
                    managed - quarantined
                if (leftManaged.isNotEmpty()) {
                    DebugLog.i(TAG, "no longer managed, giving back: ${leftManaged.joinToString()}")
                    enforcer.release(leftManaged.toList())
                }
                enforcer.apply(managed + quarantined, blocked + quarantined)
                lastAppliedBlocked = blocked
                lastAppliedManaged = managed
                lastAppliedQuarantine = quarantined
                lastApplyAt = nowClock
            }

            // Flush accrued idle into earned time about once a minute (batched to spare DataStore).
            if (idleCfg != null && idleAccumSeconds > 0 &&
                nowClock - lastEarnFlushAt >= EARN_FLUSH_MILLIS
            ) {
                app.syncManager.accrueAndConvertIdle(idleAccumSeconds, idleCfg)
                idleAccumSeconds = 0
                lastEarnFlushAt = nowClock
            }

            // The permanent notification, kept honest. Everything it needs was computed above,
            // so this costs a string comparison per tick — and it is re-posted only when what it
            // SAYS changes, which for a countdown printed in minutes is once a minute.
            runCatching {
                val status = StatusLine.of(
                    config, foreground, managed, now, usage, extra,
                    failClosed = failingClosed,
                )
                updateStatusNotification(status) { repo.inventory.label(it) }
            }.onFailure { DebugLog.w(TAG, "status notification failed", it) }

            // Tight cadence only while a managed app is actually in use (budget countdown
            // needs it); blocked apps are already suspended, so idling can tick slowly.
            delay(if (foreground != null && foreground in managed) TICK_ACTIVE_MILLIS else TICK_IDLE_MILLIS)
        }
    }

    /** What the ongoing notification currently says, so it is only re-posted when that changes. */
    private var statusText: String? = null

    /**
     * Re-posts the ongoing notification when [status] reads differently from what is on screen.
     *
     * Same id, same channel, same builder as [startForegroundCompat]: this is the service's own
     * notification being edited, not a second one. IMPORTANCE_MIN means an edit is silent and
     * does not re-surface the row.
     */
    private fun updateStatusNotification(status: PhoneStatus, appLabel: (String) -> String?) {
        val text = statusTextOf(status, appLabel)
        if (text == statusText) return
        statusText = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildStatusNotification(text))
    }

    /**
     * Whether a close-tracking session is running, which the ongoing notification says outright.
     *
     * Deliberately not hidden. Android shows its own location indicator regardless, and a mode
     * that reports where this phone is every minute for hours is a different thing from a
     * half-hourly check-in — a family app that quietly blurred the two would be teaching the
     * wrong lesson about what supervision is.
     */
    @Volatile private var liveTrackingActive = false

    /** Puts the close-tracking sentence on the ongoing notification, or takes it back off. */
    private fun setLiveTrackingBanner(active: Boolean) {
        liveTrackingActive = active
        val text = if (active) getString(R.string.status_live_tracking) else getString(R.string.service_notif_text)
        statusText = text
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatusNotification(text))
        }
    }

    /** The sentence for [status], in the phone's own language. */
    private fun statusTextOf(status: PhoneStatus, appLabel: (String) -> String?): String = when {
        // Outranks everything else: for as long as it is running it is the most surprising
        // thing this phone is doing, and the one its user is most entitled to be told about.
        liveTrackingActive -> getString(R.string.status_live_tracking)
        else -> plainStatusTextOf(status, appLabel)
    }

    private fun plainStatusTextOf(status: PhoneStatus, appLabel: (String) -> String?): String = when (status) {
        is PhoneStatus.Paused -> getString(R.string.status_paused, status.until.hhmm())
        is PhoneStatus.Bedtime -> getString(R.string.status_bedtime, status.until.hhmm())
        is PhoneStatus.ScreenFree -> getString(R.string.status_screen_free, status.until.hhmm())
        is PhoneStatus.AppRemaining -> getString(
            R.string.status_app_left,
            appLabel(status.packageName) ?: status.packageName,
            status.left.humanize(),
        )
        PhoneStatus.FailClosed -> getString(R.string.status_fail_closed)
        PhoneStatus.Quiet -> getString(R.string.service_notif_text)
    }

    private fun buildStatusNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun startForegroundCompat() {
        // IMPORTANCE_MIN: the mandatory FGS notification stays out of the status bar and sits
        // collapsed in the silent section, rather than a permanent row at the top of the child's
        // phone — which matters more now that it carries a line that changes (see StatusLine).
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel("walcott_enforcement")
            nm.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.service_channel_desc)
                },
            )
        }
        // Whatever the loop last said, so a service restarted mid-evening does not flash the
        // generic line before its first tick.
        val notification: Notification =
            buildStatusNotification(statusText ?: getString(R.string.service_notif_text))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Claim the location type only when the permission is held. Degrade to special-use
            // if the richer type is refused, so enforcement never dies at startup.
            val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val wantsLocation = LocationPolicy.hasFineLocation(this)
            val withLocation =
                if (wantsLocation) special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else special
            if (runCatching { startForeground(NOTIF_ID, notification, withLocation) }.isFailure &&
                withLocation != special
            ) {
                startForeground(NOTIF_ID, notification, special)
                locationTypeHeld = false
            } else {
                locationTypeHeld = wantsLocation
            }
        } else {
            startForeground(NOTIF_ID, notification)
            locationTypeHeld = true
        }
    }

    /**
     * Whether this service actually holds the `location` foreground-service type.
     *
     * It is the whole basis of the child's background location access — the background permission
     * is deliberately denied (see [LocationPolicy]) — and the claim can fail: at start-up the
     * permission grant may not have landed yet, and the degrade to plain special-use was
     * permanent. A service that lost that race then ran for its entire life unable to get a fix,
     * silently, and the parent's only symptom was a child that never reported a position.
     */
    @Volatile private var locationTypeHeld = false

    /** Re-runs the claim when the permission is there and the type isn't. Cheap and idempotent. */
    private fun claimLocationType() {
        if (locationTypeHeld || !LocationPolicy.hasFineLocation(this)) return
        DebugLog.w(LOC_TAG, "re-claiming the location foreground-service type")
        runCatching { startForegroundCompat() }
            .onFailure { DebugLog.e(LOC_TAG, "could not re-claim the location FGS type", it) }
    }

    companion object {
        private const val NOTIF_ID = 1

        /**
         * Its own channel id because channel importance is immutable once created: the original
         * LOW channel is deleted at start-up so installs that upgrade actually quiet down.
         */
        private const val STATUS_CHANNEL_ID = "walcott_enforcement_quiet"
        private const val TICK_ACTIVE_MILLIS = 2000L
        private const val TICK_IDLE_MILLIS = 15_000L
        private const val MAX_CREDIT_SECONDS = 15L
        private const val UPDATE_CHECK_MILLIS = 6 * 60 * 60 * 1000L
        /** Managed-set cache TTL; the package receiver invalidates it instantly anyway. */
        private const val INVENTORY_TTL_MILLIS = 60_000L
        /** Periodic full re-assert of suspension state, catching any external drift. */
        private const val REASSERT_MILLIS = 30_000L

        /** How long the cached usage-access answer is trusted (an AppOps binder call). */
        private const val USAGE_ACCESS_TTL_MILLIS = 10_000L
        private const val BLOCK_FLUSH_MILLIS = 60_000L

        /** Safety net against a counter subscription that stops delivering (see the loop). */
        private const val COUNTER_RESYNC_MILLIS = 60_000L
        // Idle-earn cadence, kept coarse so screen-off earning costs few wakeups/writes: the
        // child wakes ~every 5 min while the screen is off to accrue idle, and batched idle is
        // flushed into earned time on the same period. Earned time needs no finer precision.
        private const val EARN_FLUSH_MILLIS = 5 * 60_000L
        private const val IDLE_STEP_MILLIS = 5 * 60_000L
        /** Cap on idle credited per accrual, so a long screen-off park can't dump hours at once. */
        private const val MAX_IDLE_STEP_SECONDS = 360L
        /**
         * How soon after start-up (or an interval change) the first fix is taken, rather than
         * waiting out a whole period on a phone that has just come back.
         */
        private const val FIRST_FIX_DELAY_MILLIS = 30_000L

        /** Slack on the live-tracking wakelock, for the fix still in flight at the deadline. */
        private const val LIVE_WAKELOCK_SLACK_MILLIS = 2 * 60_000L

        /**
         * How stale a fix may be during close tracking. Short: the parent is watching this move,
         * so somebody else's minute-old fix is worth taking and nothing older is.
         */
        private const val LIVE_CACHE_MAX_AGE_MILLIS = 20_000L
        /** Screen-off checkpoint publish, skipped if anything published this recently. */
        private const val SCREEN_OFF_PUBLISH_MIN_MS = 5 * 60_000L

        /** Publish throttle for package add/remove — prompt, but one message a minute at most. */
        private const val PACKAGE_PUBLISH_MIN_MS = 60_000L
        private const val LOC_TAG = "WalcottLocation"
        private const val TAG = "WalcottEnforce"

        /** Asks a running service to re-check what it holds (see [ACTION_RECHECK]). */
        private const val ACTION_RECHECK = "dev.walcott.RECHECK"

        fun start(context: Context, recheck: Boolean = false) {
            val intent = Intent(context, EnforcementService::class.java)
                .apply { if (recheck) action = ACTION_RECHECK }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EnforcementService::class.java))
        }
    }
}
