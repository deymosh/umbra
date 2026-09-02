package com.umbra.app.ui.components

import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.extractCustomEmojis
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.nip92.extractImetaTags
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.ImmutableListSnapshot
import com.umbra.app.ui.common.UrlMetadata
import com.umbra.app.ui.components.media.FullscreenImageDialog
import com.umbra.app.ui.components.media.FullscreenVideoDialogOptIn
import com.umbra.app.ui.components.media.RenderInlineMediaSegments
import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.media3.datasource.DataSource
import coil3.compose.AsyncImage

private data class RenderPayload(
    val imageUrls: List<String>,
    val videoUrls: List<String>,
    val textWithoutMedia: String
)

private val JSON_PREFIXES = listOf("{", "[")

/**
 * Renders Nostr event text with support for:
 * - Mentions: @user, nostr:npub1..., nostr:nprofile1... (NIP-19)
 * - Hashtags: #topic (NIP-30)
 * - URLs: https://... (clickable)
 * - Images: Renders actual images from URLs (jpg, png, gif, webp, etc)
 *
 * NIP-2X Support:
 * - NIP-1: Basic Events & Signing
 * - NIP-2: Follow List
 * - NIP-6: Basic Key Derivation
 * - NIP-10: Event Relations (replies, quoted)
 * - NIP-19: bech32-encoded entities (npub1, note1, nprofile1)
 * - NIP-23: Long-form Content (Articles)
 * - NIP-25: Reactions
 * - NIP-30: Hashtag mentions
 * - NIP-54: nostr:// URLs
 */
