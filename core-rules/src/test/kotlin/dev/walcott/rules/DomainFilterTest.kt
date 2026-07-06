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
}
