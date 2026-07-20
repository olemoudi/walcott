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
}
