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
import dev.walcott.rules.Verdict
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
                Intent.ACTION_SCREEN_OFF -> screenOn.value = false
            }
        }
    }

    /** Set when a package is (un)installed, so the managed-set cache refreshes immediately. */
    @Volatile private var inventoryDirty = true
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            inventoryDirty = true
            // A genuinely NEW install (not an app self-update) during a parent-pushed install
            // window closes that window at once, re-arming the install block so the child can't
            // slip a second app in behind the pushed one.
            if (intent?.action == Intent.ACTION_PACKAGE_ADDED &&
                !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                val app = application as WalcottApplication
                if (app.syncManager.pendingInstall.value.isNotEmpty()) {
                    lifecycleScope.launch { runCatching { app.syncManager.closeInstallWindow() } }
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
        startForegroundCompat()
        lifecycleScope.launch { runLoop() }
        observeWebFilter()
        observeDeviceRestrictions()
        scheduleUpdateChecks()
        scheduleLocationSampling()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
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
                    while (currentCoroutineContext().isActive) {
                        val startedAt = SystemClock.elapsedRealtime()
                        var gotFix = false
                        runCatching {
                            val fix = sampler.currentFix()
                            if (fix != null) {
                                app.repository.recordLocation(fix)
                                gotFix = true
                                DebugLog.i(LOC_TAG, "recorded fix acc=${fix.accuracyM}m mock=${fix.mock}")
                            } else {
                                DebugLog.w(LOC_TAG, "no location fix this cycle")
                            }
                            app.syncManager.publishLocationUpdate()
                        }.onFailure { DebugLog.e(LOC_TAG, "location sampling cycle failed", it) }

                        // Sleep the REMAINDER of the period, not a full one: acquiring a fix can
                        // take up to a minute (three providers, each with its own timeout), and
                        // adding that to every cycle made the real interval drift well past the
                        // one the parent chose. A failed cycle retries sooner — a device that
                        // just walked outdoors shouldn't stay unlocatable for a whole period.
                        //
                        // The floor is what keeps that safe: with no fix available the sampler
                        // burns its full timeout on every provider, so subtracting the elapsed
                        // time would leave a zero wait and spin the GPS continuously — exactly
                        // in the indoors/airplane-mode case where it can never succeed.
                        val target = if (gotFix) periodMs else minOf(RETRY_LOCATION_MILLIS, periodMs)
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        delay((target - elapsed).coerceAtLeast(MIN_LOCATION_GAP_MILLIS))
                    }
                }
        }
    }

    /** Starts/stops the DNS filter VPN as web-filter rules appear or disappear. */
    private fun observeWebFilter() {
        val repo = (application as WalcottApplication).repository
        lifecycleScope.launch {
            repo.settingsFlow
                .map { it.hasWebFilter() }
                .distinctUntilChanged()
                .collect { enabled -> VpnController.apply(this@EnforcementService, enabled) }
        }
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

    private suspend fun runLoop() {
        val app = application as WalcottApplication
        val repo = app.repository
        var lastTick = SystemClock.elapsedRealtime()
        var lastForeground: String? = null
        var lastUsageAccess: Boolean? = null
        // Managed-set cache: enumerating PackageManager (launchable apps + labels) on every
        // 2s tick was pure binder churn — the set only changes on (un)installs and
        // classification edits, both of which invalidate it explicitly below.
        var managed: Set<String> = emptySet()
        var managedFetchedAt = 0L
        var lastAssignments: Map<String, String>? = null
        // Suspension is only re-asserted when the target state changes (or periodically as
        // a self-heal), instead of N isPackageSuspended binder calls per tick.
        var lastAppliedBlocked: Set<String>? = null
        var lastAppliedManaged: Set<String>? = null
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
                    idleCfg, config.calendar.dayTypeOf(nowForEarn.toLocalDate()), nowForEarn.toLocalTime(),
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

            val usage = repo.usageNow()
            val extra = repo.effectiveExtraNow() // manually granted + idle-earned
            if (inventoryDirty || config.assignments != lastAssignments ||
                nowClock - managedFetchedAt > INVENTORY_TTL_MILLIS
            ) {
                managed = repo.managedPackagesNow()
                managedFetchedAt = nowClock
                lastAssignments = config.assignments
                inventoryDirty = false
            }

            // Credit time only on consecutive sightings of the same managed app, so the
            // slow idle tick can't attribute time actually spent elsewhere.
            val creditedUsage = foreground != null && foreground == lastForeground &&
                foreground in managed && deltaSeconds in 1..MAX_CREDIT_SECONDS
            if (creditedUsage) {
                config.assignments[foreground]?.let { categoryId ->
                    repo.addUsageSeconds(categoryId, deltaSeconds)
                }
                // Per-app budget: also count under the package key (has a dot, never collides
                // with a category id) so RuleEngine can enforce the app's own sub-cap.
                if (foreground in config.perAppPolicies) {
                    repo.addUsageSeconds(foreground, deltaSeconds)
                }
            }
            // Idle-earn: screen on but not on a managed app, inside an earning window.
            if (idleCfg != null && earningNow && !creditedUsage && deltaSeconds in 1..MAX_IDLE_STEP_SECONDS) {
                idleAccumSeconds += deltaSeconds
            }
            lastForeground = foreground

            // Fail CLOSED when the config needs the usage counter but usage access is revoked:
            // without it budgets never count down, so a child could disable the toggle for
            // unlimited time. Suspending everything managed makes revoking it self-defeating —
            // the apps come back the moment the permission does. A Device Owner can't grant or
            // pin usage access (it's an AppOp, out of setPermissionGrantState's reach), so this
            // is the strongest enforcement available.
            val usageAccessOk = UsageAccess.granted(this)
            if (usageAccessOk != lastUsageAccess) {
                if (lastUsageAccess != null) {
                    DebugLog.w(TAG, "usage access changed: granted=$usageAccessOk")
                    // Tell the parent right away instead of waiting for the next re-emit.
                    lifecycleScope.launch { runCatching { app.syncManager.publishHealthUpdate() } }
                }
                lastUsageAccess = usageAccessOk
            }
            val failClosed = !usageAccessOk && RuleEngine.requiresUsageCounting(config)

            val blocked: Set<String> = if (failClosed) {
                managed
            } else {
                managed.filterTo(mutableSetOf()) { pkg ->
                    RuleEngine.evaluate(config, pkg, now, usage, extra) is Verdict.Blocked
                }
            }
            // Re-assert on change, plus periodically so external state drift self-heals.
            if (blocked != lastAppliedBlocked || managed != lastAppliedManaged ||
                nowClock - lastApplyAt > REASSERT_MILLIS
            ) {
                enforcer.apply(managed, blocked)
                lastAppliedBlocked = blocked
                lastAppliedManaged = managed
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
        // Idle-earn cadence, kept coarse so screen-off earning costs few wakeups/writes: the
        // child wakes ~every 5 min while the screen is off to accrue idle, and batched idle is
        // flushed into earned time on the same period. Earned time needs no finer precision.
        private const val EARN_FLUSH_MILLIS = 5 * 60_000L
        private const val IDLE_STEP_MILLIS = 5 * 60_000L
        /** Cap on idle credited per accrual, so a long screen-off park can't dump hours at once. */
        private const val MAX_IDLE_STEP_SECONDS = 360L
        /** Backoff after a cycle that produced no fix (indoors, GPS warming up, airplane mode). */
        private const val RETRY_LOCATION_MILLIS = 60_000L
        /** Hard floor between sampling cycles, so a never-succeeding fix can't spin the radio. */
        private const val MIN_LOCATION_GAP_MILLIS = 30_000L
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
