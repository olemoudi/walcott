package dev.walcott.sim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rules too big for one relay message still reach the phone.
 *
 * ntfy keeps a body over 4,096 bytes as a file, answers the publish with a 200, and leaves a
 * sentence where the body was. A phone that read only the sentence failed to decode it and dropped
 * the rules — every rule change, silently, from the day a family's rules grew past that size. The
 * relay here does what ntfy.sh does (see MockRelay.MESSAGE_LIMIT), measured against the real one.
 */
class LargePolicyScenarioTest : DeviceScenario() {

    @Test
    fun `rules kept as a file by the relay are fetched and applied`() {
        // Hundreds of hand-typed sites with no pattern to them: enough that no compression keeps
        // the message under the limit, which is the point.
        val random = java.util.Random(7)
        val domains = (1..600).map { (1..12).map { 'a' + random.nextInt(26) }.joinToString("") + ".example.org" }
        val pushed = parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                restrictions = setOf("installs"),
                extra = mapOf("blockedDomains" to JsonArray(domains.map { JsonPrimitive(it) })),
            ),
        )
        assertTrue(
            relay.published(parent.topic).any { it.attachment != null },
            "the rules were small enough to travel as a message, so this proves nothing",
        )

        awaitDevice("the install block armed by rules that travelled as a file", timeoutMs = 60_000) {
            device.installBlocked()
        }
        val reported = childReports { it.appliedPolicyVersion >= pushed.version }
        assertEquals(pushed.version, reported.appliedPolicyVersion)
    }
}
