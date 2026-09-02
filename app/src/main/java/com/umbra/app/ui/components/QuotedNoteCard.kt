package com.umbra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip36.extractContentWarning
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.toImmutableSnapshot
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.feed.getEventKindLabelModel
import com.umbra.app.ui.feed.normalizeNoteContentForDisplay
import java.net.URI

/**
 * Compact inline embed for a quoted note (NIP-18 "q" tag / nostr:note1|nevent1|naddr1 content
 * reference) — reuses UserAvatar/UserIdentityBadge rather than a bespoke header, matching the
 * full EventCard's NoteHeader in spirit at a smaller scale. Tapping navigates to the thread.
 * Public (not confined to EventCard.kt) since NostrTextRenderer renders this directly, positioned
 * where the reference actually appears in the text, instead of the caller appending it after all
 * the text unconditionally.
 */
@Composable
fun QuotedNoteCard(
    quotedEvent: Event,
    authorProfile: UserProfile?,
    onClick: () -> Unit,
    torDataSourceFactory: DataSource.Factory,
    // Threaded straight into UserAvatar's identically-named param below (with quotedEvent.pubkey
    // as authorPubkey) to enable Blossom-fallback (BUD-03) candidate retrieval for the quoted
    // note's own avatar. Compose-stability tradeoff: UserRepository is a plain (non-@Stable)
    // interface, so this parameter makes QuotedNoteCard unconditionally non-skippable — see
    // UserAvatar's own userRepository doc comment for the full rationale. Accepted deliberately
    // for BUD-03 candidacy rather than introducing a narrower stable wrapper type.
    userRepository: UserRepository,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onEventReferenceClick: (String) -> Unit = {},
    // How many QuotedNoteCard levels deep this one already is — see NostrTextRenderer's
    // quoteEmbedDepth doc comment for the recursion cap this feeds into.
    quoteEmbedDepth: Int = 0
) {
    var isExpanded by remember(quotedEvent.id) { mutableStateOf(false) }
    var isContentRevealed by remember(quotedEvent.id) { mutableStateOf(false) }
    val contentWarning = remember(quotedEvent.id, quotedEvent.tags) { extractContentWarning(quotedEvent) }
    val normalizedQuoteContent = remember(quotedEvent.id, quotedEvent.content) {
        normalizeNoteContentForDisplay(quotedEvent.content)
    }
    val textMetrics = remember(quotedEvent.id, normalizedQuoteContent) {
        computeTextRenderMetrics(normalizedQuoteContent)
    }
    val displayQuoteContent = if (isExpanded || !textMetrics.shouldShowExpandButton) {
        normalizedQuoteContent
    } else {
        textMetrics.collapsedText
    }
    // Same rationale as EventCard's fullLightningInvoices — a collapsed quote card must still
    // parse any invoice in its content correctly, not just whatever survived truncation.
    val fullLightningInvoices = remember(quotedEvent.id, normalizedQuoteContent) {
        parseInlineMediaSegments(normalizedQuoteContent).filterIsInstance<InlineMediaSegment.LightningInvoice>().map { it.invoice }
    }
    // Same rationale as fullLightningInvoices above, for LNURL strings.
    val fullLnurlReferences = remember(quotedEvent.id, normalizedQuoteContent) {
        parseInlineMediaSegments(normalizedQuoteContent).filterIsInstance<InlineMediaSegment.LnurlReference>().map { it.lnurl }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(
                    userProfile = authorProfile,
                    pubkey = quotedEvent.pubkey,
                    // 32.dp (not the 20.dp this used to be) so the avatar reads proportionate
                    // to UserIdentityBadge's two-line name+nip05 stack next to it, matching
                    // NoteHeader's own header/badge size ratio at a smaller scale instead of
                    // looking like a stray dot floating next to taller text.
                    size = 32.dp,
                    shape = CircleShape,
                    animate = false,
                    authorPubkey = quotedEvent.pubkey,
                    userRepository = userRepository
                )
                UserIdentityBadge(
                    userProfile = authorProfile,
                    pubkey = quotedEvent.pubkey,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TimeFormatter.formatRelativeTime(quotedEvent.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Non-text-note kinds (e.g. a quoted repost, article, or any kind without a
            // dedicated content renderer yet) still show up here — content is rendered as raw
            // text below regardless of kind, so a label avoids it reading as a plain kind:1 note.
            val kindLabel = if (quotedEvent.kind != Event.KIND_TEXT_NOTE) {
                getEventKindLabelModel(quotedEvent, hasQuoteRefs = false, hasProfileMentions = false)
            } else {
                null
            }
            if (kindLabel != null) {
                Text(
                    text = if (kindLabel.arg == null) {
                        stringResource(kindLabel.labelRes)
                    } else {
                        stringResource(kindLabel.labelRes, kindLabel.arg)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (contentWarning != null && !isContentRevealed) {
                ContentWarningPlaceholder(
                    reason = contentWarning.reason,
                    onShowEvent = { isContentRevealed = true },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (normalizedQuoteContent.isNotBlank()) {
                NostrTextRenderer(
                    text = displayQuoteContent,
                    tags = quotedEvent.tags.toImmutableSnapshot(),
                    torDataSourceFactory = torDataSourceFactory,
                    userRepository = userRepository,
                    authorPubkey = quotedEvent.pubkey,
                    resolveMentionProfiles = true,
                    quoteEmbedDepth = quoteEmbedDepth + 1,
                    animateMedia = false,
                    fullLightningInvoices = fullLightningInvoices,
                    fullLnurlReferences = fullLnurlReferences,
                    onMentionClick = onMentionClick,
                    onHashtagClick = onHashtagClick,
                    onUrlClick = onUrlClick,
                    onEventReferenceClick = onEventReferenceClick,
                    modifier = Modifier.fillMaxWidth(),
                    mediaContentPadding = PaddingValues(0.dp)
                )
                if (textMetrics.shouldShowExpandButton) {
                    ShowMoreLessToggle(
                        isExpanded = isExpanded,
                        onToggle = { isExpanded = !isExpanded }
                    )
                }
            }
        }
    }
}

/**
 * Placeholder shown at a quote reference's position when [QuotedNoteCard]'s event hasn't
 * resolved yet (still hydrating from relays/Room, or genuinely unavailable) — sized and shaped
 * like the resolved [QuotedNoteCard] (full width, same surface/border) rather than a small chip,
 * so a note's layout doesn't visibly jump once the quote downloads. Shows a note icon, the
 * referenced id, and — when the nevent1 reference carried any — the relay(s) it hinted at, so
 * there's something to look at besides a bare id while the quote is still being fetched.
 */
@Composable
fun UnresolvedQuoteReferenceChip(
    eventId: String,
    relayHints: List<String> = emptyList(),
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.event_quote_reference, eventId.truncatePublicKey(8, 0)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (relayHints.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.event_quote_relay_hint,
                            relayHints.joinToString(", ") { it.toRelayHost() }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun String.toRelayHost(): String =
    runCatching { URI(this).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: this
