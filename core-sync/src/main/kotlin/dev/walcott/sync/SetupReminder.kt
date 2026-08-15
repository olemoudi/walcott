package dev.walcott.sync

/**
 * When to remind the parent that a child's phone still has settings nobody granted.
 *
 * An enrollment is two halves: the QR, which the parent finishes because nothing works until
 * they do, and the permissions on the child's phone, which they can walk away from without
 * anything appearing to be wrong — the device pairs, publishes, shows up on the parent's home,
 * and quietly counts no screen time. That silence is what this exists to break.
 *
 * Pure so it is unit-tested on the JVM. The caller tracks when the device was first seen
 * incomplete ([pendingSinceMs], stamped when the child's snapshot first arrives with something
 * missing) and when it last reminded ([lastReminderMs]).
 */
object SetupReminder {

    /**
     * How long a device stays quietly incomplete before the first reminder.
     *
     * The parent is normally standing over the phone at enrollment, with the same list on the
     * screen in front of them; a notification in that minute is noise about something they are
     * already doing. An hour later they have walked away, and it is news.
     */
    const val GRACE_MS = 60 * 60 * 1000L

    /** How long a standing problem stays quiet before it is worth saying again. */
    const val REPEAT_MS = 12 * 60 * 60 * 1000L

    /**
     * True when the parent should be told now.
     *
     * A [lastReminderMs] in the future counts as due rather than as recent: the parent's clock
     * can go backwards (a timezone flight, a manual change), and a stamp parked in the future
     * would otherwise silence the reminder for as long as the drift lasts.
     */
    fun shouldRemind(pendingSinceMs: Long, lastReminderMs: Long, nowMs: Long): Boolean =
        pendingSinceMs in 1..(nowMs - GRACE_MS) &&
            (lastReminderMs <= 0L || lastReminderMs > nowMs || nowMs - lastReminderMs >= REPEAT_MS)
}
