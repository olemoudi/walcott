package dev.walcott.sync

/**
 * Dumb, replaceable message bus: publish/subscribe of opaque strings. ntfy is the v1 impl;
 * because the sync layer converges by snapshots, swapping this out (self-hosted ntfy, FCM…)
 * doesn't touch the rest of the app.
 */
interface SyncTransport {
    fun publish(message: String)

    /**
     * Publishes and WAITS for the relay to say it took the message, answering the server second
     * it stamped on it — or null when the message did not go out.
     *
     * The ordinary [publish] is fire-and-forget on purpose: almost everything this app sends is
     * a snapshot that the next re-emit would repeat anyway, and blocking a caller on the radio to
     * learn that is a poor trade. The emergency release is the one thing where "did it actually
     * leave the phone" IS the fact being established (see [PanicProtocol]) — its counter advances
     * on receipts and on nothing else — and it needs the relay's clock with it, because that
     * clock is what stops the twelve hours being compressed by moving the phone's own.
     *
     * Blocking, so callers must be off the main thread. Default: not supported.
     */
    fun publishForReceipt(message: String): Long? = null

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
