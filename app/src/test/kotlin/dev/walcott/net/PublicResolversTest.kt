package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.net.InetAddress

/**
 * Every route handed to the tunnel builder is refused on its own if it is malformed, so a typo
 * here does not crash anything — it silently leaves one resolver outside the filter. These catch
 * that at build time instead, and the ranges that must never be routed at all.
 */
class PublicResolversTest {

    private val v4 = PublicResolvers.ROUTES.filter { DnsUpstreams.isIpv4Literal(it.address) }
    private val v6 = PublicResolvers.ROUTES.filter { DnsUpstreams.isIpv6Literal(it.address) }

    private fun octets(address: String): List<Int> = address.split('.').map { it.toInt() }

    /** Parsed, never resolved: [InetAddress.getByName] only looks a literal up when it is not one. */
    private fun bytes(address: String): ByteArray = InetAddress.getByName(address).address

    private fun covers(route: PublicResolvers.Route, address: String): Boolean {
        val network = BigInteger(1, bytes(route.address))
        val candidate = BigInteger(1, bytes(address))
        val bits = bytes(address).size * 8
        if (bytes(route.address).size * 8 != bits) return false
        return network.shiftRight(bits - route.prefix) == candidate.shiftRight(bits - route.prefix)
    }

    @Test
    fun `every route is a well-formed address of one family or the other`() {
        assertEquals(PublicResolvers.ROUTES.size, v4.size + v6.size)
        for (route in v4) {
            val parts = octets(route.address)
            assertEquals(4, parts.size, route.address)
            assertTrue(parts.all { it in 0..255 }, route.address)
            assertTrue(route.prefix in 16..32, "${route.address}/${route.prefix}")
        }
        for (route in v6) {
            assertEquals(16, bytes(route.address).size, route.address)
            // Never a broad prefix: everything routed here loses whatever is not DNS.
            assertTrue(route.prefix in 48..128, "${route.address}/${route.prefix}")
        }
    }

    @Test
    fun `a range route names the start of its range`() {
        // Builder.addRoute refuses an address with bits set past the prefix.
        for (route in PublicResolvers.ROUTES.filter { it.prefix < bytes(it.address).size * 8 }) {
            val value = BigInteger(1, bytes(route.address))
            val hostBits = bytes(route.address).size * 8 - route.prefix
            assertEquals(BigInteger.ZERO, value.mod(BigInteger.ONE.shiftLeft(hostBits)), "${route.address}/${route.prefix}")
        }
    }

    @Test
    fun `nothing private, local or the tunnel's own is routed`() {
        for (route in v4) {
            val (a, b) = octets(route.address)
            assertFalse(a == 10, route.address)
            assertFalse(a == 127, route.address)
            assertFalse(a == 172 && b in 16..31, route.address)
            assertFalse(a == 192 && b == 168, route.address)
            assertFalse(a == 169 && b == 254, route.address)
        }
        for (route in v6) {
            // Global unicast (2000::/3) only: never a unique local address like the tunnel's own,
            // link-local, loopback or multicast.
            assertEquals(0x20, bytes(route.address)[0].toInt() and 0xE0, route.address)
        }
    }

    @Test
    fun `no route is listed twice, however it is spelled`() {
        val distinct = PublicResolvers.ROUTES.map { BigInteger(1, bytes(it.address)) to it.prefix }.toSet()
        assertEquals(PublicResolvers.ROUTES.size, distinct.size)
    }

    @Test
    fun `the IPv6 twins of the best-known resolvers are routed`() {
        // An IPv4 route with no IPv6 twin is a way round the filter on every network with IPv6.
        listOf(
            "2001:4860:4860::8888", "2606:4700:4700::1111", "2606:4700:4700::1113", "2620:fe::fe",
            "2620:119:35::35", "2a10:50c0::bad1:ff", "2a0d:2a00:1::", "2a07:e340::2",
        ).forEach { address -> assertTrue(v6.any { covers(it, address) }, address) }
    }

    @Test
    fun `a per-family address is covered, and nothing else the provider runs is`() {
        // A NextDNS configuration's own IPv6 address, as its setup page shows one.
        assertTrue(v6.any { covers(it, "2a07:a8c1::af:1fd7") })
        // Control D's free resolvers are inside its documented DNS ranges...
        assertTrue(v6.any { covers(it, "2606:1a40::2") })
        assertTrue(v6.any { covers(it, "2606:1a40:1::2") })
        // ...and its proxy and its website, which share the /32, are not.
        assertFalse(v6.any { covers(it, "2606:1a40:2::1") })
        assertFalse(v6.any { covers(it, "2606:1a40:3::1") })
    }
}
