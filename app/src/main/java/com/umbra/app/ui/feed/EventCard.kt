package com.umbra.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.launch
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip36.extractContentWarning
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.ActionItem
import com.umbra.app.ui.components.ActionsBottomSheet
import com.umbra.app.ui.components.ConfirmDialog
import com.umbra.app.ui.components.ContentWarningPlaceholder
import com.umbra.app.ui.components.EmojiReactionPickerSheet
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.NoteHeader
import com.umbra.app.ui.components.NostrTextRenderer
import com.umbra.app.ui.components.PROFILE_MENTION_REGEX
import com.umbra.app.ui.components.QuotedNoteCard
import com.umbra.app.ui.components.RepostBanner
import com.umbra.app.ui.components.UnresolvedQuoteReferenceChip
import com.umbra.app.ui.components.encodeQuoteReferenceForClick
import com.umbra.app.ui.components.extractQuotedEventRefs
import com.umbra.app.ui.components.extractQuotedEventRefsFromContent
import com.umbra.app.ui.components.InlineMediaSegment
import com.umbra.app.ui.components.parseInlineMediaSegments
import com.umbra.app.ui.components.ChipBadge
import com.umbra.app.ui.components.ReactionBar
import com.umbra.app.ui.components.ShowMoreLessToggle
import com.umbra.app.ui.components.TimeFormatter
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.computeTextRenderMetrics
import com.umbra.app.ui.components.UserIdentityBadge
import com.umbra.app.ui.components.launchExternalUrl
import com.umbra.app.ui.components.launchLightningInvoice
import com.umbra.app.ui.components.resolveEventReference
import com.umbra.app.ui.components.resolveProfileReference
import com.umbra.app.ui.components.truncatePublicKey
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.umbra.app.ui.common.toImmutableSnapshot


internal fun normalizeNoteContentForDisplay(content: String): String = content.trim()

/**
 * The three-dot menu's action list (pin/unpin, mute, copy id/content/nevent/json, delete) for
 * [target] — shared between EventCard's own kebab (over the note) and the repost banner's kebab
 * (over the NIP-18 repost event itself), so a repost's menu offers exactly the same set of actions
 * a normal note's does, no more and no less — a repost is a real, ownable Nostr event too.
 */
@Composable
private fun eventActionItems(
    target: Event,
    isPinned: Boolean,
    isCurrentUserEvent: Boolean,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onDeleteRequest: () -> Unit,
    getEventJson: () -> String
): List<ActionItem> {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedIdToast = stringResource(R.string.share_copy_event_id_toast)
    val copiedContentToast = stringResource(R.string.share_copy_content_toast)
    val copiedNeventToast = stringResource(R.string.share_copy_nip19_toast)
    val copiedJsonToast = stringResource(R.string.share_copy_event_json_toast)
    val json = remember(target.id) { getEventJson() }

    return buildList {
        add(
            ActionItem(
                icon = Icons.Default.PushPin,
                label = stringResource(if (isPinned) R.string.unpin_note_action else R.string.pin_note_action),
                onClick = onPin
            )
        )
        add(
            ActionItem(
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                label = stringResource(R.string.mute_user_action),
                onClick = onMute
            )
        )
        add(
            ActionItem(
                icon = Icons.Default.ContentCopy,
                label = stringResource(R.string.share_copy_id),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, target.id)))
                    }
                    Toast.makeText(context, copiedIdToast, Toast.LENGTH_SHORT).show()
                }
            )
        )
        add(
            ActionItem(
                icon = Icons.Default.ContentCopy,
                label = stringResource(R.string.share_copy_content),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, target.content)))
                    }
                    Toast.makeText(context, copiedContentToast, Toast.LENGTH_SHORT).show()
                }
            )
        )
        add(
            ActionItem(
                icon = Icons.Default.ContentCopy,
                label = stringResource(R.string.share_copy_nip19),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, Bech32Encoder.encodeNevent(target.id))))
                    }
                    Toast.makeText(context, copiedNeventToast, Toast.LENGTH_SHORT).show()
                }
            )
        )
        if (json.isNotBlank()) {
            add(
                ActionItem(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.share_copy_json),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, json)))
                        }
                        Toast.makeText(context, copiedJsonToast, Toast.LENGTH_SHORT).show()
                    }
                )
            )
        }
        if (isCurrentUserEvent) {
            add(
                ActionItem(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.delete_note_action),
                    destructive = true,
                    onClick = onDeleteRequest
                )
            )
        }
    }
}

