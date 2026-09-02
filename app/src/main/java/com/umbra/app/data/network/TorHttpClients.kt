package com.umbra.app.data.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Derives a call-bounded client from the shared @Named("tor") client for one-shot HTTP requests.
 * The shared tor client has readTimeout=0 (unbounded) — correct for long-lived relay WebSockets,
 * wrong for a one-shot GET/PUT/HEAD/DELETE. Always call on the injected tor client — never a
 * fresh OkHttpClient.Builder() (see AUDIT.md §1.1); newBuilder() inherits the proxy selector.
 */
fun OkHttpClient.boundedForOneShotCall(
    callTimeoutSeconds: Long,
    readTimeoutSeconds: Long? = null,
    writeTimeoutSeconds: Long? = null,
    connectTimeoutSeconds: Long? = null
): OkHttpClient = newBuilder()
    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    .apply { readTimeoutSeconds?.let { readTimeout(it, TimeUnit.SECONDS) } }
    .apply { writeTimeoutSeconds?.let { writeTimeout(it, TimeUnit.SECONDS) } }
    .apply { connectTimeoutSeconds?.let { connectTimeout(it, TimeUnit.SECONDS) } }
    .build()
