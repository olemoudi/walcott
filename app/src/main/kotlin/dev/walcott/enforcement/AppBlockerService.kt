package dev.walcott.enforcement

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import dev.walcott.R
import dev.walcott.WalcottApplication
import dev.walcott.rules.FamilyConfig
import dev.walcott.rules.RuleEngine
import dev.walcott.rules.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * Fallback enforcement for devices that are NOT Device Owner: detects the foreground app via
 * accessibility events and kicks the child out of blocked apps ([GLOBAL_ACTION_HOME] + a
 * heads-up notification). On Device Owner devices the stronger `setPackagesSuspended` path in
 * [EnforcementService] blocks apps and this coexists harmlessly. Usage accrual stays in
 * [EnforcementService]'s loop to avoid double counting; this service only blocks.
 */
class AppBlockerService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: WalcottApplication

    @Volatile private var config: FamilyConfig? = null
    @Volatile private var managed: Set<String> = emptySet()
    @Volatile private var usage: Map<String, Duration> = emptyMap()
    @Volatile private var extra: Map<String, Duration> = emptyMap()

    private var lastNotifiedPkg: String? = null
    private var lastNotifiedAt = 0L

    // A newly installed app is unclassified (so it must be blocked), but the config doesn't
    // change on install — without this the managed set would go stale and the blocker would
    // wave the new app through until the next policy edit.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { runCatching { managed = app.repository.managedPackagesNow() } }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        app = application as WalcottApplication
        INSTANCE = this
        scope.launch {
            app.repository.familyConfigFlow.collectLatest {
                config = it
                managed = app.repository.managedPackagesNow()
            }
        }
        scope.launch { app.repository.usageTodayFlow.collectLatest { usage = it } }
        scope.launch { app.repository.effectiveExtraTodayFlow.collectLatest { extra = it } }
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        INSTANCE = null
        runCatching { unregisterReceiver(packageReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cfg = config ?: return
        if (pkg == packageName || pkg !in managed) return
        val verdict = RuleEngine.evaluate(cfg, pkg, LocalDateTime.now(), usage, extra)
        if (verdict is Verdict.Blocked) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            notifyBlocked(pkg)
        }
    }

    private fun notifyBlocked(pkg: String) {
        val now = System.currentTimeMillis()
        if (pkg == lastNotifiedPkg && now - lastNotifiedAt < NOTIFY_THROTTLE_MS) return
        lastNotifiedPkg = pkg
        lastNotifiedAt = now
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.block_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        nm.notify(
            NOTIF_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.block_notif_title))
                .setContentText(getString(R.string.block_notif_text))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        @Volatile private var INSTANCE: AppBlockerService? = null
        private const val CHANNEL = "walcott_block"
        private const val NOTIF_ID = 42
        private const val NOTIFY_THROTTLE_MS = 30_000L

        /** True while the accessibility blocker is connected. */
        fun isConnected(): Boolean = INSTANCE != null

        /** True if the user has enabled Walcott's accessibility service in system settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = "${context.packageName}/${AppBlockerService::class.java.name}"
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
}
