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
 *  - plain DNS over TCP to one of them is answered the same way (see [DnsTcpResponder]), which is
 *    also what an app's "is there internet?" check that connects to port 53 needs;
 *  - DNS over TLS or HTTPS to one of them is TCP or QUIC to another port, which the tunnel refuses
 *    at once — a reset, or port-unreachable — so the app falls back to the system resolver
 *    instead of waiting out a timeout.
 *
 * Both families. The IPv6 twins were left out while the tunnel could not read IPv6, which kept an
 * IPv6 resolver a way round the filter on every network that has IPv6. The IPv6 addresses are the
 * providers' own documented ones as of 2026-09, with one exception: OpenDNS's live pages no longer
 * list FamilyShield's (`::123`), which its old IPv6 support article did — they sit inside the same
 * resolver prefixes as its documented `::35` and `::53`. Comodo publishes no IPv6 and gets none.
 *
 * Whole ranges only where the range carries the resolver and nothing else, because every address
 * routed here loses everything that is not DNS. NextDNS and Control D hand every family its own
 * address inside one. Control D documents 2606:1a40::/48 and 2606:1a40:1::/48 as its DNS
 * resolvers — and 2606:1a40:2:: and :3:: as its proxy and its website, which are NOT routed.
 * NextDNS documents no prefix; its per-configuration IPv6 address is one of its two anycast
 * addresses with the configuration id in the low bits (`2a07:a8c0::ab:cdef`), so the /96 below
 * each is the narrowest range that holds all of them. Walcott's own sockets stay outside the
 * tunnel, so a network whose own resolver is one of these still works.
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
    ) + listOf(
        // Google Public DNS, and its DNS64
        "2001:4860:4860::8888", "2001:4860:4860::8844", "2001:4860:4860::6464", "2001:4860:4860::64",
        // Cloudflare, its malware and family variants, and its DNS64
        "2606:4700:4700::1111", "2606:4700:4700::1001", "2606:4700:4700::1112", "2606:4700:4700::1002",
        "2606:4700:4700::1113", "2606:4700:4700::1003", "2606:4700:4700::64", "2606:4700:4700::6400",
        // Quad9
        "2620:fe::fe", "2620:fe::9", "2620:fe::10", "2620:fe::fe:10", "2620:fe::11", "2620:fe::fe:11",
        // OpenDNS / Cisco Umbrella, FamilyShield included
        "2620:119:35::35", "2620:119:53::53", "2620:119:35::123", "2620:119:53::123",
        // AdGuard DNS: default, family, non-filtering
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff", "2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff",
        "2a10:50c0::1:ff", "2a10:50c0::2:ff",
        // CleanBrowsing: security, adult, family
        "2a0d:2a00:1::2", "2a0d:2a00:2::2", "2a0d:2a00:1::1", "2a0d:2a00:2::1", "2a0d:2a00:1::", "2a0d:2a00:2::",
        // Mullvad DNS
        "2a07:e340::2", "2a07:e340::3", "2a07:e340::4", "2a07:e340::5", "2a07:e340::6", "2a07:e340::9",
    ).map { Route(it, 128) } + listOf(
        // NextDNS and Control D, as above: the per-family addresses and nothing else of theirs.
        Route("2a07:a8c0::", 96),
        Route("2a07:a8c1::", 96),
        Route("2606:1a40::", 48),
        Route("2606:1a40:1::", 48),
    )
}
