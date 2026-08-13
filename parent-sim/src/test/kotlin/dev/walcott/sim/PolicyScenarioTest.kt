package dev.walcott.sim

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rules crossing from the parent to a real device, and — more interesting — the rules that
 * must NOT cross.
 *
 * The replay and rollback gates are the reason this file exists. They are the difference
 * between a child that can be talked back into last week's laxer policy and one that cannot,
 * and until now they were only ever exercised against a child made of the same code as the
 * test. Here the refusal has to survive a real device: its own stored version, its own
 * DataStore, its own enforcement loop re-asserting whatever it believes.
 */
class PolicyScenarioTest : DeviceScenario() {

    private val installBlock = setOf("installs")

    @Test
    fun `rules reach the device and the OS actually applies them`() {
        val pushed = parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
        val reported = childReports { it.appliedPolicyVersion >= pushed.version }
        assertEquals(pushed.version, reported.appliedPolicyVersion, "the child should report what it applied")
    }

    @Test
    fun `lifting a restriction reaches the device too`() {
        // The direction that is easy to get wrong: a set that only ever grows would leave a
        // family unable to undo anything, and nothing off-device can tell you it happened.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = emptySet()))
        awaitDevice("the install block lifted") { !device.installBlocked() }
    }

    @Test
    fun `a laxer policy replayed under a version already applied is refused`() {
        // The rollback attack, and the thing no single-device test could ever show: a properly
        // signed snapshot from the real parent key, carrying real rules, at a version the child
        // has already passed. Only the replay gate stands between it and a disarmed phone.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }
        val appliedVersion = parent.currentVersion()

        parent.pushPolicyAtVersion(
            PolicyJson.build(version = 99, restrictions = emptySet()),
            atVersion = appliedVersion,
        )
        assertDeviceNever("the install block lifted by a replayed snapshot") { !device.installBlocked() }
    }

    @Test
    fun `a snapshot from before the current one cannot roll the rules back`() {
        // Same gate, arrived at the other way: the relay itself repeating an older message,
        // which is a thing ntfy genuinely does after a reconnect with a cursor.
        val beforeFirst = relay.published(parent.topic).size
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = emptySet()))
        assertTrue(relay.awaitPublished(parent.topic, beforeFirst), "the first policy never reached the relay")
        val laxIndex = relay.published(parent.topic).lastIndex

        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }

        relay.replay(parent.topic, laxIndex)
        assertDeviceNever("the install block lifted by a relay replay") { !device.installBlocked() }
    }

    @Test
    fun `two policies published in the same instant leave the child on the newer one`() {
        // Not an exotic shape: every resolution, command and bonus publishes its own snapshot,
        // so a parent answering a request while a rule edit goes out produces two in the same
        // instant — and a child reconnecting with a cursor is handed the whole backlog at once.
        //
        // Applied concurrently, the replay gate stops meaning anything. It is a read-modify-
        // write: both snapshots read the same applied version, both decide they are newer, and
        // the last WRITE wins rather than the highest version. The child then enforces the older
        // rules while reporting the newer ones, and nothing corrects it until the next edit.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = emptySet()))
        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = installBlock))

        awaitDevice("the newer of two simultaneous policies applied") { device.installBlocked() }
        val reported = childReports { it.appliedPolicyVersion >= parent.currentVersion() }
        assertEquals(
            parent.currentVersion(),
            reported.appliedPolicyVersion,
            "the child should be on the newest snapshot it has seen",
        )
    }

    @Test
    fun `a burst of snapshots is applied in order, not in whichever order they finish`() {
        // The same guarantee walked the other way: the newest says "no restrictions", so ending
        // on an OLDER one would leave a device locked down by rules the family has withdrawn.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }

        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = installBlock))
        parent.pushPolicy(PolicyJson.build(version = 4, restrictions = emptySet()))
        awaitDevice("the newest of the burst applied") { !device.installBlocked() }
    }

    @Test
    fun `the periodic re-emit does not re-apply rules it already delivered`() {
        val pushed = parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        val applied = childReports { it.appliedPolicyVersion >= pushed.version }.appliedPolicyVersion

        parent.reEmit()
        Thread.sleep(3_000)
        val after = childReports { true }
        assertEquals(applied, after.appliedPolicyVersion, "a re-emit should change nothing")
        assertTrue(device.installBlocked(), "a re-emit should not disturb what is enforced")
    }

    @Test
    fun `a policy carrying fields this build has never seen is still adopted`() {
        // Forward compatibility is a promise to every phone that hasn't updated yet, and it can
        // only be checked against a child that really decodes. An unknown key must cost nothing.
        val fromTheFuture = PolicyJson.build(
            version = 2,
            restrictions = installBlock,
            extra = mapOf(
                "someFieldFromTheFuture" to buildJsonObject {
                    put("enabled", true)
                    put("shape", "nothing this build has ever heard of")
                },
            ),
        )
        val pushed = parent.pushPolicy(fromTheFuture)
        awaitDevice("the install block armed despite an unknown field") { device.installBlocked() }
        val reported = childReports { it.appliedPolicyVersion >= pushed.version }
        assertEquals(pushed.version, reported.appliedPolicyVersion)
    }

    @Test
    fun `garbage on the family topic changes nothing`() {
        // The topic is a bearer secret in a URL: anyone who learns it can post to it. The child
        // must ignore what it cannot authenticate rather than fall over or, worse, act on it.
        parent.pushPolicy(PolicyJson.build(version = 2, restrictions = installBlock))
        awaitDevice("the install block armed") { device.installBlocked() }

        parent.publishRaw("not an envelope at all")
        parent.publishRaw("""{"kind":"parent","senderId":"parent","version":9999,"ciphertext":"AAAA","signature":"AAAA"}""")
        assertDeviceNever("rules disturbed by unauthenticated noise") { !device.installBlocked() }

        // And it is still a working child afterwards, not a wedged one.
        val pushed = parent.pushPolicy(PolicyJson.build(version = 4, restrictions = emptySet()))
        awaitDevice("the install block lifted after the noise") { !device.installBlocked() }
        childReports { it.appliedPolicyVersion >= pushed.version }
    }
}
