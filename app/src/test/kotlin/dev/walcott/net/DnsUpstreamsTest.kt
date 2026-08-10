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
        )
    }

    @Test
    fun `a network offering nothing usable falls back`() {
        assertEquals(listOf(DnsUpstreams.FALLBACK), DnsUpstreams.choose(emptyList()))
        // IPv6-only resolvers: we speak IPv4 in the tun, so they are not usable here.
        assertEquals(
            listOf(DnsUpstreams.FALLBACK),
            DnsUpstreams.choose(listOf("2001:4860:4860::8888", "fe80::1%wlan0")),
        )
    }

    @Test
    fun `our own tun addresses are never used as an upstream`() {
        // Forwarding to the sentinel would send the query back into our own tunnel.
        assertEquals(
            listOf("192.168.1.1", DnsUpstreams.FALLBACK),
            DnsUpstreams.choose(
                listOf("10.111.222.2", "192.168.1.1", "10.111.222.1"),
                exclude = setOf("10.111.222.1", "10.111.222.2"),
            ),
        )
    }

    @Test
    fun `duplicates collapse and the list is capped`() {
        assertEquals(
            listOf("1.1.1.1"),
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
}
