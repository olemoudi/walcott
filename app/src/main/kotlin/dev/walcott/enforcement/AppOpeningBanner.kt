package dev.walcott.enforcement

/**
 * Whether opening an app counts as *coming back to it*, and so is worth telling the child what
 * they have left in it.
 *
 * The rest of the app tells them when time is nearly gone ([dev.walcott.rules.CloseWatch]),
 * which is the moment it hurts and far too late to plan around. This is the other moment: the
 * one where a child decides to open something, and where knowing "twelve minutes" changes what
 * they do with it. Saying it then costs nothing and is the only point at which the number is
 * actually useful.
 *
 * The whole design is in the quiet window. Switching between two apps, or glancing at a
 * notification and going back, must say nothing at all — a phone that announces itself every
 * time the foreground changes is a phone nobody reads. Ten minutes away is a decision to do
 * something else and come back, which is a new sitting.
 *
 * Pure and clock-injected so the rule is unit-tested rather than observed on a device; the
 * enforcement loop supplies the clock it already reads every tick.
 */
class AppOpeningBanner(private val quietMs: Long = QUIET_MS) {

    /** package -> when it was last on screen, in the caller's monotonic clock. */
    private val lastSeen = mutableMapOf<String, Long>()

    /**
     * Records that [pkg] has just come to the foreground, and answers whether that is a fresh
     * enough return to be worth announcing.
     *
     * An app never seen before is announced: on the first opening of the day the child has no
     * idea what they have, which is exactly the case this exists for.
     */
    fun opened(pkg: String, nowMs: Long): Boolean {
        val last = lastSeen.put(pkg, nowMs)
        return last == null || nowMs - last > quietMs
    }

    /**
     * Keeps [pkg]'s presence current while it stays on screen.
     *
     * Without this the window would measure from when an app was OPENED rather than from when
     * it was last used, so a child who kept one app open for half an hour would be greeted the
     * moment they touched anything else and came back.
     */
    fun stillOpen(pkg: String, nowMs: Long) {
        lastSeen[pkg] = nowMs
    }

    /** Forgets everything, for a device handed back (see [PanicRelease]). */
    fun clear() = lastSeen.clear()

    companion object {
        /** Time away that makes a return a new sitting rather than a glance somewhere else. */
        const val QUIET_MS = 10 * 60 * 1000L
    }
}
