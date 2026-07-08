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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.walcott.MainActivity
import dev.walcott.R
import dev.walcott.WalcottApplication
import dev.walcott.location.LocationPolicy
import dev.walcott.location.LocationSampler
import dev.walcott.net.VpnController
import dev.walcott.rules.RuleEngine
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

    override fun onCreate() {
        super.onCreate()
        enforcer = Enforcer(this)
        sampler = UsageSampler(this)
        power = getSystemService(PowerManager::class.java)
        screenOn.value = power.isInteractive
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
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
        super.onDestroy()
    }

    /**
     * Runs the update check from this always-on foreground service, so on the child device the
     * process is guaranteed to be alive to download and (silently) install a new version.
     */
    private fun scheduleUpdateChecks() {
        lifecycleScope.launch {
            while (currentCoroutineContext().isActive) {
                runCatching { Updater(applicationContext).checkAndUpdate() }
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
                    if (minutes <= 0) return@collectLatest
                    val sampler = LocationSampler(this@EnforcementService)
                    while (currentCoroutineContext().isActive) {
                        runCatching {
                            sampler.currentFix()?.let { app.repository.recordLocation(it) }
                            app.syncManager.publishLocationUpdate()
                        }
                        delay(minutes * 60_000L)
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
        val repo = (application as WalcottApplication).repository
        var lastTick = SystemClock.elapsedRealtime()
        var lastForeground: String? = null

        while (currentCoroutineContext().isActive) {
            // Screen off: nothing accrues and blocked apps stay suspended, so park with
            // zero wakeups until it comes back on, then re-evaluate right away (a bedtime
            // or blocked window may have started meanwhile).
            if (!screenOn.value) {
                lastForeground = null
                screenOn.first { it }
                lastTick = SystemClock.elapsedRealtime()
            }

            val nowClock = SystemClock.elapsedRealtime()
            val deltaSeconds = (nowClock - lastTick) / 1000
            lastTick = nowClock

            val foreground = sampler.currentForeground()

            val config = repo.configNow()
            val usage = repo.usageNow()
            val extra = repo.effectiveExtraNow() // manually granted + earned by use
            val managed = repo.managedPackagesNow()
            val now = LocalDateTime.now()

            // Credit time only on consecutive sightings of the same managed app, so the
            // slow idle tick can't attribute time actually spent elsewhere.
            if (foreground != null && foreground == lastForeground && foreground in managed &&
                deltaSeconds in 1..MAX_CREDIT_SECONDS
            ) {
                config.assignments[foreground]?.let { categoryId ->
                    repo.addUsageSeconds(categoryId, deltaSeconds)
                }
            }
            lastForeground = foreground

            val blocked = managed.filterTo(mutableSetOf()) { pkg ->
                RuleEngine.evaluate(config, pkg, now, usage, extra) is Verdict.Blocked
            }
            enforcer.apply(managed, blocked)

            // Tight cadence only while a managed app is actually in use (budget countdown
            // needs it); blocked apps are already suspended, so idling can tick slowly.
            delay(if (foreground != null && foreground in managed) TICK_ACTIVE_MILLIS else TICK_IDLE_MILLIS)
        }
    }

    private fun startForegroundCompat() {
        val channelId = "walcott_enforcement"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
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
            // Add the location type only when the permission is held, or startForeground throws.
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (LocationPolicy.hasFineLocation(this)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIF_ID, notification, type)
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
