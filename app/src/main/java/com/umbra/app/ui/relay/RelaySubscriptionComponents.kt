package com.umbra.app.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip01.KindNames
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.relay.SubscriptionType
import com.umbra.app.ui.components.TimeFormatter
import com.umbra.app.ui.components.truncatePublicKey
import kotlin.OptIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionCard(
    req: RelayRequestInfo,
    showRelayUrl: Boolean = false,
    currentUserPubkey: String? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ID + type label on the left, event count + last-event time in the top-right corner
            // — combines what used to be 3 separate rows (type label, ID+count, last-event) into
            // one, so the card leads with the subscription's identity instead of its icon.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SelectionContainer {
                        Text(
                            text = req.subscriptionId,
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(subscriptionTypeLabelRes(req.type)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.relay_subscription_events, req.receivedEventCount),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Surfaces sentAtMillis/lastEventAtMillis, which existed on RelayRequestInfo
                    // but were only ever used for sort order, never actually shown — useful for
                    // spotting a subscription that's open but has gone quiet. Bare relative time,
                    // no "last event" label — the top-right position next to the count already
                    // makes what it's measuring obvious.
                    val lastEventText = req.lastEventAtMillis?.let { TimeFormatter.formatRelativeTime(it / 1000) }
                        ?: stringResource(R.string.relay_subscription_no_events_yet)
                    Text(
                        text = lastEventText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showRelayUrl) {
                Text(
                    text = formatRelayUrl(req.relayUrl),
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // One structured chip row per filter (rendered from the real EventFilter, not a
            // regex-parsed debug string — see RelayRequestInfo's doc comment) — kind names
            // instead of raw numbers, author/id counts instead of truncated npub lists. Each
            // filter gets its own bordered box so a multi-filter subscription reads as clearly
            // separated groups instead of every filter's chips flowing together.
            req.filters.forEachIndexed { index, filter ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChipRow(
                        filter = filter,
                        index = index,
                        showIndex = req.filters.size > 1,
                        currentUserPubkey = currentUserPubkey
                    )
                }
            }
        }
    }
}

