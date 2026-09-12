package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainFilterTest {

    private val blocked = setOf("youtube.com", "tiktok.com")

    private fun blocked(host: String, pkg: String? = null, rules: List<DomainAppRule> = emptyList()) =
        DomainFilter.isBlocked(host, pkg, blocked, rules)

    @Test
    fun `global blocklist blocks the domain and its subdomains`() {
        assertTrue(blocked("youtube.com"))
        assertTrue(blocked("www.youtube.com"))
        assertTrue(blocked("m.youtube.com"))
    }

    @Test
    fun `unrelated domains are allowed`() {
        assertFalse(blocked("example.com"))
        assertFalse(blocked("notyoutube.com")) // must not match by substring
    }

    @Test
    fun `matching is case and trailing-dot insensitive`() {
        assertTrue(blocked("WWW.YouTube.CoM."))
    }

    @Test
    fun `allow-only-from-app blocks other apps but allows the chosen one`() {
        val rules = listOf(DomainAppRule("youtube.com", "com.google.android.youtube", allowOnlyFromApp = true))
        assertFalse(DomainFilter.isBlocked("www.youtube.com", "com.google.android.youtube", emptySet(), rules))
        assertTrue(DomainFilter.isBlocked("www.youtube.com", "com.android.chrome", emptySet(), rules))
    }

    @Test
    fun `allow-only-from-app fails closed when the app is unknown`() {
        val rules = listOf(DomainAppRule("youtube.com", "com.google.android.youtube", allowOnlyFromApp = true))
        assertTrue(DomainFilter.isBlocked("www.youtube.com", null, emptySet(), rules))
    }

    @Test
    fun `block-in-app blocks only in that app`() {
        val rules = listOf(DomainAppRule("reddit.com", "com.android.chrome", allowOnlyFromApp = false))
        assertTrue(DomainFilter.isBlocked("reddit.com", "com.android.chrome", emptySet(), rules))
        assertFalse(DomainFilter.isBlocked("reddit.com", "com.other.app", emptySet(), rules))
    }

    @Test
    fun `block-in-app does not apply when app is unknown (falls back to global)`() {
        val rules = listOf(DomainAppRule("reddit.com", "com.android.chrome", allowOnlyFromApp = false))
        assertFalse(DomainFilter.isBlocked("reddit.com", null, emptySet(), rules))
    }

    @Test
    fun `allow-only takes precedence over a global block for the allowed app`() {
        val rules = listOf(DomainAppRule("youtube.com", "com.google.android.youtube", allowOnlyFromApp = true))
        // youtube.com is globally blocked, but the allowed app may reach it.
        assertFalse(DomainFilter.isBlocked("www.youtube.com", "com.google.android.youtube", blocked, rules))
        assertTrue(DomainFilter.isBlocked("www.youtube.com", "com.android.chrome", blocked, rules))
    }

    // ---- the phone's own connectivity probes --------------------------------------------

    /**
     * The failure this pins is silent and expensive: Android decides a Wi-Fi has no internet by
     * fetching a `generate_204`, and a list that refuses the probe host makes a child's phone
     * declare a working Wi-Fi dead and move to mobile data on the family's allowance.
     */
    @Test
    fun `no downloaded list can take away the phone's connectivity check`() {
        // A hostile list: every registrable domain the probes live under, which is what an
        // aggressive tracker list actually does to the vendor ones.
        val hostile = DomainMatcher.of(
            setOf("gstatic.com", "google.com", "android.com", "miui.com", "hicloud.com", "vivo.com.cn"),
        )
        BlocklistSource.CONNECTIVITY_CHECKS.forEach { probe ->
            assertFalse(
                DomainFilter.isBlocked(probe, null, DomainMatcher.EMPTY, hostile, emptyList()),
                "$probe was blocked by a list; this phone will decide its Wi-Fi is dead",
            )
        }
        // And the exemption is the probe host and nothing around it.
        assertTrue(DomainFilter.isBlocked("ads.gstatic.com", null, DomainMatcher.EMPTY, hostile, emptyList()))
        assertTrue(DomainFilter.isBlocked("dns.google.com", null, DomainMatcher.EMPTY, hostile, emptyList()))
        assertTrue(DomainFilter.isBlocked("tracking.miui.com", null, DomainMatcher.EMPTY, hostile, emptyList()))
    }

    @Test
    fun `a domain the family typed themselves still outranks the connectivity check`() {
        // Authorship comes first everywhere in this filter. A parent who blocks one of these has
        // said what they want, whatever it costs them.
        val typed = DomainMatcher.of(setOf("clients3.google.com"))
        assertTrue(
            DomainFilter.isBlocked("clients3.google.com", null, typed, DomainMatcher.EMPTY, emptyList()),
        )
    }

    @Test
    fun `a curfew still closes a probe for the app it has cut off`() {
        // The curfew is a judgement about the hour, decided above every domain rule. Exempting a
        // probe from it would hand a browser a resolvable name at one in the morning.
        val cutOff = setOf("com.android.chrome")
        assertTrue(
            DomainFilter.isBlocked(
                "www.google.com", "com.android.chrome", DomainMatcher.EMPTY, DomainMatcher.EMPTY,
                emptyList(), cutOff = cutOff,
            ),
        )
        assertFalse(
            DomainFilter.isBlocked(
                "www.google.com", "com.other.app", DomainMatcher.EMPTY, DomainMatcher.EMPTY,
                emptyList(), cutOff = cutOff,
            ),
        )
    }

    @Test
    fun `a per-app rule can still refuse a probe for one app`() {
        val rules = listOf(DomainAppRule("www.google.com", "com.some.app", allowOnlyFromApp = false))
        assertTrue(
            DomainFilter.isBlocked("www.google.com", "com.some.app", DomainMatcher.EMPTY, DomainMatcher.EMPTY, rules),
        )
        assertFalse(
            DomainFilter.isBlocked("www.google.com", "com.other.app", DomainMatcher.EMPTY, DomainMatcher.EMPTY, rules),
        )
    }

    @Test
    fun `the protected set stays small and holds only whole probe hosts`() {
        // A guard against this becoming a general exception list. A registrable domain here would
        // spare everything under it — sparing google.com would spare dns.google.com, which is
        // precisely what the bypass list exists to block.
        assertTrue(BlocklistSource.CONNECTIVITY_CHECKS.size <= 12, "the protected set is growing")
        BlocklistSource.CONNECTIVITY_CHECKS.forEach { probe ->
            assertTrue(probe.count { it == '.' } >= 2, "$probe is a registrable domain, not a probe host")
            assertTrue(probe == DomainMatcher.normalize(probe), "$probe is not normalised")
        }
    }
}
