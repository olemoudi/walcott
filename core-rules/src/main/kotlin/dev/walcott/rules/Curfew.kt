package dev.walcott.rules

/**
 * What loses the network while the whole phone is supposed to be shut.
 *
 * Bedtime suspends every app this device MANAGES, and the managed set is the non-system apps
 * (see `AppInventory.managedPackages`) — which on most phones leaves out the browser, because a
 * browser ships bundled and is therefore a system package. So the rule worked and the evening
 * went on anyway: everything the child had installed went dark at nine, and the thing already on
 * the phone opened at nine-oh-one. Anything with a WebView inside it is the same hole.
 *
 * Suspending system packages is not the answer — a Device Owner can be refused, and some of them
 * the phone genuinely needs — so what is taken away is the part that makes them worth opening: a
 * browser with no DNS resolves nothing at all, which is the wildcard "block every destination"
 * applied to one app.
 *
 * Two lists, because they are noticed differently:
 *
 *  - **The browsers**, cut off for the whole window on sight. There is nothing to observe first:
 *    a browser at bedtime is the case this exists for.
 *  - **Whatever else outstays [LINGER_SECONDS]**, which is the honest way to catch the ones
 *    nobody can enumerate — an OEM's news feed, a store, a video app, anything holding a WebView.
 *    Two minutes inside a shut phone is not a glance at the clock; it is the evening carrying on.
 *
 * Everything here is derived, and nothing is written down: the window closing is what lifts it,
 * and a device that reboots or loses its enforcement loop comes back with nothing cut off. That
 * direction is deliberate. A curfew that outlived its window would be a phone whose browser
 * stopped working at three in the afternoon with nothing anywhere to explain it.
 *
 * Pure, so what a child's phone stops resolving is unit-tested rather than reasoned about.
 */
object Curfew {

    /**
     * Foreground time, inside one window, after which an app is cut off.
     *
     * Cumulative rather than continuous, and that is the whole difference between a rule and a
     * game: two minutes of one app, or a minute each of two and back again, is the same evening.
     * Two minutes is also comfortably past every innocent reason to be in a system app while the
     * phone is shut — reading the time, silencing an alarm, answering a call (the phone app is
     * spared outright anyway).
     */
    const val LINGER_SECONDS = 120L

    /**
     * How many apps one window watches at once. A bedtime is hours long and this map lives in a
     * loop that never restarts, so it gets a ceiling like everything else here; an evening spent
     * opening thirty-three different system apps has already been answered by the first thirty-two.
     */
    const val MAX_WATCHED = 32

    /**
     * Foreground seconds accrued inside the current window, per package.
     *
     * Returns nothing at all once the window is over, which is what "when the period ends the
     * blocks are lifted" means here: there is no expiry to run and nothing to remember to undo.
     * A package that has already reached [LINGER_SECONDS] stops counting — it is cut off, and
     * how far past the line it went is not a fact anybody needs.
     */
    fun accrue(
        current: Map<String, Long>,
        foreground: String?,
        seconds: Long,
        windowOpen: Boolean,
    ): Map<String, Long> {
        if (!windowOpen) return emptyMap()
        if (foreground.isNullOrEmpty() || seconds <= 0) return current
        val soFar = current[foreground] ?: 0L
        if (soFar >= LINGER_SECONDS) return current
        val next = current.toMutableMap()
        // A new arrival into a full map displaces the app furthest from the line, which is the
        // one whose case is weakest and which will simply start again if it is still there.
        if (soFar == 0L && next.size >= MAX_WATCHED) {
            next.minByOrNull { it.value }?.let { next.remove(it.key) }
        }
        next[foreground] = (soFar + seconds).coerceAtMost(LINGER_SECONDS)
        return next
    }

    /** The packages that have outstayed [LINGER_SECONDS] in this window. */
    fun lingering(accrued: Map<String, Long>): Set<String> =
        accrued.filterValues { it >= LINGER_SECONDS }.keys

    /**
     * Everything cut off from the network right now: the browsers plus whatever outstayed its
     * welcome, minus the apps that are never limited by anything ([spared] — the phone and
     * contacts; see `AppInventory.alwaysReachablePackages`).
     *
     * Empty whenever no window is running, which is the only "lift" this feature has and the
     * reason it cannot get stuck on.
     */
    fun cutOff(
        windowOpen: Boolean,
        browsers: Set<String>,
        lingering: Set<String>,
        spared: Set<String>,
    ): Set<String> {
        if (!windowOpen) return emptySet()
        return (browsers + lingering) - spared
    }
}
