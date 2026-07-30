package dev.walcott.net

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The monitor's whole job is what it refuses to keep: nothing before a session, nothing after
 * one, and never more than it said it would. Those are also the only promises made to a family
 * about a log of the domains their child's apps resolve, so they are asserted rather than
 * commented.
 */
class DomainMonitorTest {

    private val t0 = 1_700_000_000_000L

    @BeforeEach
    @AfterEach
    fun leaveNothingBehind() = DomainMonitor.stop()

    @Test
    fun `nothing is recorded before a session starts`() {
        DomainMonitor.record("ads.example.com", "com.game", t0)
        assertTrue(DomainMonitor.state.value.sightings.isEmpty())
        assertFalse(DomainMonitor.isActive(t0))
    }

    @Test
    fun `a session records what an app looked up, with its app`() {
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record("Ads.Example.com.", "com.game", t0 + 1)
        val sighting = DomainMonitor.state.value.sightings.single()
        // Normalised on the way in, so the parent doesn't get two rows for one domain.
        assertEquals("ads.example.com", sighting.domain)
        assertEquals("com.game", sighting.packageName)
        assertEquals(1, sighting.count)
    }

    @Test
    fun `the same app asking again counts up instead of piling up`() {
        DomainMonitor.start(t0, durationMs = 60_000)
        repeat(5) { DomainMonitor.record("ads.example.com", "com.game", t0 + it) }
        val sighting = DomainMonitor.state.value.sightings.single()
        assertEquals(5, sighting.count)
        assertEquals(t0 + 4, sighting.lastSeenMs)
    }

    @Test
    fun `two apps asking for the same domain stay two rows`() {
        // The point of the feature: which app is talking to whom. Merging them would erase it.
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record("ads.example.com", "com.game", t0 + 1)
        DomainMonitor.record("ads.example.com", "com.chat", t0 + 2)
        assertEquals(2, DomainMonitor.state.value.sightings.size)
    }

    @Test
    fun `a session that has run out records nothing and drops what it saw`() {
        DomainMonitor.start(t0, durationMs = 1_000)
        DomainMonitor.record("ads.example.com", "com.game", t0 + 500)
        assertEquals(1, DomainMonitor.state.value.sightings.size)

        // One lookup after the deadline is what clears it, so an expired session leaves nothing
        // behind even if the screen that would have cleared it is never opened again.
        DomainMonitor.record("later.example.com", "com.game", t0 + 5_000)
        assertTrue(DomainMonitor.state.value.sightings.isEmpty())
        assertEquals(0, DomainMonitor.state.value.activeUntilMs)
        assertFalse(DomainMonitor.isActive(t0 + 5_000))
    }

    @Test
    fun `stopping forgets everything immediately`() {
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record("ads.example.com", "com.game", t0 + 1)
        DomainMonitor.stop()
        assertTrue(DomainMonitor.state.value.sightings.isEmpty())
        assertFalse(DomainMonitor.isActive(t0 + 2))
    }

    @Test
    fun `starting again discards the previous session`() {
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record("old.example.com", "com.game", t0 + 1)
        DomainMonitor.start(t0 + 10, durationMs = 60_000)
        assertTrue(DomainMonitor.state.value.sightings.isEmpty())
    }

    @Test
    fun `a chatty app cannot grow the record without limit`() {
        DomainMonitor.start(t0, durationMs = 10 * 60_000)
        repeat(DomainMonitor.MAX_SIGHTINGS + 50) { DomainMonitor.record("host$it.example.com", "com.game", t0 + it) }
        val sightings = DomainMonitor.state.value.sightings
        assertEquals(DomainMonitor.MAX_SIGHTINGS, sightings.size)
        // The oldest go first, so what the parent is looking at right now is what survives.
        assertTrue(sightings.none { it.domain == "host0.example.com" })
        assertTrue(sightings.any { it.domain == "host${DomainMonitor.MAX_SIGHTINGS + 49}.example.com" })
    }

    @Test
    fun `apps are ordered by when they were last heard from`() {
        // The flow this exists for: start, switch to the app, use it, come back. The app just
        // used has to be at the top, not whichever one happened to speak first.
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record("a.example.com", "com.background", t0 + 1)
        DomainMonitor.record("b.example.com", "com.target", t0 + 100)
        DomainMonitor.record("c.example.com", "com.target", t0 + 200)
        val groups = DomainMonitor.state.value.byApp()
        assertEquals(listOf("com.target", "com.background"), groups.map { it.first })
        assertEquals(listOf("c.example.com", "b.example.com"), groups.first().second.map { it.domain })
    }

    @Test
    fun `a lookup the tunnel could not attribute is still shown, not dropped`() {
        // Better an "unknown app" row the parent can judge than a silently missing domain.
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record("mystery.example.com", null, t0 + 1)
        assertEquals(null, DomainMonitor.state.value.sightings.single().packageName)
    }

    @Test
    fun `an empty host is not a sighting`() {
        DomainMonitor.start(t0, durationMs = 60_000)
        DomainMonitor.record(".", "com.game", t0 + 1)
        DomainMonitor.record("", "com.game", t0 + 2)
        assertTrue(DomainMonitor.state.value.sightings.isEmpty())
    }
}
