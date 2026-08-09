package dev.walcott.sync

/**
 * When the child home should admit it has lost contact with the family. The parent already
 * sees staleness from its side; without this, a dead channel on the child looks exactly like
 * a dead app. Every received message (including the echo of this device's own ~30-min
 * heartbeat) stamps [SyncState.lastChannelOkMs], so a healthy channel refreshes it several
 * times an hour. Pure, like [Staleness], so the threshold logic is unit-tested.
 */
object ChannelHealth {

    /** Several missed heartbeat echoes — a real outage, not just Doze breathing. */
    const val OFFLINE_AFTER_MS = 2 * 60 * 60 * 1000L

    /**
     * The wall-clock ms of the last proof the channel worked, when that is long enough ago
     * to say so; null while healthy or before the first message ever (fresh installs must
     * not greet the child with a scary offline banner).
     */
    fun offlineSinceMs(lastOkMs: Long, nowMs: Long): Long? =
        if (lastOkMs > 0 && nowMs - lastOkMs >= OFFLINE_AFTER_MS) lastOkMs else null

    /**
     * How long the socket may go without delivering anything before it is presumed dead and
     * rebuilt. The device publishes at least every ~30 min and receives its own echo, so an
     * hour is two missed heartbeats: long enough that Doze deferrals and a brief tunnel outage
     * don't churn the connection, short enough to be well inside both the "you are offline"
     * banner ([OFFLINE_AFTER_MS]) and the deadline that kills an emergency release
     * ([PanicProtocol.CHECKPOINT_INTERVAL_SEC] + grace).
     */
    const val RECONNECT_AFTER_MS = 60 * 60 * 1000L

    /**
     * Whether the transport should be torn down and rebuilt. [lastProofMs] is the most recent
     * of "a message arrived" and "this socket was opened" — without the second half a socket
     * that never delivered its first message would never be suspected, which is exactly the
     * shape of the failure this catches (see [dev.walcott.net.Http.webSocketClient]).
     *
     * Reconnecting is cheap and self-healing: the new socket replays from the `since=` cursor,
     * so nothing published during the silence is lost.
     */
    fun needsReconnect(lastProofMs: Long, nowMs: Long): Boolean =
        lastProofMs > 0 && nowMs - lastProofMs >= RECONNECT_AFTER_MS
}
