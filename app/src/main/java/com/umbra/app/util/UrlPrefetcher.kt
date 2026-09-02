package com.umbra.app.util

import androidx.compose.runtime.mutableStateMapOf
import com.umbra.app.TorProxyConfig
import com.umbra.app.util.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.umbra.app.ui.common.UrlMetadata
import java.net.URI

private const val PREFETCH_USER_AGENT =
    "Mozilla/5.0 (Android 14; Mobile; rv:126.0) Gecko/126.0 Firefox/126.0"
private const val MAX_METADATA_CACHE_SIZE = 128
private const val MAX_MISS_CACHE_SIZE = 128

@ViewModelScoped
class UrlPrefetcher @Inject constructor(
    @Named("tor") sharedTorClient: OkHttpClient
) {
    private val tag = "UrlPrefetcher"
    private val logger = UmbraLog.tag(tag)

    // The shared "tor" client has readTimeout=0 (unbounded), correct for long-lived relay
    // websockets but not for this one-shot HTML/OG-metadata fetch. Without a bound, a single
    // stalled fetch (e.g. right after the app resumes from background, before Orbot's circuits
    // are rebuilt) would hold its `semaphore` permit forever — with only 2 permits total, two
    // such stuck fetches permanently starve every future link preview for the rest of this
    // ViewModel's lifetime. Same bounded-call pattern already used by RelayInfoRepositoryImpl/
    // Nip05RepositoryImpl/TorStatusRepositoryImpl for their own one-shot HTTP fetches.
    private val torClient: OkHttpClient = sharedTorClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Matches ImagePrefetcher's own concurrency (3) — a similar "prefetch attachments for
    // visible feed content" concern. Previously 2, the tightest of this codebase's three
    // prefetch/fetch semaphores (ImagePrefetcher=3, NIP-11=8) despite having no particular
    // reason to be more conservative than ImagePrefetcher specifically. Now that each individual
    // fetch is bounded by torClient's own callTimeout (see above) rather than able to hang a
    // permit forever, there's no correctness reason to keep it lower — kept below NIP-11's 8
    // since these target arbitrary user-authored URLs (any host on the internet) rather than a
    // curated relay pool, where staying conservative about simultaneous new Tor circuits to
    // unknown hosts is still a reasonable default.
    private val semaphore = Semaphore(3)
    private val asyncJobs = ConcurrentHashMap<String, Job>()
    private val prefetchHistoryByScope = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val metadataCache = mutableStateMapOf<String, UrlMetadata>()
    private val cacheOrder = LinkedHashSet<String>()
    private val knownMisses = LinkedHashSet<String>()

    suspend fun prefetch(url: String, maxWaitMs: Long = 30_000L) {
        if (url.isBlank()) return
        if (getMetadata(url) != null || isKnownMiss(url)) return

        if (!TorProxyConfig.isReady) {
            var waited = 0L
            val pollMs = 500L
            while (!TorProxyConfig.isReady && waited < maxWaitMs) {
                delay(pollMs)
                waited += pollMs
            }
            if (!TorProxyConfig.isReady) {
                logger.d { "Tor not ready; skipping URL prefetch: ${scrubUrlForLogs(url)}" }
                return
            }
        }

        semaphore.withPermit {
            runCatching {
                val getRequest = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-32768")
                    .header("Accept", "text/html,application/xhtml+xml,image/*")
                    .header("User-Agent", PREFETCH_USER_AGENT)
                    .build()
                torClient.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val contentType = response.header("Content-Type")
                    if (isImageContentType(contentType)) {
                        val imageMetadata = buildImageOnlyMetadata(
                            sourceUrl = url,
                            resolvedUrl = response.request.url.toString()
                        )
                        withContext(Dispatchers.Main) {
                            cacheMetadata(url, imageMetadata)
                        }
                        return@use
                    }

                    if (!isHtmlContentType(contentType)) {
                        withContext(Dispatchers.Main) {
                            rememberMiss(url)
                        }
                        return@use
                    }

                    val body = response.body.string()
                    val metadata = extractUrlMetadataFromHtml(url, body)
                    withContext(Dispatchers.Main) {
                        if (metadata != null) {
                            cacheMetadata(url, metadata)
                        } else {
                            rememberMiss(url)
                        }
                    }
                    if (metadata != null) {
                        logger.d { "Captured metadata for ${scrubUrlForLogs(url)}: title=${metadata.title?.take(20)}" }
                    }
                }
            }.onFailure { error ->
                logger.d { "URL prefetch failed for ${scrubUrlForLogs(url)}: ${scrubThrowableMessageForLogs(error)}" }
            }
        }
    }

    fun prefetchAsync(url: String, scopeTag: String = "default") {
        if (url.isBlank()) return
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
        historyLimit: Int = 250
    ) {
        if (urls.isEmpty()) return

        val uniqueUrls = urls.distinct().filterNot { getMetadata(it) != null || isKnownMiss(it) }
        if (uniqueUrls.isEmpty()) return
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

    fun resetScope(scopeTag: String) {
        cancelAll(scopeTag)
        prefetchHistoryByScope.remove(scopeTag)
    }

    /**
     * Get cached metadata for a URL (captured during prefetch)
     */
    fun getMetadata(url: String): UrlMetadata? = metadataCache[url]

    private fun cacheMetadata(url: String, metadata: UrlMetadata) {
        metadataCache[url] = metadata
        knownMisses.remove(url)
        cacheOrder.remove(url)
        cacheOrder.add(url)

        while (cacheOrder.size > MAX_METADATA_CACHE_SIZE) {
            val eldest = cacheOrder.firstOrNull() ?: break
            cacheOrder.remove(eldest)
            metadataCache.remove(eldest)
        }
    }

    private fun rememberMiss(url: String) {
        knownMisses.remove(url)
        knownMisses.add(url)

        while (knownMisses.size > MAX_MISS_CACHE_SIZE) {
            val eldest = knownMisses.firstOrNull() ?: break
            knownMisses.remove(eldest)
        }
    }

    private fun isKnownMiss(url: String): Boolean = url in knownMisses
}

