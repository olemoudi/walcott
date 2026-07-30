package dev.walcott.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A time-boxed record of which domains each app was seen resolving, for the parent who picks up
 * the child's phone to find out what to block.
 *
 * The DNS tunnel already knows both halves — it parses the question and attributes the socket to
 * an app before deciding — so this is a window onto a decision the device was making anyway.
 * What makes it safe to offer is what it refuses to be:
 *
 * - **Off unless asked.** A session is started deliberately and expires by itself; nothing here
 *   accumulates in the background.
 * - **Never written down.** Sightings live in this process and nowhere else: no file, no
 *   database, no sync. Only the domains a parent explicitly selects ever leave the device, and
 *   the record is dropped the moment the session ends.
 * - **Bounded.** At most [MAX_SIGHTINGS] pairs, oldest sighting evicted first, so a chatty app
 *   can't grow it without limit.
 *
 * The clock is a parameter throughout: expiry and ordering are the whole behaviour, and a test
 * that has to wait fifteen real minutes to check them is a test nobody runs.
 */
object DomainMonitor {

    /** One app seen asking for one domain. [count] is how many lookups, not how many packets. */
    data class Sighting(
        val packageName: String?,
        val domain: String,
        val count: Int,
        val lastSeenMs: Long,
    )

    data class State(
        /** Wall clock at which the session stops by itself; 0 when no session is running. */
        val activeUntilMs: Long = 0,
        /** Newest first, so the app the parent just used is at the top when they come back. */
        val sightings: List<Sighting> = emptyList(),
    ) {
        fun isActive(nowMs: Long): Boolean = nowMs < activeUntilMs

        /** Sightings grouped by app, each group and the groups themselves newest first. */
        fun byApp(): List<Pair<String?, List<Sighting>>> =
            sightings.groupBy { it.packageName }.entries
                .sortedByDescending { entry -> entry.value.maxOf { it.lastSeenMs } }
                .map { it.key to it.value.sortedByDescending { s -> s.lastSeenMs } }
    }

    /** How long a session lasts. Long enough to open an app and poke at it, short enough to forget. */
    const val SESSION_MILLIS = 15 * 60 * 1000L

    /** Cap on distinct (app, domain) pairs kept; the oldest sighting is evicted first. */
    const val MAX_SIGHTINGS = 300

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()

    /** Whether the tunnel must stay up for this feature right now. */
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean = _state.value.isActive(nowMs)

    /** Starts (or restarts) a session, discarding anything seen in a previous one. */
    fun start(nowMs: Long = System.currentTimeMillis(), durationMs: Long = SESSION_MILLIS) {
        synchronized(lock) { _state.value = State(activeUntilMs = nowMs + durationMs) }
    }

    /** Ends the session and forgets everything it saw. */
    fun stop() {
        synchronized(lock) { _state.value = State() }
    }

    /**
     * Notes that [packageName] looked up [host]. Called from the tunnel's packet loop, so it
     * does nothing at all — no allocation, no lock — while no session is running.
     */
    fun record(host: String, packageName: String?, nowMs: Long = System.currentTimeMillis()) {
        val domain = host.lowercase().trimEnd('.')
        if (domain.isEmpty()) return
        val current = _state.value
        if (!current.isActive(nowMs)) {
            // A session that ran out drops its record on the next lookup, so nothing lingers
            // just because the screen that would have cleared it was never opened again.
            if (current.sightings.isNotEmpty() || current.activeUntilMs != 0L) stop()
            return
        }
        synchronized(lock) {
            val state = _state.value
            if (!state.isActive(nowMs)) return
            val existing = state.sightings.firstOrNull { it.packageName == packageName && it.domain == domain }
            val updated = if (existing != null) {
                state.sightings - existing + existing.copy(count = existing.count + 1, lastSeenMs = nowMs)
            } else {
                state.sightings + Sighting(packageName, domain, count = 1, lastSeenMs = nowMs)
            }
            _state.value = state.copy(
                sightings = updated
                    .sortedByDescending { it.lastSeenMs }
                    .take(MAX_SIGHTINGS),
            )
        }
    }
}
