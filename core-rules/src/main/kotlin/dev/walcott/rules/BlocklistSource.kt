package dev.walcott.rules

/**
 * Turns one line of a downloaded public blocklist into a domain this app can enforce.
 *
 * Line at a time on purpose: the sources in [Blocklists] run to half a million entries, and the
 * child streams them from the network straight to a cache file and from that file into a
 * [DomainMatcher.Builder]. Nothing here ever holds a whole list, so a 10 MB download costs the
 * phone a buffer rather than a heap full of strings.
 *
 * Format-forgiving, because the sources are not ours: some are `hosts` files
 * (`0.0.0.0 example.com`), some are one bare domain per line, some prefix a wildcard
 * (`*.example.com`), all of them carry comment headers, and any of them can change shape in a
 * commit we will never see. Anything that is not confidently a domain is dropped rather than
 * guessed at — which is also what stops an HTML error page from becoming ten thousand rules.
 *
 * [NEVER_BLOCK] wins over any source. These lists are third parties: one bad entry landing in a
 * release of theirs would otherwise cut every child in the family off from WhatsApp or from the
 * Play Store, with a parent having no way to tell what happened. What the parent typed themselves
 * is untouched by this — they are allowed to block whatever they like.
 */
object BlocklistSource {

    /**
     * The most domains one list may contribute.
     *
     * Not a memory budget — hashed, a million domains is 8 MB (see [DomainMatcher]) — but a guard
     * against absurdity: a URL that quietly starts pointing at a full threat-intelligence feed,
     * or at something that is not a blocklist at all, must not be able to fill a child's phone.
     * Every list actually in use is comfortably under it, and passing it is reported rather than
     * swallowed.
     */
    const val MAX_DOMAINS = 2_000_000

    /**
     * Never blocked by a downloaded list, whatever that list says.
     *
     * Suffix-matched, so `whatsapp.net` also spares `mmg.whatsapp.net`. Kept as short as it can
     * be: every entry here is a hole in the filter, so it holds only what the family cannot
     * afford to lose — the channel they talk on, the two hosts this app updates itself from, and
     * the Google hosts the phone and the Play Store need in order to work.
     *
     * Note what is NOT here: bare `google.com`. Sparing it would spare `dns.google.com` with it,
     * and an encrypted resolver is exactly what [Blocklists.BYPASS] exists to block.
     */
    val NEVER_BLOCK: Set<String> = setOf(
        "whatsapp.com", "whatsapp.net", "wa.me",
        "ntfy.sh",
        "github.com", "githubusercontent.com",
        "googleapis.com", "gstatic.com", "googleusercontent.com",
        "play.google.com", "android.clients.google.com",
    )

    /**
     * One line reduced to a normalised domain, or null when it is a comment, a blank, an
     * address-only entry, something [NEVER_BLOCK] spares, or anything else we would be guessing
     * about.
     *
     * Rejects entries with no dot at all (`localhost`), bare IP addresses, and anything carrying
     * characters a hostname cannot have.
     */
    fun domainOf(line: String): String? {
        val trimmed = line.substringBefore('#').trim()
        if (trimmed.isEmpty()) return null
        val fields = trimmed.split(' ', '\t').filter { it.isNotEmpty() }
        val candidate = when {
            fields.isEmpty() -> return null
            // A hosts line: take the name, ignore the address it is pointed at.
            fields.size >= 2 && isAddress(fields[0]) -> fields[1]
            fields.size == 1 -> fields[0]
            else -> return null
        }
        val domain = DomainMatcher.normalize(candidate)
        if (!domain.contains('.') || domain.length > MAX_HOST_LENGTH) return null
        if (isAddress(domain)) return null
        if (!domain.all { isHostChar(it) }) return null
        if (isSpared(domain)) return null
        return domain
    }

    /**
     * The names a phone uses to decide whether the network it is on works at all.
     *
     * **This is not a general exception list and must not become one.** It exists for one
     * specific, silent and expensive failure. Android decides whether a Wi-Fi has internet by
     * fetching a `generate_204` over it; if a downloaded list refuses the name that probe uses,
     * the probe fails, the phone marks a perfectly good Wi-Fi as having no internet, and on every
     * vendor with an "adaptive connectivity" or "switch to mobile data automatically" feature it
     * leaves the Wi-Fi for the mobile network. On a child's phone that is the family's data
     * allowance being spent, the blocklist refresh's own "unmetered only" constraint going
     * unsatisfiable, and a parent seeing a child who looks offline. Nothing anywhere says why,
     * and this app reports the filter as working perfectly — because from its point of view it is.
     *
     * [NEVER_BLOCK] already spares `gstatic.com`, and therefore Android's main probe. The rest of
     * these are not covered by it and must not be: sparing `google.com` would spare
     * `dns.google.com` with it, which is exactly what the bypass list exists to block. So they are
     * listed as whole hosts, never as a registrable domain.
     *
     * The vendor entries are here because they are the ones that actually get blocked — Xiaomi's,
     * Huawei's and vivo's probe hosts appear on aggressive lists as telemetry, which is defensible
     * about the domain and disastrous about the phone. Every entry serves an empty 204 and carries
     * no content, so sparing it costs no advertising and shows a child nothing.
     */
    val CONNECTIVITY_CHECKS: Set<String> = setOf(
        // Android's own, current and historical.
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "clients4.google.com",
        // The HTTPS half of the same probe on a modern Android.
        "www.google.com",
        // Vendors that ship their own probe.
        "connect.rom.miui.com",
        "connectivitycheck.platform.hicloud.com",
        "wifi.vivo.com.cn",
    )

    /** True when [domain] is, or sits under, something in [NEVER_BLOCK]. */
    fun isSpared(domain: String): Boolean = endsWithin(domain, NEVER_BLOCK)

    /**
     * True when [domain] is one of the phone's own connectivity probes (see [CONNECTIVITY_CHECKS]).
     *
     * Asked at decision time rather than when a list is ingested, deliberately: a child's phone
     * carries lists that were compiled by an earlier version of this app, and a guard that only
     * ran at ingest would not protect them until the next refresh — which, on the phone this bug
     * has just pushed onto mobile data, is a refresh that will not happen.
     */
    fun isConnectivityCheck(domain: String): Boolean = endsWithin(domain, CONNECTIVITY_CHECKS)

    /** True when [domain] is, or sits under a label boundary below, a member of [suffixes]. */
    private fun endsWithin(domain: String, suffixes: Set<String>): Boolean {
        if (domain in suffixes) return true
        var index = domain.indexOf('.')
        while (index in 0 until domain.length - 1) {
            if (domain.substring(index + 1) in suffixes) return true
            index = domain.indexOf('.', index + 1)
        }
        return false
    }

    private fun isHostChar(c: Char): Boolean =
        c.code < 128 && (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_')

    private fun isAddress(value: String): Boolean =
        value.all { it.isDigit() || it == '.' } || value.contains(':')

    private const val MAX_HOST_LENGTH = 253
}
