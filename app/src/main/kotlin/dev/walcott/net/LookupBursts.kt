package dev.walcott.net

/**
 * Turns the several DNS queries one name resolution makes back into one lookup.
 *
 * Android's resolver issues **A and AAAA in parallel** for every `getaddrinfo`, and a browser adds
 * an **HTTPS** query beside them. So a child tapping one link arrives at this tunnel as two or
 * three questions for the same name, milliseconds apart — and every one of them was counted. The
 * parent's "blocked today" figure was two to three times the truth, and the domain viewer said
 * "seen 2 times" about a name the child had touched once.
 *
 * The rule: within [windowMs], queries for the same name from the same app are ONE lookup **until
 * a record type repeats**. A repeat is the signal that cannot be a companion — no resolver asks
 * for A twice in one breath — so a client genuinely retrying still counts, which is the part a
 * plain "ignore duplicates within two seconds" would have thrown away. A retry is exactly what a
 * parent wants to see: it is how a blocked domain announces that something keeps trying it.
 *
 * Allocation-free and bounded: three parallel primitive arrays of [slots] entries, scanned
 * linearly, with the oldest entry reused round-robin. This is reached once per DNS query on a
 * phone that makes them all day, so it may not allocate and may not grow.
 *
 * Overflow is benign by design. More bursts in flight than [slots] forgets the oldest and counts
 * one companion as its own lookup; a hash collision does the same. Both cost one miscounted
 * lookup and neither can cost correctness — nothing here decides whether a domain is blocked.
 */
class LookupBursts(
    private val windowMs: Long = WINDOW_MS,
    private val slots: Int = SLOTS,
) {

    /** The identity of a burst: the app and the name, hashed. */
    private val keys = IntArray(slots)

    /**
     * Which record types this burst has already seen. Zero means the slot is unused, which is why
     * occupancy needs no array of its own: every live entry has at least one bit set.
     */
    private val masks = IntArray(slots)

    private val stamps = LongArray(slots)

    /** The next slot to reuse when every one is taken. */
    private var next = 0

    /**
     * Whether this query begins a new lookup, rather than joining one already counted.
     *
     * [nowMs] is a monotonic clock (`elapsedRealtime`), because a wall clock that steps backwards
     * would make every query look like the start of something.
     */
    fun beginsLookup(host: String, packageName: String?, type: Int?, nowMs: Long): Boolean {
        val key = keyOf(host, packageName)
        val bit = bitFor(type)
        synchronized(this) {
            val found = indexOf(key)
            if (found >= 0 && nowMs - stamps[found] <= windowMs && masks[found] and bit == 0) {
                // A companion: same name, same app, inside the window, a type not yet asked for.
                masks[found] = masks[found] or bit
                stamps[found] = nowMs
                return false
            }
            // Its own slot if it has one — a chatty domain must never take two — otherwise the
            // oldest. Either way this is the start of a lookup and the mask starts again.
            val slot = if (found >= 0) found else claim()
            keys[slot] = key
            masks[slot] = bit
            stamps[slot] = nowMs
            return true
        }
    }

    /** Forgets every burst in flight. Called when a tunnel goes down: nothing spans two of them. */
    fun clear() {
        synchronized(this) {
            masks.fill(0)
            next = 0
        }
    }

    private fun indexOf(key: Int): Int {
        for (i in 0 until slots) if (masks[i] != 0 && keys[i] == key) return i
        return -1
    }

    private fun claim(): Int {
        for (i in 0 until slots) if (masks[i] == 0) return i
        val slot = next
        next = (next + 1) % slots
        return slot
    }

    /** The same identity as "package|domain", without building the string. */
    private fun keyOf(host: String, packageName: String?): Int =
        31 * (packageName?.hashCode() ?: 0) + host.hashCode()

    private fun bitFor(type: Int?): Int = when (type) {
        TYPE_A -> 1
        TYPE_AAAA -> 2
        TYPE_HTTPS -> 4
        TYPE_SVCB -> 8
        // Everything else shares a bit. The point is only to notice a REPEAT, and two different
        // unusual types in one breath is not a shape any resolver produces.
        else -> 16
    }

    companion object {
        /**
         * How long a burst stays open.
         *
         * Companions arrive milliseconds apart; the margin is for a library that waits for the A
         * answer before asking for AAAA. It does not need to cover a retry, because a retry
         * repeats a record type and is counted on that.
         */
        const val WINDOW_MS = 2_000L

        /** Bursts tracked at once. Sixteen is more than a phone has in flight in two seconds. */
        const val SLOTS = 16

        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val TYPE_SVCB = 64
        const val TYPE_HTTPS = 65
    }
}
