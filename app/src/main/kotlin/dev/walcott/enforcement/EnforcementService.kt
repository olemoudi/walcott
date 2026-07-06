package dev.walcott.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.walcott.MainActivity
import dev.walcott.R
import dev.walcott.WalcottApplication
import dev.walcott.net.VpnController
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.Verdict
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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

    override fun onCreate() {
        super.onCreate()
        enforcer = Enforcer(this)
        sampler = UsageSampler(this)
        power = getSystemService(PowerManager::class.java)
        startForegroundCompat()
        lifecycleScope.launch { runLoop() }
        observeWebFilter()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private suspend fun runLoop() {
        val repo = (application as WalcottApplication).repository
        var lastTick = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            val nowClock = SystemClock.elapsedRealtime()
            val deltaSeconds = (nowClock - lastTick) / 1000
            lastTick = nowClock

            val interactive = power.isInteractive
            val foreground = if (interactive) sampler.currentForeground() else null

            val config = repo.configNow()
            val usage = repo.usageNow()
            val extra = repo.effectiveExtraNow() // manually granted + earned by use
            val managed = repo.managedPackagesNow()
            val now = LocalDateTime.now()

            // Credit time to the foreground app's category (ignoring large jumps after doze).
            if (foreground != null && foreground in managed && deltaSeconds in 1..MAX_CREDIT_SECONDS) {
                config.assignments[foreground]?.let { categoryId ->
                    repo.addUsageSeconds(categoryId, deltaSeconds)
                }
            }

            val blocked = managed.filterTo(mutableSetOf()) { pkg ->
                RuleEngine.evaluate(config, pkg, now, usage, extra) is Verdict.Blocked
            }
            enforcer.apply(managed, blocked)

            delay(TICK_MILLIS)
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
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val TICK_MILLIS = 2000L
        private const val MAX_CREDIT_SECONDS = 15L

        fun start(context: Context) {
            val intent = Intent(context, EnforcementService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
