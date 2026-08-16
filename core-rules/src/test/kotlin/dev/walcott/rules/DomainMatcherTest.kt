package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainMatcherTest {

    private val matcher = DomainMatcher.of(listOf("youtube.com", "ads.example.co.uk"))

    @Test
    fun `matches the domain and every subdomain of it`() {
        assertTrue(matcher.matches("youtube.com"))
        assertTrue(matcher.matches("www.youtube.com"))
        assertTrue(matcher.matches("a.b.c.youtube.com"))
        assertTrue(matcher.matches("x.ads.example.co.uk"))
    }

    @Test
    fun `does not match by substring or by suffix inside a label`() {
        assertFalse(matcher.matches("notyoutube.com"))
        assertFalse(matcher.matches("youtube.company"))
        assertFalse(matcher.matches("example.co.uk"))
    }

    @Test
    fun `host normalisation covers case, trailing dots, schemes and paths`() {
        assertTrue(matcher.matches("WWW.YouTube.CoM."))
        assertTrue(matcher.matches("https://www.youtube.com/watch?v=1"))
        assertTrue(matcher.matches("youtube.com:443"))
    }

    @Test
    fun `rule normalisation accepts what a parent is likely to paste`() {
        val pasted = DomainMatcher.of(listOf(" HTTPS://*.Example.COM/path ", "other.com."))
        assertTrue(pasted.matches("a.example.com"))
        assertTrue(pasted.matches("other.com"))
        assertEquals(2, pasted.size)
    }

    @Test
    fun `blank and duplicate rules collapse`() {
        val m = DomainMatcher.of(listOf("example.com", "EXAMPLE.COM.", "  ", ""))
        assertEquals(1, m.size)
        assertTrue(m.matches("example.com"))
    }

    @Test
    fun `an empty matcher matches nothing`() {
        assertTrue(DomainMatcher.EMPTY.isEmpty)
        assertFalse(DomainMatcher.EMPTY.matches("anything.com"))
        assertFalse(DomainMatcher.of(emptyList()).matches("anything.com"))
    }

    @Test
    fun `a big list still answers by walking the host, not the list`() {
        // Not a benchmark — the guarantee is behavioural: a host with three labels is three
        // lookups whether the list holds two domains or ten thousand.
        val many = (1..10_000).map { "site$it.example" } + "target.com"
        val big = DomainMatcher.of(many)
        assertTrue(big.matches("deep.sub.target.com"))
        assertFalse(big.matches("deep.sub.other.com"))
    }
}
