package dev.walcott.rules

/**
 * A blocklist compiled for matching: "is this host covered by any of these domains?", answered
 * by walking the host's own labels rather than by scanning the list.
 *
 * The scan was fine while a family's blocklist was the handful of domains they had typed in.
 * With the built-in lists ([Blocklists]) it is hundreds, and this sits in the path of every DNS
 * query the child's phone makes — so the cost is made a function of the HOST (`a.b.c` asks three
 * questions, always) instead of of the list, which can then grow without touching the hot path.
 *
 * Built once per policy change and reused, so normalisation is paid once rather than per query.
 * That is also what makes it forgiving about what a parent typed: `HTTPS://Www.Example.com/x`,
 * `*.example.com` and `example.com.` all end up as the same rule.
 */
class DomainMatcher private constructor(private val suffixes: Set<String>) {

    /** How many distinct domains this matcher covers (after normalising). */
    val size: Int get() = suffixes.size

    val isEmpty: Boolean get() = suffixes.isEmpty()

    /** True when [host], or any parent domain of it, is on the list. */
    fun matches(host: String): Boolean {
        if (suffixes.isEmpty()) return false
        var current = normalize(host)
        while (current.isNotEmpty()) {
            if (current in suffixes) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
        return false
    }

    companion object {
        val EMPTY = DomainMatcher(emptySet())

        fun of(domains: Collection<String>): DomainMatcher {
            if (domains.isEmpty()) return EMPTY
            val normalized = domains.mapNotNullTo(mutableSetOf()) { normalize(it).takeIf { n -> n.isNotEmpty() } }
            return if (normalized.isEmpty()) EMPTY else DomainMatcher(normalized)
        }

        /**
         * A host or a rule reduced to its comparable form: lower case, no scheme, no path, no
         * port, no leading `*.` or `.`, no trailing dot.
         */
        fun normalize(value: String): String {
            var s = value.trim().lowercase()
            s = s.substringAfter("://")
            s = s.substringBefore('/')
            s = s.substringBefore('?')
            // A bare IPv6 literal has colons of its own; only strip a port from something that
            // has exactly one, which is what `host:port` looks like.
            if (s.count { it == ':' } == 1) s = s.substringBefore(':')
            while (s.startsWith("*.")) s = s.removePrefix("*.")
            return s.trim('.')
        }
    }
}
