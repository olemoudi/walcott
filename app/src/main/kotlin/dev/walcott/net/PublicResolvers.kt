package dev.walcott.net

/**
 * The best-known public resolvers, by address, routed into the filter's tunnel.
 *
 * The tunnel used to route exactly one address: its own sentinel. Anything that asked a resolver
 * by address rather than by name went straight past it — an app with `8.8.8.8` compiled in, a
 * browser's secure DNS set to an address template — and past the bedtime curfew with it, because
 * the curfew takes a browser's DNS away through this same filter. With these routed:
 *
 *  - plain DNS to one of them arrives here and is answered through the filter, rules and curfew
 *    included, exactly like a query to the sentinel (the answer goes back from the address the
 *    app asked, see [IpPackets.udpResponse]);
 *  - DNS over TLS or HTTPS to one of them is TCP or QUIC, which the tunnel refuses at once — a
 *    reset, or port-unreachable — so the app falls back to the system resolver instead of
 *    waiting out a timeout.
 *
 * IPv4 only, because the tunnel is: an IPv6 route into a tunnel that cannot read IPv6 packets
 * would turn a way round the filter into a hang. The README says so.
 *
 * Whole ranges only where the range belongs to the resolver and nothing else (NextDNS and
 * Control D hand every family its own address inside one). Walcott's own sockets stay outside
 * the tunnel, so a network whose own resolver is one of these still works.
 */
object PublicResolvers {

    /** Address and prefix length. */
    data class Route(val address: String, val prefix: Int)

    val ROUTES: List<Route> = listOf(
        // Google Public DNS
        "8.8.8.8", "8.8.4.4",
        // Cloudflare, and its malware and family variants
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        // Quad9
        "9.9.9.9", "149.112.112.112", "9.9.9.10", "149.112.112.10", "9.9.9.11", "149.112.112.11",
        // OpenDNS / Cisco Umbrella
        "208.67.222.222", "208.67.220.220", "208.67.222.123", "208.67.220.123",
        // AdGuard DNS
        "94.140.14.14", "94.140.15.15", "94.140.14.15", "94.140.15.16", "94.140.14.140", "94.140.14.141",
        // CleanBrowsing
        "185.228.168.9", "185.228.169.9", "185.228.168.10", "185.228.169.11",
        "185.228.168.168", "185.228.169.168",
        // Mullvad DNS
        "194.242.2.2", "194.242.2.3", "194.242.2.4", "194.242.2.5", "194.242.2.6", "194.242.2.9",
        // Comodo Secure DNS
        "8.26.56.26", "8.20.247.20",
    ).map { Route(it, 32) } + listOf(
        // NextDNS and Control D: one anycast range each, a per-family address inside it.
        Route("45.90.28.0", 24),
        Route("45.90.30.0", 24),
        Route("76.76.2.0", 24),
        Route("76.76.10.0", 24),
    )
}
