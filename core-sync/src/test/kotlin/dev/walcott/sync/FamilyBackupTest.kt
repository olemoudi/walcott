package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FamilyBackupTest {

    /** The lowest count decrypt accepts, keeping the suite fast while exercising the KDF. */
    private val fastIterations = 10_000

    private fun payload() = FamilyBackupPayload(
        familyName = "Demo",
        topic = "walcott-abc123",
        ntfyServer = "https://ntfy.sh",
        familyKeyB64 = FamilyCrypto.toB64(FamilyCrypto.generateFamilyKey().encoded),
        signingPublicKeyB64 = "pub",
        signingPrivateKeyB64 = "priv",
        rotationCertB64 = "cert",
        policyJson = """{"familyName":"Demo","children":[{"childId":"c1","name":"Ana"}]}""",
        parentVersion = 41,
        createdAtMs = 1_720_000_000_000,
    )

    @Test
    fun `round-trips with the right passphrase`() {
        val original = payload()
        val file = FamilyBackup.encrypt(original, "correct horse".toCharArray(), fastIterations)
        assertEquals(original, FamilyBackup.decrypt(file, "correct horse".toCharArray()))
    }

    @Test
    fun `a wrong passphrase yields null, not garbage`() {
        val file = FamilyBackup.encrypt(payload(), "correct horse".toCharArray(), fastIterations)
        assertNull(FamilyBackup.decrypt(file, "wrong horse".toCharArray()))
    }

    @Test
    fun `a tampered file fails authentication`() {
        val file = FamilyBackup.encrypt(payload(), "correct horse".toCharArray(), fastIterations)
        // Flip one character inside the ciphertext field (guaranteed to actually change it).
        val at = file.indexOf("\"ciphertextB64\":\"") + "\"ciphertextB64\":\"".length + 4
        val flipped = if (file[at] == 'A') 'B' else 'A'
        val tampered = file.substring(0, at) + flipped + file.substring(at + 1)
        assertNull(FamilyBackup.decrypt(tampered, "correct horse".toCharArray()))
    }

    @Test
    fun `files that are not backups are rejected`() {
        assertNull(FamilyBackup.decrypt("not json at all", "x".toCharArray()))
        assertNull(FamilyBackup.decrypt("""{"format":"something-else","version":1,"kdfIterations":1,"saltB64":"AA","ciphertextB64":"AA"}""", "x".toCharArray()))
    }

    @Test
    fun `the recorded iteration count is what decryption uses`() {
        // Encrypt at a non-default count; decrypt must succeed by reading it from the file.
        val file = FamilyBackup.encrypt(payload(), "pass-phrase".toCharArray(), 20_000)
        assertEquals(payload().topic, FamilyBackup.decrypt(file, "pass-phrase".toCharArray())?.topic)
    }

    @Test
    fun `an absurd iteration count is rejected before any KDF work`() {
        // The header is unauthenticated; a crafted count must not pin a core for hours.
        val file = FamilyBackup.encrypt(payload(), "correct horse".toCharArray(), fastIterations)
        val bombed = file.replace("\"kdfIterations\":$fastIterations", "\"kdfIterations\":2147483647")
        assertNull(FamilyBackup.decrypt(bombed, "correct horse".toCharArray()))
    }

    @Test
    fun `a file sealed with the cached derived key still opens with the passphrase`() {
        // The fire-and-forget rewrite path: derive once, cache the key, re-seal later.
        val original = payload()
        val saltB64 = FamilyBackup.newSaltB64()
        val keyB64 = FamilyBackup.deriveKeyB64("correct horse".toCharArray(), saltB64, fastIterations)
        val file = FamilyBackup.encryptWithDerivedKey(original, keyB64, saltB64, fastIterations)
        assertEquals(original, FamilyBackup.decrypt(file, "correct horse".toCharArray()))
        assertNull(FamilyBackup.decrypt(file, "wrong horse".toCharArray()))
    }

    @Test
    fun `default iterations round-trip too`() {
        val original = payload()
        val file = FamilyBackup.encrypt(original, "real strength".toCharArray())
        assertEquals(original, FamilyBackup.decrypt(file, "real strength".toCharArray()))
    }
}
