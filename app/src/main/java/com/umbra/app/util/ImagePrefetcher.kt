package com.umbra.app.util

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import com.umbra.app.TorProxyConfig
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePrefetcher @Inject constructor(
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context,
    private val mediaLoadPriorityGate: MediaLoadPriorityGate
) {
    private val TAG = "ImagePrefetcher"
    private val logger = UmbraLog.tag(TAG)

    // Limit concurrent network prefetches to avoid saturating Tor / device
    private val concurrency = 3
    private val semaphore = Semaphore(concurrency)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val asyncJobs = ConcurrentHashMap<String, Job>()
    private val prefetchHistoryByScope = ConcurrentHashMap<String, LinkedHashSet<String>>()

    // No Compose context here to read an actual target render size from (unlike
    // NostrImageComponents' on-screen requests) — a device-screen-relative bound is a reasonable
    // stand-in so prefetch doesn't decode/cache a full-resolution bitmap purely to warm the disk
    // cache ahead of scroll; the correctly-sized decode still happens when the image is actually
    // rendered on screen.
    private val prefetchMaxWidthPx: Int by lazy { context.resources.displayMetrics.widthPixels.coerceAtLeast(1) }
    private val prefetchMaxHeightPx: Int by lazy { (context.resources.displayMetrics.heightPixels * 3).coerceAtLeast(1) }

    init {
        mediaLoadPriorityGate.addInteractiveLoadStartedListener(::cancelAllPending)
    }

    suspend fun prefetch(url: String, maxWaitMs: Long = 30_000L) {
        if (url.isBlank() || mediaLoadPriorityGate.isInteractiveLoadActive) return

        // Wait for Tor to be ready (bounded wait)
        if (!TorProxyConfig.isReady) {
            var waited = 0L
            val pollMs = 500L
            while (!TorProxyConfig.isReady && waited < maxWaitMs) {
                delay(pollMs)
                waited += pollMs
            }
            if (!TorProxyConfig.isReady) {
                logger.d { "Tor not ready; skipping prefetch: ${scrubUrlForLogs(url)}" }
                return
            }
        }

        try {
            semaphore.withPermit {
                if (mediaLoadPriorityGate.isInteractiveLoadActive) return@withPermit

                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(prefetchMaxWidthPx, prefetchMaxHeightPx)
                    .precision(Precision.INEXACT)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build()

                try {
                    // Execute synchronously on IO so semaphore bounds active network fetches
                    imageLoader.execute(request)
                } catch (e: Exception) {
                    logger.d { "Prefetch failed for ${scrubUrlForLogs(url)}: ${scrubThrowableMessageForLogs(e)}" }
                }
            }
        } catch (e: Exception) {
            logger.d { "Prefetch error for ${scrubUrlForLogs(url)}: ${scrubThrowableMessageForLogs(e)}" }
        }
    }

    fun prefetchAsync(url: String, scopeTag: String = "default") {
        if (url.isBlank() || mediaLoadPriorityGate.isInteractiveLoadActive) return
        val key = "$scopeTag::$url"

        val existing = asyncJobs[key]
        if (existing != null && existing.isActive) return

        val job = scope.launch {
            try {
                prefetch(url)
            } finally {
                asyncJobs.remove(key)
            }
        }
        asyncJobs[key] = job
    }

    fun prefetchWindowUrls(
        scopeTag: String,
        urls: List<String>,
        historyLimit: Int = 400
    ) {
        if (urls.isEmpty() || mediaLoadPriorityGate.isInteractiveLoadActive) return

        val uniqueUrls = urls.distinct()
        cancelPendingExcept(scopeTag = scopeTag, keepUrls = uniqueUrls.toSet())

        val history = prefetchHistoryByScope.getOrPut(scopeTag) { LinkedHashSet() }
        synchronized(history) {
            uniqueUrls.forEach { url ->
                if (!history.add(url)) return@forEach
                while (history.size > historyLimit) {
                    val oldest = history.firstOrNull() ?: break
                    history.remove(oldest)
                }
                prefetchAsync(url, scopeTag)
            }
        }
    }

    fun cancelPendingExcept(scopeTag: String, keepUrls: Set<String>) {
        val prefix = "$scopeTag::"
        asyncJobs.entries.forEach { (key, job) ->
            if (!key.startsWith(prefix)) return@forEach

            val url = key.removePrefix(prefix)
            if (url in keepUrls) return@forEach

            if (job.isActive) {
                job.cancel()
            }
            asyncJobs.remove(key)
        }
    }

    fun cancelAll(scopeTag: String) {
        val prefix = "$scopeTag::"
        asyncJobs.entries.forEach { (key, job) ->
            if (!key.startsWith(prefix)) return@forEach
            if (job.isActive) {
                job.cancel()
            }
            asyncJobs.remove(key)
        }
    }

    private fun cancelAllPending() {
        asyncJobs.entries.forEach { (key, job) ->
            if (job.isActive) {
                job.cancel()
            }
            asyncJobs.remove(key)
        }
    }

    fun resetScope(scopeTag: String) {
        cancelAll(scopeTag)
        prefetchHistoryByScope.remove(scopeTag)
    }
}
