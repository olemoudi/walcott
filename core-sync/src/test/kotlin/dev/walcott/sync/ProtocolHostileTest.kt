package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.crypto.SecretKey

/**
 * What [SyncProtocol.decodeVerbose] does with messages that are malformed, truncated, mislabelled
 * or forged.
 *
 * Every case here has the same required answer: return null. The channel is a public ntfy topic,
 * so anything at all can arrive, and the decode happens inside the child's sync loop — an
 * exception escaping here is a crash-restart loop on a device nobody can reach. "Refuses" and
 * "throws" look identical from a round-trip test, which is why these are written against the
 * broken shapes rather than the working one.
 */
class ProtocolHostileTest {

    private val familyKey = FamilyCrypto.generateFamilyKey()
    private val parent = FamilyCrypto.generateSigningKeyPair()

    /** An envelope carrying [plaintext] encrypted under [key], with the fields written by hand. */
    private fun envelope(
        kind: String,
        plaintext: ByteArray,
        signature: String? = null,
        key: SecretKey = familyKey,
    ): String {
        val ciphertext = FamilyCrypto.toB64(FamilyCrypto.encrypt(key, plaintext))
        val sig = signature?.let { "\"$it\"" } ?: "null"
        return """{"kind":"$kind","senderId":"d","version":1,"ciphertext":"$ciphertext","signature":$sig}"""
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun decode(wire: String) = SyncProtocol.decode(wire, familyKey, parent.public)

    // --- the envelope's own fields ---

    @Test
    fun `a ciphertext that isn't base64 is refused`() {
        val wire = """{"kind":"child","senderId":"d","version":1,"ciphertext":"not base64!!","signature":null}"""
        assertNull(decode(wire))
    }

    @Test
    fun `an envelope of an unknown kind is refused`() {
        // A newer build's message type, or a probe. Either way this build has no business
        // guessing at the body.
        val body = gzip("""{"deviceId":"d","displayName":"p","version":1,"epochDay":1}""".toByteArray())
        assertNull(decode(envelope("something-new", body)))
        assertNull(decode(envelope("", body)))
    }

    // --- the parent signature, which is the only thing standing between a topic and the rules ---

    @Test
    fun `a parent envelope with no signature at all is refused`() {
        // The family key alone must never be enough to push rules: it lives on every child
        // device, so a leaked one would otherwise let a child rewrite its own limits.
        val body = gzip("""{"version":99,"policyJson":"{}"}""".toByteArray())
        assertNull(decode(envelope("parent", body, signature = null)))
    }

    @Test
    fun `a parent envelope whose signature isn't base64 is refused`() {
        val body = gzip("""{"version":99,"policyJson":"{}"}""".toByteArray())
        assertNull(decode(envelope("parent", body, signature = "%%%not base64%%%")))
    }

    @Test
    fun `relabelling a signed parent message as a child message does not get it past the checks`() {
        // The kind field decides whether a signature is demanded, and it is not itself signed.
        // Relabelling skips the signature check — and then has to parse as a ChildSnapshot,
        // which a policy payload will not. Both directions of the confusion are closed.
        val signed = SyncProtocol.encodeParent(ParentSnapshot(version = 5, policyJson = "{}"), familyKey, parent.private)
        assertNull(decode(signed.replace("\"kind\":\"parent\"", "\"kind\":\"child\"")))

        val child = SyncProtocol.encodeChild(ChildSnapshot("d", "phone", 1, 1), familyKey)
        assertNull(decode(child.replace("\"kind\":\"child\"", "\"kind\":\"parent\"")))
    }

    // --- the payload, once it has been decrypted ---

    @Test
    fun `a payload that decrypts to something that isn't JSON is refused`() {
        assertNull(decode(envelope("child", "this is not JSON at all".toByteArray())))
    }

    @Test
    fun `a payload that claims to be gzip but is corrupt is refused`() {
        // The magic bytes are a hint from an untrusted sender, not a guarantee. Without the
        // guard the inflater throws straight into the sync loop.
        val fakeGzip = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0, 0, 0, 0, 0, 0, 0) + ByteArray(40) { 0x41 }
        assertNull(decode(envelope("child", fakeGzip)))
    }

    @Test
    fun `a payload truncated below the gzip magic is refused rather than read past its end`() {
        assertNull(decode(envelope("child", byteArrayOf(0x1f))))
        assertNull(decode(envelope("child", ByteArray(0))))
    }