private val META_TAG_REGEX = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
private val TITLE_TAG_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ATTRIBUTE_REGEX = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*([\"'])(.*?)\2""")

internal fun extractUrlMetadataFromHtml(url: String, html: String): UrlMetadata? {
    val ogTitle = extractMetaContentValue(html, "og:title", "twitter:title")
    val ogDescription = extractMetaContentValue(html, "og:description", "twitter:description", "description")
    val ogImageRaw = extractMetaContentValue(html, "og:image", "twitter:image")
    val ogImage = resolvePossiblyRelativeUrl(url, ogImageRaw)

    val fallbackTitle = TITLE_TAG_REGEX.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { decodeHtmlEntities(it).replace("\\s+".toRegex(), " ") }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val title = (ogTitle ?: fallbackTitle)?.take(500)
    val description = ogDescription?.take(500)
    val imageUrl = ogImage?.take(1024)
    val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

    val metadata = UrlMetadata(
        url = url,
        title = title,
        description = description,
        imageUrl = imageUrl,
        host = host
    )

    return metadata.takeIf { it.hasMetadata }
}

internal fun extractMetaContentValue(html: String, vararg keys: String): String? {
    if (keys.isEmpty()) return null
    val normalizedKeys = keys.map { it.lowercase() }.toSet()

    META_TAG_REGEX.findAll(html).forEach { tagMatch ->
        val attrs = mutableMapOf<String, String>()
        ATTRIBUTE_REGEX.findAll(tagMatch.value).forEach { attrMatch ->
            val name = attrMatch.groupValues.getOrNull(1)?.lowercase() ?: return@forEach
            val value = attrMatch.groupValues.getOrNull(3)?.trim().orEmpty()
            if (value.isNotBlank()) {
                attrs[name] = decodeHtmlEntities(value)
            }
        }

        val key = attrs["property"] ?: attrs["name"] ?: return@forEach
        if (key.lowercase() in normalizedKeys) {
            return attrs["content"]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }

    return null
}

internal fun resolvePossiblyRelativeUrl(baseUrl: String, candidate: String?): String? {
    if (candidate.isNullOrBlank()) return null
    val trimmed = candidate.trim()
    val parsed = runCatching { URI(trimmed) }.getOrNull()
    if (parsed != null && parsed.isAbsolute) return parsed.toString()

    return runCatching {
        URI(baseUrl).resolve(trimmed).toString()
    }.getOrNull()
}

private fun decodeHtmlEntities(value: String): String {
    return value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

internal fun isHtmlContentType(contentType: String?): Boolean {
    val normalized = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return false

    return normalized == "text/html" || normalized == "application/xhtml+xml"
}

internal fun isImageContentType(contentType: String?): Boolean {
    val normalized = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return false

    return normalized.startsWith("image/")
}

internal fun buildImageOnlyMetadata(sourceUrl: String, resolvedUrl: String): UrlMetadata {
    val host = runCatching { URI(resolvedUrl).host?.removePrefix("www.") }.getOrNull()
    return UrlMetadata(
        url = sourceUrl,
        title = null,
        description = null,
        imageUrl = resolvedUrl,
        host = host
    )
}

