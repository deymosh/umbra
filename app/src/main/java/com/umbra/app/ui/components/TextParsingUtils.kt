package com.umbra.app.ui.components

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip21.NostrUriEntity
import com.umbra.app.domain.nip21.resolveNostrUri
import com.umbra.app.domain.nip21.stripNostrUriPrefix
import java.net.URI
import java.util.Locale

internal val ENTITY_NOSTR_REGEX = Regex("""nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""", RegexOption.IGNORE_CASE)
internal val ENTITY_NOSTR_SCHEME_REGEX = Regex("""nostr://(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""", RegexOption.IGNORE_CASE)
internal val ENTITY_BARE_REGEX = Regex("""\b(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)\b""", RegexOption.IGNORE_CASE)
// Not part of NIP-21 (which only defines the `nostr:`/`nostr://` prefixes) — some clients write
// mentions as `@npub1...`/`@nprofile1...` instead. Without this, MENTION_REGEX below (@handle)
// would win the tie for the same starting position and render/handle it as a plain unresolved
// @-mention instead of a real profile/event reference — see buildAnnotatedText's `patterns` list,
// where this must stay ordered before MENTION_REGEX for that tie-break to go the right way.
internal val AT_PREFIXED_ENTITY_REGEX = Regex(
    """(?<![a-zA-Z0-9._%+-])@(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""",
    RegexOption.IGNORE_CASE
)
internal val PROFILE_MENTION_REGEX = Regex("""(?:nostr:|nostr://|@)?(npub1[a-z0-9]+|nprofile1[a-z0-9]+)""", RegexOption.IGNORE_CASE)
internal val QUOTED_EVENT_REF_REGEX = Regex("""(?:nostr:|nostr://|@)?(note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""", RegexOption.IGNORE_CASE)
internal val MENTION_REGEX = Regex("""(?<![a-zA-Z0-9._%+-])@[a-zA-Z0-9_]+""")
// A hashtag is exactly one `#` followed by at least one non-whitespace character — `(?!#)` keeps
// a second `#` from matching as part of it (`##heading`, markdown-style, is not a hashtag) and
// `\S+` (not `\S*`) keeps a bare trailing `#` with nothing after it from matching as an empty tag.
internal val HASHTAG_REGEX = Regex("""(?<!\S)#(?!#)\S+""")
internal val URL_REGEX = Regex(
    """(?:(?:https?://|www\.)[^\s\]\}\"]+|(?<![@\w])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?:/[^\s\]\}\"]*)?)""",
    RegexOption.IGNORE_CASE
)
// Lightweight inline emphasis — NOT full CommonMark/markdown (headers, lists, blockquotes, etc
// are deliberately not supported): Nostr's kind:1 short notes have no spec for markdown at all,
// and a full CommonMark parser is properly reserved for long-form NIP-23 articles, not regular
// notes — a plain short-note renderer has no bold/italic/strikethrough/code segment type either.
// Headers in particular would fight NIP-30 hashtags (`#`/`##`), which is
// exactly the ambiguity HASHTAG_REGEX above was fixed to reject. These four are additive, already-
// common informal note-writing conventions with no such conflict.
// Bold: **text** — lookaheads/behinds keep leading/trailing whitespace out of the emphasized run
// (so "5 ** 3" or a stray "**" doesn't false-trigger) without needing a separate trim step.
internal val BOLD_REGEX = Regex("""\*\*(?!\s)([^*\n]+?)(?<!\s)\*\*""")
// Italic: *text* — the (?<!\*)/(?!\*) guards keep this from matching inside/adjacent to a **bold**
// pair (a lone "*" immediately next to another "*" never starts or ends an italic run).
internal val ITALIC_REGEX = Regex("""(?<!\*)\*(?!\*)(?!\s)([^*\n]+?)(?<!\s)\*(?!\*)""")
internal val STRIKETHROUGH_REGEX = Regex("""~~(?!\s)([^~\n]+?)(?<!\s)~~""")
// Inline code: `text`. splitJsonFenceSegments/JSON_FENCE_REGEX only pre-routes ```json fences
// (not a generic ``` fence with no language tag) to JsonContentBlock, so this can still see a
// bare ```fenced``` block — the same (?<!`)/(?!`) mutual-exclusion trick as bold/italic above
// keeps a backtick that's part of a `` ``` `` run from ever starting/ending a single-backtick
// match, so a generic fence falls through as plain text (today's existing behavior) instead of
// this regex unevenly eating one backtick off each end of it.
internal val INLINE_CODE_REGEX = Regex("""(?<!`)`(?!`)([^`\n]+?)(?<!`)`(?!`)""")
internal val IMAGE_URL_REGEX = Regex("""https?://[^\s]*\.(?:jpg|jpeg|png|gif|webp|bmp)(?:[^\s]*)?""", RegexOption.IGNORE_CASE)
internal val VIDEO_URL_REGEX = Regex("""https?://[^\s]*\.(?:mp4|webm|mov|qt|m3u8)(?:[^\s]*)?""", RegexOption.IGNORE_CASE)
// A BOLT11 Lightning invoice: optional `lightning:`/`nostr:`/`nostr://` URI prefix, "ln" +
// network (bc/tb/bcrt) + optional amount digits + optional m/u/n/p multiplier, then the
// mandatory "1" bech32 separator followed by a run of bech32-alphabet characters. Detection
// only — full validity (checksum, tagged-field structure) is decided by
// domain.lightning.parseBolt11, called once a match here is found.
internal val LIGHTNING_INVOICE_REGEX = Regex(
    """\b(?:lightning:|nostr:(?://)?)?ln(?:bc|tb|bcrt)[0-9]*[munp]?1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{20,}""",
    RegexOption.IGNORE_CASE
)
// An LNURL string (LUD-01 bech32-encoded https callback URL): optional `lightning:`/`nostr:`/
// `nostr://` prefix, "lnurl1" separator, then a run of bech32 data characters (the standard
// bech32 alphabet minus '1','b','i','o' — matches Amethyst's LnWithdrawalUtil.withdrawalPattern).
// Detection only, same as LIGHTNING_INVOICE_REGEX above — there's no local decode of an LNURL
// (that requires an HTTP fetch of the callback, out of scope here), so any match renders a
// generic "open in wallet" card rather than a decoded amount/description.
internal val LNURL_REGEX = Regex(
    """\b(?:lightning:|nostr:(?://)?)?lnurl1[02-9ac-hj-np-z]+""",
    RegexOption.IGNORE_CASE
)

