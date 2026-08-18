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
import dev.walcott.location.LocationPolicy
import dev.walcott.location.LocationSampler
import dev.walcott.net.VpnController
import dev.walcott.rules.RuleEngine
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
                                else -> ""
                            },
                        )
                    }
                delay(UPDATE_CHECK_MILLIS)
            }
        }
    }

    /**
     * Periodic GPS sampling driven by the child's resolved tracking interval (0 = off). A new
     * interval restarts the loop; the Doze-exempt FGS gives near-exact cadence at any interval.
     */
    private fun scheduleLocationSampling() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            app.repository.settingsFlow
                .map { it.trackingIntervalMinutes }
                .distinctUntilChanged()
                .collectLatest { minutes ->
                    DebugLog.i(LOC_TAG, "tracking interval resolved: $minutes min")
                    if (minutes <= 0) return@collectLatest
                    val sampler = LocationSampler(this@EnforcementService)
                    val periodMs = minutes * 60_000L
                    // Consecutive cycles that produced nothing, so a device that cannot be
                    // located backs off instead of retrying at full price for ever.
                    var failures = 0
                    while (currentCoroutineContext().isActive) {
                        val startedAt = SystemClock.elapsedRealtime()
                        var gotFix = false
                        runCatching {
                            // A fix from anyone, less than a third of a period old, is as good as
                            // ours and costs nothing: a phone on a desk reports the same place
                            // whether or not we spin its GPS to hear it.
                            val fix = sampler.currentFix(maxCacheAgeMs = periodMs / 3)
                            if (fix != null) {
                                app.repository.recordLocation(fix)
                                gotFix = true
                                DebugLog.i(LOC_TAG, "recorded fix acc=${fix.accuracyM}m mock=${fix.mock}")
                            } else {
                                DebugLog.w(LOC_TAG, "no location fix this cycle")
                            }
                            app.syncManager.publishLocationUpdate()
                        }.onFailure { DebugLog.e(LOC_TAG, "location sampling cycle failed", it) }

                        // Sleep the REMAINDER of the period, not a full one: acquiring a fix takes
                        // real time, and adding that to every cycle made the interval drift well
                        // past the one the parent chose. A failed cycle retries sooner — a device
                        // that just walked outdoors shouldn't stay unlocatable for a whole period.
                        //
                        // But it retries sooner only for a while. A phone that cannot be located
                        // usually cannot be located for hours (indoors, aeroplane mode, no sky),
                        // and a fixed one-minute retry meant powering the radio every ninety
                        // seconds all afternoon to be told the same thing. Each failure doubles
                        // the wait, up to the interval the parent asked for; one success resets
                        // it. The floor stays as the last guard against a zero wait.
                        failures = if (gotFix) 0 else failures + 1
                        val backoff = RETRY_LOCATION_MILLIS shl (failures - 1).coerceIn(0, MAX_LOCATION_BACKOFF_SHIFT)
                        val target = if (gotFix) periodMs else minOf(backoff, periodMs)
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        delay((target - elapsed).coerceAtLeast(MIN_LOCATION_GAP_MILLIS))
                    }
                }
        }
    }

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
            // Either reason keeps the tunnel up: rules to enforce, or a parent watching which
            // domains an app resolves. When the session expires the flow drops back by itself.
            kotlinx.coroutines.flow.combine(
                repo.settingsFlow.map { it.hasWebFilter() },
                dev.walcott.net.DomainMonitor.state,
            ) { hasRules, monitor -> hasRules || monitor.isActive(System.currentTimeMillis()) }
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

    /** Keeps the Device Owner user restrictions in sync with the policy. */
    private fun observeDeviceRestrictions() {
        val app = application as WalcottApplication
        lifecycleScope.launch {
            combine(
                app.repository.settingsFlow.map { it.deviceRestrictions },
                app.syncManager.installExemption,
            ) { keys, exemptUntil -> keys to exemptUntil }
                .distinctUntilChanged()
                .collectLatest { (keys, exemptUntil) ->
                    DeviceRestrictions.apply(this@EnforcementService, keys, exemptUntil)
                    // Re-arm the install block when the exemption window closes.
                    val untilExpiry = exemptUntil - System.currentTimeMillis()
                    if (untilExpiry > 0 && DeviceRestrictions.KEY_INSTALLS in keys) {
                        delay(untilExpiry + 1_000)
                        DeviceRestrictions.apply(this@EnforcementService, keys, exemptUntil)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
            if (!failingClosed) {
                val deviceBlock = RuleEngine.deviceWideBlock(config, now)
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

            // Tight cadence only while a managed app is actually in use (budget countdown
            // needs it); blocked apps are already suspended, so idling can tick slowly.
            delay(if (foreground != null && foreground in managed) TICK_ACTIVE_MILLIS else TICK_IDLE_MILLIS)
        }
    }

    private fun startForegroundCompat() {
        // IMPORTANCE_MIN: the mandatory FGS notification stays out of the status bar and sits
        // collapsed in the silent section, instead of a permanent "Walcott is protecting your
        // device" row on the child's phone. A new channel id because channel importance is
        // immutable once created; the old LOW channel is deleted so installs that upgrade
        // actually quiet down.
        val channelId = "walcott_enforcement_quiet"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel("walcott_enforcement")
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                    description = getString(R.string.service_channel_desc)
                },
            )
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Claim the location type only when the permission is held. Degrade to special-use
            // if the richer type is refused, so enforcement never dies at startup.
            val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val withLocation =
                if (LocationPolicy.hasFineLocation(this)) special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else special
            if (runCatching { startForeground(NOTIF_ID, notification, withLocation) }.isFailure && withLocation != special) {
                startForeground(NOTIF_ID, notification, special)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1
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
        /** First backoff after a cycle that produced no fix (indoors, GPS warming up, airplane mode). */
        private const val RETRY_LOCATION_MILLIS = 60_000L

        /** Caps the doubling at 2^4 = 16 minutes, before the period's own cap applies. */
        private const val MAX_LOCATION_BACKOFF_SHIFT = 4
        /** Hard floor between sampling cycles, so a never-succeeding fix can't spin the radio. */
        private const val MIN_LOCATION_GAP_MILLIS = 30_000L
        /** Screen-off checkpoint publish, skipped if anything published this recently. */
        private const val SCREEN_OFF_PUBLISH_MIN_MS = 5 * 60_000L

        /** Publish throttle for package add/remove — prompt, but one message a minute at most. */
        private const val PACKAGE_PUBLISH_MIN_MS = 60_000L
        private const val LOC_TAG = "WalcottLocation"
        private const val TAG = "WalcottEnforce"

        fun start(context: Context) {
            val intent = Intent(context, EnforcementService::class.java)
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