private fun subscriptionTypeLabelRes(type: SubscriptionType): Int = when (type) {
    SubscriptionType.OUTBOX_PROFILE -> R.string.relay_subscription_type_outbox_profile
    SubscriptionType.OUTBOX_NOTES -> R.string.relay_subscription_type_outbox_notes
    SubscriptionType.INBOX_NOTES -> R.string.relay_subscription_type_inbox_notes
    SubscriptionType.FEED_NOTES -> R.string.relay_subscription_type_feed_notes
    SubscriptionType.FEED_PROFILES_ONDEMAND -> R.string.relay_subscription_type_feed_profiles_ondemand
    SubscriptionType.FEED_PROFILES -> R.string.relay_subscription_type_feed_profiles
    SubscriptionType.FEED_OUTBOX_SWEEP -> R.string.relay_subscription_type_feed_outbox_sweep
    SubscriptionType.SEARCH_NOTES -> R.string.relay_subscription_type_search_notes
    SubscriptionType.SEARCH_PROFILES -> R.string.relay_subscription_type_search_profiles
    SubscriptionType.EVENT_LOOKUP -> R.string.relay_subscription_type_event_lookup
    SubscriptionType.EVENT_INTERACTIONS -> R.string.relay_subscription_type_event_interactions
    SubscriptionType.PROFILE_BACKFILL -> R.string.relay_subscription_type_profile_backfill
    SubscriptionType.PROFILE_LOOKUP -> R.string.relay_subscription_type_profile_lookup
    SubscriptionType.COUNT -> R.string.relay_subscription_type_count
    SubscriptionType.NEGENTROPY_SYNC -> R.string.relay_subscription_type_negentropy_sync
    SubscriptionType.NEGENTROPY_FETCH -> R.string.relay_subscription_type_negentropy_fetch
    SubscriptionType.DEFAULT -> R.string.relay_subscription_type_default
    SubscriptionType.OTHER -> R.string.relay_subscription_type_other
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipRow(
    filter: EventFilter,
    index: Int,
    showIndex: Boolean,
    currentUserPubkey: String? = null
) {
    val you = stringResource(R.string.relay_filter_you)
    val taggedYou = stringResource(R.string.relay_filter_tagged_you)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showIndex) {
            Text(
                text = stringResource(R.string.relay_subscription_filter_index, index + 1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filter.kinds.sorted().forEach { kind -> KindChip(kind) }

            if (filter.authors.isNotEmpty()) {
                val isSelf = filter.authors.size == 1 &&
                    currentUserPubkey != null &&
                    filter.authors.first().equals(currentUserPubkey, ignoreCase = true)
                SubscriptionStatChip(
                    text = if (isSelf) you else pluralStringResource(R.plurals.relay_filter_authors, filter.authors.size, filter.authors.size),
                    icon = Icons.Outlined.Person
                )
            }
            if (filter.ids.isNotEmpty()) {
                SubscriptionStatChip(
                    text = pluralStringResource(R.plurals.relay_filter_ids, filter.ids.size, filter.ids.size),
                    icon = Icons.Outlined.Tag
                )
            }
            // #p ("tagged"/mentioned pubkeys) gets author-style treatment (count, or "You" when
            // it's a single self-reference — e.g. this session's own inbox filters) instead of
            // the generic "#tag:value" rendering every other tag gets below.
            filter.tagFilters["p"]?.let { taggedPubkeys ->
                if (taggedPubkeys.isNotEmpty()) {
                    val isSelf = taggedPubkeys.size == 1 &&
                        currentUserPubkey != null &&
                        taggedPubkeys.first().equals(currentUserPubkey, ignoreCase = true)
                    // "Tagged you", not the bare "You" the authors chip uses above — otherwise an
                    // authors=[me] filter and a #p=[me] filter render as the identical chip, even
                    // though they mean opposite things (things I wrote vs. things that mention me).
                    SubscriptionStatChip(
                        text = if (isSelf) taggedYou else stringResource(R.string.relay_filter_tagged, taggedPubkeys.size),
                        icon = Icons.Outlined.AlternateEmail
                    )
                }
            }
            filter.tagFilters.toSortedMap().forEach { (tag, values) ->
                if (tag == "p" || values.isEmpty()) return@forEach
                val text = if (values.size == 1) {
                    val value = values.first()
                    "#$tag:" + if (value.length > 8) value.truncatePublicKey(8, 0) else value
                } else {
                    "#$tag ×${values.size}"
                }
                SubscriptionStatChip(text = text)
            }
            filter.since?.let {
                SubscriptionStatChip(
                    text = stringResource(R.string.relay_filter_since, TimeFormatter.formatRelativeTime(it)),
                    icon = Icons.Outlined.Schedule
                )
            }
            filter.until?.let {
                SubscriptionStatChip(
                    text = stringResource(R.string.relay_filter_until, TimeFormatter.formatRelativeTime(it)),
                    icon = Icons.Outlined.History
                )
            }
            if (filter.limit > 0) {
                SubscriptionStatChip(
                    text = stringResource(R.string.relay_filter_limit, filter.limit),
                    icon = Icons.Outlined.FilterList
                )
            }
            filter.search?.takeIf { it.isNotBlank() }?.let { search ->
                SubscriptionStatChip(
                    text = summarizeFilterSegment(search, 20),
                    icon = Icons.Outlined.Search
                )
            }
        }
    }
}

// Coarse categories purely for KindChip coloring at a glance — not a protocol taxonomy, just
// "posts you'd see in a feed" / "lists/settings" / "DM-ish" vs. everything else. A `zaps` bucket
// would need a bitcoin-branded color Umbra's theme doesn't define; zaps fall into "other" here
// instead of borrowing an ill-fitting color role (errorContainer would misread as "something's
// wrong").
private val KIND_CHIP_POST_KINDS = setOf(
    Event.KIND_TEXT_NOTE, Event.KIND_REPOST, Event.KIND_GENERIC_REPOST, Event.KIND_REACTION,
    Event.KIND_WEBSITE_REACTION, Event.KIND_LONG_FORM, Event.KIND_PICTURE, Event.KIND_VIDEO_EVENT,
    Event.KIND_SHORT_FORM_PORTRAIT_VIDEO_EVENT, Event.KIND_COMMENT, Event.KIND_THREAD, Event.KIND_PUBLIC_MESSAGE
)
private val KIND_CHIP_LIST_KINDS = setOf(
    Event.KIND_METADATA, Event.KIND_CONTACT_LIST, Event.KIND_RELAY_LIST_METADATA, Event.KIND_MUTED_USERS,
    Event.KIND_PINNED_EVENTS, Event.KIND_BOOKMARK_LIST, Event.KIND_COMMUNITIES_LIST,
    Event.KIND_BLOCKED_RELAYS, Event.KIND_SEARCH_RELAYS, Event.KIND_INTERESTS_LIST, Event.KIND_DM_RELAY_LIST,
    Event.KIND_INDEX_RELAYS
)
private val KIND_CHIP_DM_KINDS = setOf(
    Event.KIND_ENCRYPTED_DM, Event.KIND_DIRECT_MESSAGE, Event.KIND_FILE_MESSAGE, Event.KIND_SEAL,
    Event.KIND_CHAT_MESSAGE, Event.KIND_GROUP_CHAT_THREADED_REPLY, Event.KIND_GROUP_THREAD_REPLY
)

/**
 * Chips stay neutral; meaning comes from a small leading mark. Kinds get a category dot (posts,
 * lists, DMs, deletions) instead of a different fill each, so a busy subscription reads as one
 * calm row rather than a rainbow.
 */
@Composable
private fun KindChip(kind: Int) {
    val label = remember(kind) { KindNames.labelFor(kind) }
    val dot = when (kind) {
        Event.KIND_EVENT_DELETION -> MaterialTheme.colorScheme.error
        in KIND_CHIP_POST_KINDS -> MaterialTheme.colorScheme.primary
        in KIND_CHIP_LIST_KINDS -> UmbraTheme.colors.secure
        in KIND_CHIP_DM_KINDS -> UmbraTheme.colors.zap
        else -> MaterialTheme.colorScheme.outline
    }
    ChipShell {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        ChipText(label)
    }
}

@Composable
private fun SubscriptionStatChip(
    text: String,
    icon: ImageVector? = null
) {
    ChipShell {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        ChipText(text)
    }
}

@Composable
private fun ChipShell(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        content = content
    )
}

@Composable
private fun ChipText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun summarizeFilterSegment(value: String, maxChars: Int = 40): String {
    val compact = value.replace('\n', ' ').trim()
    return if (compact.length <= maxChars) compact else compact.take(maxChars) + "…"
}
