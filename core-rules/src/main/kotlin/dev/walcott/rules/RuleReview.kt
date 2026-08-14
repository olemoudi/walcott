package dev.walcott.rules

import java.time.DayOfWeek

/**
 * Reads a screen-free schedule back to the parent who wrote it, and says what is not doing what
 * they think.
 *
 * Rules accumulate. One gets added for homework, another for dinner, a third after a holiday,
 * and nothing ever removes the first two — so a list grows until nobody can say what it does,
 * and a rule that fires on no day of the year looks exactly like one that fires every day. This
 * finds the three shapes worth telling someone about: a rule that can never fire, a rule another
 * rule already covers, and rules that are one rule written twice.
 *
 * **Every finding is verified, not argued.** A proposed merge is accepted only when its coverage
 * — the exact set of minutes-of-the-week it blocks — equals the union of the coverage of the
 * rules it replaces. Nothing here can be offered to a parent unless applying it leaves the
 * child's phone behaving identically, which is the only basis on which an app should offer to
 * rewrite someone's rules.
 */
object RuleReview {

    /** Minutes in a week: the timeline everything here is compared on. */
    private const val WEEK_MINUTES = 7 * 24 * 60
    private const val DAY_MINUTES = 24 * 60

    /** Something worth saying about a schedule. [indices] point into the list as given. */
    sealed interface Finding {
        val indices: List<Int>

        /**
         * This rule blocks nothing, ever. In practice it is a window whose start equals its end —
         * "from 17:00 to 17:00" — which reads like a rule and is not one.
         */
        data class NeverFires(val index: Int) : Finding {
            override val indices: List<Int> get() = listOf(index)
        }

        /**
         * [index] is entirely inside [coveredBy]: every minute it blocks is already blocked, on
         * every day, so deleting it changes nothing.
         */
        data class Redundant(val index: Int, val coveredBy: Int) : Finding {
            override val indices: List<Int> get() = listOf(index, coveredBy)
        }

        /** These rules say together exactly what [merged] says alone. */
        data class Mergeable(override val indices: List<Int>, val merged: TimeWindow) : Finding
    }

    /**
     * Everything worth telling the parent about [windows], in the order the rules appear.
     *
     * A rule reported as never firing is not then reported as redundant or mergeable: it has one
     * problem, and listing it three times would bury the schedule's real shape under its noise.
     */
    fun review(windows: List<TimeWindow>): List<Finding> {
        if (windows.isEmpty()) return emptyList()
        val coverage = windows.map { coverage(it) }
        val dead = windows.indices.filter { coverage[it].isEmpty() }.toSet()
        val findings = mutableListOf<Finding>()
        dead.sorted().forEach { findings += Finding.NeverFires(it) }

        val live = windows.indices.filterNot { it in dead }
        // Reported once each, and never twice for the same rule: a rule already accounted for is
        // out of the running for anything else, so the list reads as one job per line.
        val spent = mutableSetOf<Int>()

        for (i in live) {
            if (i in spent) continue
            val cover = live.firstOrNull { other ->
                other != i && other !in spent &&
                    // Ties (two identical rules) resolve towards keeping the earlier one, which
                    // is the one the parent wrote first and recognises.
                    !(coverage[other] == coverage[i] && other > i) &&
                    dominates(windows[other], windows[i]) &&
                    coverage[other].containsAll(coverage[i])
            }
            if (cover != null) {
                findings += Finding.Redundant(index = i, coveredBy = cover)
                spent += i
            }
        }

        for (i in live) {
            if (i in spent) continue
            val group = mutableListOf(i)
            var merged = windows[i]
            for (j in live) {
                if (j <= i || j in spent) continue
                val candidate = mergeOf(merged, windows[j]) ?: continue
                // The guarantee: a merge is only a merge if it blocks exactly what the parts
                // blocked between them. Anything else is a rule change wearing a tidy-up's hat.
                if (coverage(candidate) != coverage(merged) + coverage[j]) continue
                merged = candidate
                group += j
            }
            if (group.size > 1) {
                findings += Finding.Mergeable(group, merged)
                spent += group
            }
        }
        return findings
    }

    /**
     * Whether a window carrying [outer]'s special-day rule applies on every day one carrying
     * [inner]'s does.
     *
     * Equal states always. Beyond that only [SpecialDays.ALWAYS] dominates, because it is the one
     * state that never stands down — which also settles the awkward case a looser rule would
     * open: a window crossing midnight is judged against the date of the morning it is blocking,
     * so two windows with different special-day rules do not compare cleanly across that
     * boundary. Requiring equality (or an ALWAYS that cannot care) keeps every answer exact.
     */
    private fun dominates(outer: TimeWindow, inner: TimeWindow): Boolean =
        outer.specialDays == inner.specialDays || outer.specialDays == SpecialDays.ALWAYS

    /**
     * The single window that would replace [a] and [b], or null when no single window could.
     *
     * Two shapes, because they are the two a parent actually creates: the same hours written
     * once per set of days (what splitting the week into sections used to force), and the same
     * days written as two touching stretches of time. The caller verifies the result regardless.
     */
    private fun mergeOf(a: TimeWindow, b: TimeWindow): TimeWindow? {
        if (a.specialDays != b.specialDays) return null
        if (a.start == b.start && a.end == b.end) {
            return a.copy(days = effectiveDays(a) + effectiveDays(b))
        }
        if (effectiveDays(a) != effectiveDays(b)) return null
        // Only for windows that stay inside one day: two that cross midnight can union into
        // something no single window describes, and the verification would reject it anyway —
        // this just declines to guess.
        if (a.start >= a.end || b.start >= b.end) return null
        if (a.start > b.end || b.start > a.end) return null // disjoint, and not even touching
        return a.copy(start = minOf(a.start, b.start), end = maxOf(a.end, b.end))
    }

    /** Its days, with the empty set read as what it means: every day. */
    private fun effectiveDays(window: TimeWindow): Set<DayOfWeek> =
        window.days.ifEmpty { DayOfWeek.entries.toSet() }

    /**
     * Exactly which minutes of the week this window blocks.
     *
     * The unit every comparison here is made in, so that midnight-crossing windows, day filters
     * and "empty means every day" are all handled once, by construction, instead of by a special
     * case per question. A window whose start equals its end covers nothing — which is precisely
     * what [TimeWindow.contains] says about it, and what makes it findable.
     */
    private fun coverage(window: TimeWindow): Set<Int> {
        val start = window.start.hour * 60 + window.start.minute
        val end = window.end.hour * 60 + window.end.minute
        if (start == end) return emptySet()
        val minutes = HashSet<Int>()
        for (day in effectiveDays(window)) {
            val base = (day.value - 1) * DAY_MINUTES
            if (start < end) {
                for (m in start until end) minutes += base + m
            } else {
                // Crosses midnight: the rest of this day, then the head of the next one.
                for (m in start until DAY_MINUTES) minutes += base + m
                for (m in 0 until end) minutes += (base + DAY_MINUTES + m) % WEEK_MINUTES
            }
        }
        return minutes
    }
}
