package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.PublicKey
import javax.crypto.SecretKey

/**
 * End-to-end exercise of the whole sync loop at the protocol level: pairing, config push,
 * a child request, parent approval, and last-write-wins convergence. This is what the
 * Android [SyncManager] wires around; testing it here keeps the core deterministic.
 */
class SyncLoopTest {

    // A trivial in-memory transport: publish() hands the string to the other side's inbox.
    private class Wire {
        val toParent = ArrayDeque<String>()
        val toChild = ArrayDeque<String>()
    }

    // --- Parent device state ---
    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val parentKeys = FamilyCrypto.generateSigningKeyPair()
    private var parentVersion = 0L
    private var parentPolicy = "RULES-v1"
    private val parentResolutions = mutableListOf<Resolution>()
    private var parentSeesChildren = emptyMap<String, ChildSnapshot>()

    // --- Child device state (paired from the QR) ---
    private lateinit var childFamilyKey: SecretKey
    private lateinit var childParentPublic: PublicKey
    private var childPolicy: String? = null
    private var childGrantedMinutes = 0
    private val childPending = mutableListOf<ExtraTimeRequest>()
    private val childApplied = mutableSetOf<String>()
    private var childVersion = 0L

    private fun parentSnapshot() =
        ParentSnapshot(parentVersion, parentPolicy, parentResolutions.toList())

    private fun publishParent(wire: Wire) {
        wire.toChild.addLast(SyncProtocol.encodeParent(parentSnapshot(), familyKey, parentKeys.private))
    }

    private fun childApplyInbox(wire: Wire) {
        while (wire.toChild.isNotEmpty()) {
            val msg = SyncProtocol.decode(wire.toChild.removeFirst(), childFamilyKey, childParentPublic)
            val snap = (msg as? IncomingMessage.FromParent)?.snapshot ?: continue
            childPolicy = snap.policyJson
            val pendingIds = childPending.map { it.requestId }.toSet()
            for (res in SyncEngine.newResolutions(snap, pendingIds, childApplied)) {
                if (res.approved) childGrantedMinutes += res.grantedMinutes
                childApplied += res.requestId
                childPending.removeAll { it.requestId == res.requestId }
            }
        }
    }

    private fun publishChild(wire: Wire) {
        val snap = ChildSnapshot("dev-1", "Ana", childVersion, 20_000, requests = childPending.toList())
        wire.toParent.addLast(SyncProtocol.encodeChild(snap, childFamilyKey))
    }

    private fun parentApplyInbox(wire: Wire) {
        while (wire.toParent.isNotEmpty()) {
            val msg = SyncProtocol.decode(wire.toParent.removeFirst(), familyKey, parentKeys.public)
            val snap = (msg as? IncomingMessage.FromChild)?.snapshot ?: continue
            parentSeesChildren = SyncEngine.mergeChild(parentSeesChildren, snap)
        }
    }

