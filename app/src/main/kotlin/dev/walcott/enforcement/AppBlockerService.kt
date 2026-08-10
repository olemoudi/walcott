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
    /**
     * Whether this device enforces at all. A parent phone — and a device freed by an emergency
     * release ([PanicRelease]) — must never be kicked out of apps, and after a release the
     * wiped policy would classify every app as unknown, i.e. block everything.
     */
    @Volatile private var enforcing: Boolean = true
    /** False once the clock is provably wrong (see [dev.walcott.sync.ClockGuard]). */
    @Volatile private var clockTrusted: Boolean = true
    @Volatile private var usage: Map<String, Duration> = emptyMap()
    @Volatile private var extra: Map<String, Duration> = emptyMap()

    private var lastNotifiedPkg: String? = null
    private var lastNotifiedAt = 0L

    /**
     * Cached answer to "is usage access still granted", with the moment it was read.
     *
     * The query is an AppOps lookup — a binder round trip — and this service runs on every
     * window change, which on a busy phone is many per second. The Device Owner loop caches it
     * the same way; this path simply never did. A few seconds of staleness costs nothing: it
     * only decides whether to fail closed, and the revocation itself is noticed within the
     * window either way.
     */
    @Volatile private var usageAccessOk = true
    @Volatile private var usageAccessReadAt = 0L

    // A newly installed app is unclassified (so it must be blocked), but the config doesn't
    // change on install — without this the managed set would go stale and the blocker would
    // wave the new app through until the next policy edit.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            app.repository.inventory.invalidate()
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
        scope.launch { app.identityStore.identity.collectLatest { enforcing = it.enforcesLocally } }
        scope.launch {
            app.syncManager.state.collectLatest {
                clockTrusted = !dev.walcott.sync.ClockGuard.isTampered(it.clockSkewMs)
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
        if (!enforcing) return
        val pkg = event.packageName?.toString() ?: return
        val cfg = config ?: return
        if (pkg == packageName || pkg !in managed) return
        // Mirror the Device Owner path's fail-closed rules: without the usage counter or with a
        // clock we can't trust, every managed app is blocked (see RuleEngine.blockedPackages).
        val failClosed = (!usageAccessGranted() && RuleEngine.requiresUsageCounting(cfg)) ||
            (!clockTrusted && RuleEngine.requiresTrustedClock(cfg))
        val verdict = if (failClosed) {
            Verdict.Blocked(dev.walcott.rules.BlockReason.FAIL_CLOSED)
        } else {
            RuleEngine.evaluate(cfg, pkg, LocalDateTime.now(), usage, extra)
        }
        if (verdict is Verdict.Blocked) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            notifyBlocked(pkg)
        }
    }

    /** Usage access, re-read at most every [USAGE_ACCESS_TTL_MS] (see [usageAccessOk]). */
    private fun usageAccessGranted(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - usageAccessReadAt > USAGE_ACCESS_TTL_MS) {
            usageAccessOk = UsageAccess.grantedForEnforcement(this)
            usageAccessReadAt = now
        }
        return usageAccessOk
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
        /** How long the cached usage-access answer is trusted (see [usageAccessOk]). */
        private const val USAGE_ACCESS_TTL_MS = 10_000L

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
