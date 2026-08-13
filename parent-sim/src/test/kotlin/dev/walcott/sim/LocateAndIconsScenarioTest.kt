package dev.walcott.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two things a parent asks a child to go and fetch: where it is, and what its apps look
 * like. Both are request/answer round trips with an "already answered" marker, and both send
 * their answer as data the parent could not produce for itself.
 */
class LocateAndIconsScenarioTest : DeviceScenario() {

    @Test
    fun `a locate now is answered, and the answer is marked so it is not answered twice`() {
        // The marker is the whole protocol here: the request rides in every re-emitted snapshot,
        // so without it a single "where are they?" would wake the GPS every fifteen minutes for
        // as long as the parent's snapshot carried it.
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        val request = parent.requestLocation(deviceId)
        val answered = parent.awaitChild { it.answeredLocationRequestMs >= request.locationRequests.first().requestedAtMs }
        assertEquals(
            request.locationRequests.first().requestedAtMs,
            answered.answeredLocationRequestMs,
            "the child should mark exactly the request it answered",
        )

        val marker = answered.answeredLocationRequestMs
        repeat(2) { parent.reEmit() }
        Thread.sleep(5_000)
        assertEquals(
            marker,
            childReports { true }.answeredLocationRequestMs,
            "a re-emitted request was answered all over again",
        )
    }

    @Test
    fun `a second, newer request is answered again`() {
        // The other half of the same rule: "answered once" must mean that request, not that
        // parent. A later ask is a new question.
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        val first = parent.requestLocation(deviceId).locationRequests.first().requestedAtMs
        parent.awaitChild { it.answeredLocationRequestMs >= first }

        Thread.sleep(1_100)
        val second = parent.requestLocation(deviceId).locationRequests.first().requestedAtMs
        assertTrue(second > first, "the sim should be issuing a newer request")
        val answered = parent.awaitChild { it.answeredLocationRequestMs >= second }
        assertEquals(second, answered.answeredLocationRequestMs)
    }

    @Test
    fun `the answer carries an actual position`() {
        // The marker says the child answered; this says it answered with something. A device
        // that marked every request answered and never reported a point would look perfectly
        // healthy on the parent's screen and be useless the one time it mattered.
        device.setLocation(latitude = 40.4168, longitude = -3.7038)
        val request = parent.requestLocation(deviceId)
        val answered = parent.awaitChild { snapshot ->
            snapshot.answeredLocationRequestMs >= request.locationRequests.first().requestedAtMs &&
                snapshot.locations.isNotEmpty()
        }
        val fix = answered.locations.last()
        assertEquals(40.4168, fix.lat, 0.01, "latitude came back as ${fix.lat}")
        assertEquals(-3.7038, fix.lng, 0.01, "longitude came back as ${fix.lng}")
    }

    @Test
    fun `a locate now aimed at another device is not answered here`() {
        val before = childReports { true }.answeredLocationRequestMs
        parent.requestLocation("some-other-device")
        Thread.sleep(6_000)
        assertEquals(
            before,
            childReports { true }.answeredLocationRequestMs,
            "this device answered a locate meant for a sibling",
        )
    }

    @Test
    fun `the parent asks for an app icon and the child renders and sends it`() {
        // Icons travel as their own message kind so the log tail and the app list never share a
        // size budget. Nothing but a device can produce one: it is the actual drawable, rendered.
        val target = installFixtureApp()
        try {
            childReports { snapshot -> snapshot.apps.any { it.packageName == target } }
            parent.requestIcons(listOf(target))
            val payload = parent.awaitIcons()
            assertEquals(deviceId, payload.deviceId)
            val icon = payload.icons.single { it.packageName == target }
            assertTrue(icon.webpB64.isNotBlank(), "an icon with no bytes is not an icon")
        } finally {
            device.ensureRemoved(target)
        }
    }

    @Test
    fun `the child answers only for apps it actually has`() {
        val target = installFixtureApp()
        try {
            childReports { snapshot -> snapshot.apps.any { it.packageName == target } }
            parent.requestIcons(listOf(target, "com.not.installed.anywhere"))
            val payload = parent.awaitIcons()
            assertTrue(
                payload.icons.none { it.packageName == "com.not.installed.anywhere" },
                "the child invented an icon for an app it does not have",
            )
        } finally {
            device.ensureRemoved(target)
        }
    }
}
