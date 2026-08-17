package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlocklistSourceTest {

    private fun domains(text: String): List<String> =
        text.lineSequence().mapNotNull { BlocklistSource.domainOf(it) }.toList()

    @Test
    fun `reads a hosts file, whatever it points the names at`() {
        val text = """
            # Title: something
            0.0.0.0 ads.example.com
            127.0.0.1	tracker.example.org
            0.0.0.0 sub.domain.example.net # trailing comment
        """.trimIndent()
        assertEquals(
            listOf("ads.example.com", "tracker.example.org", "sub.domain.example.net"),
            domains(text),
        )
    }

    @Test
    fun `reads a plain domain list, wildcards and all`() {
        // What oisd and hagezi serve: one domain per line, sometimes with a wildcard in front.
        val text = """
            ! a comment in the other style
            example.com
            *.wildcard.example
              spaced.example
        """.trimIndent()
        assertEquals(listOf("example.com", "wildcard.example", "spaced.example"), domains(text))
    }

    @Test
    fun `an error page does not become a filter`() {
        // GitHub answers 429 with prose, and this used to be the shape of the disaster: a page of
        // English becoming rules. The store refuses the result on top of this, by size.
        val text = """
            429: Too Many Requests
            For more on scraping GitHub and how it may affect your rights, please review our
            Terms of Service (https://docs.github.com/en/site-policy/github-terms/)
            <!DOCTYPE html>
            <html lang="en"><head><title>Not found</title></head></html>
        """.trimIndent()
        // Nothing that survives is a rule anybody would notice: no bare domain appears on a line
        // of its own, and github.com is spared outright.
        assertTrue(domains(text).isEmpty(), "parsed ${domains(text)}")
    }

    @Test
    fun `things that are not domains are dropped rather than guessed at`() {
        assertNull(BlocklistSource.domainOf(""))
        assertNull(BlocklistSource.domainOf("   "))
        assertNull(BlocklistSource.domainOf("# just a comment"))
        assertNull(BlocklistSource.domainOf("localhost"))
        assertNull(BlocklistSource.domainOf("0.0.0.0"))
        assertNull(BlocklistSource.domainOf("192.168.1.1"))
        assertNull(BlocklistSource.domainOf("::1 localhost"))
        assertNull(BlocklistSource.domainOf("three fields here.com now"))
        assertNull(BlocklistSource.domainOf("españa.example"))
        assertNull(BlocklistSource.domainOf("a".repeat(300) + ".com"))
    }

    @Test
    fun `never-block wins over whatever a source says`() {
        // The scenario this exists for: a public list ships a release with WhatsApp on it, and
        // every child in the family loses the channel their parents talk to them on.
        assertNull(BlocklistSource.domainOf("0.0.0.0 whatsapp.com"))
        assertNull(BlocklistSource.domainOf("0.0.0.0 mmg.whatsapp.net"))
        assertNull(BlocklistSource.domainOf("play.google.com"))
        assertNull(BlocklistSource.domainOf("firebaseinstallations.googleapis.com"))
        assertNull(BlocklistSource.domainOf("raw.githubusercontent.com"))
        assertTrue(BlocklistSource.isSpared("a.b.whatsapp.net"))
        assertFalse(BlocklistSource.isSpared("whatsapp.com.phishing.example"))
    }

    @Test
    fun `an encrypted resolver under google is still blockable`() {
        // The reason NEVER_BLOCK holds play.google.com and not google.com: dns.google.com is a
        // DoH endpoint, and sparing all of google.com would spare it with everything else.
        assertEquals("dns.google.com", BlocklistSource.domainOf("dns.google.com"))
        assertEquals("dns.google", BlocklistSource.domainOf("0.0.0.0 dns.google"))
    }

    @Test
    fun `normalisation happens once, here, so the cache is canonical`() {
        assertEquals("example.com", BlocklistSource.domainOf("0.0.0.0 EXAMPLE.com."))
        assertEquals("example.com", BlocklistSource.domainOf("https://example.com/path"))
    }
}