    @Test
    fun `full pairing, config, request, approval loop`() {
        val wire = Wire()

        // 1. Child pairs from the parent's QR.
        val qr = PairingPayload(
            "topic",
            FamilyCrypto.toB64(familyKey.encoded),
            FamilyCrypto.toB64(parentKeys.public.encoded),
        ).encode()
        val paired = PairingPayload.decode(qr)!!
        childFamilyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(paired.familyKeyB64))
        childParentPublic = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(paired.parentPublicKeyB64))

        // 2. Parent pushes initial rules; child adopts them.
        parentVersion = 1
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals("RULES-v1", childPolicy)

        // 3. Child asks for extra time; parent sees it pending.
        childPending += ExtraTimeRequest("r1", "games", 15, "please", 1000)
        childVersion = 1
        publishChild(wire)
        parentApplyInbox(wire)
        val pendingAtParent = parentSeesChildren.getValue("dev-1").requests.map { it.requestId }
        assertEquals(listOf("r1"), pendingAtParent)

        // 4. Parent approves; child applies the grant exactly once.
        parentResolutions += Resolution("r1", approved = true, grantedMinutes = 15, resolvedAtEpochMs = 2000)
        parentVersion = 2
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals(15, childGrantedMinutes)
        assertTrue(childPending.isEmpty())

        // 5. Re-delivering the same resolution is idempotent (no double grant).
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals(15, childGrantedMinutes)
    }

    @Test
    fun `a per-child QR carries identity and the childId flows back to the parent`() {
        val wire = Wire()

        // 1. Parent generates a QR for the registered child "Ana".
        val qr = PairingPayload(
            "topic",
            FamilyCrypto.toB64(familyKey.encoded),
            FamilyCrypto.toB64(parentKeys.public.encoded),
            childId = "child-ana",
            childName = "Ana",
            familyName = "Moudis",
        ).encode()

        // 2. Child scans it and reconstructs keys AND identity.
        val paired = PairingPayload.decode(qr)!!
        assertEquals("child-ana", paired.childId)
        assertEquals("Ana", paired.childName)
        assertEquals("Moudis", paired.familyName)
        childFamilyKey = FamilyCrypto.familyKeyFromBytes(FamilyCrypto.fromB64(paired.familyKeyB64))
        childParentPublic = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(paired.parentPublicKeyB64))

        // 3. The child's snapshot carries the childId through the wire to the parent's map.
        val snap = ChildSnapshot("dev-1", paired.childName, 1, 20_000, childId = paired.childId)
        wire.toParent.addLast(SyncProtocol.encodeChild(snap, childFamilyKey))
        parentApplyInbox(wire)
        assertEquals("child-ana", parentSeesChildren.getValue("dev-1").childId)

        // 4. Re-publishing with the same deviceId replaces the entry — no duplicates.
        wire.toParent.addLast(SyncProtocol.encodeChild(snap.copy(version = 2), childFamilyKey))
        parentApplyInbox(wire)
        assertEquals(1, parentSeesChildren.size)
        assertEquals(2, parentSeesChildren.getValue("dev-1").version)
    }

    /** Simulate a lossy channel: throw away every message currently queued in [both] directions. */
    private fun dropAll(wire: Wire) {
        wire.toParent.clear()
        wire.toChild.clear()
    }

    @Test
    fun `a dropped approval heals on the next re-emit, still granting exactly once`() {
        val wire = Wire()
        childFamilyKey = familyKey
        childParentPublic = parentKeys.public

        // Child has a pending request the parent approves.
        childPending += ExtraTimeRequest("r1", "games", 15, "", 1000)
        parentResolutions += Resolution("r1", approved = true, grantedMinutes = 15, resolvedAtEpochMs = 2000)
        parentVersion = 1

        // The approval is published but the channel drops it before the child sees it.
        publishParent(wire)
        dropAll(wire)
        childApplyInbox(wire)
        assertEquals(0, childGrantedMinutes) // nothing arrived

        // Re-emit (the periodic heartbeat) carries the same resolution again; now it lands.
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals(15, childGrantedMinutes)

        // And a third delivery (duplicate) does not double-grant.
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals(15, childGrantedMinutes)
    }

    @Test
    fun `a dropped child request heals when the child re-emits its snapshot`() {
        val wire = Wire()
        childFamilyKey = familyKey
        childParentPublic = parentKeys.public

        childPending += ExtraTimeRequest("r1", "games", 15, "", 1000)
        childVersion = 1

        // First publish is lost entirely.
        publishChild(wire)
        dropAll(wire)
        parentApplyInbox(wire)
        assertTrue(parentSeesChildren.isEmpty()) // parent never saw the child

        // The child re-emits (snapshots carry all pending requests every cycle) and converges.
        publishChild(wire)
        parentApplyInbox(wire)
        assertEquals(listOf("r1"), parentSeesChildren.getValue("dev-1").requests.map { it.requestId })
    }

    @Test
    fun `losing intermediate snapshots still converges to the latest state`() {
        val wire = Wire()
        childFamilyKey = familyKey
        childParentPublic = parentKeys.public

        // Parent edits rules several times; every intermediate publish is dropped.
        for (v in 1..4) {
            parentVersion = v.toLong()
            parentPolicy = "RULES-v$v"
            publishParent(wire)
            dropAll(wire)
        }
        childApplyInbox(wire)
        assertNull(childPolicy) // nothing got through

        // A single surviving re-emit of the latest state is all it takes to catch up.
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals("RULES-v4", childPolicy)
    }

    @Test
    fun `child ignores a stale out-of-order parent snapshot`() {
        val wire = Wire()
        childFamilyKey = familyKey
        childParentPublic = parentKeys.public

        parentVersion = 3
        parentPolicy = "RULES-v3"
        publishParent(wire)
        childApplyInbox(wire)
        assertEquals("RULES-v3", childPolicy)

        // A delayed v1 message arrives; the child must keep v3. We model the child holding
        // the newest snapshot via SyncEngine.mergeParent.
        val stale = ParentSnapshot(1, "RULES-v1")
        val newest = SyncEngine.mergeParent(ParentSnapshot(3, "RULES-v3"), stale)
        assertEquals("RULES-v3", newest.policyJson)
    }
}
