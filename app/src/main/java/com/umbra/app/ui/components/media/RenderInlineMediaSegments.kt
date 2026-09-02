package com.umbra.app.ui.components.media

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.UrlMetadata
import com.umbra.app.ui.components.InlineMediaSegment
import com.umbra.app.ui.components.LightningInvoiceCard
import com.umbra.app.ui.components.LnurlPaymentCard
import com.umbra.app.ui.components.QuotedNoteCard
import com.umbra.app.ui.components.RenderRichText
import com.umbra.app.ui.components.SimpleLinkCard
import com.umbra.app.ui.components.UnresolvedQuoteReferenceChip
import com.umbra.app.ui.components.buildAnnotatedText
import com.umbra.app.ui.components.encodeQuoteReferenceForClick
import com.umbra.app.ui.feed.UrlPreviewWithMetadata
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource

// A QuoteReference renders as a real embedded QuotedNoteCard while quoteEmbedDepth is below this,
// and as an UnresolvedQuoteReferenceChip at or past it — see quoteEmbedDepth's doc comment on
// NostrTextRenderer's own parameter.
private const val MAX_QUOTE_EMBED_DEPTH = 1

@Composable
internal fun RenderInlineMediaSegments(
    segments: List<InlineMediaSegment>,
    imetaByUrl: Map<String, ImetaTag> = emptyMap(),
    torDataSourceFactory: DataSource.Factory,
    userRepository: UserRepository? = null,
    authorPubkey: String? = null,
    getUrlMetadata: (String) -> UrlMetadata? = { null },
    textStyle: TextStyle,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    customEmojis: Map<String, CustomEmoji>,
    mentionProfiles: Map<String, UserProfile>,
    emojiInlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent>,
    textContentPadding: PaddingValues,
    mediaContentPadding: PaddingValues,
    onMentionClick: (String) -> Unit,
    onEventReferenceClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onUrlClick: (String) -> Unit,
    onOpenImageFullscreen: (String) -> Unit,
    onOpenVideoFullscreen: (url: String, posMs: Long, playing: Boolean, muted: Boolean) -> Unit,
    getQuotedEvent: (String) -> Event? = { null },
    getQuotedEventAuthorProfile: (String) -> UserProfile? = { null },
    quotedAuthorProfiles: Map<String, UserProfile> = emptyMap(),
    hiddenEventIds: Set<String> = emptySet(),
    animateMedia: Boolean = true,
    compactMedia: Boolean = false,
    quoteEmbedDepth: Int = 0
) {
    Column(
        modifier = Modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Manual index walk (not segments.forEach) so a run of 2+ consecutive ImageUrl segments
        // can be grouped into one ImageGalleryAttachment mosaic instead of each getting its own
        // full-width row — a lone image (no adjacent ImageUrl segment) still renders through the
        // single-image ImageAttachment path unchanged.
        var segmentIndex = 0
        while (segmentIndex < segments.size) {
            val segment = segments[segmentIndex]
            if (segment is InlineMediaSegment.ImageUrl) {
                var runEnd = segmentIndex
                while (runEnd < segments.size && segments[runEnd] is InlineMediaSegment.ImageUrl) runEnd++
                val runUrls = segments.subList(segmentIndex, runEnd).map { (it as InlineMediaSegment.ImageUrl).url }
                if (runUrls.size >= 2) {
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        ImageGalleryAttachment(
                            urls = runUrls,
                            onOpenFullscreen = onOpenImageFullscreen,
                            authorPubkey = authorPubkey,
                            userRepository = userRepository
                        )
                    }
                } else {
                    val url = runUrls.first()
                    val imeta = imetaByUrl[url]
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        ImageAttachment(
                            url = url,
                            onOpenFullscreen = { onOpenImageFullscreen(url) },
                            animate = animateMedia,
                            contentDescription = imeta?.alt,
                            aspectRatio = imeta?.dimensions?.ratio,
                            blurHash = imeta?.blurhash,
                            compact = compactMedia,
                            authorPubkey = authorPubkey,
                            userRepository = userRepository
                        )
                    }
                }
                segmentIndex = runEnd
                continue
            }

            when (segment) {
                // Unreachable: handled above via the run-grouping branch, which always
                // `continue`s past every ImageUrl index. Kept only so this exhaustive `when`
                // over InlineMediaSegment's sealed subtypes still compiles.
                is InlineMediaSegment.ImageUrl -> Unit
                is InlineMediaSegment.Text -> {
                    if (segment.value.isNotBlank()) {
                        // Keyed on mentionProfiles/hiddenEventIds too — otherwise a profile
                        // that resolves after first render (async fetch) or a quote that
                        // resolves into an embed never re-renders this cached AnnotatedString.
                        val annotatedText = remember(segment.value, mentionProfiles, hiddenEventIds) {
                            buildAnnotatedText(
                                segment.value,
                                primaryColor,
                                secondaryColor,
                                tertiaryColor,
                                customEmojis,
                                mentionProfiles,
                                onMentionClick,
                                onEventReferenceClick,
                                onHashtagClick,
                                onUrlClick,
                                hiddenEventIds
                            )
                        }
                        RenderRichText(
                            text = annotatedText,
                            inlineContent = emojiInlineContent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(textContentPadding),
                            style = textStyle
                        )
                    }
                }
                is InlineMediaSegment.VideoUrl -> {
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        SafeInlineVideoAttachment(
                            url = segment.url,
                            torDataSourceFactory = torDataSourceFactory,
                            onOpenFullscreen = { posMs, playing, muted ->
                                onOpenVideoFullscreen(segment.url, posMs, playing, muted)
                            },
                            compact = compactMedia
                        )
                    }
                }
                is InlineMediaSegment.LightningInvoice -> {
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        // Reuses the same external-open confirmation path as a plain link click
                        // (EventCard sets pendingExternalUrl -> ExternalUrlWarningDialog) so
                        // opening a wallet app for a "lightning:" URI goes through the exact same
                        // gate AUDIT.md requires for every externally-opened URL.
                        LightningInvoiceCard(
                            invoice = segment.invoice,
                            onPay = onUrlClick
                        )
                    }
                }
                is InlineMediaSegment.LnurlReference -> {
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        // Same external-open confirmation path as LightningInvoiceCard above.
                        LnurlPaymentCard(
                            lnurl = segment.lnurl,
                            onOpen = onUrlClick
                        )
                    }
                }
                is InlineMediaSegment.Url -> {
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        val metadata = getUrlMetadata(segment.url)
                        if (metadata != null) {
                            UrlPreviewWithMetadata(
                                metadata = metadata,
                                torDataSourceFactory = torDataSourceFactory,
                                onUrlClick = onUrlClick
                            )
                        } else {
                            SimpleLinkCard(
                                url = segment.url,
                                onUrlClick = onUrlClick
                            )
                        }
                    }
                }
                is InlineMediaSegment.QuoteReference -> {
                    Box(modifier = Modifier.padding(mediaContentPadding)) {
                        val quotedEvent = getQuotedEvent(segment.eventId)
                        // Re-encoded with segment.relays (if any) rather than the bare id, so a
                        // still-unresolved quote's relay hints survive the click -> navigation
                        // round trip instead of being dropped right before the one lookup that
                        // could actually use them — see encodeQuoteReferenceForClick's doc comment.
                        val clickReference = remember(segment.eventId, segment.relays) {
                            encodeQuoteReferenceForClick(segment.eventId, segment.relays)
                        }
                        if (quotedEvent != null && quoteEmbedDepth < MAX_QUOTE_EMBED_DEPTH && userRepository != null) {
                            // quotedAuthorProfiles (reactive, observeProfile-backed) preferred
                            // over the synchronous getQuotedEventAuthorProfile cache snapshot —
                            // see its computation in NostrTextRenderer for why the synchronous
                            // one alone could leave this stale even after the profile arrived.
                            QuotedNoteCard(
                                quotedEvent = quotedEvent,
                                authorProfile = quotedAuthorProfiles[quotedEvent.pubkey]
                                    ?: getQuotedEventAuthorProfile(quotedEvent.pubkey),
                                onClick = { onEventReferenceClick(clickReference) },
                                torDataSourceFactory = torDataSourceFactory,
                                userRepository = userRepository,
                                onMentionClick = onMentionClick,
                                onHashtagClick = onHashtagClick,
                                onUrlClick = onUrlClick,
                                onEventReferenceClick = onEventReferenceClick,
                                quoteEmbedDepth = quoteEmbedDepth
                            )
                        } else {
                            UnresolvedQuoteReferenceChip(
                                eventId = segment.eventId,
                                relayHints = segment.relays,
                                onClick = { onEventReferenceClick(clickReference) }
                            )
                        }
                    }
                }
            }
            segmentIndex++
        }
    }
}
