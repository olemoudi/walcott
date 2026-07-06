package dev.walcott.sync

/**
 * Dumb, replaceable message bus: publish/subscribe of opaque strings. ntfy is the v1 impl;
 * because the sync layer converges by snapshots, swapping this out (self-hosted ntfy, FCM…)
 * doesn't touch the rest of the app.
 */
interface SyncTransport {
    fun publish(message: String)
    fun connect(onMessage: (String) -> Unit)
    fun close()
}