/**
 * Strips an optional `nostr://`/`nostr:` prefix, then an optional `lightning:` prefix
 * (case-insensitive) from a raw LIGHTNING_INVOICE_REGEX/LNURL_REGEX match, leaving the bare
 * bech32 string. Segments must carry the bare string — LightningInvoiceCard/LnurlPaymentCard
 * both re-add a single `lightning:` prefix themselves when building the "Pay"/"Open" URI, so a
 * segment that still carried its own matched prefix would double it up.
 */
internal fun stripLightningPrefix(raw: String): String {
    val afterNostr = when {
        raw.startsWith("nostr://", ignoreCase = true) -> raw.substring(8)
        raw.startsWith("nostr:", ignoreCase = true) -> raw.substring(6)
        else -> raw
    }
    return if (afterNostr.startsWith("lightning:", ignoreCase = true)) afterNostr.substring(10) else afterNostr
}
private val URL_LEADING_DELIMITERS = charArrayOf('(', '[', '{', '"', '\'')
private val URL_TRAILING_DELIMITERS = charArrayOf('.', ',', ';', ':', '!', ')', ']', '}', '"', '\'')
private val HEX_64_REGEX = Regex("^[a-fA-F0-9]{64}$")
private val WINDOWS_PATH_REGEX = Regex("^[A-Za-z]:\\\\")

// File extensions that are not valid TLDs. Used to reject bare filenames like
// "file.tar.gz" or "script.py" from being parsed as bare-domain URLs.
private val KNOWN_FILE_EXTENSIONS = setOf(
    // Archives
    "gz", "tar", "zip", "bz2", "xz", "7z", "rar", "tgz", "zst",
    // Source code
    "py", "kt", "java", "js", "ts", "rb", "go", "rs", "cpp", "cc", "c", "h",
    "cs", "php", "swift", "m", "scala", "groovy", "dart", "lua", "pl", "r",
    // Config / data
    "json", "xml", "yaml", "yml", "toml", "csv", "sql", "env",
    // Text / docs
    "md", "txt", "log", "rst", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    // Binaries / packages
    "exe", "dll", "so", "apk", "ipa", "jar", "war", "whl", "gem", "deb", "rpm",
    // Scripts / build
    "sh", "bat", "ps1", "mk", "makefile",
    // Media (these are already handled via IMAGE/VIDEO_URL_REGEX when prefixed with https://)
    "mp4", "webm", "mp3", "ogg", "flac", "wav", "avi", "mov", "qt",
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico",
    // Other common non-TLD suffixes
    "iso", "img", "bin", "conf", "cfg", "ini", "lock"
)

