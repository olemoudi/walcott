package dev.walcott.ui.format

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The compact duration every screen prints, in whatever units the language uses (see
 * [DurationUnits]): "1h 20m" in English, "1h 20min" in Spanish, never the same string for both.
 */
class HumanizeTest {

    @AfterEach
    fun english() {
        DurationUnits.hour = "h"
        DurationUnits.minute = "m"
        DurationUnits.second = "s"
    }

    @Test
    fun `hours, minutes and seconds in the default units`() {
        assertEquals("1h 20m", Duration.ofMinutes(80).humanize())
        assertEquals("2h", Duration.ofHours(2).humanize())
        assertEquals("20m", Duration.ofMinutes(20).humanize())
        assertEquals("45s", Duration.ofSeconds(45).humanize())
        assertEquals("0s", Duration.ofSeconds(-5).humanize())
    }

    @Test
    fun `the units follow the language`() {
        DurationUnits.minute = "min"
        assertEquals("1h 20min", Duration.ofMinutes(80).humanize())
        assertEquals("20min", Duration.ofMinutes(20).humanize())
    }
}
