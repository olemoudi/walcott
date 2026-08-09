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
     * With a ping interval the pong that doesn't come fails the socket within ~a minute, which
     * is what makes [dev.walcott.sync.NtfyTransport]'s reconnect fire at all. Shares the pools.
     */
    val webSocketClient: OkHttpClient by lazy {
        client.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
    }
}
