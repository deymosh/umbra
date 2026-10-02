package com.umbra.app.ui.notifications

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.umbra.app.ui.components.LoadMoreEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.notifications.NotificationGroup
import com.umbra.app.domain.notifications.NotificationType
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.TimeFormatter
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
import com.umbra.app.ui.zap.formatSats

@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NotificationsContent(
        state = state,
        onNavigateBack = onNavigateBack,
        onFilter = viewModel::setFilter,
        onOpenThread = onOpenThread,
        onOpenProfile = onOpenProfile,
        onLoadOlder = viewModel::loadOlder
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsContent(
    state: NotificationsState,
    onNavigateBack: () -> Unit,
    onFilter: (NotificationFilter) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLoadOlder: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(
        listState = listState,
        itemCount = state.visible.size,
        enabled = !state.isLoading && !state.isLoadingMore && !state.olderExhausted,
        onLoadMore = onLoadOlder
    )
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotificationFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilter(filter) },
                        label = { Text(stringResource(filter.labelRes())) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            when {
                state.isAnonymous -> EmptyState(
                    title = stringResource(R.string.notifications_title),
                    message = stringResource(R.string.notifications_anonymous),
                    modifier = Modifier.fillMaxSize()
                )
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingSpinner() }
                state.visible.isEmpty() -> EmptyState(
                    title = stringResource(R.string.notifications_empty),
                    message = stringResource(R.string.notifications_empty_message),
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.visible, key = { it.key }, contentType = { it.type }) { group ->
                        NotificationRow(
                            group = group,
                            profiles = state.profiles,
                            target = group.targetEventId?.let(state.targets::get),
                            unseen = group.latestAt > state.seenAt,
                            onClick = {
                                val id = group.event?.id ?: group.targetEventId
                                if (id != null) onOpenThread(id) else group.actorPubkeys.firstOrNull()?.let(onOpenProfile)
                            },
                            onOpenProfile = onOpenProfile
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (state.isLoadingMore) {
                        item(key = "loading-more", contentType = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                LoadingSpinner(size = 20.dp, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun NotificationFilter.labelRes(): Int = when (this) {
    NotificationFilter.ALL -> R.string.notifications_filter_all
    NotificationFilter.REPLIES -> R.string.notifications_filter_replies
    NotificationFilter.ZAPS -> R.string.notifications_filter_zaps
    NotificationFilter.REACTIONS -> R.string.notifications_filter_reactions
}

@Composable
private fun NotificationRow(
    group: NotificationGroup,
    profiles: Map<String, UserProfile>,
    target: Event?,
    unseen: Boolean,
    onClick: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val (icon, tint) = group.type.iconAndTint()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (unseen) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            if (unseen) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(UmbraTheme.colors.corona)
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AvatarStack(group.actorPubkeys, profiles, onOpenProfile)
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = headline(group, profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TimeFormatter.formatCompactRelativeTime(group.latestAt),
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            val body = group.event?.content ?: group.zapComment
            if (!body.isNullOrBlank()) {
                Text(
                    text = body.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (target != null && group.event == null) {
                Text(
                    text = target.content.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AvatarStack(pubkeys: List<String>, profiles: Map<String, UserProfile>, onOpenProfile: (String) -> Unit) {
    val shown = pubkeys.take(5)
    Box(Modifier.size(width = (28 + (shown.size - 1).coerceAtLeast(0) * 20).dp, height = 28.dp)) {
        shown.forEachIndexed { index, pubkey ->
            Box(
                Modifier
                    .offset(x = (index * 20).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp)
                    .clickable { onOpenProfile(pubkey) }
            ) {
                UserAvatar(userProfile = profiles[pubkey], pubkey = pubkey, size = 25.dp, animate = false)
            }
        }
    }
}

@Composable
private fun headline(group: NotificationGroup, profiles: Map<String, UserProfile>) = buildAnnotatedString {
    val first = group.actorPubkeys.firstOrNull()
    val name = first?.let { profiles[it]?.getUserDisplayName() ?: it.truncatePublicKey() }.orEmpty()
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
    val others = group.actorPubkeys.size - 1
    if (others > 0) {
        append(" ")
        append(pluralStringResource(R.plurals.notifications_and_others, others, others))
    }
    append(" ")
    append(
        when (group.type) {
            NotificationType.REPLY -> stringResource(R.string.notifications_verb_reply)
            NotificationType.MENTION -> stringResource(R.string.notifications_verb_mention)
            NotificationType.REACTION -> stringResource(R.string.notifications_verb_reaction)
            NotificationType.REPOST -> stringResource(R.string.notifications_verb_repost)
            NotificationType.ZAP -> stringResource(R.string.notifications_verb_zap, formatSats(group.zapTotalSats))
        }
    )
    val emojis = group.reactions.filterNot { it == "+" || it.startsWith(":") }.take(4)
    if (emojis.isNotEmpty()) append("  " + emojis.joinToString(" "))
}

@Composable
private fun NotificationType.iconAndTint(): Pair<ImageVector, Color> = when (this) {
    NotificationType.REPLY -> Icons.Outlined.ChatBubbleOutline to MaterialTheme.colorScheme.primary
    NotificationType.MENTION -> Icons.Outlined.AlternateEmail to MaterialTheme.colorScheme.primary
    NotificationType.REACTION -> Icons.Outlined.Favorite to UmbraTheme.colors.like
    NotificationType.REPOST -> Icons.Outlined.Repeat to UmbraTheme.colors.repost
    NotificationType.ZAP -> Icons.Outlined.Bolt to UmbraTheme.colors.zap
}
