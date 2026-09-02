package com.umbra.app.domain.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Sanitizes tracking tokens from URLs contained in user-authored text.
 */
object TrackingTokenSanitizer {

    data class SanitizationResult(
        val sanitizedText: String,
        val removedTrackingTokens: Boolean
    )

    private val urlRegex = Regex("""(https?://\S+?)(?=[.,;:!?]*(?:\s|$))""")

    private val globalExactKeys = setOf(
        "fbclid",
        "gclid",
        "dclid",
        "msclkid",
        "mc_cid",
        "mc_eid",
        "twclid",
        "gbraid",
        "wbraid",
        "igshid",
        "vero_id",
        "oly_anon_id",
        "oly_enc_id",
        "mkt_tok",
        "pk_campaign",
        "pk_kwd",
        "_hsenc",
        "_hsmi",
        "ref_src",
        "ref_url",
        "spm",
        "scid",
        "yclid"
    )

    private val globalPrefixKeys = listOf("utm_")

    private val hostSpecificTrackingKeys: Map<Regex, Set<String>> = mapOf(
        Regex("""(^|\.)x\.com$|(^|\.)twitter\.com$|(^|\.)t\.co$""") to setOf(
            "s", "t", "src", "ref_src", "ref_url", "twclid"
        ),
        Regex("""(^|\.)youtube\.com$|(^|\.)youtu\.be$|(^|\.)yt\.be$""") to setOf(
            "feature", "gclid", "fbclid", "si", "is", "pp"
        ),
        Regex("""(^|\.)spotify\.com$""") to setOf(
            "si", "nd", "context", "context_id", "sp_cid", "sp_ac", "sp_gaid",
            "sp_aid", "go", "fbclid", "product", "referral"
        )
    )

    private val redirectHosts = setOf("www.google.com", "google.com")
    private val redirectParamKeys = setOf("q", "url")

    fun sanitizeText(input: String): String {
        if (input.isBlank()) return input

        return urlRegex.replace(input) { match ->
            sanitizeUrl(match.value)
        }
    }

    fun sanitizeTextWithResult(input: String): SanitizationResult {
        val sanitized = sanitizeText(input)
        return SanitizationResult(
            sanitizedText = sanitized,
            removedTrackingTokens = sanitized != input
        )
    }

    /**
     * Builds an `OutlinedTextField`-style `onValueChange` lambda that sanitizes tracking tokens
     * out of every keystroke before handing the result to [setText], then reports whether *this*
     * keystroke's text contained one via [onSanitized]. Every text field that wants this behavior
     * (compose/reply dialogs, profile edit fields) otherwise repeats the same three-line
     * `sanitizeTextWithResult` + branch shape at each call site. Deliberately passes the raw
     * per-keystroke boolean rather than only firing on `true` — callers differ on what "removed"
     * should mean over time: a transient inline notice that should clear itself the next time the
     * user types text with no token (`{ flag = it }`) versus a monotonic per-removal-event counter
     * that should only ever go up (`{ if (it) counter++ }`).
     */
    fun sanitizingOnValueChange(
        setText: (String) -> Unit,
        onSanitized: (removedTrackingTokens: Boolean) -> Unit
    ): (String) -> Unit = { incoming ->
        val result = sanitizeTextWithResult(incoming)
        setText(result.sanitizedText)
        onSanitized(result.removedTrackingTokens)
    }

    fun sanitizeUrl(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val host = uri.host?.lowercase().orEmpty()

        if (host in redirectHosts && uri.path.equals("/url", ignoreCase = true)) {
            val redirected = extractRedirectTarget(uri.rawQuery)
            if (redirected != null) {
                return sanitizeUrl(redirected)
            }
        }

        val keysToStrip = buildSet {
            addAll(globalExactKeys)
            addAll(globalPrefixKeys)
            hostSpecificTrackingKeys.forEach { (hostRegex, keys) ->
                if (hostRegex.containsMatchIn(host)) addAll(keys)
            }
        }

        val cleanedQuery = filterQuery(uri.rawQuery, keysToStrip)
        val cleanedFragment = filterQuery(uri.rawFragment, keysToStrip)

        return URI(
            uri.scheme,
            uri.rawAuthority,
            uri.rawPath,
            cleanedQuery,
            cleanedFragment
        ).toString()
    }

    private fun extractRedirectTarget(rawQuery: String?): String? {
        val pairs = parseQuery(rawQuery)
        val target = pairs.firstOrNull { (decodedKey, _) ->
            decodedKey.lowercase() in redirectParamKeys
        }?.second ?: return null

        val decodedTarget = runCatching {
            URLDecoder.decode(target, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null

        return decodedTarget.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun filterQuery(rawQuery: String?, keysToStrip: Set<String>): String? {
        if (rawQuery.isNullOrBlank()) return rawQuery

        val kept = rawQuery.split("&")
            .filter { segment ->
                if (segment.isBlank()) return@filter false

                val rawKey = segment.substringBefore("=", missingDelimiterValue = segment)
                val decodedKey = runCatching {
                    URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                }.getOrElse { rawKey }
                val normalized = decodedKey.lowercase()

                keysToStrip.none { stripKey ->
                    if (stripKey.endsWith("_")) {
                        normalized.startsWith(stripKey)
                    } else {
                        normalized == stripKey
                    }
                }
            }

        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    private fun parseQuery(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery.isNullOrBlank()) return emptyList()

        return rawQuery.split("&")
            .filter { it.isNotBlank() }
            .map { pair ->
                val key = pair.substringBefore("=", missingDelimiterValue = pair)
                val value = pair.substringAfter("=", missingDelimiterValue = "")
                val decodedKey = runCatching {
                    URLDecoder.decode(key, StandardCharsets.UTF_8.name())
                }.getOrElse { key }
                decodedKey to value
            }
    }
}