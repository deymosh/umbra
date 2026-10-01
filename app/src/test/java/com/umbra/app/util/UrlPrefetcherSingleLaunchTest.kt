package com.umbra.app.util

import com.umbra.app.TorProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins UrlPrefetcher.prefetchAsync's single-launch contract: when two racing callers arrive for
 * the same scope-keyed URL, exactly one actual fetch round trip is started (the put-winner's
 * LAZY job) — the old check-then-put let both callers pass the isActive gate and launch
 * duplicate fetches. Runs against a mocked-network client so nothing touches the network and
 * the interceptor short-circuits any fetch instantly (a plain JVM unit test, no device).
 */
class UrlPrefetcherSingleLaunchTest {

    /** Counts every request passing through; answers with a minimal HTML 200. */
    private class CountingInterceptor : Interceptor {
        val fetches = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            synchronized(fetches) { fetches += chain.request().url.toString() }
            return Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(500)
                .message("short-circuited")
                .body(okhttp3.ResponseBody.EMPTY)
                .build()
        }
    }

    @Test
    fun `given two racing prefetchAsync calls for the same url when both run then only one fetch is started`() = runTest {
        TorProxyConfig.update("127.0.0.1", 9050) // skip the Tor-ready wait branch
        val interceptor = CountingInterceptor()
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val prefetcher = UrlPrefetcher(client)
        val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fetchedUrl = "https://example.test/page"

        // Two callers racing on the exact same scopeTag + url key. The latch lines them up so
        // both reach prefetchAsync's ConcurrentHashMap.compute in the same window.
        val job1 = launch(Dispatchers.Unconfined) {
            latch.await()
            prefetcher.prefetchAsync(fetchedUrl, scopeTag = "test")
        }
        val job2 = launch(Dispatchers.Unconfined) {
            latch.await()
            prefetcher.prefetchAsync(fetchedUrl, scopeTag = "test")
        }
        latch.complete(Unit)
        advanceUntilIdle()
        job1.join(); job2.join()

        // prefetchAsync launches on its own IO scope — outside this TestScope's virtual time —
        // so give the (instant, interceptor-short-circuited) fetch a real moment to run before
        // asserting. Fail clearly if no fetch ever landed.
        var observed = false
        repeat(30) {
            if (!observed && synchronized(interceptor.fetches) { interceptor.fetches.isNotEmpty() }) {
                observed = true
            }
            if (!observed) Thread.sleep(50)
        }
        org.junit.Assert.assertTrue("no fetch observed at all", observed)

        // Both racing callers must collapse into exactly one actual fetch of that URL.
        assertEquals(1, synchronized(interceptor.fetches) { interceptor.fetches.count { it == fetchedUrl } })
    }
}
