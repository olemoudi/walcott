package dev.walcott.net

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

    private val _tunnelUp = MutableStateFlow(false)
    val tunnelUp: StateFlow<Boolean> = _tunnelUp.asStateFlow()

    internal fun set(up: Boolean) {
        _tunnelUp.value = up
    }
}
