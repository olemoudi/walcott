package dev.walcott.sync

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire half of finding a phone: the ring that must not land late, the lost mode that must
 * land however late, and the last word a phone leaves before going quiet.
 */
class FindPhoneProtocolTest {

    private val issued = 1_720_000_000_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a ring is honoured while it is fresh and refused once it is stale`() {
        assertFalse(RemoteAction.expired(RemoteAction.RING_NOW, issued, issued + 60_000))
        assertFalse(RemoteAction.expired(RemoteAction.RING_NOW, issued, issued + RemoteAction.RING_TTL_MS))
        // A ring that arrives tomorrow is a phone going off in a classroom.
        assertTrue(RemoteAction.expired(RemoteAction.RING_NOW, issued, issued + RemoteAction.RING_TTL_MS + 1))
    }

    @Test
    fun `lost mode never expires in either direction`() {
        val fortnight = 14 * 24 * 60 * 60 * 1000L
        assertFalse(RemoteAction.expired(RemoteAction.LOST_MODE, issued, issued + fortnight))
        assertFalse(RemoteAction.expired(RemoteAction.LOST_MODE, issued, issued + SyncEngine.COMMAND_TTL_MS))
    }

    @Test
    fun `the seconds a ring asks for are bounded, and a blank asks for the default`() {
        assertEquals(RemoteAction.RING_DEFAULT_SECONDS, RemoteAction.ringSeconds(""))
        assertEquals(RemoteAction.RING_DEFAULT_SECONDS, RemoteAction.ringSeconds("   "))
        assertEquals(20, RemoteAction.ringSeconds("20"))
        assertEquals(20, RemoteAction.ringSeconds(" 20 "))
        assertEquals(RemoteAction.RING_MAX_SECONDS, RemoteAction.ringSeconds("9999"))
        assertEquals(RemoteAction.RING_MIN_SECONDS, RemoteAction.ringSeconds("1"))
        assertEquals(RemoteAction.RING_MIN_SECONDS, RemoteAction.ringSeconds("-5"))
        assertNull(RemoteAction.ringSeconds("soon"))
        assertTrue(RemoteAction.RING_MIN_SECONDS < RemoteAction.RING_DEFAULT_SECONDS)
        assertTrue(RemoteAction.RING_DEFAULT_SECONDS < RemoteAction.RING_MAX_SECONDS)
    }

    @Test
    fun `a child too old to ring or be lost is never offered either`() {
        assertFalse(RemoteAction.canFind(0))
        assertFalse(RemoteAction.canFind(RemoteAction.FIND_MIN_CHILD_VERSION - 1))
        assertTrue(RemoteAction.canFind(RemoteAction.FIND_MIN_CHILD_VERSION))
        assertTrue(RemoteAction.canFind(RemoteAction.FIND_MIN_CHILD_VERSION + 40))
    }

    @Test
    fun `the last word travels with its position and survives the round trip`() {
        val gasp = LastGasp(
            kind = LastGasp.KIND_BATTERY,
            atMs = issued,
            fix = LocationPoint(lat = 40.4168, lng = -3.7038, epochMs = issued - 5_000, accuracyM = 12f),
            batteryPercent = 14,
        )
        val snapshot = ChildSnapshot(deviceId = "d1", displayName = "Sim", version = 3, epochDay = 19_900, lostMode = true, lastGasp = gasp)
        val decoded = json.decodeFromString(ChildSnapshot.serializer(), json.encodeToString(ChildSnapshot.serializer(), snapshot))
        assertEquals(gasp, decoded.lastGasp)
        assertTrue(decoded.lostMode)
    }

    @Test
    fun `a snapshot from a child that knows neither field reads as a phone that is fine`() {
        // Forward compatibility in the usual direction: an older child never says either, and
        // the parent must read that as "not lost, nothing to report", not as an error.
        val legacy = """{"deviceId":"d1","displayName":"Sim","version":1,"epochDay":19900}"""
        val decoded = json.decodeFromString(ChildSnapshot.serializer(), legacy)
        assertFalse(decoded.lostMode)
        assertNull(decoded.lastGasp)
    }

    @Test
    fun `a last word with no position is still a last word`() {
        val gasp = LastGasp(kind = LastGasp.KIND_SHUTDOWN, atMs = issued)
        val decoded = json.decodeFromString(LastGasp.serializer(), json.encodeToString(LastGasp.serializer(), gasp))
        assertEquals(LastGasp.KIND_SHUTDOWN, decoded.kind)
        assertNull(decoded.fix)
        assertEquals(-1, decoded.batteryPercent)
    }

    @Test
    fun `a stop lives exactly as long as the ring it is meant to cancel`() {
        // Not longer, and above all not shorter. A phone that was off for ten minutes takes the
        // ring when it comes back — the ring is still inside its own TTL — and if the stop had
        // expired first it would go off in somebody's bag with the parent's only way of ending
        // it already thrown away.
        val issued = 1_000_000L
        assertFalse(RemoteAction.expired(RemoteAction.RING_STOP, issued, issued + RemoteAction.RING_TTL_MS))
        assertTrue(RemoteAction.expired(RemoteAction.RING_STOP, issued, issued + RemoteAction.RING_TTL_MS + 1))
    }

    @Test
    fun `a ring is timed from when its message arrived, not from the other phone's clock`() {
        val arrived = 5_000_000L
        assertEquals(arrived + 60_000L, RemoteAction.ringEndsAt(60, arrived))
        // Not ringing, and never heard from, are both "no button".
        assertNull(RemoteAction.ringEndsAt(0, arrived))
        assertNull(RemoteAction.ringEndsAt(-5, arrived))
        assertNull(RemoteAction.ringEndsAt(60, null))
    }

    @Test
    fun `a nonsense remainder cannot pin the button to a parent's screen`() {
        // The card this drives offers to stop a noise. A child reporting a day's worth of ring —
        // a bug, a build nobody has seen — must not be able to leave that offer standing for a
        // day; the longest ring there is, is the longest this can claim.
        val arrived = 5_000_000L
        assertEquals(
            arrived + RemoteAction.RING_MAX_SECONDS * 1000L,
            RemoteAction.ringEndsAt(86_400, arrived),
        )
    }
}
