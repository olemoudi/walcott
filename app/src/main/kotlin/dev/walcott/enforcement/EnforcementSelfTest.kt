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
 * actual suspension state end to end. On a mismatch it re-asserts immediately.
 *
 * Only a gap that SURVIVES that re-assert is reported to the parent. Most mismatches are
 * ordinary and self-healing: the enforcement loop parks with zero wakeups while the screen is
 * off, so a rule that starts during the night (bedtime, a blocked window) isn't applied until
 * something wakes the device — and this alarm is usually what does. Reporting those meant a
 * "N apps aren't actually blocked" alert most nights, cleared an hour later, for apps nobody
 * could have opened anyway (the screen was off). What deserves the parent's attention is the
 * gap that persists after re-asserting: a package the OS refuses to suspend.
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
        val drift = enforcer.unenforced(blocked)
        // Nothing to fix: report the clean bill of health (this is what clears a standing alert).
        if (drift.isEmpty()) {
            app.syncManager.recordEnforcementGap(emptyList())
            return
        }
        DebugLog.i(TAG, "self-test found drift; re-asserting: ${drift.joinToString()}")
        enforcer.apply(managed, blocked)
        // Re-measure: whatever is still unsuspended after asking the OS again is a real,
        // unfixable gap. The transient we just repaired stays in the log and out of the feed.
        val persistent = enforcer.unenforced(blocked)
        if (persistent.isNotEmpty()) {
            DebugLog.w(TAG, "self-test gap persists after re-assert: ${persistent.joinToString()}")
        }
        app.syncManager.recordEnforcementGap(persistent.take(REPORT_LIMIT))
    }
}
