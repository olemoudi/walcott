package dev.walcott.ui

import dev.walcott.ui.parent.nextMorning
import dev.walcott.ui.parent.nextOccurrenceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "Pause until nine" — which nine.
 *
 * The arithmetic behind a pause the parent gives an hour for rather than a length. Getting it
 * wrong is not a rounding error: an hour read as tomorrow's is a phone shut for a day.
 */
class PauseUntilTest {

    private val evening = LocalDateTime.of(2026, 3, 2, 20, 0)

    @Test
    fun `an hour still ahead is tonight`() {
        assertEquals(
            LocalDateTime.of(2026, 3, 2, 21, 0),
            nextOccurrenceOf(evening, LocalTime.of(21, 0)),
        )
    }

    @Test
    fun `an hour already gone is tomorrow`() {
        // "Until 09:00" typed at eight in the evening can only mean the morning, and a pause
        // dated to this morning would be over before it began.
        assertEquals(
            LocalDateTime.of(2026, 3, 3, 9, 0),
            nextOccurrenceOf(evening, LocalTime.of(9, 0)),
        )
    }

    @Test
    fun `this exact minute counts as gone, so a pause is never zero long`() {
        assertEquals(
            LocalDateTime.of(2026, 3, 3, 20, 0),
            nextOccurrenceOf(evening, LocalTime.of(20, 0)),
        )
    }

    @Test
    fun `an open-ended pause ends at the next morning, whichever side of midnight it started`() {
        assertEquals(LocalDateTime.of(2026, 3, 3, 6, 0), nextMorning(evening, 6))
        // Started at two in the morning, it ends at six that same morning rather than running
        // for another whole day.
        val smallHours = LocalDateTime.of(2026, 3, 3, 2, 0)
        assertEquals(LocalDateTime.of(2026, 3, 3, 6, 0), nextMorning(smallHours, 6))
    }
}
