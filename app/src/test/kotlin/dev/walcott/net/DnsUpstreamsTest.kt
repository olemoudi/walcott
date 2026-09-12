package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsUpstreamsTest {

    @Test
    fun `the network's own resolvers come first, the public one last`() {
        assertEquals(
            listOf("192.168.1.1", "8.8.8.8", DnsUpstreams.FALLBACK),
            DnsUpstreams.choose(listOf("192.168.1.1", "8.8.8.8")),
            "the cap keeps the network's own and the first last resort",
        )
    }

    @Test
    fun `a network offering nothing usable falls back, in both address families`() {
        // Both families, because one IPv4 literal was the whole last resort: an IPv6-only mobile
        // network with no translator then had nothing this filter could open a socket to, and
        // every lookup on the phone timed out while the filter reported itself healthy.
        assertEquals(DnsUpstreams.FALLBACKS, DnsUpstreams.choose(emptyList()))
        assertTrue(DnsUpstreams.FALLBACKS.any { DnsUpstreams.isIpv6Literal(it) }, "no IPv6 last resort")
        assertTrue(DnsUpstreams.FALLBACKS.any { DnsUpstreams.isIpv4Literal(it) }, "no IPv4 last resort")
        // Only link-local ones, which cannot be reached without the zone id: nothing usable.
        assertEquals(
            DnsUpstreams.FALLBACKS,
            DnsUpstreams.choose(listOf("fe80::1%wlan0", "169.254.3.4")),
        )
    }

    @Test
    fun `an IPv6 resolver is a resolver`() {
        // The tunnel is IPv4 and where an allowed query is FORWARDED has nothing to do with
        // that. Refusing these left a carrier that offers only IPv6 resolvers with the public
        // fallback alone — and, with no translator on the network, no working DNS at all.
        assertEquals(
            listOf("2001:4860:4860::8888") + DnsUpstreams.FALLBACKS,
            DnsUpstreams.choose(listOf("2001:4860:4860::8888")),
        )
        // The zone id goes; the address stays. And it is already a last resort, so it does not
        // appear twice.
        assertEquals(
            listOf("2606:4700:4700::1111", DnsUpstreams.FALLBACK),
            DnsUpstreams.choose(listOf("2606:4700:4700::1111%rmnet0")),
        )
    }

    @Test
    fun `our own tun addresses are never used as an upstream`() {
        // Forwarding to the sentinel would send the query back into our own tunnel.
        assertEquals(
            listOf("192.168.1.1") + DnsUpstreams.FALLBACKS,
            DnsUpstreams.choose(
                listOf("10.111.222.2", "192.168.1.1", "10.111.222.1"),
                exclude = setOf("10.111.222.1", "10.111.222.2"),
            ),
        )
    }

    @Test
    fun `duplicates collapse and the list is capped`() {
        assertEquals(
            listOf("1.1.1.1", "2606:4700:4700::1111"),
            DnsUpstreams.choose(listOf("1.1.1.1", "1.1.1.1")),
        )
        assertEquals(
            DnsUpstreams.MAX_UPSTREAMS,
            DnsUpstreams.choose(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4")).size,
        )
    }

    @Test
    fun `only IPv4 literals are accepted`() {
        listOf("1.1.1.1", "0.0.0.0", "255.255.255.255", "192.168.1.10").forEach {
            assertTrue(DnsUpstreams.isIpv4Literal(it), it)
        }
        // A hostname would be resolved by InetAddress.getByName — a blocking lookup, made
        // through the very filter that is trying to forward this query.
        listOf(
            "dns.google", "", "1.1.1", "1.1.1.1.1", "256.1.1.1", "1.1.1.-1",
            "1.1.1.a", "::1", "1.1.1.", ".1.1.1", "1.1.1.0001",
        ).forEach {
            assertFalse(DnsUpstreams.isIpv4Literal(it), it)
        }
    }

    @Test
    fun `a hostname is never an upstream, in either family`() {
        // Everything accepted here reaches InetAddress.getByName, which for a name is a
        // blocking lookup — made through the very filter that is trying to forward this query.
        listOf("dns.google", "", "example.com", "1.1.1", "1.1.1.1.1", "256.1.1.1")
            .forEach { assertFalse(DnsUpstreams.isIpLiteral(it), it) }
        listOf("2001:4860:4860::8888", "::1", "::", "fe80::1", "::ffff:1.2.3.4")
            .forEach { assertTrue(DnsUpstreams.isIpLiteral(it), it) }
    }

    @Test
    fun `an address that only means something on its own wire is left out`() {
        assertTrue(DnsUpstreams.isLinkLocal("fe80::1"))
        assertTrue(DnsUpstreams.isLinkLocal("FE80::abcd"))
        assertTrue(DnsUpstreams.isLinkLocal("169.254.10.1"))
        assertFalse(DnsUpstreams.isLinkLocal("2001:4860:4860::8888"))
        assertFalse(DnsUpstreams.isLinkLocal("192.168.1.1"))
        assertFalse(DnsUpstreams.isLinkLocal("fee1::1"), "not every fe- prefix is link-local")
    }
}
