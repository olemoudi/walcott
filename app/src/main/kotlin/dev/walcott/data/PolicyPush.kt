package dev.walcott.data

/**
 * How long a rule edit is held before it is published.
 *
 * Editing rules is not one action, it is a sitting: a parent opens the limits screen and changes
 * four things in twenty seconds. Publishing each keystroke sent four policies, bumped the version
 * four times, woke every child's radio four times and — worse for the person doing it — made the
 * middle two states real on a child's phone for a few seconds each, including whatever half-typed
 * intermediate they immediately corrected.
 *
 * So edits coalesce, under two deadlines, and the earlier one wins:
 *
 * - [IDLE_HOLD_MS] after the parent's last touch. This is the common case — they finish, and a
 *   few seconds later the whole sitting goes out as one policy.
 * - [MAX_HOLD_MS] after the FIRST edit still waiting, whatever happens next. A long sitting used
 *   to defer everything in it indefinitely: the hold was measured only from the last edit, so a
 *   parent who kept adjusting kept pushing the deadline out in front of them, and a change made
 *   at the start could sit on the phone for as long as they went on. The first edit's own clock
 *   is what stops that, and it never stops for anything.
 *
 * Pure, because "when does this actually get sent" is the part that has to be reasoned about
 * rather than watched: the whole mechanism is invisible when it works.
 */
object PolicyPush {

    /**
     * The wait after the parent's last touch.
     *
     * Long enough to swallow the correction that follows a typo and the second tap of a pair,
     * short enough that "I changed it and nothing happened" is never a fair description.
     */
    const val IDLE_HOLD_MS = 10_000L

    /** The longest anything already changed waits, however long the parent keeps going. */
    const val MAX_HOLD_MS = 30_000L

    /**
     * When a burst that began at [firstEditAtMs] and last moved at [lastEditAtMs] is due.
     *
     * The earlier of the two deadlines, so continuing to edit can postpone a change up to the
     * ceiling and not one millisecond past it.
     */
    fun dueAtMs(firstEditAtMs: Long, lastEditAtMs: Long): Long =
        minOf(lastEditAtMs + IDLE_HOLD_MS, firstEditAtMs + MAX_HOLD_MS)

    /**
     * How long is still left to wait at [nowMs], never negative.
     *
     * Clamped at both ends on purpose: a clock that jumped forward must not make a pending edit
     * look overdue by a day, and one that jumped backwards must not park it in the future. The
     * caller only ever needs "how much longer", and the honest answer is bounded by the idle
     * hold — no deadline can ever be further away than that, since a fresh edit resets it.
     */
    fun remainingMs(firstEditAtMs: Long, lastEditAtMs: Long, nowMs: Long): Long =
        (dueAtMs(firstEditAtMs, lastEditAtMs) - nowMs).coerceIn(0, IDLE_HOLD_MS)
}