    @Test
    fun `a payload that only half-matches the gzip magic is treated as plain, not inflated`() {
        // First byte matches, second doesn't. Reading it as plain text is right; the JSON parse
        // is then what rejects it, quietly.
        assertNull(decode(envelope("child", byteArrayOf(0x1f, 0x7b, 0x7d))))
    }

    // --- a well-formed body of the wrong shape, one per message kind ---

    @Test
    fun `a child envelope carrying a parent body is refused`() {
        val parentBody = gzip("""{"version":3,"policyJson":"{}"}""".toByteArray())
        assertNull(decode(envelope("child", parentBody)))
    }

    @Test
    fun `a parent envelope carrying a child body is refused even when properly signed`() {
        // Signed by the real parent key, so this is not a forgery — it is a parent build sending
        // something this child cannot read. It still must not be applied as rules.
        val childBody = gzip("""{"deviceId":"d","displayName":"p","version":1,"epochDay":1}""".toByteArray())
        val ciphertext = FamilyCrypto.encrypt(familyKey, childBody)
        val sig = FamilyCrypto.toB64(FamilyCrypto.sign(parent.private, ciphertext))
        val wire = """{"kind":"parent","senderId":"p","version":1,""" +
            """"ciphertext":"${FamilyCrypto.toB64(ciphertext)}","signature":"$sig"}"""
        assertNull(decode(wire))
    }

    @Test
    fun `an icons envelope with an unreadable body is refused`() {
        assertNull(decode(envelope("icons", gzip("""{"nope":1}""".toByteArray()))))
    }

    @Test
    fun `a diag envelope with an unreadable body is refused`() {
        assertNull(decode(envelope("diag", gzip("""{"nope":1}""".toByteArray()))))
    }

    // --- and the shapes that must still get through ---

    @Test
    fun `a valid message of every kind still decodes`() {
        // The guard rail above only means something if it isn't refusing everything.
        val child = SyncProtocol.encodeChild(ChildSnapshot("d", "phone", 1, 1), familyKey)
        assertInstanceOf(IncomingMessage.FromChild::class.java, decode(child))

        val parentMsg = SyncProtocol.encodeParent(ParentSnapshot(version = 1, policyJson = "{}"), familyKey, parent.private)
        assertInstanceOf(IncomingMessage.FromParent::class.java, decode(parentMsg))

        val icons = SyncProtocol.encodeChildIcons(IconPayload("d", listOf(AppIconData("com.a", "AAAA"))), familyKey)
        assertInstanceOf(IncomingMessage.FromChildIcons::class.java, decode(icons))

        val diag = SyncProtocol.encodeChildDiag(DiagPayload(deviceId = "d", atMs = 1), familyKey)
        assertInstanceOf(IncomingMessage.FromChildDiag::class.java, decode(diag))
    }

    @Test
    fun `the two directions are authenticated differently, on purpose`() {
        // Pinned so the asymmetry stays a decision instead of decaying into an accident, in
        // either direction. See the note on SyncProtocol for why child messages carry no
        // per-child signature.
        //
        // Parent -> child: a signature is mandatory. Forging here would be a child writing its
        // own rules, so it must stay impossible for anyone without the parent's private key.
        val unsignedParent = envelope("parent", gzip("""{"version":1,"policyJson":"{}"}""".toByteArray()))
        assertNull(decode(unsignedParent))

        // Child -> parent: no signature, and it still decodes. Anyone with the family key can
        // send this — accepted, because a child message only ever informs the parent, and the
        // family key needs root on a managed device to reach.
        val unsignedChild = envelope("child", gzip("""{"deviceId":"d","displayName":"p","version":1,"epochDay":1}""".toByteArray()))
        assertInstanceOf(IncomingMessage.FromChild::class.java, decode(unsignedChild))
    }

    @Test
    fun `a clean decode reports no key rotation`() {
        val wire = SyncProtocol.encodeChild(ChildSnapshot("d", "phone", 1, 1), familyKey)
        val decoded = SyncProtocol.decodeVerbose(wire, familyKey, parent.public)!!
        assertNull(decoded.rotatedParentPublicKeyB64) { "a rotation must never be inferred from an ordinary message" }
        assertEquals(IncomingMessage.FromChild(ChildSnapshot("d", "phone", 1, 1)), decoded.message)
    }
}