internal data class ParsedExternalUrl(
    val displayUrl: String,
    val normalizedUrl: String
)

internal fun sanitizeDetectedUrl(raw: String): String {
    var value = raw.trim()
    while (value.isNotEmpty() && value.first() in URL_LEADING_DELIMITERS) {
        value = value.drop(1)
    }
    while (value.isNotEmpty() && shouldTrimTrailingDelimiter(value)) {
        value = value.dropLast(1)
    }
    return value.trimEnd('\\')
}

internal fun normalizeAndValidateExternalUrl(raw: String): String? {
    val sanitized = sanitizeDetectedUrl(raw)
        .replace("\\/", "/")
    if (sanitized.isBlank()) return null
    if (sanitized.startsWith("/")) return null
    if (WINDOWS_PATH_REGEX.containsMatchIn(sanitized)) return null

    val normalized = if (sanitized.startsWith("http://", ignoreCase = true) ||
        sanitized.startsWith("https://", ignoreCase = true)
    ) {
        sanitized
    } else {
        "https://$sanitized"
    }

    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host ?: return null
    if (host.isBlank()) return null

    // Reject bare filenames like "file.tar.gz" or "script.py" where the TLD
    // is actually a file extension. Only applied to bare domains (no explicit
    // scheme in the original input) to avoid false positives on real https:// URLs.
    val hasSchemeSanitized = sanitized.startsWith("http://", ignoreCase = true) ||
        sanitized.startsWith("https://", ignoreCase = true)
    if (!hasSchemeSanitized) {
        val tld = host.substringAfterLast('.').lowercase(Locale.ROOT)
        // No real TLD is a single character; also blocks intermediate retry
        if (tld.length < 2 || tld in KNOWN_FILE_EXTENSIONS) return null
    }

    return normalized
}

internal fun parseExternalUrlCandidate(raw: String): ParsedExternalUrl? {
    var candidate = sanitizeDetectedUrl(raw)
        .replace("\\/", "/")
    if (candidate.isBlank()) return null

    // The retry loop is only needed for explicit-scheme URLs (https://, www.)
    // to handle embedded quotes or JSON artifacts like "url\",{...}".
    // For bare domain candidates, sanitizeDetectedUrl already strips trailing
    // punctuation, so if the first attempt fails we return immediately.
    // This prevents the loop from shortening a versioned filename like
    // "archive-1.2.tar.gz" character by character until it reaches a fragment
    // whose truncated TLD passes the length/extension check despite not being
    // a real URL.
    val isExplicitScheme = candidate.startsWith("http://", ignoreCase = true) ||
        candidate.startsWith("https://", ignoreCase = true) ||
        candidate.startsWith("www.", ignoreCase = true)

    while (candidate.isNotEmpty()) {
        val truncated = candidate
            .substringBefore("\\\"")
            .substringBefore('"')
            .let(::trimUrlTrailingDelimiters)

        val normalized = normalizeAndValidateExternalUrl(truncated)
        if (normalized != null) {
            return ParsedExternalUrl(
                displayUrl = truncated,
                normalizedUrl = normalized
            )
        }

        if (!isExplicitScheme) return null

        candidate = candidate.dropLast(1)
        while (candidate.isNotEmpty() && shouldTrimTrailingDelimiter(candidate)) {
            candidate = candidate.dropLast(1)
        }
    }

    return null
}

private fun trimUrlTrailingDelimiters(input: String): String {
    var value = input
    while (value.isNotEmpty() && shouldTrimTrailingDelimiter(value)) {
        value = value.dropLast(1)
    }
    return value
}

private fun shouldTrimTrailingDelimiter(value: String): Boolean {
    val last = value.lastOrNull() ?: return false
    if (last !in URL_TRAILING_DELIMITERS) return false

    return when (last) {
        ')' -> value.count { it == ')' } > value.count { it == '(' }
        ']' -> value.count { it == ']' } > value.count { it == '[' }
        '}' -> value.count { it == '}' } > value.count { it == '{' }
        else -> true
    }
}

