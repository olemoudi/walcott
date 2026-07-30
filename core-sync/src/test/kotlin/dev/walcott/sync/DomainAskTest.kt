package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The domains ask travels inside a plain request's text field, so that a parent on an older
 * build reads a sentence instead of receiving an empty request it can't act on. That makes the
 * format load-bearing in both directions: the parent's per-app blocking depends on getting the
 * package name back out exactly as it went in.
 */
class DomainAskTest {

    @Test
    fun `it round-trips`() {
        val text = DomainAsk.encode("Instagram", "com.instagram.android", listOf("graph.example.com", "cdn.example.com"))
        val parsed = DomainAsk.decode(text)!!
        assertEquals("Instagram", parsed.label)
        assertEquals("com.instagram.android", parsed.packageName)
        assertEquals(listOf("graph.example.com", "cdn.example.com"), parsed.domains)
    }

    @Test
    fun `it reads as a sentence for a parent whose app knows nothing about it`() {
        assertEquals(
            "Instagram (com.instagram.android): a.example.com, b.example.com",
            DomainAsk.encode("Instagram", "com.instagram.android", listOf("a.example.com", "b.example.com")),
        )
    }

    @Test
    fun `a label with brackets of its own still yields the right package`() {
        // App labels are arbitrary strings; parsing from the right is what keeps this honest.
        val parsed = DomainAsk.decode(
            DomainAsk.encode("Chrome (Beta)", "com.chrome.beta", listOf("ads.example.com")),
        )!!
        assertEquals("Chrome (Beta)", parsed.label)
        assertEquals("com.chrome.beta", parsed.packageName)
    }

    @Test
    fun `anything that is not one of these decodes to nothing rather than to nonsense`() {
        // Other ask kinds share the field. Mistaking "can I install Roblox" for a block list
        // would have the parent blocking domains they never chose.
        assertNull(DomainAsk.decode("Roblox"))
        assertNull(DomainAsk.decode("can I install Roblox?"))
        assertNull(DomainAsk.decode(""))
        assertNull(DomainAsk.decode("): "))
        assertNull(DomainAsk.decode("Label (com.pkg): "))
        // No " (" before the "): " — the package is where the label should be, so there is no
        // package to hand the parent's per-app rule.
        assertNull(DomainAsk.decode("Label): a.com"))
        assertNull(DomainAsk.decode(" (com.pkg): a.com"))
        // An empty package between the brackets: a rule scoped to nothing at all.
        assertNull(DomainAsk.decode("Label (): a.com"))
        // A bracket inside the "package" means the parse latched onto the wrong one — a real
        // package name has none, so this is a mis-read rather than an exotic package.
        assertNull(DomainAsk.decode("Label (com.p(kg): a.com"))
    }

    @Test
    fun `a single domain works and stray spacing is tolerated`() {
        val parsed = DomainAsk.decode("Games (com.game):  a.example.com ,, b.example.com ")!!
        assertEquals(listOf("a.example.com", "b.example.com"), parsed.domains)
    }
}
