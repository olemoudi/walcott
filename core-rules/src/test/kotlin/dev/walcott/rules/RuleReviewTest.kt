package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Reading a schedule back to the parent who wrote it.
 *
 * The tests that matter most are the ones asserting SILENCE: an app offering to rewrite
 * somebody's rules has to be wrong approximately never, and a false "you can delete this" costs
 * a family a rule they meant to keep.
 */
class RuleReviewTest {

    private fun w(
        from: String,
        to: String,
        days: Set<DayOfWeek> = emptySet(),
        special: SpecialDays = SpecialDays.ALWAYS,
    ) = TimeWindow(LocalTime.parse(from), LocalTime.parse(to), days, special)

    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    // --- Rules that can never fire ---

    @Test
    fun `a window that starts when it ends blocks nothing and says so`() {
        val findings = RuleReview.review(listOf(w("17:00", "17:00")))
        assertEquals(listOf(RuleReview.Finding.NeverFires(0)), findings)
    }

    @Test
    fun `a dead rule is reported once, not also as redundant or mergeable`() {
        // It has one problem. Listing it three times would bury the schedule's real shape.
        val findings = RuleReview.review(listOf(w("17:00", "19:00"), w("17:00", "17:00")))
        assertEquals(listOf(RuleReview.Finding.NeverFires(1)), findings)
    }

    // --- Rules another rule already covers ---

    @Test
    fun `a window inside another on the same days is redundant`() {
        val findings = RuleReview.review(listOf(w("17:00", "20:00"), w("18:00", "19:00")))
        assertEquals(listOf(RuleReview.Finding.Redundant(index = 1, coveredBy = 0)), findings)
    }

    @Test
    fun `fewer days inside more days is redundant`() {
        val findings = RuleReview.review(
            listOf(w("17:00", "19:00"), w("17:00", "19:00", days = setOf(DayOfWeek.MONDAY))),
        )
        assertEquals(listOf(RuleReview.Finding.Redundant(index = 1, coveredBy = 0)), findings)
    }

    @Test
    fun `an identical duplicate keeps the rule the parent wrote first`() {
        val findings = RuleReview.review(listOf(w("17:00", "19:00"), w("17:00", "19:00")))
        assertEquals(listOf(RuleReview.Finding.Redundant(index = 1, coveredBy = 0)), findings)
    }

    @Test
    fun `an always rule covers a matching one that stands down on special days`() {
        val findings = RuleReview.review(
            listOf(w("17:00", "19:00"), w("17:00", "19:00", special = SpecialDays.NEVER)),
        )
        assertEquals(listOf(RuleReview.Finding.Redundant(index = 1, coveredBy = 0)), findings)
    }

    @Test
    fun `rules for different special-day states never cover each other`() {
        // "Not on special days" and "only on special days" are disjoint. Neither is redundant,
        // and neither merges: they are two genuinely different rules.
        val findings = RuleReview.review(
            listOf(
                w("17:00", "19:00", special = SpecialDays.NEVER),
                w("17:00", "19:00", special = SpecialDays.ONLY),
            ),
        )
        assertTrue(findings.isEmpty(), "expected silence, got $findings")
    }

    @Test
    fun `an only-on-special rule is not covered by an ordinary rule of the same hours`() {
        // The ordinary rule here stands down on special days, which is exactly when the other
        // one fires — so deleting either changes what the child can open.
        val findings = RuleReview.review(
            listOf(
                w("08:00", "22:00", special = SpecialDays.NEVER),
                w("17:00", "19:00", special = SpecialDays.ONLY),
            ),
        )
        assertTrue(findings.isEmpty(), "expected silence, got $findings")
    }

    // --- Rules that are one rule written twice ---

    @Test
    fun `the same hours on weekdays and at the weekend are one rule for the whole week`() {
        // The shape the old weekday/weekend sections forced a parent to write.
        val findings = RuleReview.review(
            listOf(w("17:00", "19:00", days = weekdays), w("17:00", "19:00", days = weekend)),
        )
        val merge = findings.single() as RuleReview.Finding.Mergeable
        assertEquals(listOf(0, 1), merge.indices)
        assertEquals(weekdays + weekend, merge.merged.days)
        assertEquals(LocalTime.of(17, 0), merge.merged.start)
        assertEquals(LocalTime.of(19, 0), merge.merged.end)
    }

    @Test
    fun `two touching stretches of the same day become one`() {
        val findings = RuleReview.review(
            listOf(w("17:00", "18:00", days = weekdays), w("18:00", "19:00", days = weekdays)),
        )
        val merge = findings.single() as RuleReview.Finding.Mergeable
        assertEquals(LocalTime.of(17, 0), merge.merged.start)
        assertEquals(LocalTime.of(19, 0), merge.merged.end)
    }

    @Test
    fun `three rules that chain together merge into one`() {
        val findings = RuleReview.review(
            listOf(w("17:00", "18:00"), w("18:00", "19:00"), w("19:00", "20:00")),
        )
        val merge = findings.single() as RuleReview.Finding.Mergeable
        assertEquals(listOf(0, 1, 2), merge.indices)
        assertEquals(LocalTime.of(20, 0), merge.merged.end)
    }

