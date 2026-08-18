package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a family's address on a public relay may say about itself: nothing.
 *
 * The topic used to begin with "walcott-", which made every family's traffic identifiable as this
 * app's at a glance — and a relay operator's only means of acting on all of it at once. It is also
 * the family's bearer secret, so the bits matter as much as the silence.
 *
 * The generator lives in `SyncManager.newTopic` (Android-bound); what is pinned here is the shape
 * it has to produce, which is what a reviewer would otherwise have to take on trust.
 */
class TopicAnonymityTest {

    /** The same construction `newTopic` uses, so this test fails if that one drifts from it. */
    private fun topicLike(bytes: ByteArray) = FamilyCrypto.toB64(bytes)

    @Test
    fun `a topic says nothing about what created it`() {
        val topic = topicLike(ByteArray(16) { it.toByte() })
        assertFalse(topic.contains("walcott", ignoreCase = true))
        assertFalse(topic.contains("-", ignoreCase = true) && topic.startsWith("walcott"))
    }

    @Test
    fun `it is URL-safe, because it becomes a path segment on the relay`() {
        // base64url: a '+' or a '/' in a topic would be a different topic once encoded, or a path
        // separator — either way, a family talking to an address nobody else resolves the same.
        val topic = topicLike(ByteArray(16) { (it * 7 + 3).toByte() })
        assertTrue(topic.all { it.isLetterOrDigit() || it == '-' || it == '_' }, topic)
    }

    @Test
    fun `it carries the full 128 bits, not a printed UUID's worth`() {
        // The old form base64'd the TEXT of a UUID and kept 24 characters of it — about 60 bits of
        // entropy for something that is, on a public relay, the whole of the family's privacy.
        val topic = topicLike(ByteArray(16) { it.toByte() })
        assertEquals(22, topic.length, "16 bytes is 22 base64url characters without padding")
    }

    @Test
    fun `two families do not collide`() {
        val random = java.security.SecureRandom()
        val topics = (1..500).map { topicLike(ByteArray(16).also { b -> random.nextBytes(b) }) }
        assertEquals(topics.size, topics.toSet().size)
    }
}
