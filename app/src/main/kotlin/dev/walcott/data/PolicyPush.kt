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
 * So edits coalesce. The first one waits [FIRST_HOLD_MS]; each further edit inside the window
 * extends the wait by [STEP_MS] up to [MAX_HOLD_MS], always measured from the LAST edit. Once at
 * the maximum, every new edit simply restarts that maximum. The burst ends when the parent stops
 * touching things, and exactly one policy goes out with all of it.
 *
 * Pure, because "when does this actually get sent" is the part that has to be reasoned about
 * rather than watched: the whole mechanism is invisible when it works.
 */
object PolicyPush {

    /** Wait after a single edit — long enough to catch the correction that follows a typo. */
    const val FIRST_HOLD_MS = 15_000L

    /** Added per further edit within the window. */
    const val STEP_MS = 5_000L

    /** Ceiling, so a parent editing steadily still ships something every half minute. */
    const val MAX_HOLD_MS = 30_000L

    /**
     * How long to wait from the most recent edit, given how many have accumulated unsent
     * ([edits] is 1 for the first). 15 s, 20 s, 25 s, then 30 s for ever.
     */
    fun holdMs(edits: Int): Long {
        if (edits <= 1) return FIRST_HOLD_MS
        return (FIRST_HOLD_MS + STEP_MS * (edits - 1)).coerceAtMost(MAX_HOLD_MS)
    }

    /** When a burst of [edits] whose last edit landed at [lastEditAtMs] should be published. */
    fun dueAtMs(lastEditAtMs: Long, edits: Int): Long = lastEditAtMs + holdMs(edits)

    /**
     * How long is still left to wait at [nowMs], never negative.
     *
     * Clamped at both ends on purpose: a clock that jumped forward must not make a pending edit
     * look overdue by a day, and one that jumped backwards must not park it in the future. The
     * caller only ever needs "how much longer", and the honest answer is bounded by the hold.
     */
    fun remainingMs(lastEditAtMs: Long, edits: Int, nowMs: Long): Long =
        (dueAtMs(lastEditAtMs, edits) - nowMs).coerceIn(0, holdMs(edits))
}