@Composable
fun NostrTextRenderer(
    modifier: Modifier = Modifier,
    text: String,
    tags: ImmutableListSnapshot<List<String>> = ImmutableListSnapshot(),
    mediaContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    torDataSourceFactory: DataSource.Factory,
    userRepository: UserRepository,
    // BUD-03 client-retrieval fallback: this note's author, so a broken inline image URL can be
    // retried against the author's own kind:10063 Blossom server list before giving up. Always
    // available (Event.pubkey is never null) — see ImageAttachment's authorPubkey doc comment.
    authorPubkey: String,
    resolveMentionProfiles: Boolean = true,
    onMentionClick: (String) -> Unit = {},
    onEventReferenceClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    getUrlMetadata: (String) -> UrlMetadata? = { null },
    // Best-effort, synchronous, cache-only lookups (mirrors getUrlMetadata's shape) for rendering
    // a nostr:note1/nevent1/naddr1 content reference as an inline QuotedNoteCard, positioned
    // exactly where it appears in the text — same treatment images already get. Never trigger a
    // fetch; when either returns null a small unresolved chip is shown at that position instead.
    getQuotedEvent: (String) -> Event? = { null },
    getQuotedEventAuthorProfile: (String) -> UserProfile? = { null },
    // Event ids already rendered as an inline QuotedNoteCard by the caller — their raw
    // nostr:note1/nevent1/naddr1 reference is dropped from the text instead of shown twice. Only
    // matters for the JSON-fence content path, which still renders text via buildAnnotatedText
    // directly instead of the inline-segment splitter below (which already extracts quote
    // references into their own positioned segments, so they never reach that path's text scan).
    hiddenEventIds: Set<String> = emptySet(),
    // Same signal as UserAvatar's `animate` (false while the list is flinging) — pauses
    // animated GIF/WebP post images during scroll instead of decoding every frame.
    animateMedia: Boolean = true,
    // Full image-url list for the fullscreen viewer's swipe pager, computed by the caller from
    // the *untruncated* note content — [text] itself may be a "Show more"-collapsed excerpt (see
    // EventCard's displayText/collapsedText), which would otherwise silently drop any image past
    // the truncation point from the pager entirely. Falls back to deriving from [text] when the
    // caller doesn't have (or need) a wider list, e.g. a live-typed composer draft that's never
    // truncated in the first place.
    fullImageUrls: List<String>? = null,
    // Correctly-parsed Lightning invoice strings from the *untruncated* note content, computed by
    // the caller the same way fullImageUrls is above — [text] may be a "Show more"-collapsed
    // excerpt whose bech32 data got cut mid-string, which corrupts LIGHTNING_INVOICE_REGEX's match
    // against [text] alone and fails Bolt11 decoding. Used to swap in the full string for any
    // truncated LightningInvoice segment found below. Falls back to deriving from [text] itself
    // when the caller has nothing wider (e.g. a live-typed composer draft).
    fullLightningInvoices: List<String>? = null,
    // Same rationale/shape as fullLightningInvoices above, for LNURL strings.
    fullLnurlReferences: List<String>? = null,
    // Caps single-image height instead of full aspect-ratio sizing — for a note shown as context
    // rather than as its own post (e.g. the "replying to" card above a reply composer). See
    // ImageAttachment's `compact` param.
    compactMedia: Boolean = false,
    // How many QuotedNoteCard levels this renderer is already nested inside — 0 at the top level.
    // A QuoteReference segment only renders as a real embedded QuotedNoteCard while this is below
    // the max embed-depth cap enforced in RenderInlineMediaSegments; at or past that cap it
    // degrades to an UnresolvedQuoteReferenceChip instead, so a quote can itself render a quote
    // (one level) without nesting QuotedNoteCard -> NostrTextRenderer -> QuotedNoteCard
    // indefinitely for a circular/adversarial quote chain.
    quoteEmbedDepth: Int = 0
) {
    val useSimpleTextPath = remember(text, tags) {
        shouldUseSimpleTextPath(text = text, tags = tags.toList())
    }

    if (useSimpleTextPath) {
        SimpleNostrText(
            text = text,
            modifier = modifier,
            textContentPadding = PaddingValues(0.dp)
        )
        return
    }

    // Detect pubkeys/npubs/nprofile1 in text for mentions, to fetch profiles and show names instead of raw keys.
    val mentions = remember(text) {
        val regexes = listOf(
            PROFILE_MENTION_REGEX // Include bare npub1/nprofile1 for mentions
        )

        regexes.flatMap { it.findAll(text).map { m -> m.value } }.distinct()
    }

    // Load profiles for mentioned pubkeys (if any)
    val mentionPubkeys by remember(mentions, useSimpleTextPath, resolveMentionProfiles) {
        mutableStateOf(
            if (useSimpleTextPath || !resolveMentionProfiles) {
                emptyList()
            } else {
                mentions.mapNotNull { resolveProfileReference(it) }.distinct()
            }
        )
    }
    // Reactive (not a one-shot getProfiles() read): a mentioned pubkey's profile can arrive
    // later — e.g. a ViewModel's viewport prefetch requesting it because this note scrolled into
    // view (see collectViewportMentionedPubkeys) — and observeProfile's Room-backed Flow means
    // this recomposes with the resolved "@name" as soon as that write lands, instead of staying
    // a raw npub until something else happens to recompose this card.
    val mentionProfiles by produceState<Map<String, UserProfile>>(
        initialValue = emptyMap(),
        mentionPubkeys,
        useSimpleTextPath,
        resolveMentionProfiles
    ) {
        if (useSimpleTextPath || !resolveMentionProfiles || mentionPubkeys.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        combine(
            mentionPubkeys.map { pubkey ->
                userRepository.observeProfile(pubkey).map { profile -> pubkey to profile }
            }
        ) { pairs ->
            pairs.mapNotNull { (pubkey, profile) -> profile?.let { pubkey to it } }.toMap()
        }.collect { value = it }
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val onMentionClickState = rememberUpdatedState(onMentionClick)
    val onEventReferenceClickState = rememberUpdatedState(onEventReferenceClick)
    val onHashtagClickState = rememberUpdatedState(onHashtagClick)
    val onUrlClickState = rememberUpdatedState(onUrlClick)

    val stableOnMentionClick = remember { { value: String -> onMentionClickState.value(value) } }
    val stableOnEventReferenceClick = remember { { value: String -> onEventReferenceClickState.value(value) } }
    val stableOnHashtagClick = remember { { value: String -> onHashtagClickState.value(value) } }
    val stableOnUrlClick = remember { { value: String -> onUrlClickState.value(value) } }
    val customEmojis = remember(tags) { extractCustomEmojis(tags.toList()) }
    val imetaByUrl = remember(tags) { extractImetaTags(tags.toList()) }
    val emojiInlineContent = remember(customEmojis) {
        customEmojis.values.associate { emoji ->
            customEmojiInlineContentId(emoji.shortcode) to androidx.compose.foundation.text.InlineTextContent(
                Placeholder(
                    width = 1.15.em,
                    height = 1.15.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                AsyncImage(
                    model = emoji.url,
                    contentDescription = context.getString(R.string.custom_emoji_content_description, emoji.shortcode),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    var fullscreenImageIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var fullscreenVideoUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreenVideoPositionMs by rememberSaveable { mutableLongStateOf(0L) }
    var fullscreenVideoWasPlaying by rememberSaveable { mutableStateOf(false) }
    var fullscreenVideoIsMuted by rememberSaveable { mutableStateOf(true) }

    val contentSegments = remember(text) { splitJsonFenceSegments(text) }
    val hasJsonFence = remember(contentSegments) { contentSegments.any { it is ContentSegment.JsonFence } }
    val isJsonContent = remember(text) { isLikelyJson(text) }
    val inlineMediaSegments = remember(text, hasJsonFence, isJsonContent, fullLightningInvoices, fullLnurlReferences, imetaByUrl) {
        if (hasJsonFence || isJsonContent) {
            emptyList()
        } else {
            val parsed = reclassifyUrlSegmentsWithImeta(parseInlineMediaSegments(text), imetaByUrl)
            if (fullLightningInvoices.isNullOrEmpty() && fullLnurlReferences.isNullOrEmpty()) {
                parsed
            } else {
                parsed.map { segment ->
                    when (segment) {
                        is InlineMediaSegment.LightningInvoice -> {
                            val full = fullLightningInvoices?.firstOrNull { it.startsWith(segment.invoice, ignoreCase = true) }
                            if (full != null && full != segment.invoice) segment.copy(invoice = full) else segment
                        }
                        is InlineMediaSegment.LnurlReference -> {
                            val full = fullLnurlReferences?.firstOrNull { it.startsWith(segment.lnurl, ignoreCase = true) }
                            if (full != null && full != segment.lnurl) segment.copy(lnurl = full) else segment
                        }
                        else -> segment
                    }
                }
            }
        }
    }
    val allImageUrls = fullImageUrls
        ?: inlineMediaSegments.filterIsInstance<InlineMediaSegment.ImageUrl>().map { it.url }

    // Reactive, same reasoning/pattern as mentionProfiles above: getQuotedEventAuthorProfile is a
    // synchronous cache snapshot threaded through EventCard -> NostrTextRenderer ->
    // RenderInlineMediaSegments as a stable (remember-with-no-keys) lambda — a quoted note's
    // author kind:0 that arrives *after* this card's first composition had no reliable way to
    // reach this specific call site through that many composable/skip boundaries, which is what
    // made a quoted note's avatar/name in the feed intermittently stay blank even once the
    // profile genuinely existed in Room. Observing it directly here removes that whole chain.
    val quotedEventIds = remember(inlineMediaSegments) {
        inlineMediaSegments.filterIsInstance<InlineMediaSegment.QuoteReference>().map { it.eventId }
    }
    // Deliberately NOT keyed on getQuotedEvent itself (see EventCard's resolvedQuotes comment) —
    // it's a stable lambda whose underlying data can change without its own identity changing, so
    // re-deriving this on every recomposition (at most a couple ids) is what keeps it fresh.
    val quotedAuthorPubkeys = quotedEventIds.mapNotNull { id -> getQuotedEvent(id)?.pubkey }.distinct()
    val quotedAuthorProfiles by produceState<Map<String, UserProfile>>(
        initialValue = emptyMap(),
        quotedAuthorPubkeys
    ) {
        if (quotedAuthorPubkeys.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        combine(
            quotedAuthorPubkeys.map { pubkey ->
                userRepository.observeProfile(pubkey).map { profile -> pubkey to profile }
            }
        ) { pairs ->
            pairs.mapNotNull { (pubkey, profile) -> profile?.let { pubkey to it } }.toMap()
        }.collect { value = it }
    }

    val prettyJsonContent by produceState(initialValue = "", key1 = text, key2 = isJsonContent) {
        if (isJsonContent) {
            value = withContext(Dispatchers.Default) { prettyFormatJson(text) }
        } else {
            value = ""
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (text.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(mediaContentPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val secondaryColor = MaterialTheme.colorScheme.secondary
                val tertiaryColor = MaterialTheme.colorScheme.tertiary
                val textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                )

                if (contentSegments.any { it is ContentSegment.JsonFence }) {
                    contentSegments.forEachIndexed { index, segment ->
                        when (segment) {
                            is ContentSegment.Text -> {
                                if (segment.value.isNotBlank()) {
                                    val annotatedText = remember(segment.value, primaryColor, secondaryColor, tertiaryColor, mentionProfiles, hiddenEventIds) {
                                        buildAnnotatedText(
                                            segment.value,
                                            primaryColor,
                                            secondaryColor,
                                            tertiaryColor,
                                            customEmojis,
                                            mentionProfiles,
                                            stableOnMentionClick,
                                            stableOnEventReferenceClick,
                                            stableOnHashtagClick,
                                            stableOnUrlClick,
                                            hiddenEventIds,
                                        )
                                    }
                                    RenderRichText(
                                        text = annotatedText,
                                        inlineContent = emojiInlineContent,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = textStyle
                                    )
                                }
                            }
                            is ContentSegment.JsonFence -> {
                                val prettyBlock = remember(segment.value) { prettyFormatJson(segment.value) }
                                JsonContentBlock(
                                    json = prettyBlock,
                                    onCopy = {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, prettyBlock)))
                                        }
                                        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                } else if (isJsonContent) {
                    JsonContentBlock(
                        json = prettyJsonContent,
                        onCopy = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, prettyJsonContent)))
                            }
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    RenderInlineMediaSegments(
                        segments = inlineMediaSegments,
                        imetaByUrl = imetaByUrl,
                        torDataSourceFactory = torDataSourceFactory,
                        userRepository = userRepository,
                        authorPubkey = authorPubkey,
                        getUrlMetadata = getUrlMetadata,
                        textStyle = textStyle,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        tertiaryColor = tertiaryColor,
                        customEmojis = customEmojis,
                        mentionProfiles = mentionProfiles,
                        emojiInlineContent = emojiInlineContent,
                        textContentPadding = PaddingValues(0.dp),
                        mediaContentPadding = PaddingValues(0.dp),
                        onMentionClick = stableOnMentionClick,
                        onEventReferenceClick = stableOnEventReferenceClick,
                        onHashtagClick = stableOnHashtagClick,
                        onUrlClick = stableOnUrlClick,
                        getQuotedEvent = getQuotedEvent,
                        getQuotedEventAuthorProfile = getQuotedEventAuthorProfile,
                        quotedAuthorProfiles = quotedAuthorProfiles,
                        hiddenEventIds = hiddenEventIds,
                        animateMedia = animateMedia,
                        compactMedia = compactMedia,
                        quoteEmbedDepth = quoteEmbedDepth,
                        onOpenImageFullscreen = { url ->
                            val idx = allImageUrls.indexOf(url)
                            if (idx >= 0) fullscreenImageIndex = idx
                        },
                        onOpenVideoFullscreen = { url, posMs, playing, muted ->
                            fullscreenVideoUrl = url
                            fullscreenVideoPositionMs = posMs
                            fullscreenVideoWasPlaying = playing
                            fullscreenVideoIsMuted = muted
                        }
                    )
                }
            }
        }
    }

    fullscreenImageIndex?.let { selectedIndex ->
        FullscreenImageDialog(
            imageUrls = allImageUrls,
            initialIndex = selectedIndex,
            onDismiss = { fullscreenImageIndex = null }
        )
    }

    fullscreenVideoUrl?.let { videoUrl ->
        FullscreenVideoDialogOptIn(
            videoUrl = videoUrl,
            torDataSourceFactory = torDataSourceFactory,
            initialPositionMs = fullscreenVideoPositionMs,
            initialPlayWhenReady = fullscreenVideoWasPlaying,
            initialMuted = fullscreenVideoIsMuted,
            onDismiss = {
                fullscreenVideoUrl = null
                fullscreenVideoPositionMs = 0L
                fullscreenVideoWasPlaying = false
                fullscreenVideoIsMuted = true
            },
            onStateCaptured = { positionMs, wasPlaying ->
                fullscreenVideoPositionMs = positionMs
                fullscreenVideoWasPlaying = wasPlaying
            }
        )
    }
}

@Composable
private fun SimpleNostrText(
    text: String,
    modifier: Modifier,
    textContentPadding: PaddingValues
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (text.isNotEmpty()) {
            val textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(textContentPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RenderRichText(
                    text = AnnotatedString(text),
                    inlineContent = emptyMap(),
                    modifier = Modifier.fillMaxWidth(),
                    style = textStyle
                )
            }
        }
    }
}

internal fun shouldUseSimpleTextPath(text: String, tags: List<List<String>>): Boolean {
    if (text.isBlank()) return true
    if (tags.any { tag -> tag.getOrNull(0) == "emoji" }) return false

    val trimmed = text.trim()
    if (JSON_PREFIXES.any { prefix -> trimmed.startsWith(prefix) }) return false

    return !(trimmed.contains("http://") ||
        trimmed.contains("https://") ||
        trimmed.contains("nostr:") ||
        trimmed.contains("npub1") ||
        trimmed.contains("note1") ||
        trimmed.contains("nprofile1") ||
        // A note with a lightning invoice/LNURL and nothing else (no URL, mention, hashtag) has
        // none of the other triggers below, so without these it silently took the simple-text
        // path and never ran parseInlineMediaSegments at all — the invoice/LNURL regexes never
        // even got a chance to run, no matter how correct they are.
        trimmed.contains("lnbc", ignoreCase = true) ||
        trimmed.contains("lntb", ignoreCase = true) ||
        trimmed.contains("lnbcrt", ignoreCase = true) ||
        trimmed.contains("lnurl", ignoreCase = true) ||
        trimmed.contains("lightning:", ignoreCase = true) ||
        trimmed.contains("#") ||
        trimmed.contains("@") ||
        // "`" is a strict superset of "```" (a fence contains three of them) — also covers the
        // new inline-code path. "*" and "~" cover the new bold/italic/strikethrough emphasis
        // below; a false-positive trigger here just means an unpaired "*"/"~" or literal
        // backtick goes through the richer path for nothing, not a correctness bug — the actual
        // regexes (BOLD_REGEX etc) are what decide whether anything really gets styled.
        trimmed.contains("`") ||
        trimmed.contains("*") ||
        trimmed.contains("~"))
}

/**
 * True when NostrTextRenderer will actually run [parseInlineMediaSegments] on this text — i.e.
 * a quote reference in it gets positioned inline as a [QuotedNoteCard] rather than left as plain
 * text. False for the simple-text and JSON/JSON-fence paths, which render via buildAnnotatedText
 * directly and never call parseInlineMediaSegments (see NostrTextRenderer's body) — callers that
 * append quote embeds themselves (EventCard's end-of-post block, for "q"-tag-only references with
 * no inline occurrence) need this to know which ids NostrTextRenderer already rendered so neither
 * renders the same quote twice, nor a JSON-post's quote silently renders nowhere.
 */
internal fun textRendersQuotesInline(text: String, tags: List<List<String>>): Boolean {
    if (shouldUseSimpleTextPath(text, tags)) return false
    if (isLikelyJson(text)) return false
    if (splitJsonFenceSegments(text).any { it is ContentSegment.JsonFence }) return false
    return true
}


