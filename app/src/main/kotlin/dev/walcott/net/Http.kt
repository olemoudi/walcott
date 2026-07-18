package dev.walcott.net

import okhttp3.OkHttpClient

/**
 * One process-wide OkHttp client. Every OkHttpClient owns a dispatcher executor and a
 * connection pool that are never shut down, so building a fresh client per transport,
 * per update check and per poll (as the code used to) slowly churned threads for the
 * lifetime of an always-on process. Derive variants with [OkHttpClient.newBuilder],
 * which shares the pools.
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
