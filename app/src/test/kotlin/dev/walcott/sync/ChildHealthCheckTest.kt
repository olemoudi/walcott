package dev.walcott.sync

import dev.walcott.setup.DeviceRequirement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChildHealthCheckTest {

    private val now = 1_700_000_000_000L
    private val usage = DeviceRequirement.USAGE_ACCESS.key
    private val battery = DeviceRequirement.BATTERY_OPTIMIZATION.key

    @Test
    fun `a problem never reported is due at once`() {
        assertEquals(listOf(usage), ChildHealthCheck.due(setOf(usage), emptyMap(), now))
    }

    @Test
    fun `a problem just reported stays quiet until the window passes`() {
        val stamped = mapOf(usage to now)
        assertTrue(ChildHealthCheck.due(setOf(usage), stamped, now + 60_000).isEmpty())
        assertTrue(
            ChildHealthCheck.due(setOf(usage), stamped, now + ChildHealthCheck.REPEAT_AFTER_MS - 1).isEmpty(),
        )
        assertEquals(
            listOf(usage),
            ChildHealthCheck.due(setOf(usage), stamped, now + ChildHealthCheck.REPEAT_AFTER_MS),
        )
    }

    @Test
    fun `a stamp in the future is due, not silenced forever`() {
        // The child moving the clock backwards must not be a way to mute the nudge.
        val stamped = mapOf(usage to now + 30 * 24 * 60 * 60 * 1000L)
        assertEquals(listOf(usage), ChildHealthCheck.due(setOf(usage), stamped, now))
    }

    @Test
    fun `nothing broken means nothing due`() {
        assertTrue(ChildHealthCheck.due(emptySet(), mapOf(usage to now), now).isEmpty())
    }

    @Test
    fun `a setting that recovered loses its stamp, so a relapse reports at once`() {
        val previous = mapOf(usage to now, battery to now)
        // Usage access came back; battery optimisation is still wrong and was not re-notified.
        val next = ChildHealthCheck.nextNotifiedAt(previous, broken = setOf(battery), notifiedNow = emptyList(), nowMs = now)
        assertEquals(mapOf(battery to now), next)
        // And with the stamp gone, the same problem returning is due immediately.
        assertEquals(listOf(usage), ChildHealthCheck.due(setOf(usage), next, now + 1))
    }

    @Test
    fun `what was notified now is stamped now`() {
        val next = ChildHealthCheck.nextNotifiedAt(
            previous = emptyMap(),
            broken = setOf(usage, battery),
            notifiedNow = listOf(usage, battery),
            nowMs = now,
        )
        assertEquals(mapOf(usage to now, battery to now), next)
    }

    @Test
    fun `a standing problem is re-stamped, not left on its first report`() {
        val previous = mapOf(usage to now)
        val later = now + ChildHealthCheck.REPEAT_AFTER_MS
        val due = ChildHealthCheck.due(setOf(usage), previous, later)
        val next = ChildHealthCheck.nextNotifiedAt(previous, setOf(usage), due, later)
        assertEquals(mapOf(usage to later), next)
    }
}
