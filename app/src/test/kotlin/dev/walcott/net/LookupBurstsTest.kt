package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One resolution is one lookup, however many questions it takes.
 *
 * The figure this protects is the one a parent reads: "blocked today". It was two to three times
 * the truth, because every `getaddrinfo` on Android asks A and AAAA in parallel and a browser adds
 * HTTPS beside them.
 */
class LookupBurstsTest {

    private val bursts = LookupBursts()

    @Test
    fun `the AAAA that comes with an A is the same lookup`() {
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_000))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_AAAA, 1_010))
    }

    @Test
    fun `a browser's third question is still the same lookup`() {
        assertTrue(bursts.beginsLookup("ads.example.com", "com.browser", LookupBursts.TYPE_A, 1_000))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.browser", LookupBursts.TYPE_AAAA, 1_005))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.browser", LookupBursts.TYPE_HTTPS, 1_020))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.browser", LookupBursts.TYPE_SVCB, 1_030))
    }

    @Test
    fun `a repeated record type is a retry, and a retry counts`() {
        // The whole reason this is not "ignore duplicates for two seconds". A blocked domain
        // something keeps trying is exactly what a parent wants to see.
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_000))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_AAAA, 1_010))
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_100))
        // And the new burst starts clean, so its own companion joins it rather than counting.
        assertFalse(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_AAAA, 1_110))
    }

    @Test
    fun `past the window it is a new lookup whatever the type`() {
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_000))
        assertTrue(
            bursts.beginsLookup(
                "ads.example.com", "com.app", LookupBursts.TYPE_AAAA, 1_000 + LookupBursts.WINDOW_MS + 1,
            ),
        )
    }

    @Test
    fun `two apps asking for the same name are two lookups`() {
        assertTrue(bursts.beginsLookup("ads.example.com", "com.one", LookupBursts.TYPE_A, 1_000))
        assertTrue(bursts.beginsLookup("ads.example.com", "com.two", LookupBursts.TYPE_A, 1_001))
        // And each keeps its own companion.
        assertFalse(bursts.beginsLookup("ads.example.com", "com.one", LookupBursts.TYPE_AAAA, 1_002))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.two", LookupBursts.TYPE_AAAA, 1_003))
    }

    @Test
    fun `two names are two lookups`() {
        assertTrue(bursts.beginsLookup("one.example.com", "com.app", LookupBursts.TYPE_A, 1_000))
        assertTrue(bursts.beginsLookup("two.example.com", "com.app", LookupBursts.TYPE_A, 1_001))
    }

    @Test
    fun `an unattributed lookup is tracked like any other`() {
        // Attribution is best-effort and off entirely on a family with no per-app rules, so the
        // common case on a quiet phone is a null package.
        assertTrue(bursts.beginsLookup("ads.example.com", null, LookupBursts.TYPE_A, 1_000))
        assertFalse(bursts.beginsLookup("ads.example.com", null, LookupBursts.TYPE_AAAA, 1_010))
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_011))
    }

    @Test
    fun `a type this build has no name for still groups, and still notices a repeat`() {
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", 99, 1_000))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_005))
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", 99, 1_010))
    }

    @Test
    fun `a question whose type could not be read groups like any other`() {
        // Every type this build has no name for shares one bucket, an unreadable one included —
        // so it opens a burst and takes its companions, and a second of its own reads as a retry.
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", null, 1_000))
        assertFalse(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_AAAA, 1_005))
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", null, 1_010))
    }

    @Test
    fun `more bursts than there are slots forgets the oldest rather than growing`() {
        val small = LookupBursts(slots = 2)
        assertTrue(small.beginsLookup("a.example.com", null, LookupBursts.TYPE_A, 1_000))
        assertTrue(small.beginsLookup("b.example.com", null, LookupBursts.TYPE_A, 1_001))
        assertTrue(small.beginsLookup("c.example.com", null, LookupBursts.TYPE_A, 1_002))
        // c took a slot; whichever it took, c's own companion still joins it.
        assertFalse(small.beginsLookup("c.example.com", null, LookupBursts.TYPE_AAAA, 1_003))
    }

    @Test
    fun `clearing forgets everything, so nothing spans two tunnels`() {
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_A, 1_000))
        bursts.clear()
        assertTrue(bursts.beginsLookup("ads.example.com", "com.app", LookupBursts.TYPE_AAAA, 1_010))
    }
}