/**
 * Event card component for displaying Nostr events with full interaction support
 * Renders content with support for images, mentions, hashtags via NostrTextRenderer
 * Supports all NIP-01 event kinds with appropriate styling and interactions
 *
 * Features:
 * - Like button (NIP-25 reactions)
 * - Share button (NIP-18 reposts or share intent)
 * - Reply button (NIP-10 threading)
 * - User profile display (cached)
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun EventCard(
    modifier: Modifier = Modifier,
    event: Event,
    enableEventClick: Boolean = true,
    initiallyExpanded: Boolean = false,
    userProfile: UserProfile? = null,
    replyToProfile: UserProfile? = null,
    userRepository: UserRepository,
    threadDepth: Int = 0,
    replyToLabel: String? = null,
    replyCount: Int = 0,
    reactionCount: Int = 0,
    repostCount: Int = 0,
    // Non-null when this note arrived via a NIP-18 repost — drives the "reposted by" banner and
    // avatar badge above NoteHeader. event/userProfile above stay the ORIGINAL note/author either
    // way (see NoteView's doc comment); this is purely additive chrome.
    repostedByPubkey: String? = null,
    repostedByProfile: UserProfile? = null,
    repostedAt: Long? = null,
    // The NIP-18 kind-6/16 repost event itself — null when either not reposted or not yet
    // threaded through by the caller. Its overflow menu offers the exact same actions
    // event/isPinned/onPin/onMute/onDelete drive below, applied to this event instead — a repost
    // is a real, ownable Nostr event too, no practical difference from any other.
    repostEvent: Event? = null,
    isRepostPinned: Boolean = false,
    isLiked: Boolean = false,
    isReposted: Boolean = false,
    torDataSourceFactory: DataSource.Factory,
    onEventClick: (Event) -> Unit = {},
    onLike: (Event, String, CustomEmoji?) -> Boolean = { _, _, _ -> false },
    reactionEmojis: List<ReactionEmoji> = emptyList(),
    onAddReactionEmoji: (ReactionEmoji) -> Unit = {},
    onRemoveReactionEmoji: (String) -> Unit = {},
    onRepost: (Event) -> Unit = {},
    onQuote: (Event) -> Unit = {},
    onShare: (Event) -> Unit = {},
    onReply: (Event) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onEventReferenceClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    currentUserPubkey: String? = null,
    onDelete: (Event) -> Unit = {},
    onMute: (String) -> Unit = {},
    isPinned: Boolean = false,
    onPin: (Event) -> Unit = {},
    // Same shape as the deleted ShareEventDialog's param it replaces — cache-only, synchronous.
    // The kebab's "Copy JSON" row only appears when this returns a non-blank string.
    getEventJson: (Event) -> String = { "" },
    // Best-effort, synchronous, cache-only lookups for rendering a quoted note inline instead
    // of a bare "Quote: <id>" chip — mirrors getUrlMetadata's shape. Never trigger a fetch;
    // when either returns null the existing chip fallback is used.
    getQuotedEvent: (String) -> Event? = { null },
    getQuotedEventAuthorProfile: (String) -> UserProfile? = { null },
    animateAvatars: Boolean = true,
    getUrlMetadata: (String) -> com.umbra.app.ui.common.UrlMetadata? = { null },
    // Caps embedded image height instead of full aspect-ratio sizing — for a note shown as
    // context rather than as its own post (e.g. the "replying to" card above a reply composer).
    compactMedia: Boolean = false
) {
    val context = LocalContext.current
    val onEventClickState = rememberUpdatedState(onEventClick)
    val onLikeState = rememberUpdatedState(onLike)
    val onAddReactionEmojiState = rememberUpdatedState(onAddReactionEmoji)
    val onRemoveReactionEmojiState = rememberUpdatedState(onRemoveReactionEmoji)
    val onRepostState = rememberUpdatedState(onRepost)
    val onQuoteState = rememberUpdatedState(onQuote)
    val onShareState = rememberUpdatedState(onShare)
    val onReplyState = rememberUpdatedState(onReply)
    val onProfileClickState = rememberUpdatedState(onProfileClick)
    val onEventReferenceClickState = rememberUpdatedState(onEventReferenceClick)
    val onHashtagClickState = rememberUpdatedState(onHashtagClick)
    val onDeleteState = rememberUpdatedState(onDelete)
    val onMuteState = rememberUpdatedState(onMute)
    val onPinState = rememberUpdatedState(onPin)
    val getEventJsonState = rememberUpdatedState(getEventJson)
    val getUrlMetadataState = rememberUpdatedState(getUrlMetadata)

    val isExpanded = remember(event.id) { mutableStateOf(initiallyExpanded) }
    var isContentRevealed by remember(event.id) { mutableStateOf(false) }
    var showActionsSheet by remember(event.id) { mutableStateOf(false) }
    var showRepostActionsSheet by remember(event.id) { mutableStateOf(false) }
    // Holds whichever event (event or repostEvent) a delete was requested for — a single dialog
    // shared by both menus instead of one boolean flag per menu.
    var deleteConfirmationTarget by remember(event.id) { mutableStateOf<Event?>(null) }
    var showReactionPicker by remember(event.id) { mutableStateOf(false) }
    var pendingExternalUrl by remember(event.id) { mutableStateOf<String?>(null) }
    val normalizedContent = remember(event.id, event.content) {
        normalizeNoteContentForDisplay(event.content)
    }
    val textMetrics = remember(event.id, normalizedContent) {
        computeTextRenderMetrics(normalizedContent)
    }
    val displayText = if (isExpanded.value || !textMetrics.shouldShowExpandButton) {
        normalizedContent
    } else {
        textMetrics.collapsedText
    }
    // Computed from the untruncated normalizedContent (not displayText, which is chopped to
    // textMetrics.collapsedText while collapsed) so the fullscreen image viewer can always swipe
    // to every image in the note — including ones past the "Show more" cutoff — not just whatever
    // survived truncation. See NostrTextRenderer's fullImageUrls param.
    val fullImageUrls = remember(event.id, normalizedContent) {
        parseInlineMediaSegments(normalizedContent).filterIsInstance<InlineMediaSegment.ImageUrl>().map { it.url }
    }
    // Same rationale as fullImageUrls above: computeTextRenderMetrics already keeps an invoice
    // intact when it straddles the collapse cutoff, but this is a defense-in-depth correctness net
    // for NostrTextRenderer to swap in the full, correctly-parsed invoice string wherever the
    // (possibly still-truncated) displayText's own parse only captured a prefix of it. See
    // NostrTextRenderer's fullLightningInvoices param.
    val fullLightningInvoices = remember(event.id, normalizedContent) {
        parseInlineMediaSegments(normalizedContent).filterIsInstance<InlineMediaSegment.LightningInvoice>().map { it.invoice }
    }
    // Same rationale as fullLightningInvoices above, for LNURL strings.
    val fullLnurlReferences = remember(event.id, normalizedContent) {
        parseInlineMediaSegments(normalizedContent).filterIsInstance<InlineMediaSegment.LnurlReference>().map { it.lnurl }
    }

    val isTextNote = remember(event.kind) { event.kind == Event.KIND_TEXT_NOTE }
    val quotedEventFullRefs = remember(isTextNote, event.content, event.tags) {
        if (isTextNote) extractQuotedEventRefs(event) else emptyList()
    }
    val quotedEventRefs = remember(quotedEventFullRefs) { quotedEventFullRefs.map { it.id } }
    // Resolved here (not just inside the embed-rendering block below) so the raw
    // nostr:note1/nevent1/naddr1 reference can be hidden from the main NostrTextRenderer call
    // for anything that's about to render as an inline QuotedNoteCard instead.
    //
    // Deliberately NOT wrapped in remember(quotedEventRefs): a quote can still be hydrating from
    // relays/Room when this card first composes, so getQuotedEvent(id) may flip from null to
    // non-null on a later recomposition without quotedEventRefs itself ever changing — keying
    // only on that would freeze the raw-reference chip fallback forever even after the quoted
    // note arrives. quotedEventRefs is at most a couple of ids, so recomputing this on every
    // recomposition is cheap; the remember below on the resulting value still avoids redundant
    // downstream work when the resolved set is actually unchanged.
    val resolvedQuotes = quotedEventRefs.mapNotNull { id -> getQuotedEvent(id)?.let { id to it } }
    // "q" tag refs (or, more rarely, a content match dropped for some other reason) with no
    // matching nostr:note1/nevent1/naddr1 substring anywhere in event.content — some clients
    // attach the quote purely via the tag and never put the reference in the text at all, so
    // there's no in-text position to render these inline. Rendered as their own block at the end
    // of the post instead (see the append block below) rather than silently dropped.
    val quotesWithoutInlinePosition = remember(isTextNote, event.content, quotedEventFullRefs) {
        if (!isTextNote) return@remember emptyList()
        val inlinePositionedIds = extractQuotedEventRefsFromContent(event).mapTo(HashSet()) { it.id }
        quotedEventFullRefs.filterNot { it.id in inlinePositionedIds }
    }
    // Ids of quotes resolved above — hides their raw nostr:note1/nevent1/naddr1 reference from
    // NostrTextRenderer's plain-text output wherever it renders them inline as a QuotedNoteCard
    // instead (see NostrTextRenderer's hiddenEventIds param).
    val hiddenEventIds = remember(resolvedQuotes) { resolvedQuotes.mapTo(HashSet()) { it.first } }
    val hasProfileMentionsMemo = remember(isTextNote, event.content) {
        isTextNote && hasProfileMentions(event)
    }
    val eventKindLabel = remember(event.kind, event.content, event.tags) {
        getEventKindLabelModel(event, quotedEventRefs.isNotEmpty(), hasProfileMentionsMemo)
    }
    val parentEventId = remember(event.id, event.tags) { event.getParentEventId() }
    val isReply = remember(event.id, event.tags) { event.isReply() }
    val hashtags = remember(event.id, event.tags) { event.getHashtags() }
    val eventTagsSnapshot = remember(event.id, event.tags) { event.tags.toImmutableSnapshot() }
    val contentWarning = remember(event.id, event.tags) { extractContentWarning(event) }
    val isCurrentUserEvent = remember(event.pubkey, currentUserPubkey) {
        !currentUserPubkey.isNullOrBlank() && event.pubkey.equals(currentUserPubkey, ignoreCase = true)
    }
    val profileClick = remember(event.pubkey) {
        { onProfileClickState.value(event.pubkey) }
    }
    val mentionClick = remember {
        { mention: String ->
            resolveProfileReference(mention)?.let(onProfileClickState.value)
            Unit
        }
    }
    val eventReferenceClick = remember {
        { reference: String ->
            // Forward the original bech32 reference (still carrying any nevent1 relay hints),
            // not the resolved bare id — resolveEventReference here only gates that it's a real,
            // parseable reference before navigating; ThreadViewModel.resolveAnchorFromReference
            // re-resolves it the same way on the other end and can use the hints this time.
            if (resolveEventReference(reference) != null) {
                onEventReferenceClickState.value(reference)
            }
            Unit
        }
    }
    val hashtagClick = remember {
        { tag: String -> onHashtagClickState.value(tag) }
    }
    val urlMetadataLookup = remember {
        { url: String -> getUrlMetadataState.value(url) }
    }
    val replyAction = remember(event.id) { { onReplyState.value(event) } }
    // Tapping Like always opens the reaction picker (see EmojiReactionPickerSheet below) rather
    // than instantly publishing a plain "+" — the actual reaction is only sent once the user picks
    // an entry from that sheet.
    val likeAction = remember(event.id) { { showReactionPicker = true } }
    val repostAction = remember(event.id) { { onRepostState.value(event) } }
    // Quoting is scoped to kind-1 text notes for now (see ReactionBar's onQuote doc comment) —
    // null hides the chip for any other kind rather than silently no-op'ing on tap.
    val quoteAction = remember(event.id, isTextNote) {
        if (isTextNote) ({ onQuoteState.value(event) }) else null
    }
    val shareAction = remember(event.id) { { onShareState.value(event) } }

    pendingExternalUrl?.let { url ->
        val isLightningInvoice = url.startsWith("lightning:", ignoreCase = true)
        ExternalUrlWarningDialog(
            url = url,
            onConfirm = {
                if (isLightningInvoice) {
                    launchLightningInvoice(context, url)
                } else {
                    launchExternalUrl(context, url)
                }
                pendingExternalUrl = null
            },
            onDismiss = { pendingExternalUrl = null },
            message = if (isLightningInvoice) stringResource(R.string.event_lightning_pay_warning) else null
        )
    }

    if (showReactionPicker) {
        EmojiReactionPickerSheet(
            reactionEmojis = reactionEmojis,
            onSelect = { content, emoji -> onLikeState.value(event, content, emoji) },
            onAddReactionEmoji = { onAddReactionEmojiState.value(it) },
            onRemoveReactionEmoji = { onRemoveReactionEmojiState.value(it) },
            onDismissRequest = { showReactionPicker = false }
        )
    }

    deleteConfirmationTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete_note_title),
            message = stringResource(R.string.delete_note_confirmation_message),
            confirmLabel = stringResource(R.string.delete_note_action),
            onConfirm = {
                onDeleteState.value(target)
                deleteConfirmationTarget = null
            },
            onDismiss = { deleteConfirmationTarget = null },
            isDestructive = true
        )
    }

    if (showActionsSheet) {
        ActionsBottomSheet(
            actions = eventActionItems(
                target = event,
                isPinned = isPinned,
                isCurrentUserEvent = isCurrentUserEvent,
                onPin = { onPinState.value(event) },
                onMute = { onMuteState.value(event.pubkey) },
                onDeleteRequest = { deleteConfirmationTarget = event },
                getEventJson = { getEventJsonState.value(event) }
            ),
            onDismissRequest = { showActionsSheet = false }
        )
    }

    if (showRepostActionsSheet && repostedByPubkey != null && repostEvent != null) {
        ActionsBottomSheet(
            actions = eventActionItems(
                target = repostEvent,
                isPinned = isRepostPinned,
                isCurrentUserEvent = !currentUserPubkey.isNullOrBlank() &&
                    repostEvent.pubkey.equals(currentUserPubkey, ignoreCase = true),
                onPin = { onPinState.value(repostEvent) },
                onMute = { onMuteState.value(repostedByPubkey) },
                onDeleteRequest = { deleteConfirmationTarget = repostEvent },
                getEventJson = { getEventJsonState.value(repostEvent) }
            ),
            onDismissRequest = { showRepostActionsSheet = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enableEventClick) {
                    Modifier.clickable { onEventClickState.value(event) }
                } else {
                    Modifier
                }
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp + (threadDepth.coerceAtMost(4) * 6).dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isReply) {
                val threadLabel = replyToLabel
                    ?: parentEventId?.truncatePublicKey(4, 4)
                    ?: stringResource(R.string.event_thread_fallback)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.event_reply_arrow),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (replyToProfile != null) {
                        UserAvatar(
                            userProfile = replyToProfile,
                            pubkey = replyToProfile.pubkey,
                            size = 18.dp,
                            shape = CircleShape,
                            animate = animateAvatars,
                            authorPubkey = replyToProfile.pubkey,
                            userRepository = userRepository
                        )
                    }
                    Text(
                        text = threadLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (repostedByPubkey != null) {
                RepostBanner(
                    pubkey = repostedByPubkey,
                    userProfile = repostedByProfile,
                    onClick = { onProfileClickState.value(repostedByPubkey) },
                    repostedAt = repostedAt,
                    onMenuClick = { showRepostActionsSheet = true },
                    authorPubkey = repostedByPubkey,
                    userRepository = userRepository
                )
            }

            NoteHeader(
                userProfile = userProfile,
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                onProfileClick = profileClick,
                animateAvatar = animateAvatars,
                authorPubkey = event.pubkey,
                userRepository = userRepository,
                trailingContent = {
                    IconButton(
                        onClick = { showActionsSheet = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.event_more_actions),
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            // Content with images, mentions, hashtags, URLs
            if (contentWarning != null && !isContentRevealed) {
                ContentWarningPlaceholder(
                    reason = contentWarning.reason,
                    onShowEvent = { isContentRevealed = true },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (normalizedContent.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NostrTextRenderer(
                        text = displayText,
                        tags = eventTagsSnapshot,
                        torDataSourceFactory = torDataSourceFactory,
                        userRepository = userRepository,
                        authorPubkey = event.pubkey,
                        // getProfiles() is cache/Room-only (no network call) — safe to resolve
                        // per-card so nostr:npub1.../nprofile1... mentions render as "@name"
                        // when we already have kind-0 metadata for that pubkey (never triggers
                        // a fetch for ones we don't).
                        resolveMentionProfiles = true,
                        getUrlMetadata = urlMetadataLookup,
                        modifier = Modifier.fillMaxWidth(),
                        mediaContentPadding = PaddingValues(horizontal = 0.dp),
                        onMentionClick = mentionClick,
                        onEventReferenceClick = eventReferenceClick,
                        onHashtagClick = hashtagClick,
                        onUrlClick = { url ->
                            pendingExternalUrl = url
                        },
                        getQuotedEvent = getQuotedEvent,
                        getQuotedEventAuthorProfile = getQuotedEventAuthorProfile,
                        hiddenEventIds = hiddenEventIds,
                        animateMedia = animateAvatars,
                        fullImageUrls = fullImageUrls,
                        fullLightningInvoices = fullLightningInvoices,
                        fullLnurlReferences = fullLnurlReferences,
                        compactMedia = compactMedia
                    )

                    if (textMetrics.shouldShowExpandButton) {
                        ShowMoreLessToggle(
                            isExpanded = isExpanded.value,
                            onToggle = { isExpanded.value = !isExpanded.value }
                        )
                    }

                }

            }

            // Quotes referenced only via a "q" tag, with no matching nostr:note1/nevent1/naddr1
            // substring anywhere in the content to anchor an inline embed to (see
            // quotesWithoutInlinePosition's doc comment above) — rendered here instead of dropped.
            if (quotesWithoutInlinePosition.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quotesWithoutInlinePosition.forEach { ref ->
                        val quotedEvent = getQuotedEvent(ref.id)
                        val clickReference = encodeQuoteReferenceForClick(ref.id, ref.relays)
                        if (quotedEvent != null) {
                            QuotedNoteCard(
                                quotedEvent = quotedEvent,
                                authorProfile = getQuotedEventAuthorProfile(quotedEvent.pubkey),
                                onClick = { eventReferenceClick(clickReference) },
                                torDataSourceFactory = torDataSourceFactory,
                                userRepository = userRepository,
                                onMentionClick = mentionClick,
                                onHashtagClick = hashtagClick,
                                onUrlClick = { url -> pendingExternalUrl = url },
                                onEventReferenceClick = eventReferenceClick
                            )
                        } else {
                            UnresolvedQuoteReferenceChip(
                                eventId = ref.id,
                                relayHints = ref.relays,
                                onClick = { eventReferenceClick(clickReference) }
                            )
                        }
                    }
                }
            }

            // Hashtags display (from NIP-30) — compact one-line with "+X more"
            if (hashtags.isNotEmpty()) {
                var expanded by remember(event.id) { mutableStateOf(false) }
                val maxVisible = 3

                if (!expanded) {
                    // FlowRow (not Row) so a chip that doesn't fit on the current line wraps to
                    // the next one instead of overflowing past the screen edge — still reads as
                    // "compact" since it's capped to maxVisible + "+X more" chips.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val display = if (hashtags.size <= maxVisible) hashtags else hashtags.take(maxVisible - 1)
                        display.forEach { tag ->
                            // No onClick: onHashtagClick has no wired destination yet (see
                            // EventCard's default), so a chevron here would promise navigation
                            // that tapping doesn't deliver.
                            ChipBadge(
                                text = "#$tag",
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                                textColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (hashtags.size > display.size) {
                            val remaining = hashtags.size - display.size
                            ChipBadge(
                                text = stringResource(R.string.event_more_count, remaining),
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                                textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                onClick = { expanded = true }
                            )
                        }
                    }
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        hashtags.forEach { tag ->
                            ChipBadge(
                                text = "#$tag",
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                                textColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        // Add a collapse control when hashtags are expanded
                        ChipBadge(
                            text = stringResource(R.string.event_show_less),
                            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = { expanded = false },
                            collapses = true
                        )
                    }
                }
            }

            ReactionBar(
                replyCount = replyCount,
                reactionCount = reactionCount,
                repostCount = repostCount,
                isLiked = isLiked,
                canSign = !currentUserPubkey.isNullOrBlank(),
                onReply = replyAction,
                onLike = likeAction,
                onRepost = repostAction,
                onQuote = quoteAction,
                onShare = shareAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                isReposted = isReposted,
                eventKindLabel = eventKindLabel?.let { label ->
                    if (label.arg == null) {
                        stringResource(label.labelRes)
                    } else {
                        stringResource(label.labelRes, label.arg)
                    }
                }
            )
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    }
}

/**
 * Get human-readable label for event kind (NIP-01 and extensions)
 */
