package dev.walcott.net

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the DNS tunnel is actually up right now.
 *
 * Asking for a tunnel and having one are different things: only one VPN can be active on
 * Android at a time, consent can be missing, and a device-policy call can be refused. Every one
 * of those ends the same way — `establish()` returns null, the filter is off, and nothing on
 * screen says so. A parent watching an empty list would conclude the app talks to nothing.
 *
 * So the answer is published, and the screens that depend on it say when it is no.
 */
object VpnStatus {

    /**
     * How long the tunnel must be down before anyone is told about it.
     *
     * Every process start begins with the tunnel down and the service still on its way up, so
     * without this the first snapshot after every reboot, every self-update and every low-memory
     * kill would report the filter as broken — an alert to the parent, and a card on the child's
     * home, for something that fixes itself a few seconds later. Reported failures have to be
     * failures, or they stop being read.
     */
    const val GRACE_MS = 90_000L

    private val _tunnelUp = MutableStateFlow(false)
    val tunnelUp: StateFlow<Boolean> = _tunnelUp.asStateFlow()

    private val _lockdown = MutableStateFlow(false)

    /**
     * Whether the phone's "Block connections without VPN" is on.
     *
     * It is the one system setting that turns this filter into a broken phone: the tun routes a
     * single /32, so with lockdown on everything else is refused outright — a raw connection to
     * an IP address, no DNS involved. Nothing here sets it (see [VpnController], which passes
     * lockdown = false on purpose); this is for noticing that somebody else did.
     */
    val lockdown: StateFlow<Boolean> = _lockdown.asStateFlow()

    /** Monotonic (so a moved clock can't shorten it) instant the tunnel was last seen down. */
    @Volatile private var downSince: Long = SystemClock.elapsedRealtime()

    internal fun set(up: Boolean, lockdown: Boolean = _lockdown.value) {
        if (up != _tunnelUp.value) {
            if (!up) downSince = SystemClock.elapsedRealtime()
            _tunnelUp.value = up
        }
        _lockdown.value = lockdown
    }

    /**
     * Whether the filter should be treated as genuinely down: not up, and not up for longer than
     * [GRACE_MS]. This — not [tunnelUp] — is what health reports and nudges are built on.
     */
    fun settledDown(): Boolean =
        !_tunnelUp.value && SystemClock.elapsedRealtime() - downSince >= GRACE_MS
}
