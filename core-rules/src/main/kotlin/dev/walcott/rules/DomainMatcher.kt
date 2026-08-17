package dev.walcott.rules

/**
 * A blocklist compiled for matching: "is this host covered by any of these domains?", answered
 * by walking the host's own labels rather than by scanning the list.
 *
 * The scan was fine while a family's blocklist was the handful of domains they had typed in.
 * With the public lists ([Blocklists]) it is over a million, and this sits in the path of every
 * DNS query the child's phone makes — so the cost is made a function of the HOST (`a.b.c` asks
 * three questions, always) instead of of the list, which can then grow without touching the hot
 * path.
 *
 * **Two halves, for two different kinds of entry.**
 *
 *  - What a person typed (a parent's own domains, the bundled seeds) is kept as strings and
 *    matched exactly. It is small, and it is the half whose behaviour someone would notice and
 *    have to explain.
 *  - What a machine downloaded is kept as a sorted array of 64-bit hashes — **8 bytes per
 *    domain**, so oisd's 494 000-entry NSFW list costs 4 MB instead of the ~50 MB the same
 *    domains would cost as a `HashSet<String>`. That is what makes lists this size affordable in
 *    a process that stays alive all day on a hand-me-down phone.
 *
 * The hashed half can in principle collide, and then a domain nobody listed is blocked. With
 * 64-bit hashes and even two million entries the chance of a single collision anywhere in the
 * table is on the order of 10⁻⁷ — several orders of magnitude below the chance that a public
 * list simply contains a domain it should not, which is a risk this feature already lives with
 * and reports ([BlocklistSource.NEVER_BLOCK] is the guard for both).
 *
 * Built once per policy change and reused, so normalisation is paid once rather than per query.
 * That is also what makes it forgiving about what a parent typed: `HTTPS://Www.Example.com/x`,
 * `*.example.com` and `example.com.` all end up as the same rule.
 */
class DomainMatcher private constructor(
    private val suffixes: Set<String>,
    /** Sorted, deduplicated hashes of the bulk half; empty when there is none. */
    private val hashes: LongArray,
) {

    /**
     * How many distinct domains this matcher covers. The two halves are counted separately, so a
     * domain a parent typed AND a downloaded list carries counts twice — which is why this is
     * only ever used for a log line and a diagnostics row, never as an authority on coverage.
     */
    val size: Int get() = suffixes.size + hashes.size

    val isEmpty: Boolean get() = suffixes.isEmpty() && hashes.isEmpty()

    /** True when [host], or any parent domain of it, is on the list. */
    fun matches(host: String): Boolean {
        if (isEmpty) return false
        var current = normalize(host)
        while (current.isNotEmpty()) {
            if (current in suffixes) return true
            if (hashes.isNotEmpty() && java.util.Arrays.binarySearch(hashes, hash(current)) >= 0) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
        return false
    }

    /**
     * Accumulates a matcher without ever holding the bulk half as strings.
     *
     * That is the whole point of it: the caller streams a cache file of half a million domains
     * through [addNormalized] line by line, and the peak cost is this builder's `long` array
     * rather than half a million live `String`s.
     */
    class Builder(exact: Collection<String>, expectedHashed: Int = 0) {

        private val suffixes: Set<String> =
            exact.mapNotNullTo(mutableSetOf()) { normalize(it).takeIf { n -> n.isNotEmpty() } }
        // Sized from what the caller knows it is about to add, when it knows: growing by doubling
        // from nothing to a million entries copies ~16 MB of array through a phone's heap for no
        // reason, and the store already has the count in its state file.
        private var hashes = LongArray(expectedHashed.coerceAtLeast(INITIAL_CAPACITY))
        private var count = 0

        /**
         * Adds a domain already in canonical form — what a cache file holds, because the store
         * normalised it when it wrote it. Skips blanks; does not re-normalise, which is what
         * keeps a five-hundred-thousand-line read from allocating a string per line twice.
         */
        fun addNormalized(domain: String) {
            if (domain.isEmpty()) return
            if (count == hashes.size) hashes = hashes.copyOf(hashes.size * 2)
            hashes[count++] = hash(domain)
        }

        /** Adds a domain of unknown shape (normalises first). */
        fun add(domain: String) = addNormalized(normalize(domain))

        fun build(): DomainMatcher {
            if (suffixes.isEmpty() && count == 0) return EMPTY
            val packed = hashes.copyOf(count)
            packed.sort()
            return DomainMatcher(suffixes, dedup(packed))
        }

        /** Sorted in place already, so duplicates are adjacent and compacting is one pass. */
        private fun dedup(sorted: LongArray): LongArray {
            if (sorted.isEmpty()) return sorted
            var unique = 1
            for (i in 1 until sorted.size) {
                if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
            }
            return if (unique == sorted.size) sorted else sorted.copyOf(unique)
        }

        private companion object {
            const val INITIAL_CAPACITY = 1024
        }
    }

    companion object {
        val EMPTY = DomainMatcher(emptySet(), LongArray(0))

        fun of(domains: Collection<String>): DomainMatcher {
            if (domains.isEmpty()) return EMPTY
            val normalized = domains.mapNotNullTo(mutableSetOf()) { normalize(it).takeIf { n -> n.isNotEmpty() } }
            return if (normalized.isEmpty()) EMPTY else DomainMatcher(normalized, LongArray(0))
        }

        /**
         * A matcher whose exact half is [domains] and whose bulk half the caller streams in.
         * [expectedHashed] pre-sizes the array when the caller knows roughly how many are coming.
         */
        fun builder(domains: Collection<String>, expectedHashed: Int = 0): Builder =
            Builder(domains, expectedHashed)

        /**
         * FNV-1a, 64-bit, over the domain's bytes. Chosen for being three lines and having no
         * pathological input in this alphabet; the array it fills is sorted afterwards, so the
         * hash never has to be well-distributed for bucketing, only for collisions.
         */
        fun hash(domain: String): Long {
            var h = FNV_OFFSET
            for (i in domain.indices) {
                h = h xor (domain[i].code.toLong() and 0xFF)
                h *= FNV_PRIME
            }
            return h
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

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L
    }
}
