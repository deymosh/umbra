package com.umbra.app.ui.components

import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.extractCustomEmojis
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.profile.UserProfile
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * The handle shown for an "@name" mention: prefers the kind-0 `display_name` field, falling
 * back to `name`, then a truncated pubkey via getUserDisplayName(). Returns null (raw entity
 * text) only when the profile hasn't been fetched at all.
 */
internal fun mentionDisplayHandle(profile: UserProfile?): String? {
    return profile?.getUserDisplayName()
}

private val JSON_FENCE_REGEX = Regex("""```json\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
private val CUSTOM_EMOJI_REGEX = Regex(":([A-Za-z0-9_+-]+):")
private const val PARSE_CACHE_MAX_SIZE = 220

// jsonFenceSegmentsCache, inlineMediaSegmentsCache, inlineUrlSegmentsCache, and jsonDetectCache
// are only ever read/written from remember { } blocks during Compose composition (see
// NostrTextRenderer.kt) — main-thread-only, single-writer, so they're deliberately left
// unsynchronized. If a future caller needs one of these off the main thread, route it through
// readCache/writeCache (or add its own lock) instead of assuming direct map access is safe.
private val jsonFenceSegmentsCache = object : LinkedHashMap<String, List<ContentSegment>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ContentSegment>>): Boolean {
        return size > PARSE_CACHE_MAX_SIZE
    }
}

private val inlineMediaSegmentsCache = object : LinkedHashMap<String, List<InlineMediaSegment>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<InlineMediaSegment>>): Boolean {
        return size > PARSE_CACHE_MAX_SIZE
    }
}

private val inlineUrlSegmentsCache = object : LinkedHashMap<String, List<InlineMediaSegment>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<InlineMediaSegment>>): Boolean {
        return size > PARSE_CACHE_MAX_SIZE
    }
}

private val jsonDetectCache = object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
        return size > PARSE_CACHE_MAX_SIZE
    }
}

// Unlike the caches above, prettyJsonCache is populated from NostrTextRenderer's produceState via
// withContext(Dispatchers.Default) — genuinely concurrent background-thread-pool writers — so it
// keeps synchronized access via readCache/writeCache below.
private val prettyJsonCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
        return size > PARSE_CACHE_MAX_SIZE
    }
}

private fun <T> readCache(cache: LinkedHashMap<String, T>, key: String): T? = synchronized(cache) { cache[key] }

private fun <T> writeCache(cache: LinkedHashMap<String, T>, key: String, value: T): T {
    synchronized(cache) {
        cache[key] = value
    }
    return value
}

internal sealed class ContentSegment {
    data class Text(val value: String) : ContentSegment()
    data class JsonFence(val value: String) : ContentSegment()
}

internal sealed class InlineMediaSegment {
    data class Text(val value: String) : InlineMediaSegment()
    data class ImageUrl(val url: String) : InlineMediaSegment()
    data class VideoUrl(val url: String) : InlineMediaSegment()
    data class Url(val url: String) : InlineMediaSegment()
    // eventId is either a 64-hex event id (note1/nevent1) or the raw naddr1 bech32 string
    // (addressable events have no single id — see resolveEventReference) — same shape
    // EventCard's getQuotedEvent(id)/getQuotedEventAuthorProfile(id) already expect. relays
    // carries nevent1's NIP-19 hints (empty for note1/naddr1), used only when the click handler
    // re-encodes for navigation (see encodeQuoteReferenceForClick) — never for the id-keyed
    // lookups above.
    data class QuoteReference(val eventId: String, val relays: List<String> = emptyList()) : InlineMediaSegment()
    // invoice is the bare bolt11 string (lightning:/nostr: prefix already stripped) — the
    // renderer decodes it via domain.lightning.parseBolt11 itself rather than carrying a
    // pre-decoded Bolt11Invoice here, keeping this sealed class free of domain-layer imports.
    data class LightningInvoice(val invoice: String) : InlineMediaSegment()
    // lnurl is the bare bech32 string (lightning:/nostr: prefix already stripped, same as
    // LightningInvoice above) — see LNURL_REGEX's doc comment for why this never gets decoded
    // locally the way a BOLT11 invoice does.
    data class LnurlReference(val lnurl: String) : InlineMediaSegment()
}

internal fun buildAnnotatedText(
    text: String,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    customEmojis: Map<String, CustomEmoji>,
    mentionProfiles: Map<String, UserProfile>,
    onMentionClick: (String) -> Unit,
    onEventReferenceClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    // Event ids (hex, lowercase) already rendered as an inline QuotedNoteCard embed elsewhere
    // in this event — their raw nostr:note1/nevent1/naddr1 reference is omitted from the text
    // entirely so the quote isn't shown twice.
    hiddenEventIds: Set<String> = emptySet()
): AnnotatedString {
    return buildAnnotatedString {
        var lastIndex = 0

        val patterns = listOf(
            ENTITY_NOSTR_REGEX to "entity",
            ENTITY_NOSTR_SCHEME_REGEX to "entity",
            // Must come before MENTION_REGEX: both match the same starting position for
            // "@npub1..."/"@nprofile1..."/etc, and allMatches.sortBy is a stable sort, so
            // whichever is added to allMatches first wins that tie (see AT_PREFIXED_ENTITY_REGEX's
            // own doc comment).
            AT_PREFIXED_ENTITY_REGEX to "entity",
            ENTITY_BARE_REGEX to "entity",
            MENTION_REGEX to "mention",
            HASHTAG_REGEX to "hashtag",
            URL_REGEX to "url",
            BOLD_REGEX to "bold",
            ITALIC_REGEX to "italic",
            STRIKETHROUGH_REGEX to "strikethrough",
            INLINE_CODE_REGEX to "code"
        )

        val allMatches = mutableListOf<Triple<IntRange, String, String>>()

        CUSTOM_EMOJI_REGEX.findAll(text).forEach { match ->
            val shortcode = match.groupValues.getOrNull(1).orEmpty()
            if (customEmojis.containsKey(shortcode)) {
                allMatches.add(Triple(match.range, shortcode, "custom_emoji"))
            }
        }

        for ((pattern, type) in patterns) {
            pattern.findAll(text).forEach { match ->
                allMatches.add(Triple(match.range, match.value, type))
            }
        }

        allMatches.sortBy { it.first.first }

        for ((range, matchText, type) in allMatches) {
            if (range.first < lastIndex) continue

            val normalizedEntity = normalizeNostrEntity(matchText)
            val resolvedType = when {
                type != "entity" -> type
                normalizedEntity.startsWith("npub1") -> "npub_ref"
                normalizedEntity.startsWith("nprofile1") -> "profile_ref"
                normalizedEntity.startsWith("note1") || normalizedEntity.startsWith("nevent1") || normalizedEntity.startsWith("naddr1") -> "event_ref"
                else -> "mention"
            }

            if (resolvedType == "event_ref" && hiddenEventIds.isNotEmpty()) {
                val resolvedEventId = resolveEventReference(normalizedEntity)?.lowercase()
                if (resolvedEventId != null && resolvedEventId in hiddenEventIds) {
                    // Already shown as an inline QuotedNoteCard — drop the raw reference and
                    // any whitespace right before it, so it doesn't leave a dangling blank line.
                    append(text.substring(lastIndex, range.first).trimEnd(' ', '\n', '\t'))
                    lastIndex = range.last + 1
                    continue
                }
            }

            if (lastIndex < range.first) {
                append(text.substring(lastIndex, range.first))
            }

            if (resolvedType == "custom_emoji") {
                val inlineId = customEmojiInlineContentId(matchText)
                appendInlineContent(inlineId, ":$matchText:")
                lastIndex = range.last + 1
                continue
            }

            val style = when (resolvedType) {
                "profile_ref", "event_ref", "npub_ref" -> SpanStyle(
                    color = primaryColor
                )
                "mention" -> SpanStyle(
                    color = secondaryColor,
                )
                "hashtag" -> SpanStyle(
                    color = secondaryColor,
                    fontStyle = FontStyle.Italic
                )
                "url" -> SpanStyle(
                    color = tertiaryColor,
                    textDecoration = TextDecoration.Underline
                )
                "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
                "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
                "strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                "code" -> SpanStyle(fontFamily = FontFamily.Monospace)
                else -> SpanStyle()
            }

            val parsedUrl = if (resolvedType == "url") parseExternalUrlCandidate(matchText) else null

            val displayText = when (resolvedType) {
                "url" -> parsedUrl?.displayUrl ?: sanitizeDetectedUrl(matchText)
                // matchText here is still the full delimited match (e.g. "**bold**") — allMatches
                // never stored the capture group, only match.value — so the display text is
                // re-derived by matching the same regex again and taking its group 1, mirroring
                // how the "url" case above re-derives its own displayText from matchText rather
                // than threading a second value through allMatches.
                "bold" -> BOLD_REGEX.matchEntire(matchText)?.groupValues?.getOrNull(1) ?: matchText
                "italic" -> ITALIC_REGEX.matchEntire(matchText)?.groupValues?.getOrNull(1) ?: matchText
                "strikethrough" -> STRIKETHROUGH_REGEX.matchEntire(matchText)?.groupValues?.getOrNull(1) ?: matchText
                "code" -> INLINE_CODE_REGEX.matchEntire(matchText)?.groupValues?.getOrNull(1) ?: matchText
                "npub_ref", "profile_ref" -> {
                    // mentionProfiles is keyed by hex pubkey (NostrTextRenderer resolves both
                    // npub1 and nprofile1 via resolveProfileReference before fetching profiles),
                    // not by the raw bech32 entity — must decode nprofile1's TLV-embedded pubkey
                    // (and npub1's) the same way, or the lookup always misses.
                    val resolvedPubkey = resolveProfileReference(normalizedEntity)
                    val handle = mentionDisplayHandle(mentionProfiles[resolvedPubkey])
                    if (handle != null) "@$handle" else matchText
                }
                else -> matchText
            }
            val trailingText = if (resolvedType == "url" && matchText.startsWith(displayText)) {
                matchText.removePrefix(displayText)
            } else {
                ""
            }
            val itemValue = when (resolvedType) {
                "npub_ref", "profile_ref" -> normalizedEntity
                "event_ref" -> displayText
                "url" -> parsedUrl?.normalizedUrl ?: normalizeExternalUrl(displayText)
                else -> matchText
            }
            val link = LinkAnnotation.Clickable(
                tag = resolvedType,
                linkInteractionListener = { _: LinkAnnotation ->
                    when (resolvedType) {
                        "mention", "profile_ref", "npub_ref" -> onMentionClick(itemValue)
                        "event_ref" -> onEventReferenceClick(itemValue)
                        "hashtag" -> onHashtagClick(itemValue)
                        "url" -> onUrlClick(itemValue)
                    }
                }
            )

            withLink(link) {
                withStyle(style) {
                    append(displayText)
                }
            }

            if (trailingText.isNotEmpty()) {
                append(trailingText)
            }

            if (type == "hashtag") {
                append('\u200B')
            }

            lastIndex = range.last + 1
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

internal fun isLikelyJson(text: String): Boolean {
    jsonDetectCache[text]?.let { return it }

    val trimmed = text.trim()
    if (!((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]")))) {
        jsonDetectCache[text] = false
        return false
    }

    val result = runCatching {
        JsonUtils.NostrJson.parseToJsonElement(trimmed)
    }.isSuccess
    jsonDetectCache[text] = result
    return result
}

internal fun prettyFormatJson(text: String): String {
    readCache(prettyJsonCache, text)?.let { return it }

    val result = runCatching {
        val element = JsonUtils.NostrJson.parseToJsonElement(text.trim())
        JsonUtils.PrettyJsonTwoSpace.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), element
        )
    }.getOrDefault(text)

    return writeCache(prettyJsonCache, text, result)
}

internal fun splitJsonFenceSegments(text: String): List<ContentSegment> {
    jsonFenceSegmentsCache[text]?.let { return it }

    val matches = JSON_FENCE_REGEX.findAll(text).toList()
    if (matches.isEmpty()) {
        return listOf(ContentSegment.Text(text)).also { jsonFenceSegmentsCache[text] = it }
    }

    val segments = mutableListOf<ContentSegment>()
    var cursor = 0
    for (match in matches) {
        if (match.range.first > cursor) {
            val plainText = text.substring(cursor, match.range.first)
            if (plainText.isNotEmpty()) {
                segments += ContentSegment.Text(plainText)
            }
        }

        val jsonBody = match.groupValues.getOrNull(1).orEmpty()
        if (jsonBody.isNotBlank()) {
            segments += ContentSegment.JsonFence(jsonBody)
        } else {
            segments += ContentSegment.Text(match.value)
        }

        cursor = match.range.last + 1
    }

    if (cursor < text.length) {
        val tail = text.substring(cursor)
        if (tail.isNotEmpty()) {
            segments += ContentSegment.Text(tail)
        }
    }

    val result = if (segments.isEmpty()) listOf(ContentSegment.Text(text)) else segments
    jsonFenceSegmentsCache[text] = result
    return result
}

internal fun customEmojiInlineContentId(shortcode: String): String = "custom_emoji:$shortcode"

internal fun extractImageUrls(text: String): List<String> {
    return IMAGE_URL_REGEX.findAll(text)
        .mapNotNull { normalizeAndValidateExternalUrl(it.value) }
        .toList()
}

internal fun extractVideoUrls(text: String): List<String> {
    return VIDEO_URL_REGEX.findAll(text)
        .mapNotNull { normalizeAndValidateExternalUrl(it.value) }
        .toList()
}

internal fun removeMediaUrls(text: String): String {
    return VIDEO_URL_REGEX.replace(IMAGE_URL_REGEX.replace(text, ""), "").trim()
}

internal fun parseInlineMediaSegments(text: String): List<InlineMediaSegment> {
    if (text.isBlank()) return emptyList()
    inlineMediaSegmentsCache[text]?.let { return it }

    val segments = mutableListOf<InlineMediaSegment>()
    val allMatches = mutableListOf<Pair<IntRange, InlineMediaSegment>>()
    fun appendDelegatedSegments(chunk: String) {
        parseUrlsInText(chunk).forEach { delegated ->
            when (delegated) {
                is InlineMediaSegment.Text -> {
                    val normalized = delegated.value.trim()
                    if (normalized.isNotBlank()) {
                        segments.add(InlineMediaSegment.Text(normalized))
                    }
                }

                else -> segments.add(delegated)
            }
        }
    }

    IMAGE_URL_REGEX.findAll(text).forEach { match ->
        val url = normalizeAndValidateExternalUrl(match.value) ?: return@forEach
        allMatches.add(match.range to InlineMediaSegment.ImageUrl(url))
    }

    VIDEO_URL_REGEX.findAll(text).forEach { match ->
        val url = normalizeAndValidateExternalUrl(match.value) ?: return@forEach
        allMatches.add(match.range to InlineMediaSegment.VideoUrl(url))
    }

    LIGHTNING_INVOICE_REGEX.findAll(text).forEach { match ->
        allMatches.add(match.range to InlineMediaSegment.LightningInvoice(stripLightningPrefix(match.value)))
    }

    LNURL_REGEX.findAll(text).forEach { match ->
        allMatches.add(match.range to InlineMediaSegment.LnurlReference(stripLightningPrefix(match.value)))
    }

    // findQuotedEventReferenceMatches already excludes note1/nevent1/naddr1 substrings embedded
    // inside a URL (see its doc comment), so this never fights the Url segment a link like
    // ".../invite/naddr1..." would otherwise become below.
    findQuotedEventReferenceMatches(text).forEach { match ->
        val ref = resolveEventReferenceWithHints(match.groupValues[1]) ?: return@forEach
        allMatches.add(match.range to InlineMediaSegment.QuoteReference(ref.id, ref.relays))
    }

    allMatches.sortBy { it.first.first }

    if (allMatches.isEmpty()) {
        val delegated = mutableListOf<InlineMediaSegment>()
        parseUrlsInText(text).forEach { segment ->
            when (segment) {
                is InlineMediaSegment.Text -> {
                    val normalized = segment.value.trim()
                    if (normalized.isNotBlank()) {
                        delegated.add(InlineMediaSegment.Text(normalized))
                    }
                }

                else -> delegated.add(segment)
            }
        }
        return delegated.ifEmpty { listOf(InlineMediaSegment.Text(text.trim())) }
            .also { inlineMediaSegmentsCache[text] = it }
    }

    var lastIndex = 0
    for ((range, mediaSegment) in allMatches) {
        if (range.first < lastIndex) continue

        if (lastIndex < range.first) {
            val beforeText = text.substring(lastIndex, range.first)
            appendDelegatedSegments(beforeText)
        }

        segments.add(mediaSegment)
        lastIndex = range.last + 1
    }

    if (lastIndex < text.length) {
        val afterText = text.substring(lastIndex)
        appendDelegatedSegments(afterText)
    }

    return segments.ifEmpty { listOf(InlineMediaSegment.Text(text)) }
        .also { inlineMediaSegmentsCache[text] = it }
}

/**
 * Reclassifies plain [InlineMediaSegment.Url] segments as [InlineMediaSegment.ImageUrl]/
 * [InlineMediaSegment.VideoUrl] when a matching NIP-92 `imeta` tag declares an image/video mime
 * type — covers extensionless media URLs (common on Blossom/hash-path servers) that
 * [IMAGE_URL_REGEX]/[VIDEO_URL_REGEX] can never match since they key off the URL's file
 * extension. Applied as a post-processing pass over the (text-only-keyed, cached) output of
 * [parseInlineMediaSegments]/[parseUrlsInText] rather than threaded into their own cache key, so
 * the same text with different tags doesn't require separate cache entries.
 *
 * [imetaByUrl] is expected to be keyed the same way [InlineMediaSegment.Url.url] is produced
 * (i.e. via [parseExternalUrlCandidate]'s normalization) — an imeta tag whose raw `url` field
 * differs from that normalized form (trailing slash, percent-encoding, scheme case) simply won't
 * match here, same as the existing imeta-decoration lookups elsewhere in this file.
 */
internal fun reclassifyUrlSegmentsWithImeta(
    segments: List<InlineMediaSegment>,
    imetaByUrl: Map<String, ImetaTag>
): List<InlineMediaSegment> {
    if (imetaByUrl.isEmpty()) return segments
    return segments.map { segment ->
        if (segment !is InlineMediaSegment.Url) return@map segment
        val mimeType = imetaByUrl[segment.url]?.mimeType ?: return@map segment
        when {
            mimeType.startsWith("image/", ignoreCase = true) -> InlineMediaSegment.ImageUrl(segment.url)
            mimeType.startsWith("video/", ignoreCase = true) -> InlineMediaSegment.VideoUrl(segment.url)
            else -> segment
        }
    }
}

internal fun parseUrlsInText(text: String): List<InlineMediaSegment> {
    if (text.isBlank()) return listOf(InlineMediaSegment.Text(text))
    inlineUrlSegmentsCache[text]?.let { return it }

    val segments = mutableListOf<InlineMediaSegment>()
    val urlMatches = mutableListOf<Pair<IntRange, String>>()

    URL_REGEX.findAll(text).forEach { match ->
        if (IMAGE_URL_REGEX.matches(match.value) || VIDEO_URL_REGEX.matches(match.value)) {
            return@forEach
        }
        val url = parseExternalUrlCandidate(match.value)?.normalizedUrl
        if (url != null) {
            urlMatches.add(match.range to url)
        }
    }

    if (urlMatches.isEmpty()) {
        return listOf(InlineMediaSegment.Text(text)).also { inlineUrlSegmentsCache[text] = it }
    }

    urlMatches.sortBy { it.first.first }

    var lastIndex = 0
    for ((range, url) in urlMatches) {
        if (range.first < lastIndex) continue

        if (lastIndex < range.first) {
            val beforeText = text.substring(lastIndex, range.first).trim()
            if (beforeText.isNotBlank()) {
                segments.add(InlineMediaSegment.Text(beforeText))
            }
        }

        segments.add(InlineMediaSegment.Url(url))
        lastIndex = range.last + 1
    }

    if (lastIndex < text.length) {
        val afterText = text.substring(lastIndex).trim()
        if (afterText.isNotBlank()) {
            segments.add(InlineMediaSegment.Text(afterText))
        }
    }

    return segments.ifEmpty { listOf(InlineMediaSegment.Text(text)) }
        .also { inlineUrlSegmentsCache[text] = it }
}

internal fun clearTextParsingCachesForTest() {
    jsonFenceSegmentsCache.clear()
    inlineMediaSegmentsCache.clear()
    inlineUrlSegmentsCache.clear()
    jsonDetectCache.clear()
    synchronized(prettyJsonCache) { prettyJsonCache.clear() }
}

internal data class TextParsingCacheSnapshot(
    val jsonFenceSize: Int,
    val inlineMediaSize: Int,
    val inlineUrlSize: Int,
    val jsonDetectSize: Int,
    val prettyJsonSize: Int,
    val maxSize: Int
)

internal fun textParsingCacheSnapshotForTest(): TextParsingCacheSnapshot {
    val jsonFenceSize = jsonFenceSegmentsCache.size
    val inlineMediaSize = inlineMediaSegmentsCache.size
    val inlineUrlSize = inlineUrlSegmentsCache.size
    val jsonDetectSize = jsonDetectCache.size
    val prettyJsonSize = synchronized(prettyJsonCache) { prettyJsonCache.size }
    return TextParsingCacheSnapshot(
        jsonFenceSize = jsonFenceSize,
        inlineMediaSize = inlineMediaSize,
        inlineUrlSize = inlineUrlSize,
        jsonDetectSize = jsonDetectSize,
        prettyJsonSize = prettyJsonSize,
        maxSize = PARSE_CACHE_MAX_SIZE
    )
}

