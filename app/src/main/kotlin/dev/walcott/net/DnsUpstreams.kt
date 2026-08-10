package dev.walcott.net

/**
 * Which resolvers the DNS filter forwards an allowed query to.
 *
 * The filter used to send every lookup to one hard-coded public resolver. That is fine on a
 * home Wi-Fi and wrong nearly everywhere else: a network that blocks it (a hotel portal, a
 * school or office that forces its own resolver) leaves the child with no working DNS at all
 * for as long as the filter is up — and, because a failed forward answered nothing, the failure
 * looked to every app like the network being broken. It also loses every name that only the
 * local resolver knows: the printer, the NAS, a captive portal's own host.
 *
 * So the network's own resolvers come first, in the order it offered them, and the public one
 * is kept only as a last resort for the case that made it attractive — a network that hands out
 * no resolver at all, or only ones we can't use.
 *
 * Pure, because the filtering rules here are the whole safety of the thing: an address that is
 * not an IPv4 literal must never reach `InetAddress.getByName`, which would do a blocking
 * (and, on this thread, filtered) DNS lookup to resolve the resolver.
 */
object DnsUpstreams {

    /** Used only when the network offers nothing usable of its own. */
    const val FALLBACK = "1.1.1.1"

    /** At most this many are tried per query, so a bad list can't stretch one lookup for ever. */
    const val MAX_UPSTREAMS = 3

    /**
     * The resolvers to try, in order: the network's own (IPv4 literals only), then [FALLBACK].
     *
     * [exclude] carries our own tun and sentinel addresses. A network that somehow reported the
     * sentinel back to us would otherwise make the filter forward queries to itself — an
     * infinite loop inside the tunnel rather than a slow lookup.
     */
    fun choose(fromNetwork: List<String>, exclude: Set<String> = emptySet()): List<String> {
        val usable = fromNetwork
            .map { it.substringBefore('%') } // strip any zone id ("fe80::1%wlan0"); never IPv4 anyway
            .filter { isIpv4Literal(it) && it !in exclude }
            .distinct()
        return (usable + FALLBACK).distinct().take(MAX_UPSTREAMS)
    }

    /**
     * A dotted-quad IPv4 literal, and nothing else — not a hostname, not IPv6, not an empty
     * string. Deliberately strict: everything this returns true for is passed to
     * `InetAddress.getByName`, which only stays non-blocking for literals.
     */
    fun isIpv4Literal(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.toIntOrNull() ?: return@all false) <= 255
        }
    }
}
