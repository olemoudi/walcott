package dev.walcott.sim

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the parent's map is actually drawn from.
 *
 * The map replays where a phone has been, and every pixel of that comes off the wire: a trail of
 * fixes, oldest first, plus a count of how many the device really holds. Neither can be produced
 * without a device that has been in more than one place, which is why none of it had a scenario —
 * the pure half (thinning a trail to fit one message) is well covered, and the half between the
 * recorder and the wire was not covered at all. That is the same stretch where six releases of
 * screen time went missing.
 *
 * Two claims, and the second is the one a family notices: with history on the phone sends where
 * it has BEEN, and with history off it sends only where it IS. Off is the privacy promise on the
 * setting's own switch, so a device that kept publishing a trail after it was turned off would be
 * breaking a promise rather than merely reporting oddly.
 */
class LocationTrailScenarioTest : DeviceScenario() {

    @Test
    fun `with history on the phone reports where it has been, and says how much it is holding back`() {
        // Waited on the SNAPSHOT's version, not the policy JSON's: both exist, and comparing
        // against the wrong one waits for a number that is never coming.
        val on = parent.pushPolicy(policy(version = 2, history = true))
        childEventuallyReports { it.appliedPolicyVersion >= on.version }

        // Two places, each pinned by a locate rather than waited out: the periodic sampler runs
        // on its own alarm, and a scenario that idled for one would be a scenario about Doze.
        val first = fixAt(latitude = 40.4168, longitude = -3.7038)
        val second = fixAt(latitude = 40.4200, longitude = -3.7100)
        assertTrue(second > first, "the second locate should be a newer request than the first")

        val reported = childEventuallyReports { it.locations.size >= 2 }
        val trail = reported.locations
        assertTrue(trail.size >= 2, "history is on and only ${trail.size} fix(es) travelled")
        assertEquals(
            trail.sortedBy { it.epochMs },
            trail,
            "the trail must arrive oldest first — the scrubber reads it as time, and it would replay backwards",
        )
        // What the map says out loud as "120 of 613". It may exceed what travelled (the trail is
        // thinned to fit one message) but it can never be less, or the parent is shown a sample
        // presented as the whole of it.
        assertTrue(
            reported.locationsTotal >= trail.size,
            "reported total ${reported.locationsTotal} is smaller than the ${trail.size} fixes it sent",
        )
        assertTrue(
            trail.any { kotlin.math.abs(it.lat - 40.4200) < 0.01 },
            "the newest position never arrived: ${trail.map { it.lat }}",
        )
    }

    @Test
    fun `with history off only the current position travels`() {
        // The switch's promise, checked on the wire. Turned OFF after a trail exists, because
        // that is the case that can go wrong: a device with nothing recorded would pass this
        // whatever it did.
        val on = parent.pushPolicy(policy(version = 2, history = true))
        childEventuallyReports { it.appliedPolicyVersion >= on.version }
        fixAt(latitude = 40.4168, longitude = -3.7038)
        fixAt(latitude = 40.4200, longitude = -3.7100)
        childEventuallyReports { it.locations.size >= 2 }

        val off = parent.pushPolicy(policy(version = 3, history = false))
        childEventuallyReports { it.appliedPolicyVersion >= off.version }
        val now = fixAt(latitude = 40.4250, longitude = -3.7150)

        val reported = childEventuallyReports { it.answeredLocationRequestMs >= now && it.locations.size == 1 }
        assertEquals(
            1,
            reported.locations.size,
            "history is off and the phone still sent a trail of ${reported.locations.size}",
        )
        assertEquals(
            40.4250,
            reported.locations.single().lat,
            0.01,
            "the one fix that travels should be the current one",
        )
    }

    /**
     * Puts the device somewhere and makes it take a fix there, answering the request's stamp.
     *
     * A locate is the only way to make a phone record a position on demand — the alternative is
     * the periodic sampler, whose shortest interval is five minutes of real time.
     */
    private fun fixAt(latitude: Double, longitude: Double): Long {
        device.setLocation(latitude, longitude)
        val request = parent.requestLocation(deviceId).locationRequests.first().requestedAtMs
        parent.awaitChild(timeoutMs = 45_000) { it.answeredLocationRequestMs >= request }
        return request
    }

    /** Tracking off, history as asked: the trail must come from the locates, not from an alarm. */
    private fun policy(version: Long, history: Boolean): String = PolicyJson.build(
        version = version,
        extra = mapOf(
            "locationHistoryEnabled" to JsonPrimitive(history),
            "trackingIntervalMinutes" to JsonPrimitive(0),
        ),
    )
}
