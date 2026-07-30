package dev.walcott.sync

import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * Which of the three on-device backup copies get rewritten tonight.
 *
 * Three files, each overwritten in place, so the folder never grows: last night, up to a week
 * back, up to a month back. The depth is the point — a single always-current copy faithfully
 * preserves a mistake (rules wrecked, a policy corrupted, somebody messing with the phone) as
 * soon as it happens, and by the time anyone notices there is nothing older to go back to.
 *
 * Slots roll on calendar boundaries rather than "N days since the last write", so a phone that
 * was off for a week doesn't shift every future rotation, and a parent can predict what they'll
 * find. Pure, so the decision is unit-tested instead of observed over a month of real nights.
 */
object BackupRotation {

    enum class Slot { DAILY, WEEKLY, MONTHLY }

    /**
     * Slots to rewrite on [today], given the date each was last written on (absent = never).
     *
     * A slot never written is always due: the first run fills all three, so every copy exists
     * from day one even though they start out identical.
     */
    fun due(today: LocalDate, lastWritten: Map<Slot, LocalDate>): Set<Slot> =
        Slot.entries.filterTo(mutableSetOf()) { slot ->
            val last = lastWritten[slot] ?: return@filterTo true
            // A clock that jumped backwards must not freeze a slot until real time catches up.
            if (last > today) return@filterTo true
            when (slot) {
                Slot.DAILY -> last < today
                Slot.WEEKLY -> weekOf(last) != weekOf(today)
                Slot.MONTHLY -> last.year != today.year || last.month != today.month
            }
        }

    /** ISO week-based year and week, so late December and early January don't collide. */
    private fun weekOf(date: LocalDate): Pair<Int, Int> =
        date.get(IsoFields.WEEK_BASED_YEAR) to date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}
