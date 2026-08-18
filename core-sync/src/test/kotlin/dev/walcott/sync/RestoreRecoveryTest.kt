package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole disaster-recovery story, played out message by message: the parent's phone is lost,
 * a new one is set up from the backup file, and every child obeys it again without being touched.
 *
 * This is deliberately end-to-end across the pieces that decide whether that works — the backup
 * file, the key rotation, the envelope verification and the replay gate — because each of them
 * passes its own unit tests while the recovery as a whole can still fail. It fails in exactly two
 * ways, and both are silent: the restored parent's snapshots don't verify (children ignore a
 * parent they no longer recognise), or they verify but lose the version comparison (children
 * ignore rules they consider stale). A family only finds out when a rule change does nothing.
 *
 * Both families are covered, because they recover differently:
 *
 *  - **a family created since v0.11** exports its software signing key, so the restored parent IS
 *    the same key and there is nothing to prove — but its version counter restarts, which is what
 *    the leap in `SyncManager.restoreBackup` is for.
 *  - **a legacy family** has its key locked in the Keystore and cannot export it, so the backup
 *    carries a fresh key plus a [RotationCert] minted by the old one, and the children have to
 *    adopt a key they have never seen on the strength of that proof.
 */
class RestoreRecoveryTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()

    /** What a child holds: the trusted parent key, and the newest rules version it has applied. */
    private data class Child(var trustedKeyB64: String, var appliedVersion: Long = 0)

    /**
     * One message, as a child processes it: verify, adopt the rotated key if the envelope proves
     * one, then decide whether the rules are fresh enough to apply.
     *
     * A faithful miniature of `SyncManager.handleIncoming` + `applyParentSnapshotLocked`, which
     * are the two Android-bound functions this contract actually lives in.
     */
    private fun deliver(child: Child, envelope: String): ParentSnapshot? {
        val trusted = FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(child.trustedKeyB64))
        val decoded = SyncProtocol.decodeVerbose(envelope, familyKey, trusted) ?: return null
        val rotated = decoded.rotatedParentPublicKeyB64
        if (rotated != null) child.trustedKeyB64 = rotated
        val snapshot = (decoded.message as IncomingMessage.FromParent).snapshot
        if (!SyncEngine.adoptsPolicy(snapshot.version, child.appliedVersion, rotationAdopted = rotated != null)) {
            return null
        }
        child.appliedVersion =
            SyncEngine.rebasedPolicyVersion(snapshot.version, child.appliedVersion, rotationAdopted = rotated != null)
        return snapshot
    }

    private fun snapshot(version: Long, policy: String) = ParentSnapshot(version = version, policyJson = policy)

    /** How far `SyncManager.restoreBackup` jumps the counter past the backup's. */
    private val restoreLeap = 1_000_000L

    @Test
    fun `a family created today recovers from its backup and rules flow again`() {
        val original = FamilyCrypto.generateSigningKeyPair()
        val child = Child(FamilyCrypto.toB64(original.public.encoded))

        // Life before the loss: the parent publishes rules and the child adopts them.
        assertNotNull(
            deliver(child, SyncProtocol.encodeParent(snapshot(40, "bedtime 21:00"), familyKey, original.private)),
        )
        assertEquals(40, child.appliedVersion)

        // The backup is taken here — at version 41 — and the parent goes on editing afterwards,
        // which is the case that makes a naive restore fail: the child ends up ahead of the file.
        val backup = FamilyBackup.encrypt(
            FamilyBackupPayload(
                topic = "walcott-topic",
                ntfyServer = "https://ntfy.sh",
                familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
                signingPublicKeyB64 = FamilyCrypto.toB64(original.public.encoded),
                signingPrivateKeyB64 = FamilyCrypto.toB64(original.private.encoded),
                policyJson = """{"familyName":"Demo"}""",
                parentVersion = 41,
            ),
            "a passphrase worth its length".toCharArray(),
            iterations = 10_000,
        )
        deliver(child, SyncProtocol.encodeParent(snapshot(55, "bedtime 21:30"), familyKey, original.private))
        assertEquals(55, child.appliedVersion)

        // The phone is lost. A new one opens the file with the passphrase and becomes the parent.
        val restored = checkNotNull(FamilyBackup.decrypt(backup, "a passphrase worth its length".toCharArray()))
        val restoredKey = FamilyCrypto.privateKeyFromBytes(FamilyCrypto.fromB64(restored.signingPrivateKeyB64))
        val restoredVersion = restored.parentVersion + restoreLeap

        // First publish from the new phone: same key, so nothing to prove — and the leap is what
        // clears the 14 edits the lost phone published after the backup was written.
        val adopted = deliver(child, SyncProtocol.encodeParent(snapshot(restoredVersion, "bedtime 22:00"), familyKey, restoredKey))
        assertEquals("bedtime 22:00", adopted?.policyJson)

        // And it keeps working from there, one edit at a time.
        assertNotNull(
            deliver(child, SyncProtocol.encodeParent(snapshot(restoredVersion + 1, "bedtime 22:15"), familyKey, restoredKey)),
        )
        assertEquals(restoredVersion + 1, child.appliedVersion)
    }

    @Test
    fun `a legacy family recovers through the rotation cert its backup carries`() {
        // The Keystore key: it signs, it can never be exported, and it dies with the phone.
        val keystoreKey = FamilyCrypto.generateSigningKeyPair()
        val child = Child(FamilyCrypto.toB64(keystoreKey.public.encoded))
        deliver(child, SyncProtocol.encodeParent(snapshot(90, "old rules"), familyKey, keystoreKey.private))

        // Taking the backup mints the recovery key and has the Keystore key vouch for it.
        val recovery = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(recovery.public, keystoreKey.private)
        val backup = FamilyBackup.encrypt(
            FamilyBackupPayload(
                topic = "walcott-topic",
                ntfyServer = "https://ntfy.sh",
                familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
                signingPublicKeyB64 = FamilyCrypto.toB64(recovery.public.encoded),
                signingPrivateKeyB64 = FamilyCrypto.toB64(recovery.private.encoded),
                rotationCertB64 = KeyRotation.encode(cert),
                policyJson = """{"familyName":"Legacy"}""",
                parentVersion = 90,
            ),
            "a passphrase worth its length".toCharArray(),
            iterations = 10_000,
        )

        val restored = checkNotNull(FamilyBackup.decrypt(backup, "a passphrase worth its length".toCharArray()))
        val restoredKey = FamilyCrypto.privateKeyFromBytes(FamilyCrypto.fromB64(restored.signingPrivateKeyB64))
        val restoredCert = checkNotNull(KeyRotation.decode(restored.rotationCertB64))

        // A snapshot from the new key alone would be refused — that is the whole point of the cert.
        assertNull(
            SyncProtocol.decodeVerbose(
                SyncProtocol.encodeParent(snapshot(91, "new rules"), familyKey, restoredKey),
                familyKey,
                FamilyCrypto.publicKeyFromBytes(FamilyCrypto.fromB64(child.trustedKeyB64)),
            ),
        )

        // With it, the child adopts both the key and the rules, even at a LOWER version than it
        // has already applied (a restored parent's counter legitimately restarts).
        val adopted = deliver(
            child,
            SyncProtocol.encodeParent(snapshot(3, "new rules"), familyKey, restoredKey, restoredCert),
        )
        assertEquals("new rules", adopted?.policyJson)
        assertEquals(FamilyCrypto.toB64(recovery.public.encoded), child.trustedKeyB64)
        assertEquals(3, child.appliedVersion)

        // From here the new key verifies on its own and the rebased baseline keeps rules flowing.
        assertNotNull(deliver(child, SyncProtocol.encodeParent(snapshot(4, "newer rules"), familyKey, restoredKey)))
    }

    @Test
    fun `a child that slept through the restore still catches up later`() {
        // The case the rotation cert must keep riding in every envelope for: a phone that was off
        // during the restore has never seen the hand-over and still trusts the old key.
        val keystoreKey = FamilyCrypto.generateSigningKeyPair()
        val asleep = Child(FamilyCrypto.toB64(keystoreKey.public.encoded))
        val recovery = FamilyCrypto.generateSigningKeyPair()
        val cert = KeyRotation.create(recovery.public, keystoreKey.private)

        // Weeks later, the first message it receives is an ordinary re-emit from the new parent.
        val adopted = deliver(
            asleep,
            SyncProtocol.encodeParent(snapshot(12, "current rules"), familyKey, recovery.private, cert),
        )
        assertEquals("current rules", adopted?.policyJson)
        assertEquals(FamilyCrypto.toB64(recovery.public.encoded), asleep.trustedKeyB64)
    }

    @Test
    fun `a cert nobody trusted proves nothing`() {
        // The forgery: someone mints their own pair and their own cert. The child's trusted key
        // never signed it, so both the key and the rules are refused.
        val keystoreKey = FamilyCrypto.generateSigningKeyPair()
        val child = Child(FamilyCrypto.toB64(keystoreKey.public.encoded))
        val attacker = FamilyCrypto.generateSigningKeyPair()
        val selfSigned = KeyRotation.create(attacker.public, attacker.private)

        val delivered = deliver(
            child,
            SyncProtocol.encodeParent(snapshot(99, "no bedtime at all"), familyKey, attacker.private, selfSigned),
        )
        assertNull(delivered)
        assertEquals(FamilyCrypto.toB64(keystoreKey.public.encoded), child.trustedKeyB64)
    }

    @Test
    fun `a restore does not resurrect the rules the family has since replaced`() {
        // Restoring from an OLD backup is a real thing to do (the newest file is unreadable, or
        // the parent grabs last month's copy). It must not silently lose to the replay gate: the
        // leap is what makes the file's older rules win over what the children currently hold.
        val key = FamilyCrypto.generateSigningKeyPair()
        val child = Child(FamilyCrypto.toB64(key.public.encoded))
        deliver(child, SyncProtocol.encodeParent(snapshot(500, "rules as of today"), familyKey, key.private))

        val fromOldBackup = 12L + restoreLeap
        assertTrue(fromOldBackup > child.appliedVersion)
        val adopted = deliver(
            child,
            SyncProtocol.encodeParent(snapshot(fromOldBackup, "rules as of last month"), familyKey, key.private),
        )
        assertEquals("rules as of last month", adopted?.policyJson)
    }

    @Test
    fun `the backup file refuses to open with the wrong secret`() {
        val key = FamilyCrypto.generateSigningKeyPair()
        val file = FamilyBackup.encrypt(
            FamilyBackupPayload(
                topic = "t",
                ntfyServer = "https://ntfy.sh",
                familyKeyB64 = FamilyCrypto.toB64(familyKey.encoded),
                signingPublicKeyB64 = FamilyCrypto.toB64(key.public.encoded),
                signingPrivateKeyB64 = FamilyCrypto.toB64(key.private.encoded),
                policyJson = "{}",
            ),
            "the right passphrase".toCharArray(),
            iterations = 10_000,
        )
        assertNull(FamilyBackup.decrypt(file, "the wrong passphrase".toCharArray()))
        assertFalse(FamilyBackup.decrypt(file, "the right passphrase".toCharArray()) == null)
    }
}
