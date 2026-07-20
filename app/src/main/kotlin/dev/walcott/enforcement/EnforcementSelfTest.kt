package dev.walcott.enforcement

import android.content.Context
import dev.walcott.WalcottApplication
import dev.walcott.debug.DebugLog
import dev.walcott.rules.RuleEngine
import java.time.LocalDateTime

/**
 * The heartbeat's "looks healthy, isn't blocking" check: recompute what should be suspended
 * right now and ask the OS whether it agrees. The scariest enforcement failure is the silent
 * one — service running, rules right, and yet an app usable — and nothing else verifies the
 * actual suspension state end to end. On a mismatch it re-asserts immediately and records the
 * gap so the next publish carries it to the parent.
 */
object EnforcementSelfTest {

    private const val TAG = "WalcottEnforce"

    /** Cap on the packages reported to the parent; the debug log carries the full list. */
    private const val REPORT_LIMIT = 8

    suspend fun run(context: Context) {
        val app = context.applicationContext as WalcottApplication
        val enforcer = Enforcer(context)
        // Suspension state is only measurable (and enforced this way) as Device Owner; on the
        // accessibility backend there is nothing to query, so the self-test stays silent.
        if (!enforcer.isDeviceOwner()) return
        val repo = app.repository
        val managed = repo.managedPackagesNow()
        val blocked = RuleEngine.blockedPackages(
            repo.configNow(),
            managed,
            LocalDateTime.now(),
            repo.usageNow(),
            repo.effectiveExtraNow(),
            usageCountingAvailable = UsageAccess.granted(context),
        )
        val gaps = enforcer.unenforced(blocked)
        if (gaps.isNotEmpty()) {
            DebugLog.w(TAG, "self-test failed; not actually suspended: ${gaps.joinToString()}")
            enforcer.apply(managed, blocked)
        }
        app.syncManager.recordEnforcementGap(gaps.take(REPORT_LIMIT))
    }
}