internal fun normalizeNostrEntity(raw: String): String = stripNostrUriPrefix(raw)

internal fun extractFirstUrl(text: String): String? {
    return URL_REGEX.findAll(text)
        .mapNotNull { normalizeAndValidateExternalUrl(it.value) }
        .firstOrNull()
}

internal fun normalizeExternalUrl(raw: String): String {
    val sanitized = sanitizeDetectedUrl(raw)
    return if (sanitized.startsWith("http", ignoreCase = true)) sanitized else "https://$sanitized"
}

internal fun resolveProfileReference(raw: String): String? {
    val entity = resolveNostrUri(raw)
    if (entity != null) return (entity as? NostrUriEntity.Profile)?.pubkey

    // Bare 64-hex is ambiguous between a pubkey and an event id — resolveNostrUri doesn't
    // guess, so this stays a caller-local fallback (this function's context: it's a profile).
    return normalizeNostrEntity(raw).takeIf { it.matches(HEX_64_REGEX) }
}

internal fun resolveEventReference(raw: String): String? {
    val entity = resolveNostrUri(raw)
    if (entity != null) {
        return when (entity) {
            is NostrUriEntity.Note -> entity.eventId
            // naddr has no single "id" — pass the bech32 form through unchanged, as before.
            is NostrUriEntity.Address -> normalizeNostrEntity(raw)
            is NostrUriEntity.Profile -> null
        }
    }

    return normalizeNostrEntity(raw).takeIf { it.matches(HEX_64_REGEX) }
}

/**
 * Builds the string a quote-click handler should navigate with: [eventId] unchanged when there
 * are no [relays] to carry, otherwise a freshly-encoded nevent1 (NIP-19 TLV type 1) so the hints
 * survive the click -> nav-argument -> ThreadViewModel.resolveAnchorFromReference round trip
 * instead of being silently dropped by passing the bare id — see
 * EventRepository.fetchEventById's relayHints doc comment for why that matters (a quote whose
 * author only published to relays outside our pool can otherwise never resolve).
 */
internal fun encodeQuoteReferenceForClick(eventId: String, relays: List<String>): String =
    if (relays.isEmpty()) eventId else Bech32Encoder.encodeNevent(eventId, relays)

/**
 * QUOTED_EVENT_REF_REGEX matches with an OPTIONAL `nostr:`/`nostr://`/`@` prefix, so a bare
 * "note1.../nevent1.../naddr1..." substring matches wherever it appears — including inside an
 * unrelated URL's path or fragment (e.g. an invite link whose path happens to embed a naddr1, or
 * a URL fragment used to pass some other opaque token). Filters out any match fully contained
 * within a URL_REGEX match so those aren't mistaken for a real content quote. Shared by
 * [extractQuotedEventReferences] and NostrTextParsing's inline-segment splitter so both agree on
 * what counts as a real quote reference in text (not just what the bare pattern matches).
 */
internal fun findQuotedEventReferenceMatches(text: String): List<MatchResult> {
    if (!QUOTED_EVENT_REF_REGEX.containsMatchIn(text)) return emptyList()
    val urlRanges = URL_REGEX.findAll(text).map { it.range }.toList()
    return QUOTED_EVENT_REF_REGEX.findAll(text)
        .filterNot { match -> urlRanges.any { match.range.first >= it.first && match.range.last <= it.last } }
        .toList()
}

/**
 * Quoted-event ids (NIP-18 "q" tag / nostr:note1|nevent1|naddr1 content reference) for a single
 * event — shared by EventCard's inline QuotedNoteCard resolution and by the viewport quote
 * prefetch planner (see ViewportImagePrefetchPlanner.kt) so both agree on what counts as "this
 * note quotes that note".
 */
internal fun extractQuotedEventReferences(event: Event): List<String> =
    extractQuotedEventRefs(event).map { it.id }

/**
 * Like [extractQuotedEventReferences] but keeps each reference's NIP-19 relay hints (nevent1's
 * TLV type 1) alongside the id — a bare note1 carries none, but a nevent1 exists specifically to
 * tell a client where to find an event it doesn't have yet, and dropping that (as
 * [extractQuotedEventReferences] does for its id-only callers) is why a quoted note whose author
 * only published to relays outside our pool could never resolve. Only the viewport quote
 * prefetch planner needs the hints (to actually dial them, see
 * EventRepository.connectToRelayHints); EventCard's own use of the id-only variant is unaffected.
 */
