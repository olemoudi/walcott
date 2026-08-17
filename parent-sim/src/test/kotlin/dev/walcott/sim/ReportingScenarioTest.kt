package dev.walcott.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a child tells its parent about itself.
 *
 * Everything on a parent's screen comes from here, and every field is produced by the device
 * rather than by any logic a unit test can reach: the app list, the screen time, the battery,
 * the enforcement backend, whether the OS is really suspending what the rules block.
 *
 * The screen-time case is the one with a history. Between 0.35 and 0.41 the usage a child
 * reported was thrown away before it left the device — the parent's stats were empty for every
 * family, for six releases, and nothing noticed. The pure logic was correct throughout; the
 * mistake was in the reporting path between the counters and the wire, which is exactly the
 * stretch no test could see. It can be seen from here.
 */
class ReportingScenarioTest : DeviceScenario() {

    /**
     * Seconds already counted for [pkg]. Screen time lives in Room and outlives a re-pairing —
     * as it should, it is the device's own day — so every assertion here is a DELTA. A test
     * comparing totals would pass or fail depending on what ran before it.
     */
    private fun countedFor(pkg: String): Long =
        childReports { true }.usage.firstOrNull { it.categoryId == pkg }?.seconds ?: 0

    @Test
    fun `screen time reaches the parent, per app, with the packages intact`() {
        // Counter keys are PACKAGE NAMES now, and they contain dots — the filter that used to
        // strip category detail started stripping everything the day that changed. Assert on a
        // real package name arriving with its seconds, which is the claim that failed silently.
        val gameBefore = countedFor("com.example.game")
        val chatBefore = countedFor("com.example.chat")
        device.addUsage("com.example.game" to 1_800L, "com.example.chat" to 600L)

        val reported = childReports { snapshot ->
            snapshot.usage.any { it.categoryId == "com.example.game" && it.seconds >= gameBefore + 1_800 }
        }
        val byPackage = reported.usage.associate { it.categoryId to it.seconds }
        assertEquals(gameBefore + 1_800, byPackage["com.example.game"], "per-app usage lost on the way: $byPackage")
        assertEquals(chatBefore + 600, byPackage["com.example.chat"], "a second app was lost: $byPackage")
    }

    @Test
    fun `usage accumulates rather than replacing what was already counted`() {
        val before = countedFor("com.example.game")
        device.addUsage("com.example.game" to 300L)
        childReports { s -> s.usage.any { it.categoryId == "com.example.game" && it.seconds >= before + 300 } }
        device.addUsage("com.example.game" to 200L)
        val after = childReports { s ->
            s.usage.any { it.categoryId == "com.example.game" && it.seconds >= before + 500 }
        }
        assertEquals(
            before + 500,
            after.usage.single { it.categoryId == "com.example.game" }.seconds,
            "the sampler adds to a running total; replacing it would lose the day",
        )
    }

    @Test
    fun `the child sends the app list the parent classifies from`() {
        // The parent cannot invent this: it is what is actually installed over there, resolved
        // to human names on the device that has them. The fixtures are installed here rather
        // than assumed, because a freshly wiped emulator genuinely has no third-party apps —
        // and Walcott correctly leaves itself off the list it offers up to be blocked.
        val fixtures = Fixture.entries.associate { it.pkg to it.label }
        Fixture.entries.forEach { installFixtureApp(it) }
        try {
            val reported = childReports { snapshot ->
                fixtures.keys.all { pkg -> snapshot.apps.any { it.packageName == pkg } }
            }
            for ((pkg, label) in fixtures) {
                assertEquals(
                    label,
                    reported.apps.single { it.packageName == pkg }.label,
                    "the human name has to be resolved on the device that has the app",
                )
            }
            assertTrue(
                reported.apps.none { it.packageName == ChildDevice.PACKAGE },
                "Walcott should not offer itself up to be blocked",
            )
        } finally {
            fixtures.keys.forEach { device.ensureRemoved(it) }
        }
    }


    @Test
    fun `the device reports the things a parent judges its health by`() {
        val reported = childReports { it.enforcement.isNotBlank() }
        assertEquals(
            device.isDeviceOwner(),
            reported.enforcement == "device_owner",
            "the enforcement backend must be the truth, not the hope",
        )
        assertTrue(reported.batteryPercent in 0..100, "battery: ${reported.batteryPercent}")
        assertTrue(reported.appVersionCode > 0, "the child should say which build it runs")
        assertTrue(reported.appVersionName.isNotBlank())
        assertNotNull(reported.tzOffsetMinutes, "the parent dates the counters by the child's clock")
        assertTrue(reported.epochDay > 19_000, "the child's own day, not the parent's")
    }

    @Test
    fun `a clean device reports no enforcement gaps and a trustworthy clock`() {
        // The absence has to be reported as an absence: a parent reads "nothing wrong here" from
        // these being empty, so a child that simply never populated them would look healthy while
        // saying nothing at all.
        device.heartbeat()
        val reported = childReports { it.enforcement.isNotBlank() }
        assertTrue(
            reported.enforcementGaps.isEmpty(),
            "unexpected gaps on a device with nothing blocked: ${reported.enforcementGaps}",
        )
        assertEquals(0L, reported.clockSkewMs, "a device agreeing with the server should report no skew")
        assertEquals(0, reported.pinWrongTotal, "nobody has guessed a PIN here")
        assertTrue(reported.updateError.isEmpty(), "unexpected update error: ${reported.updateError}")
    }

    @Test
    fun `a family with no web filter does not report one as missing`() {
        // webFilterExpected is what makes the parent's "the filter isn't running" card appear.
        // With no domains blocked there is nothing to expect, and a child that said otherwise
        // would raise an alarm about a feature the family never turned on.
        val reported = childReports { it.enforcement.isNotBlank() }
        assertEquals(false, reported.webFilterExpected, "no rules ask for a filter here")
        // Same for what the filter is MADE of: a family using no blocklists must not have a
        // device telling them a list is missing, or that it downloaded nothing — there was
        // nothing to download, and the parent's screen would invent a problem out of it.
        assertEquals(0, reported.filterListDomains, "nothing to download, nothing to report")
        assertEquals(
            emptyList<String>(),
            reported.filterListsPending,
            "a list nobody switched on cannot be pending",
        )
    }
}
