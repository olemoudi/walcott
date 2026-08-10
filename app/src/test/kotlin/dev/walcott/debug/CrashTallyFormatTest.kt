package dev.walcott.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrashTallyFormatTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a tally survives a round trip`() {
        val tally = CrashTally(3, now)
        assertEquals(tally, CrashTallyFormat.parse(CrashTallyFormat.format(tally)))
    }

    @Test
    fun `a crash adds one and moves the timestamp`() {
        assertEquals(CrashTally(1, now), CrashTallyFormat.plusCrash(CrashTally.NONE, now))
        assertEquals(CrashTally(4, now), CrashTallyFormat.plusCrash(CrashTally(3, now - 1000), now))
    }

    @Test
    fun `anything unreadable counts as no crashes, never as some`() {
        // A corrupt counter must not be able to invent crashes: the parent is alerted on growth,
        // so a garbage read that produced a number would raise an alarm about nothing.
        listOf(null, "", "   ", "nonsense", "3", "3 4 5", "x 1", "3 x", "-1 5", "3 -5").forEach {
            assertEquals(CrashTally.NONE, CrashTallyFormat.parse(it), "input: <$it>")
        }
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(CrashTally(2, 7), CrashTallyFormat.parse(" 2 7\n"))
    }
}
