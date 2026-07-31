package dev.walcott.sync

/**
 * When to nag the parent about an install window that is still open on a child device.
 *
 * The long "I don't know how long I need" window (8 h) is the one this exists for: the parent
 * lifted the block, walked away, and every extra hour is time the child can install anything.
 * Short windows (10–30 min) expire before the first reminder is due, so they never nag.
 *
 * Pure so it is unit-tested on the JVM. The caller tracks when it FIRST saw the window open
 * ([firstSeenMs], from the child's snapshot) and when it last reminded ([lastReminderMs]).
 */
object InstallWindowReminder {

    /** No reminder until the window has been open this long (the first quiet hour). */
    const val MIN_OPEN_MS = 60 * 60 * 1000L

    /** Minimum gap between reminders; slightly under an hour so an hourly worker never skips. */
    const val REPEAT_MS = 55 * 60 * 1000L

    fun shouldRemind(untilMs: Long, firstSeenMs: Long, lastReminderMs: Long, nowMs: Long): Boolean =
        untilMs > nowMs &&
            firstSeenMs in 1..(nowMs - MIN_OPEN_MS) &&
            (lastReminderMs == 0L || nowMs - lastReminderMs >= REPEAT_MS)
}
