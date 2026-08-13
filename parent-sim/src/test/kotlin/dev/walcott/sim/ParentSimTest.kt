package dev.walcott.sim

import dev.walcott.sync.ChildSnapshot
import dev.walcott.sync.CommandAck
import dev.walcott.sync.ExtraTimeRequest
import dev.walcott.sync.FamilyCrypto
import dev.walcott.sync.IncomingMessage
import dev.walcott.sync.PairingPayload
import dev.walcott.sync.ParentSnapshot
import dev.walcott.sync.RemoteAction
import dev.walcott.sync.SyncProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the harness itself, with a child made of nothing but the protocol.
 *
 * Every device scenario rests on this: if the sim signed wrongly, versioned wrongly, or read
 * the wire wrongly, a failure on the emulator would be blamed on the product. So the sim is
 * held to the wire contract here — against a child that is only [SyncProtocol] — and the
 * device suite is then free to be about the device.
 */
class ParentSimTest {

    private lateinit var relay: MockRelay
    private lateinit var parent: ParentSim

    @BeforeEach fun setUp() {
        relay = MockRelay().start()
        parent = ParentSim(relay.localUrl).start()
    }

    @AfterEach fun tearDown() {
        parent.stop()
        relay.stop()
    }

    /** A child that does nothing but hold the family's keys and speak the wire. */
    private inner class ProtocolChild(pairingText: String, val deviceId: String = "dev-1") {
        private val payload = requireNotNull(PairingPayload.decode(pairingText)) { "unreadable QR" }
        private val familyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(payload.familyKeyB64))
        private val parentPublic = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(payload.parentPublicKeyB64))
        private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val fromParent = CopyOnWriteArrayList<ParentSnapshot>()
        private val arrived = CountDownLatch(1)
        private var version = 0L

        val topic: String get() = payload.topic
        val relayFromQr: String get() = payload.ntfyServer
        val childId: String get() = payload.childId
        val childName: String get() = payload.childName

        fun connect(): ProtocolChild {
            client.newWebSocket(
                Request.Builder()
                    .url("${payload.ntfyServer.replaceFirst("http", "ws")}/${payload.topic}/ws")
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val body = NtfyEvent.messageBody(text) ?: return
                        val decoded = SyncProtocol.decode(body, familyKey, parentPublic) ?: return
                        if (decoded is IncomingMessage.FromParent) {
                            fromParent += decoded.snapshot
                            arrived.countDown()
                        }
                    }
                },
            )
            return this
        }

        fun publish(build: (ChildSnapshot) -> ChildSnapshot = { it }) {
            version++
            val snapshot = build(
                ChildSnapshot(
                    deviceId = deviceId,
                    displayName = childName.ifBlank { "Protocol Child" },
                    version = version,
                    epochDay = 20_000,
                    childId = childId,
                ),
            ).copy(version = version)
            val body = SyncProtocol.encodeChild(snapshot, familyKey)
            client.newCall(
                Request.Builder()
                    .url("${payload.ntfyServer}/${payload.topic}")
                    .post(body.toByteArray().toRequestBody())
                    .build(),
            ).execute().close()
        }

        /** True when a parent snapshot arrives within the window. */
        fun awaitParent(count: Int = 1, timeoutMs: Long = 5_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (fromParent.size < count && System.currentTimeMillis() < deadline) Thread.sleep(25)
            return fromParent.size >= count
        }
    }

    @Test
    fun `the pairing payload carries everything a child needs, relay included`() {
        // The relay address is the part that makes a device talk to US rather than to the public
        // server. A QR missing it would send the emulator to ntfy.sh and the scenario would hang
        // on a channel nobody is listening to.
        val child = ProtocolChild(parent.pairingFor(childId = "c7", childName = "Ana"))
        assertEquals(parent.topic, child.topic)
        assertEquals(relay.localUrl, child.relayFromQr)
        assertEquals("c7", child.childId)
        assertEquals("Ana", child.childName)
    }

    @Test
    fun `a child snapshot reaches the parent, decrypted`() {
        val child = ProtocolChild(parent.pairingFor()).connect()
        child.publish { it.copy(displayName = "Ana", batteryPercent = 42) }
        val seen = parent.awaitChild(timeoutMs = 5_000) { it.deviceId == "dev-1" }
        assertEquals("Ana", seen.displayName)
        assertEquals(42, seen.batteryPercent)
    }

    @Test
    fun `the parent's snapshots are signed and readable by the child`() {
        val child = ProtocolChild(parent.pairingFor()).connect()
        parent.pushPolicy(PolicyJson.build(version = 3, restrictions = setOf("installs")))
        assertTrue(child.awaitParent(), "no parent snapshot arrived")
        assertTrue(child.fromParent.last().policyJson.contains("installs"))
    }

    @Test
    fun `every push moves the version forward, because a child refuses one that doesn't`() {
        val child = ProtocolChild(parent.pairingFor()).connect()
        parent.pushPolicy(PolicyJson.minimal())
        parent.pushPolicy(PolicyJson.minimal())
        assertTrue(child.awaitParent(count = 2))
        val versions = child.fromParent.map { it.version }
        assertEquals(versions.sorted(), versions)
        assertTrue(versions[1] > versions[0], "version did not advance: $versions")
    }

    @Test
    fun `a re-emit repeats the snapshot without advancing the version`() {
        // What the parent app's periodic re-emit does. A child must take the resolutions it
        // carries and still refuse to re-adopt the policy, so the sim has to be able to produce
        // exactly this shape.
        val child = ProtocolChild(parent.pairingFor()).connect()
        parent.pushPolicy(PolicyJson.minimal())
        parent.reEmit()
        assertTrue(child.awaitParent(count = 2))
        assertEquals(child.fromParent[0].version, child.fromParent[1].version)
    }

    @Test
    fun `commands and resolutions accumulate into the snapshot the child reads`() {
        val child = ProtocolChild(parent.pairingFor()).connect()
        val commandId = parent.sendCommand("dev-1", RemoteAction.DIAGNOSE)
        parent.resolve("req-1", approved = true, grantedMinutes = 15)
        assertTrue(child.awaitParent(count = 2))
        val latest = child.fromParent.last()
        assertEquals(listOf(commandId), latest.commands.map { it.id })
        assertEquals(15, latest.resolutions.single { it.requestId == "req-1" }.grantedMinutes)
    }

    @Test
    fun `awaitAck finds an ack that a later snapshot has already replaced`() {
        // A snapshot carries only the LAST ack. Two commands answered in quick succession would
        // leave the first invisible in current state, and a scenario waiting on it would hang on
        // a command that in fact ran.
        val child = ProtocolChild(parent.pairingFor()).connect()
        val first = parent.sendCommand("dev-1", RemoteAction.DIAGNOSE)
        val second = parent.sendCommand("dev-1", RemoteAction.REAPPLY_POLICY)
        child.publish { it.copy(lastCommand = CommandAck(first, RemoteAction.DIAGNOSE, true, "diag_sent", 1)) }
        child.publish { it.copy(lastCommand = CommandAck(second, RemoteAction.REAPPLY_POLICY, true, "ok", 2)) }
        assertEquals("diag_sent", parent.awaitAck(first, timeoutMs = 5_000).detail)
        assertEquals("ok", parent.awaitAck(second, timeoutMs = 5_000).detail)
    }

    @Test
    fun `a replayed older snapshot does not walk the parent's view backwards`() {
        val child = ProtocolChild(parent.pairingFor()).connect()
        child.publish { it.copy(batteryPercent = 10) }
        parent.awaitChild(timeoutMs = 5_000) { it.batteryPercent == 10 }
        child.publish { it.copy(batteryPercent = 90) }
        parent.awaitChild(timeoutMs = 5_000) { it.batteryPercent == 90 }
        relay.replay(parent.topic, index = 0)
        parent.assertNoChild(windowMs = 1_500) { it.batteryPercent == 10 }
    }

    @Test
    fun `garbage on the topic is ignored rather than fatal`() {
        // The topic is a bearer secret in a URL: anyone who learns it can post anything. A sim
        // that fell over on that would be unable to test the case the product handles.
        val child = ProtocolChild(parent.pairingFor()).connect()
        parent.publishRaw("this is not an envelope")
        parent.publishRaw("""{"kind":"child","senderId":"x","version":1,"ciphertext":"!!!"}""")
        child.publish { it.copy(batteryPercent = 55) }
        assertEquals(55, parent.awaitChild(timeoutMs = 5_000) { it.batteryPercent == 55 }.batteryPercent)
    }

    @Test
    fun `assertNoChild fails when the thing it forbids does happen`() {
        // A negative assertion that cannot fail is worse than none, and half the device suite
        // leans on this one.
        val child = ProtocolChild(parent.pairingFor()).connect()
        child.publish { it.copy(batteryPercent = 7) }
        val error = runCatching { parent.assertNoChild(windowMs = 3_000) { it.batteryPercent == 7 } }
            .exceptionOrNull()
        assertNotNull(error, "assertNoChild passed on a snapshot that was there")
        assertTrue(error is AssertionError)
    }

    @Test
    fun `a policy built here decodes to the keys the child reads`() {
        // The child decodes with ignoreUnknownKeys, so a misspelt field is not an error — it is
        // a rule that silently never applies. Pin the names that matter.
        val policy = PolicyJson.build(
            version = 5,
            restrictions = setOf("installs", "apps_control"),
            dailyMinutes = mapOf("com.game" to 30),
            unlimited = setOf("com.phone"),
        )
        assertTrue(policy.contains("\"deviceRestrictions\""))
        assertTrue(policy.contains("\"appPolicies\""))
        assertTrue(policy.contains("\"budgets\""))
        assertTrue(policy.contains("\"SCHOOL\":30"))
        assertTrue(policy.contains("\"unlimited\":true"))
        assertNull(PolicyJson.build().let { if (it.contains("dailyMinutes")) "leaked helper name" else null })
    }
}