internal data class EventKindLabel(
    @StringRes val labelRes: Int,
    val arg: Int? = null
)

internal fun getEventKindLabelModel(event: Event, hasQuoteRefs: Boolean, hasProfileMentions: Boolean): EventKindLabel? {
    return when (event.kind) {
        Event.KIND_METADATA -> EventKindLabel(R.string.event_kind_metadata)
        Event.KIND_TEXT_NOTE -> when {
            hasQuoteRefs -> EventKindLabel(R.string.event_kind_quote)
            hasProfileMentions -> EventKindLabel(R.string.event_kind_mention)
            // A plain kind:1 text note is the overwhelming majority of the feed — labeling it
            // "Note" on every single card is chrome that states the obvious rather than
            // distinguishing content, unlike the quote/mention/repost/article cases below where
            // the label is genuinely informative. Omit it here instead.
            else -> null
        }
        Event.KIND_RELAY_RECOMMENDATION -> EventKindLabel(R.string.event_kind_relay_rec)
        Event.KIND_CONTACT_LIST -> EventKindLabel(R.string.event_kind_contacts)
        Event.KIND_ENCRYPTED_DM -> EventKindLabel(R.string.event_kind_dm_enc)
        Event.KIND_EVENT_DELETION -> EventKindLabel(R.string.event_kind_deletion)
        Event.KIND_REPOST -> EventKindLabel(R.string.event_kind_repost)
        Event.KIND_REACTION -> EventKindLabel(R.string.event_kind_reaction)
        Event.KIND_COMMENT -> EventKindLabel(R.string.event_kind_comment)
        Event.KIND_BADGE_AWARD -> EventKindLabel(R.string.event_kind_badge)
        Event.KIND_LONG_FORM -> EventKindLabel(R.string.event_kind_article)
        Event.KIND_MUTED_USERS -> EventKindLabel(R.string.event_kind_mutes)
        Event.KIND_PINNED_EVENTS -> EventKindLabel(R.string.event_kind_pins)
        else -> EventKindLabel(R.string.event_kind_unknown, event.kind)
    }
}

private fun hasProfileMentions(event: Event): Boolean = PROFILE_MENTION_REGEX.containsMatchIn(event.content)


