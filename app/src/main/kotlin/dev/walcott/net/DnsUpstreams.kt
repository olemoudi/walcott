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
 * not an IP literal must never reach `InetAddress.getByName`, which would do a blocking (and, on
 * this thread, filtered) DNS lookup to resolve the resolver.
 */
object DnsUpstreams {

    /** Used only when the network offers nothing usable of its own. */
    const val FALLBACK = "1.1.1.1"

    /** At most this many are tried per query, so a bad list can't stretch one lookup for ever. */
    const val MAX_UPSTREAMS = 3

    /**
     * The resolvers to try, in order: the network's own, then [FALLBACK].
     *
     * IPv6 resolvers count. They used to be filtered out on the grounds that the tunnel is IPv4
     * — but the tunnel's address family has nothing to do with where an allowed query is
     * forwarded, and a carrier that offers only IPv6 resolvers left this filter with the public
     * fallback alone. On such a network without a translator every lookup then timed out three
     * times over and answered SERVFAIL: a child's phone that resolves nothing, caused by the
     * filter refusing to talk to the only resolvers there were.
     *
     * [exclude] carries our own tun and sentinel addresses. A network that somehow reported the
     * sentinel back to us would otherwise make the filter forward queries to itself — an
     * infinite loop inside the tunnel rather than a slow lookup.
     */
    fun choose(fromNetwork: List<String>, exclude: Set<String> = emptySet()): List<String> {
        val usable = fromNetwork
            // A zone id ("fe80::1%wlan0") is meaningful only to the interface that offered it,
            // and it is the zone that makes a link-local address routable — so one arriving here
            // is dropped below rather than carried without it.
            .map { it.substringBefore('%') }
            .filter { isIpLiteral(it) && !isLinkLocal(it) && it !in exclude }
            .distinct()
        return (usable + FALLBACK).distinct().take(MAX_UPSTREAMS)
    }

    /**
     * An address that only means something on the wire it came from.
     *
     * Kept out because the zone id that would make it reachable is stripped above: a query sent
     * to `fe80::1` with no scope cannot leave the phone, so it would cost a full timeout on
     * every lookup and then be tried again on the next one.
     */
    fun isLinkLocal(address: String): Boolean {
        val lower = address.lowercase()
        if (lower.startsWith("fe8") || lower.startsWith("fe9") ||
            lower.startsWith("fea") || lower.startsWith("feb")
        ) {
            return isIpv6Literal(address)
        }
        return lower.startsWith("169.254.") && isIpv4Literal(address)
    }

    /**
     * An IP literal of either family, and nothing else — not a hostname, not an empty string.
     * Deliberately strict: everything this returns true for is passed to
     * `InetAddress.getByName`, which only stays non-blocking for literals.
     */
    fun isIpLiteral(address: String): Boolean = isIpv4Literal(address) || isIpv6Literal(address)

    /** A dotted-quad IPv4 literal. */
    fun isIpv4Literal(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.toIntOrNull() ?: return@all false) <= 255
        }
    }

    /**
     * An IPv6 literal, judged by shape rather than by parsing: hex groups separated by colons,
     * at most one "::", and nothing that could be a hostname. `InetAddress.getByName` only has
     * to be kept away from names, and a name cannot contain a colon.
     */
    fun isIpv6Literal(address: String): Boolean {
        if (':' !in address) return false
        if (address.count { it == ':' } > 8) return false
        if (address.split("::").size > 2) return false
        val groups = address.split(':').filter { it.isNotEmpty() }
        if (groups.isEmpty()) return address == "::"
        return groups.all { group ->
            // The last group of a v4-mapped address ("::ffff:1.2.3.4") is a dotted quad.
            group.length <= 4 && group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ||
                isIpv4Literal(group)
        }
    }
}