    @Test
    fun `a gap between two rules is left alone`() {
        // Merging these would block the hour between them, which nobody asked for.
        val findings = RuleReview.review(
            listOf(w("17:00", "18:00", days = weekdays), w("19:00", "20:00", days = weekdays)),
        )
        assertTrue(findings.isEmpty(), "expected silence, got $findings")
    }

    @Test
    fun `the same hours on different days do not merge across special-day states`() {
        val findings = RuleReview.review(
            listOf(
                w("17:00", "19:00", days = weekdays, special = SpecialDays.NEVER),
                w("17:00", "19:00", days = weekend, special = SpecialDays.ONLY),
            ),
        )
        assertTrue(findings.isEmpty(), "expected silence, got $findings")
    }

    // --- Midnight, where an off-by-one becomes a rule that fires at the wrong time ---

    @Test
    fun `a window crossing midnight covers a matching one inside its tail`() {
        val findings = RuleReview.review(
            listOf(
                w("22:00", "07:00", days = setOf(DayOfWeek.FRIDAY)),
                // Saturday 01:00–02:00 is inside Friday night's tail.
                w("01:00", "02:00", days = setOf(DayOfWeek.SATURDAY)),
            ),
        )
        assertEquals(listOf(RuleReview.Finding.Redundant(index = 1, coveredBy = 0)), findings)
    }

    @Test
    fun `a window crossing midnight does not cover the same clock hours on its own day`() {
        // Friday 22:00–07:00 blocks Saturday's small hours, NOT Friday's. A comparison done on
        // clock times alone would call the second rule redundant and delete a real one.
        val findings = RuleReview.review(
            listOf(
                w("22:00", "07:00", days = setOf(DayOfWeek.FRIDAY)),
                w("01:00", "02:00", days = setOf(DayOfWeek.FRIDAY)),
            ),
        )
        assertTrue(findings.isEmpty(), "expected silence, got $findings")
    }

    @Test
    fun `the week wraps, so Sunday night covers Monday morning`() {
        val findings = RuleReview.review(
            listOf(
                w("23:00", "06:00", days = setOf(DayOfWeek.SUNDAY)),
                w("01:00", "02:00", days = setOf(DayOfWeek.MONDAY)),
            ),
        )
        assertEquals(listOf(RuleReview.Finding.Redundant(index = 1, coveredBy = 0)), findings)
    }

    @Test
    fun `two windows that both cross midnight are not guessed at`() {
        // Their union may be describable by no single window; the engine declines rather than
        // proposing something it would then have to reject.
        val findings = RuleReview.review(listOf(w("22:00", "02:00"), w("23:00", "03:00")))
        assertTrue(findings.none { it is RuleReview.Finding.Mergeable }, "expected no merge, got $findings")
    }

    // --- The healthy case, which has to stay quiet ---

    @Test
    fun `a schedule with nothing wrong produces nothing`() {
        val findings = RuleReview.review(
            listOf(
                w("13:00", "14:00", days = weekdays),
                w("17:00", "19:00", days = weekdays),
                w("22:00", "07:00"),
            ),
        )
        assertTrue(findings.isEmpty(), "expected silence, got $findings")
    }

    @Test
    fun `an empty schedule produces nothing`() {
        assertTrue(RuleReview.review(emptyList()).isEmpty())
    }

    @Test
    fun `a single rule is never reported against itself`() {
        assertTrue(RuleReview.review(listOf(w("17:00", "19:00"))).isEmpty())
    }

    // --- The guarantee itself ---

    @Test
    fun `every proposed merge blocks exactly what its parts blocked`() {
        // The engine verifies this internally; this pins that the verification is load-bearing
        // by checking the property from the outside, through the engine everything else uses.
        val schedule = listOf(
            w("17:00", "18:00", days = weekdays),
            w("18:00", "19:00", days = weekdays),
            w("17:00", "19:00", days = weekend),
        )
        val merge = RuleReview.review(schedule).filterIsInstance<RuleReview.Finding.Mergeable>().single()
        val before = schedule.filterIndexed { i, _ -> i in merge.indices }
        assertEquals(
            before.flatMap { minutesOf(it) }.toSet(),
            minutesOf(merge.merged),
            "the merged rule must block exactly the minutes its parts blocked",
        )
    }

    /**
     * Independent re-implementation of coverage, so this test is not just the engine agreeing
     * with itself. Deliberately the other way round: walk forward from the start for as many
     * minutes as the window lasts, wrapping the week, rather than splitting it into two ranges
     * at midnight the way the engine does.
     */
    private fun minutesOf(window: TimeWindow): Set<Int> {
        val week = 7 * 24 * 60
        val start = window.start.hour * 60 + window.start.minute
        val end = window.end.hour * 60 + window.end.minute
        val length = when {
            start == end -> 0
            start < end -> end - start
            else -> (24 * 60 - start) + end
        }
        val days = window.days.ifEmpty { DayOfWeek.entries.toSet() }
        return days.flatMapTo(mutableSetOf()) { day ->
            val from = (day.value - 1) * 24 * 60 + start
            (0 until length).map { (from + it) % week }
        }
    }
}
