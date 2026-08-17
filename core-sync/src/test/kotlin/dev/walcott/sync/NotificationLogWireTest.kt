package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The notification log as it crosses the wire: what a request means, and what an answer promises.
 *
 * Both halves have a failure mode that is silent rather than loud. A query misread does not throw,
 * it answers a different question — "everything from yesterday" instead of "this one app". And a
 * page trimmed at the wrong end, or trimmed without saying so, produces a screen that answers "did
 * it arrive?" with a confident no.
 */
class NotificationLogWireTest {

    // --- The request ---

    @Test
    fun `an empty query means every app, starting now`() {
        val query = NotificationQuery.decode(NotificationQuery.encode())
        assertEquals("", query.pkg)
        assertEquals(0L, query.beforeMs)
    }

    @Test
    fun `a query survives a round trip in both halves`() {
        val encoded = NotificationQuery.encode(pkg = "com.whatsapp", beforeMs = 1_726_000_000_000)
        val query = NotificationQuery.decode(encoded)
        assertEquals("com.whatsapp", query.pkg)
        assertEquals(1_726_000_000_000, query.beforeMs)
    }

    @Test
    fun `a bare number still reads as a page cursor`() {
        // What the first cut of this sent, before the per-app query existed. A newer child must
        // keep understanding an older parent, or paging silently restarts at "now" every time.
        val query = NotificationQuery.decode("1726000000000")
        assertEquals(1_726_000_000_000, query.beforeMs)
        assertEquals("", query.pkg)
    }

    @Test
    fun `a key this build has never heard of is skipped, not misread as the next one`() {
        // The whole reason for `k=v;k=v` rather than positional fields: a newer parent adding a
        // parameter must not shift the meaning of the ones an older child does understand.
        val query = NotificationQuery.decode("pkg=com.bank;something=42;before=99")
        assertEquals("com.bank", query.pkg)
        assertEquals(99L, query.beforeMs)
    }

    @Test
    fun `nonsense decodes to the safest question rather than throwing`() {
        val query = NotificationQuery.decode(";;=;garbage")
        assertEquals("", query.pkg)
        assertEquals(0L, query.beforeMs)
    }

    // --- The answer ---

    private val key = FamilyCrypto.generateFamilyKey()
    private val signing = FamilyCrypto.generateSigningKeyPair()

    private fun entries(count: Int, textChars: Int = 200): List<NotificationEntry> =
        (0 until count).map {
            NotificationEntry(
                // Newest first, which is the order the device reads them in and the screen shows.
                atMs = 2_000_000L - it,
                pkg = "com.example.app$it",
                title = "Title $it",
                text = "x".repeat(textChars),
            )
        }

    private fun decode(encoded: String): NotificationPayload {
        val message = SyncProtocol.decode(encoded, key, signing.public)
        assertNotNull(message, "a notification page must decode with the family key")
        assertTrue(message is IncomingMessage.FromChildNotifications, "wrong message kind: $message")
        return (message as IncomingMessage.FromChildNotifications).payload
    }

    @Test
    fun `a small page travels whole, with the oldest entry as the cursor`() {
        val all = entries(5)
        val encoded = NotificationFit.encode(
            NotificationPayload(deviceId = "d1", atMs = 1, entries = all, total = 5),
            key,
        )
        val page = decode(encoded)
        assertEquals(5, page.entries.size)
        assertEquals(all.last().atMs, page.oldestAtMs, "the cursor must be the OLDEST entry sent")
        assertEquals(5, page.total)
    }

    @Test
    fun `a page too big is trimmed from the oldest end and fits`() {
        val all = entries(400)
        val encoded = NotificationFit.encode(
            NotificationPayload(deviceId = "d1", atMs = 1, entries = all, total = all.size),
            key,
        )
        assertTrue(
            encoded.length <= SnapshotFit.MAX_BYTES,
            "a trimmed page still did not fit: ${encoded.length} bytes",
        )
        val page = decode(encoded)
        assertTrue(page.entries.size < all.size, "nothing was trimmed from a page that could not fit")
        assertTrue(page.entries.isNotEmpty(), "trimming took everything")
        // The right end: the newest survive, because that is what the parent is reading and the
        // cursor lets them ask for the rest.
        assertEquals(all.first().atMs, page.entries.first().atMs, "the NEWEST entry was dropped")
        assertEquals(page.entries.last().atMs, page.oldestAtMs)
    }

    @Test
    fun `a trimmed page still says how much it is not showing`() {
        // The difference between "the 40 most recent of 137" and a family believing 40 was all
        // that arrived yesterday — which is a wrong answer to the only question they asked.
        val all = entries(400)
        val page = decode(
            NotificationFit.encode(
                NotificationPayload(deviceId = "d1", atMs = 1, entries = all, total = all.size),
                key,
            ),
        )
        assertEquals(all.size, page.total, "total must be what the device HAS, not what fitted")
        assertTrue(page.total > page.entries.size)
    }

    @Test
    fun `an answer with nothing in it still carries why`() {
        // The two states that must never look like a quiet day.
        val notEnabled = decode(
            NotificationFit.encode(
                NotificationPayload(deviceId = "d1", atMs = 1, notEnabled = true),
                key,
            ),
        )
        assertTrue(notEnabled.notEnabled)
        assertEquals(0, notEnabled.oldestAtMs, "no entries means no cursor to page back from")

        val noAccess = decode(
            NotificationFit.encode(
                NotificationPayload(deviceId = "d1", atMs = 1, noAccess = true),
                key,
            ),
        )
        assertTrue(noAccess.noAccess)
    }

    @Test
    fun `a per-app page names the app it answers for`() {
        // Carried back rather than assumed: the parent keeps pages per device, and a page about
        // one app folded into the list that claims to be everything would answer "what arrived
        // yesterday?" with a filtered subset.
        val page = decode(
            NotificationFit.encode(
                NotificationPayload(
                    deviceId = "d1",
                    atMs = 1,
                    entries = entries(3),
                    total = 3,
                    pkg = "com.bank",
                ),
                key,
            ),
        )
        assertEquals("com.bank", page.pkg)
    }
}
