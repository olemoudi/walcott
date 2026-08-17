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

    /** True when [domain] is, or sits under, something in [NEVER_BLOCK]. */
    fun isSpared(domain: String): Boolean {
        if (domain in NEVER_BLOCK) return true
        var index = domain.indexOf('.')
        while (index in 0 until domain.length - 1) {
            if (domain.substring(index + 1) in NEVER_BLOCK) return true
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
