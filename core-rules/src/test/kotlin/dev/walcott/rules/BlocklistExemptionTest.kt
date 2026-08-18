package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Waiving the blocklists for one app, without waiving the rules the family wrote.
 *
 * The failure it answers is specific: a published list of half a million domains carries one that
 * some app needs, the app breaks in a way that looks nothing like a filter, and there is no
 * realistic way for a family to find the entry. Naming the app is the fix that does not require
 * finding it — but it has to stay a waiver of the LISTS, because everything else in the filter is
 * something a person chose on purpose and would not expect an app to be able to shrug off.
 */
class BlocklistExemptionTest {

    /** What a person typed: two domains, blocked everywhere. */
    private val family = DomainMatcher.of(setOf("tiktok.com"))

    /** What a list decided: the bulk half, hashed exactly as the real filter compiles it. */
    private val lists = DomainMatcher.builder(setOf("ads.example.com"))
        .apply { add("cdn.somebank.example") }
        .build()

    private val bank = "com.example.bank"

    private fun blocked(
        host: String,
        pkg: String?,
        exempt: Set<String> = emptySet(),
        rules: List<DomainAppRule> = emptyList(),
    ) = DomainFilter.isBlocked(host, pkg, family, lists, rules, exempt)

    @Test
    fun `without an exemption the lists apply to everybody`() {
        assertTrue(blocked("cdn.somebank.example", bank))
        assertTrue(blocked("ads.example.com", bank))
    }

    @Test
    fun `an exempt app is not judged by the lists`() {
        assertFalse(blocked("cdn.somebank.example", bank, exempt = setOf(bank)))
        // Including the subdomains of what a list carries — the app is out, not one host of it.
        assertFalse(blocked("images.cdn.somebank.example", bank, exempt = setOf(bank)))
    }

    @Test
    fun `the exemption is for that app alone`() {
        assertTrue(blocked("cdn.somebank.example", "com.other.app", exempt = setOf(bank)))
    }

    @Test
    fun `the family's own domains still apply to an exempt app`() {
        // The whole point of the split: this is a rule somebody chose, and an app must not be
        // able to shrug it off by being on a list of exceptions to something else.
        assertTrue(blocked("tiktok.com", bank, exempt = setOf(bank)))
        assertTrue(blocked("www.tiktok.com", bank, exempt = setOf(bank)))
    }

    @Test
    fun `per-app rules still apply to an exempt app`() {
        val blockHere = listOf(DomainAppRule("news.example.com", bank, allowOnlyFromApp = false))
        assertTrue(blocked("news.example.com", bank, exempt = setOf(bank), rules = blockHere))

        // And the other shape: a domain restricted to one app stays restricted, exemption or not.
        val onlyElsewhere = listOf(DomainAppRule("intranet.example", "com.other.app", allowOnlyFromApp = true))
        assertTrue(blocked("intranet.example", bank, exempt = setOf(bank), rules = onlyElsewhere))
    }

    @Test
    fun `a lookup nobody could attribute is never exempt`() {
        // Attribution is best-effort (see WalcottVpnService.ownerPackage). An unattributed query
        // is judged by the lists as usual — the same fail-closed rule allow-only-from-app follows,
        // and the alternative would be an exemption that quietly waives the lists for everything.
        assertTrue(blocked("cdn.somebank.example", null, exempt = setOf(bank)))
    }

    @Test
    fun `exempting an app changes nothing about what it is allowed anyway`() {
        assertFalse(blocked("example.org", bank, exempt = setOf(bank)))
        assertFalse(blocked("example.org", bank))
    }
}
