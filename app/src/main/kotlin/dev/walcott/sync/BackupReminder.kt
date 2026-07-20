package dev.walcott.sync

import java.util.concurrent.TimeUnit

/**
 * The backup-nudge ladder (pure, tested). Two regimes:
 *
 *  - **Never backed up**: first nudge [FIRST_NUDGE_DAYS] after the device became a parent,
 *    then every [REPEAT_DAYS] until a backup exists.
 *  - **Backed up before**: only when the policy changed AFTER the backup — an unchanged
 *    backup never goes stale, so it never nags. First nudge when the backup turns
 *    [STALE_FIRST_DAYS] old, the next at [STALE_SECOND_DAYS], then every [REPEAT_DAYS].
 *
 * With auto-backup on, every edit refreshes the file (lastBackupAtMs moves with it), so the
 * stale regime stays silent unless the auto-refresh is failing — exactly when nagging helps.
 * The "don't remind me again" notification action flips the identity switch off for good.
 */
object BackupReminder {

    const val FIRST_NUDGE_DAYS = 5L
    const val STALE_FIRST_DAYS = 30L
    const val STALE_SECOND_DAYS = 45L
    const val REPEAT_DAYS = 2L

    fun shouldRemind(
        nowMs: Long,
        enabled: Boolean,
        parentSetupAtMs: Long,
        lastBackupAtMs: Long,
        lastPolicyEditAtMs: Long,
        lastReminderAtMs: Long,
    ): Boolean {
        if (!enabled || parentSetupAtMs <= 0) return false
        val due = if (lastBackupAtMs <= 0) {
            nextDue(anchorMs = parentSetupAtMs + days(FIRST_NUDGE_DAYS), lastReminderAtMs)
        } else {
            if (lastPolicyEditAtMs <= lastBackupAtMs) return false
            when {
                lastReminderAtMs < lastBackupAtMs + days(STALE_FIRST_DAYS) ->
                    lastBackupAtMs + days(STALE_FIRST_DAYS)
                lastReminderAtMs < lastBackupAtMs + days(STALE_SECOND_DAYS) ->
                    lastBackupAtMs + days(STALE_SECOND_DAYS)
                else -> lastReminderAtMs + days(REPEAT_DAYS)
            }
        }
        return nowMs >= due
    }

    /** First reminder at [anchorMs]; once one fired, the next is [REPEAT_DAYS] after it. */
    private fun nextDue(anchorMs: Long, lastReminderAtMs: Long): Long =
        if (lastReminderAtMs < anchorMs) anchorMs else lastReminderAtMs + days(REPEAT_DAYS)

    private fun days(d: Long): Long = TimeUnit.DAYS.toMillis(d)
}
