package dev.walcott.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One process-wide OkHttp client. Every OkHttpClient owns a dispatcher executor and a
 * connection pool that are never shut down, so building a fresh client per transport,
 * per update check and per poll (as the code used to) slowly churned threads for the
 * lifetime of an always-on process. Derive variants with [OkHttpClient.newBuilder],
 * which shares the pools.
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }

    /**
     * The variant long-lived WebSockets must use. OkHttp sends no pings by default, so a
     * connection dropped without a FIN — a carrier or NAT timeout, which is the normal way a
     * mobile socket dies — leaves a half-open socket that never reports a failure and never
     * reconnects. On the child that is the whole inbound channel: rules, granted time and every
     * remote command stop arriving while the device still looks healthy to the parent, because
     * publishing is a separate HTTP call and keeps working.
     *
     * With a ping interval the pong that doesn't come fails the socket within ~a ping, which is
     * what makes [dev.walcott.sync.NtfyTransport]'s reconnect fire at all. Shares the pools.
     *
     * FOUR MINUTES, not the thirty seconds this started at. A ping is a tiny packet, but on a
     * mobile network it drags the radio out of idle and the RRC tail then holds it up for another
     * five to ten seconds — so a 30 s ping kept the radio awake something like a third of the
     * time, on both phones, for ever. Four minutes is comfortably inside the carrier and NAT
     * timeouts a keepalive exists to beat (5-30 min is the usual range), and it costs about a
     * tenth of the wakeups.
     *
     * What that buys back in detection latency is affordable because it was never the only line
     * of defence: a dead socket is noticed within ~8 minutes here, and the child's 30-minute
     * heartbeat rebuilds anything this misses ([dev.walcott.sync.ChannelHealth.needsReconnect]).
     */
    val webSocketClient: OkHttpClient by lazy {
        client.newBuilder().pingInterval(IDLE_PING_MINUTES, TimeUnit.MINUTES).build()
    }

    /**
     * The variant used while someone is actually looking at the app.
     *
     * The four-minute interval above is tuned for the case that dominates the day: nobody
     * holding the phone, screen off, radio asleep. It is the wrong trade the moment a person is
     * waiting on an answer — a child who has just asked for more time and is watching the
     * screen, a parent who opened the app to approve it. A silently dead socket costs them up to
     * eight minutes of nothing happening, and neither of them knows to try again.
     *
     * While the app is in the foreground that cost is worth avoiding and the saving is not worth
     * having: the screen is on, so the radio is up regardless, and a ping every forty-five
     * seconds disappears into what the display is already drawing. Switching between the two
     * means rebuilding the socket — OkHttp fixes the ping interval when the client is built — so
     * [dev.walcott.sync.SyncManager] only does it for real transitions, never for app-switching
     * churn. The rebuild is itself the liveness check the waiting person wanted.
     */
    val activeWebSocketClient: OkHttpClient by lazy {
        client.newBuilder().pingInterval(ACTIVE_PING_SECONDS, TimeUnit.SECONDS).build()
    }

    private const val IDLE_PING_MINUTES = 4L
    private const val ACTIVE_PING_SECONDS = 45L
}
