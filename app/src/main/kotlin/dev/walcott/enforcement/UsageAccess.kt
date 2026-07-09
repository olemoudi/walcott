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
    fun granted(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(true) // fail open: never raise a tamper alarm on a check error
}
