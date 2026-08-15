package dev.walcott.ui.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Relative ages against a clock that ticks once a minute.
 *
 * The bug this pins was visible on the parent's wall: approve a request and the line about it
 * appeared reading "In 0 minutes". Nothing was wrong with the event — the screen's clock was up
 * to a minute old, so something that had just happened was in its future, and `DateUtils` will
 * cheerfully render that in the future tense.
 */
class AgeReferenceTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `an event older than the tick ages against the tick`() {
        assertEquals(now, ageReference(atMs = now - 60_000, nowMs = now))
    }

    @Test
    fun `an event newer than the tick ages against itself`() {
        // Without this it would be rendered in the future for up to a whole tick.
        val justHappened = now + 30_000
        assertEquals(justHappened, ageReference(atMs = justHappened, nowMs = now))
    }

    @Test
    fun `the two agree at the boundary`() {
        assertEquals(now, ageReference(atMs = now, nowMs = now))
    }
}
