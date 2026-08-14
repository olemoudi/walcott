package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a child's unanswered request stops counting as live.
 *
 * The rule exists because the child's home refuses to send a second request for something that
 * already has one pending: without an expiry, a request nobody ever answered left that app's
 * button permanently dead, and pinned a week-old question above everything current on the
 * parent's home.
 */
class RequestStateTest {

    private val now = 1_700_000_000_000L
    private val fresh = mapOf("r1" to now - 60_000)
    private val old = mapOf("r1" to now - SyncEngine.REQUEST_TTL_MS - 1)

    @Test
    fun `a request still waiting reads as pending`() {
        assertEquals(
            SyncEngine.RequestState.PENDING,
            SyncEngine.requestState("r1", emptySet(), fresh, now),
        )
    }

    @Test
    fun `an answered request says so, however long ago it was answered`() {
        // The ordering that matters: an answered request keeps ageing like any other, so
        // checking expiry first would tell a parent nobody replied to a child they replied to
        // three days ago. That is the exact confusion the message exists to end.
        assertEquals(
            SyncEngine.RequestState.ANSWERED,
            SyncEngine.requestState("r1", setOf("r1"), old, now),
        )
        assertEquals(
            SyncEngine.RequestState.ANSWERED,
            SyncEngine.requestState("r1", setOf("r1"), fresh, now),
        )
        // Answered by the OTHER parent, so this phone never had it pending at all.
        assertEquals(
            SyncEngine.RequestState.ANSWERED,
            SyncEngine.requestState("r1", setOf("r1"), emptyMap(), now),
        )
    }

    @Test
    fun `a request nobody answered in time reads as expired, not answered`() {
        assertEquals(
            SyncEngine.RequestState.EXPIRED,
            SyncEngine.requestState("r1", emptySet(), old, now),
        )
    }

    @Test
    fun `a request this phone has never heard of is unknown`() {
        assertEquals(
            SyncEngine.RequestState.UNKNOWN,
            SyncEngine.requestState("r1", emptySet(), emptyMap(), now),
        )
        // Another family's resolutions must not answer for this one.
        assertEquals(
            SyncEngine.RequestState.UNKNOWN,
            SyncEngine.requestState("r1", setOf("r2"), mapOf("r2" to now), now),
        )
    }
}

class RequestExpiryTest {

    private val now = 1_700_000_000_000L
    private val ttl = SyncEngine.REQUEST_TTL_MS

    @Test
    fun `a fresh request is live`() {
        assertFalse(SyncEngine.requestExpired(createdAtEpochMs = now, nowMs = now))
        assertFalse(SyncEngine.requestExpired(createdAtEpochMs = now - ttl + 1, nowMs = now))
    }

    @Test
    fun `a request still counts on its last minute`() {
        // The boundary belongs to the child: exactly at the limit it is still answerable.
        assertFalse(SyncEngine.requestExpired(createdAtEpochMs = now - ttl, nowMs = now))
        assertTrue(SyncEngine.requestExpired(createdAtEpochMs = now - ttl - 1, nowMs = now))
    }

    @Test
    fun `two days is long enough to survive a weekend away`() {
        // The number itself matters: a parent on a Friday-to-Sunday trip has not said no.
        assertTrue(ttl >= 48 * 60 * 60 * 1000L)
        assertFalse(SyncEngine.requestExpired(createdAtEpochMs = now - 47 * 60 * 60 * 1000L, nowMs = now))
    }

    @Test
    fun `a request with no timestamp never expires`() {
        // Legacy children send 0. Measured against it every request is impossibly old, so the
        // whole list would be retired on sight — "I can't tell" must not read as "ancient".
        assertFalse(SyncEngine.requestExpired(createdAtEpochMs = 0, nowMs = now))
    }

    @Test
    fun `a clock that jumped backwards doesn't retire anything`() {
        // A child whose clock is behind the one that stamped the request: negative age, still live.
        assertFalse(SyncEngine.requestExpired(createdAtEpochMs = now + ttl, nowMs = now))
    }
}
