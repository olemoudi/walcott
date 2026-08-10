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
        client.newBuilder().pingInterval(4, TimeUnit.MINUTES).build()
    }
}
