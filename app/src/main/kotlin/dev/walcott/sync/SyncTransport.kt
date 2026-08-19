package dev.walcott.sync

/**
 * Dumb, replaceable message bus: publish/subscribe of opaque strings. ntfy is the v1 impl;
 * because the sync layer converges by snapshots, swapping this out (self-hosted ntfy, FCM…)
 * doesn't touch the rest of the app.
 */
interface SyncTransport {
    fun publish(message: String)
    /** [onMessage] gets the body plus the server-side receive time in unix seconds (0 if unknown). */
    fun connect(onMessage: (body: String, timeSec: Long) -> Unit)
    fun close()

    /**
     * The device just got a usable network back.
     *
     * A transport that is sitting out a reconnect backoff should stop waiting and try now: the
     * backoff exists to stop a phone with no signal hammering the radio, and the moment the
     * signal returns it is pure latency. Default no-op, since a transport with no reconnect of
     * its own has nothing to bring forward.
     */
    fun onNetworkAvailable() {}
}
