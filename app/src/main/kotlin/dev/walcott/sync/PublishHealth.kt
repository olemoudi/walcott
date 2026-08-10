package dev.walcott.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this device's messages are actually being accepted by the relay.
 *
 * Publishing is a plain HTTP POST whose result nobody was reading: a rejection was one line in
 * the debug log and nothing else. That hides the two failures a family can actually do something
 * about — a relay that is rate-limiting them (the public server has per-visitor limits, and a
 * family with several children checking in, reporting rules and sending locations can reach
 * them), and one that is down or blocked on their network. In both cases the phones keep looking
 * healthy to each other, because the *inbound* socket is a separate connection.
 *
 * So the outcome is counted, and a run of failures becomes something the app can say out loud —
 * pointing at the one setting that fixes it, the relay server itself.
 */
object PublishHealth {

    /**
     * Consecutive failures before this is worth a family's attention. Three, because the
     * transport already retries within a single publish: reaching three means three separate
     * messages, each retried, all rejected — not one bad moment on a train.
     */
    const val FAILURES_BEFORE_ALERT = 3

    data class Status(
        val consecutiveFailures: Int = 0,
        /** HTTP status of the last rejection (429 = rate limited), or 0 for a network failure. */
        val lastRejectionCode: Int = 0,
        val lastFailureAtMs: Long = 0,
    ) {
        /** True once the failures are consistent enough to be worth telling someone about. */
        val failing: Boolean get() = consecutiveFailures >= FAILURES_BEFORE_ALERT

        /** True when the relay is refusing us for sending too much, which needs its own advice. */
        val rateLimited: Boolean get() = failing && lastRejectionCode == HTTP_TOO_MANY_REQUESTS
    }

    const val HTTP_TOO_MANY_REQUESTS = 429

    private val mutable = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutable.asStateFlow()

    /**
     * The status after one publish outcome. Pure: "how many in a row" is the whole signal, and
     * a single success has to clear it completely — a channel that works is not half-broken
     * because it was broken this morning.
     */
    fun next(previous: Status, ok: Boolean, code: Int, atMs: Long): Status =
        if (ok) {
            Status()
        } else {
            Status(previous.consecutiveFailures + 1, code, atMs)
        }

    fun record(ok: Boolean, code: Int = 0, atMs: Long = System.currentTimeMillis()) {
        mutable.value = next(mutable.value, ok, code, atMs)
    }
}
