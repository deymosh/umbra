package com.umbra.app.domain.nipb7

import java.net.URI

/**
 * Bare lowercase domain for a Blossom server URL, as BUD-11's `server` auth tag requires
 * (e.g. `https://cdn.example.com/` -> `cdn.example.com`). Falls back to the trimmed input
 * (schemeless, path-stripped as best-effort) if the URL can't be parsed, rather than throwing —
 * an auth token with a slightly malformed scope tag is still safer than one with none.
 */
fun blossomServerDomain(serverUrl: String): String {
    val trimmed = serverUrl.trim()
    val host = runCatching { URI(trimmed).host }.getOrNull()
    return (host ?: trimmed.substringAfter("://").substringBefore('/')).lowercase()
}

/**
 * Validates and normalizes a user-entered Blossom server URL for storage in a kind:10063 list —
 * requires an explicit `http://`/`https://` scheme and a resolvable host, trailing slash
 * stripped. Returns null for anything else (blank, no scheme, unparseable host) so the caller can
 * reject the input instead of publishing a malformed `server` tag.
 */
fun normalizeBlossomServerUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
    if (blossomServerDomain(trimmed).isBlank()) return null
    return trimmed
}

/**
 * BUD-03 client retrieval rule: the sha256 blob hash is the LAST occurrence of a 64-char hex
 * string in the URL (handles both `https://cdn.example.com/<sha256>.ext` and non-Blossom URLs
 * that happen to embed the hash elsewhere in the path, e.g. `.../user/<pubkey>/media/<sha256>.pdf`).
 * Returns null if no 64-hex-char run is present.
 */
private val SHA256_HEX_PATTERN = Regex("[0-9a-fA-F]{64}")

fun extractBlobSha256FromUrl(url: String): String? =
    SHA256_HEX_PATTERN.findAll(url).lastOrNull()?.value?.lowercase()

/** Bare filename extension (no dot), from the URL's last path segment only — never the host. */
private fun fileExtension(url: String): String? {
    val lastSegment = url.substringAfterLast('/')
    return lastSegment.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() && it.length in 1..5 && it.all(Char::isLetterOrDigit) }
}

/**
 * BUD-03 "Client Retrieval Implementation": once a media URL fails to load, extract its sha256
 * hash and retry against the author's own Blossom servers (in their declared priority order),
 * then [DefaultBlossomServer.URL] as a last resort — same fallback the app already applies to
 * its own uploads (see [preferredUploadServer]).
 *
 * Returns just `[originalUrl]` when no hash can be extracted (a non-Blossom image link has
 * nothing to retry against) or [authorServerList] is null/empty *and* the default server would
 * just repeat the original host. Order: original URL first (so a transient failure still tries
 * the real source before spending a request on a fallback), then each author server, then the
 * default — duplicates removed while preserving that order.
 */
fun blossomFallbackCandidates(originalUrl: String, authorServerList: UserServerList?): List<String> {
    val hash = extractBlobSha256FromUrl(originalUrl) ?: return listOf(originalUrl)
    val suffix = fileExtension(originalUrl)?.let { "$hash.$it" } ?: hash

    val authorCandidates = authorServerList?.servers.orEmpty()
        .map { server -> server.trimEnd('/') + "/" + suffix }
    val defaultCandidate = DefaultBlossomServer.URL.trimEnd('/') + "/" + suffix

    return (listOf(originalUrl) + authorCandidates + defaultCandidate).distinct()
}
