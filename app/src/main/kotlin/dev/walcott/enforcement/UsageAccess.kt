package dev.walcott.enforcement

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Usage-access (screen-time) permission check. [UsageSampler] fails *silently* without it
 * (queryEvents just returns nothing), so this state is reported to the parent in the child
 * snapshot rather than being discovered when budgets mysteriously stop counting.
 */
object UsageAccess {

    /** The permission's real state, or null when the platform wouldn't say (OEM quirk). */
    private fun state(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /**
     * For the enforcement decision: an unanswerable check counts as NOT granted. This value
     * feeds [dev.walcott.rules.RuleEngine]'s fail-closed branch, and it was the one place in
     * the app where an exception quietly favoured the child — budgets that never count down,
     * with no alert either, because the same call also fed the report below.
     */
    fun grantedForEnforcement(context: Context): Boolean = state(context) ?: false

    /**
     * For what the child reports to the parent: an unanswerable check counts as granted, so a
     * platform quirk raises no false "usage access is off" alarm on the parent's phone.
     */
    fun granted(context: Context): Boolean = state(context) ?: true
}