internal data class QuotedEventRef(val id: String, val relays: List<String> = emptyList())

/**
 * Like [extractQuotedEventRefs] but only the subset actually found as a substring in
 * [Event.content] — i.e. what [parseInlineMediaSegments] positions as an inline
 * [InlineMediaSegment.QuoteReference]. The gap between this and [extractQuotedEventRefs] (which
 * also pulls in "q" tags with no content match) is exactly the "tag-only quote" set that needs to
 * render at the end of the post instead of inline, since there's no text position to anchor it to.
 */
internal fun extractQuotedEventRefsFromContent(event: Event): List<QuotedEventRef> =
    findQuotedEventReferenceMatches(event.content).mapNotNull { resolveEventReferenceWithHints(it.groupValues[1]) }

internal fun extractQuotedEventRefs(event: Event): List<QuotedEventRef> {
    // findQuotedEventReferenceMatches, not a bare QUOTED_EVENT_REF_REGEX scan — otherwise a
    // note1/nevent1/naddr1 substring embedded in an unrelated URL (an invite link whose path or
    // fragment happens to contain one, e.g. armada.buzz/invite/naddr1...#...) gets treated as a
    // real quote: an unresolved "Quote: ..." chip appended to the post, and the viewport prefetch
    // planner (ViewportImagePrefetchPlanner.kt, which calls this same function) wastefully asking
    // relays for an "event" that's just a URL fragment.
    val fromContent = extractQuotedEventRefsFromContent(event)

    val fromQTags = event.tags
        .filter { it.isNotEmpty() && it[0] == "q" }
        .mapNotNull { it.getOrNull(1) }
        .mapNotNull { resolveEventReferenceWithHints(it) }

    return (fromContent + fromQTags).distinctBy { it.id }
}

internal fun resolveEventReferenceWithHints(raw: String): QuotedEventRef? {
    val entity = resolveNostrUri(raw)
    if (entity != null) {
        return when (entity) {
            is NostrUriEntity.Note -> QuotedEventRef(entity.eventId, entity.relays)
            // naddr has no single "id" — pass the bech32 form through unchanged, as before.
            // getLatestAddressableEvent (unlike fetchEventById) has no relay-hint fallback yet,
            // so relays are dropped here too; left as a known follow-up.
            is NostrUriEntity.Address -> QuotedEventRef(normalizeNostrEntity(raw))
            is NostrUriEntity.Profile -> null
        }
    }

    return normalizeNostrEntity(raw).takeIf { it.matches(HEX_64_REGEX) }?.let { QuotedEventRef(it) }
}

/**
 * Mentioned pubkeys (nostr:npub1|nprofile1 content reference) for a single event — shared by
 * NostrTextRenderer's mention resolution and the viewport mention prefetch planner.
 */
internal fun extractMentionedPubkeys(event: Event): List<String> =
    extractMentionedProfileRefs(event).map { it.pubkey }

/**
 * Like [extractMentionedPubkeys] but keeps each mention's NIP-19 relay hints (nprofile1's TLV
 * type 1) — see [QuotedEventRef]'s doc comment for why these matter; only the viewport mention
 * prefetch planner needs them.
 */
internal data class MentionedProfileRef(val pubkey: String, val relays: List<String> = emptyList())

internal fun extractMentionedProfileRefs(event: Event): List<MentionedProfileRef> {
    return PROFILE_MENTION_REGEX.findAll(event.content)
        .mapNotNull { resolveProfileReferenceWithHints(it.value) }
        .distinctBy { it.pubkey }
        .toList()
}

private fun resolveProfileReferenceWithHints(raw: String): MentionedProfileRef? {
    val entity = resolveNostrUri(raw)
    if (entity != null) {
        return (entity as? NostrUriEntity.Profile)?.let { MentionedProfileRef(it.pubkey, it.relays) }
    }

    // Bare 64-hex is ambiguous between a pubkey and an event id — resolveNostrUri doesn't
    // guess, so this stays a caller-local fallback (this function's context: it's a profile).
    return normalizeNostrEntity(raw).takeIf { it.matches(HEX_64_REGEX) }?.let { MentionedProfileRef(it) }
}

