package dev.walcott.sim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the web filter is expected on this phone, and whether it is actually running.
 *
 * The two flags are a pair and only mean anything together: a family with no domains blocked has
 * no tunnel to miss, and a family that blocked some has a phone resolving them normally the
 * moment the tunnel is not up. Between them they raise the card that tells a parent the filter
 * they are relying on is not filtering.
 *
 * Only the harmless half had a scenario before this one — a family with no filter not reporting
 * one as missing, which is true of a device that does nothing at all. What is asserted here is
 * the half a family actually depends on: rules arrive, a real VpnService establish happens on a
 * real (emulated) kernel, and the phone says so.
 *
 * The remaining gap, and it is a real one: "expected but genuinely down". It cannot be arranged
 * from adb on a device that is Device Owner. Taking ACTIVATE_VPN away with `appops` does not
 * refuse the tunnel — verified on the image this suite runs against, where the app logs "DNS
 * tunnel established" with the op set to `ignore` — because a Device Owner's VPN needs no
 * consent, which is the whole reason enforcement can rely on it. Reproducing it needs a second
 * VPN app to win the slot, or an OEM that kills the service; neither is an emulator away.
 */
class WebFilterScenarioTest : DeviceScenario() {

    @Test
    fun `blocking domains brings a real tunnel up, and withdrawing them takes it away again`() {
        // The baseline this moves away from: nothing asks for a filter, so nothing is missing.
        assertEquals(
            false,
            childReports { it.enforcement.isNotBlank() }.webFilterExpected,
            "nothing is blocked yet and the child already expects a filter",
        )

        parent.pushPolicy(filterPolicy(version = 2))
        // Both flags, but NOT necessarily in the same breath, and that is worth knowing:
        // `webFilterExpected` flips as soon as the rules land, while the tunnel takes a few
        // seconds more — and until it is up `webFilterOn` reads false, because VpnStatus has
        // been counting "down" since the process started. So the wait is for the state that is
        // meant to LAST. The transient in between is a brief, genuine false alarm on the
        // parent's screen; it is noted here rather than asserted, because asserting it would
        // pin a behaviour worth changing.
        val filtering = childEventuallyReports { it.webFilterExpected && it.webFilterOn }
        assertTrue(filtering.webFilterExpected, "domains are blocked and the child does not expect a filter")
        assertTrue(
            filtering.webFilterOn,
            "the tunnel never came up on a phone that was asked for one — the parent would be shown " +
                "a filter that is not filtering, which is the alarm this pair exists to raise",
        )

        // Withdrawn. A family that turns the filter off must stop being told about one, or the
        // card that means "your filter is broken" starts appearing for families who have none.
        val withdrawn = parent.pushPolicy(PolicyJson.minimal(version = 3))
        val after = childEventuallyReports {
            it.appliedPolicyVersion >= withdrawn.version && !it.webFilterExpected
        }
        assertEquals(false, after.webFilterExpected, "the filter was withdrawn and the phone still expects one")
    }

    @Test
    fun `the lists a family never switched on are never reported as pending`() {
        // The other half of what the filter reports. A family filtering by hand-typed domains
        // uses no blocklists at all, so a device that reported one as pending — or reported
        // downloading zero domains as a fault — would invent a problem out of a feature nobody
        // turned on. Asserted WITH a filter running, which is the case that can get it wrong:
        // the existing scenario only checks it on a phone with no filter at all.
        parent.pushPolicy(filterPolicy(version = 2))
        val filtering = childEventuallyReports { it.webFilterExpected && it.webFilterOn }
        assertEquals(
            emptyList<String>(),
            filtering.filterListsPending,
            "a list nobody switched on cannot be pending",
        )
        assertEquals(0, filtering.filterListDomains, "no lists are on, so no list domains were downloaded")
    }

    @Test
    fun `with the filter up, ordinary names resolve, allowed ones pass a list, IPv6 leaves and TCP DNS answers`() {
        // What no parent ever looks at and every page load depends on: the tunnel is up for a family
        // that asked for SOME names to be blocked, and everything else has to work as if it were not
        // there. Nothing here used to check that — only that the tunnel came up — and two things did
        // not work: IPv6 was cut for every app (a VPN that adds no IPv6 address has the whole family
        // blocked by the platform), and a TCP connection to a public resolver's port 53, which is how
        // several app frameworks decide whether they are online at all, was refused.
        parent.pushPolicy(
            PolicyJson.build(
                version = 2,
                extra = mapOf(
                    // Bundled-only, so nothing has to download: pinterest.com and reddit.com are on it.
                    "enabledBlocklists" to JsonArray(listOf(JsonPrimitive("social"))),
                    "allowedDomains" to JsonArray(listOf(JsonPrimitive("reddit.com"))),
                ),
            ),
        )
        childEventuallyReports { it.webFilterExpected && it.webFilterOn }

        awaitDevice("a name on the list is refused") { !resolves("www.pinterest.com") }
        awaitDevice("a name the family allowed back from the list resolves") { resolves("www.reddit.com") }
        assertTrue(resolves("www.wikipedia.org"), "a name nobody blocked did not resolve through the tunnel")

        // IPv6 to anywhere but a routed resolver goes out the ordinary way…
        val ordinary = device.run("shell", "ip", "-6", "route", "get", ORDINARY_IPV6)
        org.junit.jupiter.api.Assertions.assertFalse(
            "No route to host" in ordinary || "unreachable" in ordinary,
            "IPv6 is cut off for every app while the filter runs: $ordinary",
        )
        // …and to a public resolver it lands in the tunnel, like its IPv4 twin, so asking one over
        // IPv6 is not a way round the filter either.
        val resolver = device.run("shell", "ip", "-6", "route", "get", "2001:4860:4860::8888")
        assertTrue("tun0" in resolver, "a public IPv6 resolver is not routed into the filter: $resolver")

        val tcp = device.run("shell", "nc -z -w 3 8.8.8.8 53 && echo TCP_DNS_OK || echo TCP_DNS_REFUSED")
        assertTrue("TCP_DNS_OK" in tcp, "a TCP connection to a public resolver's port 53 was refused: $tcp")
    }

    /** Whether [host] resolves on the device, asked the way an app asks: through the system resolver. */
    private fun resolves(host: String): Boolean =
        !device.run("shell", "ping", "-c", "1", "-W", "2", host).contains("unknown host")

    private companion object {
        /** Any IPv6 address that is not a public resolver (a Google web front end). */
        const val ORDINARY_IPV6 = "2a00:1450:4003:80e::200e"
    }

    /** A policy that asks for a DNS filter, which is what makes the tunnel wanted at all. */
    private fun filterPolicy(version: Long): String = PolicyJson.build(
        version = version,
        extra = mapOf(
            "blockedDomains" to JsonArray(listOf(JsonPrimitive("casino.example"), JsonPrimitive("tracker.example"))),
        ),
    )
}
