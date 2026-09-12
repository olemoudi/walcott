package dev.walcott.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every route handed to the tunnel builder is refused on its own if it is malformed, so a typo
 * here does not crash anything — it silently leaves one resolver outside the filter. These catch
 * that at build time instead, and the ranges that must never be routed at all.
 */
class PublicResolversTest {

    private fun octets(address: String): List<Int> = address.split('.').map { it.toInt() }

    @Test
    fun `every route is a well-formed IPv4 address`() {
        for (route in PublicResolvers.ROUTES) {
            val parts = address(route)
            assertEquals(4, parts.size, route.address)
            assertTrue(parts.all { it in 0..255 }, route.address)
            assertTrue(route.prefix in 16..32, "${route.address}/${route.prefix}")
        }
    }

    private fun address(route: PublicResolvers.Route) = octets(route.address)

    @Test
    fun `a range route names the start of its range`() {
        // Builder.addRoute refuses an address with bits set past the prefix.
        for (route in PublicResolvers.ROUTES.filter { it.prefix < 32 }) {
            val value = address(route).fold(0L) { acc, octet -> acc * 256 + octet }
            val hostBits = 32 - route.prefix
            assertEquals(0L, value % (1L shl hostBits), "${route.address}/${route.prefix}")
        }
    }

    @Test
    fun `nothing private, local or the tunnel's own is routed`() {
        for (route in PublicResolvers.ROUTES) {
            val (a, b) = address(route)
            assertFalse(a == 10, route.address)
            assertFalse(a == 127, route.address)
            assertFalse(a == 172 && b in 16..31, route.address)
            assertFalse(a == 192 && b == 168, route.address)
            assertFalse(a == 169 && b == 254, route.address)
        }
    }

    @Test
    fun `no route is listed twice`() {
        assertEquals(PublicResolvers.ROUTES.size, PublicResolvers.ROUTES.toSet().size)
    }
}
