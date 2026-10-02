package com.umbra.app.ui.profile

import kotlin.math.roundToInt
import com.umbra.app.ui.components.ShowMoreLessToggle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.Orientation
import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.umbra.app.ui.components.formatCount
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.ui.zap.LocalZapLauncher
import com.umbra.app.ui.zap.ZapTarget
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.Screen
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.CustomEmojiText
import com.umbra.app.ui.components.PersonRow
import com.umbra.app.ui.lists.LocalPeopleLists
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.ui.components.HASHTAG_REGEX
import com.umbra.app.ui.components.URL_REGEX
import com.umbra.app.ui.components.buildThreadDepthByEventId
import com.umbra.app.ui.components.customEmojiInlineContentId
import com.umbra.app.ui.components.rememberCustomEmojiInlineContent
import com.umbra.app.ui.components.notesFeedSection
import com.umbra.app.ui.components.normalizeExternalUrl
import com.umbra.app.ui.components.QuickActionBottomBar
import com.umbra.app.ui.components.launchExternalUrl
import com.umbra.app.ui.components.shareEventUrl
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.media.rememberRetryingAsyncImagePainter
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.common.awaitViewportPrefetchQuietWindow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class ProfileTab {
    NOTES,
    PICTURES,
    REPLIES,
    FOLLOWS,
    RELAYS,
    MUTES,
    PINNED
}

/**
 * Profile screen - shows a Nostr user's avatar, bio and recent notes.
 * Accessible by tapping any author name/avatar in the feed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reactionEmojis by viewModel.reactionEmojis.collectAsStateWithLifecycle()
    val profile = state.profile
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val npub = remember(viewModel.pubkey) { Bech32Encoder.encodeNpub(viewModel.pubkey) }
    val profileNotes = state.notes
    val topLevelNotes = remember(profileNotes) { profileNotes.filter { it.isTopLevelFeedNote() } }
    val replyNotes = remember(profileNotes) { profileNotes.filterNot { it.isTopLevelFeedNote() } }
    // Merges in quoted events resolved by viewport prefetch (see ProfileViewModel.
    // prefetchViewportImages) that aren't part of this profile's own note list.
    val eventsById = remember(profileNotes, state.resolvedQuotedEvents) {
        profileNotes.associateBy { it.id } + state.resolvedQuotedEvents.toMap()
    }
    val navControllerState = rememberUpdatedState(navController)
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(ProfileTab.NOTES) }
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }
    val canSign = viewModel.canSignEvents()
    val isOwnProfile = viewModel.isCurrentUserProfile()
    val resolveProfileForPubkey = remember(profile, state.followedProfiles, state.profiles, viewModel.pubkey) {
        { authorPubkey: String ->
            when {
                authorPubkey.equals(viewModel.pubkey, ignoreCase = true) -> profile
                else -> state.followedProfiles[authorPubkey.lowercase()]
                    ?: state.profiles[authorPubkey]
                    ?: state.profiles[authorPubkey.lowercase()]
            }
        }
    }

    // NIP-68 picture posts get their own tab, only once this author has any.
    val hasPictures = state.pictures.isNotEmpty()
    val tabs = remember(isOwnProfile, hasPictures) {
        buildList {
            add(ProfileTab.NOTES)
            if (hasPictures) add(ProfileTab.PICTURES)
            add(ProfileTab.REPLIES)
            add(ProfileTab.FOLLOWS)
            add(ProfileTab.RELAYS)
            if (isOwnProfile) {
                add(ProfileTab.MUTES)
                add(ProfileTab.PINNED)
            }
        }
    }
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(selectedTab).coerceAtLeast(0)) { tabs.size }
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.currentPage }.collect { page -> selectedTab = tabs[page] }
    }
    // One list per tab so each keeps its own scroll position while swiping between them.
    // Keyed per tab: tabs can appear (Pictures), so positional remember would shift scroll states.
    val listStates = tabs.associateWith { tab -> key(tab) { rememberLazyListState() } }
    val listState = listStates.getValue(selectedTab)
    LaunchedEffect(listState, selectedTab) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 2
        }
            .conflate()
            .distinctUntilChanged()
            .collectLatest { nearBottom ->
                if (selectedTab != ProfileTab.NOTES && selectedTab != ProfileTab.REPLIES) return@collectLatest
                if (!nearBottom) return@collectLatest
                viewModel.loadMoreNotes()
            }
    }

    val stableOpenThread = remember {
        { eventId: String -> navControllerState.value.navigate(Screen.Thread.forEvent(eventId)) }
    }
    val stableLike = remember {
        { event: Event, content: String, emoji: CustomEmoji? -> viewModel.likeEvent(event, content, emoji) }
    }
    val onAddReactionEmoji = remember { { emoji: ReactionEmoji -> viewModel.addReactionEmoji(emoji) } }
    val onRemoveReactionEmoji = remember { { key: String -> viewModel.removeReactionEmoji(key) } }
    val stableRepost = remember { { event: Event -> viewModel.repostEvent(event) } }
    val stableShare = remember { { event: Event -> viewModel.shareEvent(event) } }
    // These used to be built as raw inline lambdas at the notesFeedSection() call site below,
    // recreated on every ProfileState emission (reply/reaction/repost counts update live from
    // incoming relay events, same as FeedScreen) — defeating EventCard's recomposition-skip for
    // every visible row on each count change. Same fix already applied to FeedScreen/ThreadScreen.
    val onEventClickStable = remember { { event: Event -> stableOpenThread(event.id) } }
    val onEventReferenceClickStable = remember { { eventId: String -> stableOpenThread(eventId) } }
    val onReplyStable = remember {
        { event: Event -> navControllerState.value.navigate(Screen.Composer.reply(event.id)) }
    }
    val onQuoteStable = remember {
        { event: Event -> navControllerState.value.navigate(Screen.Composer.quote(event.id)) }
    }
    val onDeleteStable = remember(viewModel) { { event: Event -> viewModel.deleteEvent(event) } }
    val onMuteStable = remember(viewModel) { { targetPubkey: String -> viewModel.muteUser(targetPubkey) } }
    val onPinStable = remember(viewModel) { { event: Event -> viewModel.togglePin(event) } }
    // Navigates for anyone else mentioned in a note's header or body text; no-ops only for the
    // profile currently being viewed, since a note's own author avatar/@self-mention shouldn't
    // re-push the same profile onto the nav stack. A blanket no-op here previously also broke
    // clicking mentions of *other* people within note content on this screen.
    val stableProfileClick = remember(viewModel.pubkey) {
        { clickedPubkey: String ->
            if (!clickedPubkey.equals(viewModel.pubkey, ignoreCase = true)) {
                navControllerState.value.navigate(Screen.Profile.forPubkey(clickedPubkey))
            }
        }
    }
    val getUrlMetadata = remember(viewModel) { { url: String -> viewModel.getUrlMetadata(url) } }
    val getEventJson = remember(viewModel) { { event: Event -> viewModel.getEventJson(event) } }

    // requestSignEvent()'s Amber round trip goes through the single app-wide launcher
    // (AppSessionEffects) now — no per-screen launcher needed here.

    LaunchedEffect(viewModel) {
        viewModel.shareUrlEffect.collect { url -> shareEventUrl(context, url) }
    }

    pendingExternalUrl?.let { url ->
        ExternalUrlWarningDialog(
            url = url,
            onConfirm = {
                launchExternalUrl(context, url)
                pendingExternalUrl = null
            },
            onDismiss = { pendingExternalUrl = null }
        )
    }

    fun notesFor(tab: ProfileTab) = when (tab) {
        ProfileTab.NOTES -> topLevelNotes
        ProfileTab.REPLIES -> replyNotes
        ProfileTab.PINNED -> state.pinnedNotes
        ProfileTab.PICTURES -> state.pictures
        else -> emptyList()
    }
    val visibleNotes = notesFor(selectedTab)
    val pinnedEventIds = remember(state.pinnedNotes) { state.pinnedNotes.mapTo(HashSet()) { it.id } }
    // pinnedEventIds is a plain remember(key) val, not a State-delegate read — rememberUpdatedState
    // gives the permanently-stable lambda below a way to always see the latest set without being
    // rebuilt itself (mirrors currentNavController's rememberUpdatedState use elsewhere).
    val currentPinnedEventIds by rememberUpdatedState(pinnedEventIds)
    val isPinnedForEventStable = remember { { eventId: String -> currentPinnedEventIds.contains(eventId) } }
    val latestVisibleNotes by rememberUpdatedState(visibleNotes)
    // Each tab's list holds only that tab's rows now (the header lives outside the pager).
    val notesSectionStartIndex = 0
    val noNotesTitle = stringResource(R.string.no_notes_yet)
    val noRepliesTitle = stringResource(R.string.profile_no_replies_yet)
    val noPinsTitle = stringResource(R.string.profile_no_pins)
    fun emptyTitleFor(tab: ProfileTab) = when (tab) {
        ProfileTab.REPLIES -> noRepliesTitle
        ProfileTab.PINNED -> noPinsTitle
        else -> noNotesTitle
    }

    LaunchedEffect(listState, selectedTab, notesSectionStartIndex) {
        if (selectedTab != ProfileTab.NOTES && selectedTab != ProfileTab.REPLIES) return@LaunchedEffect

        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val firstNoteItemIndex = visibleItems.firstOrNull { it.index >= notesSectionStartIndex }?.index
            val firstNoteVisibleIndex = (firstNoteItemIndex ?: notesSectionStartIndex) - notesSectionStartIndex
            val visibleNoteCount = visibleItems.count { it.index >= notesSectionStartIndex }
            firstNoteVisibleIndex to visibleNoteCount
        }
            .conflate()
            .distinctUntilChanged()
            .collectLatest { (firstVisibleNoteIndex, visibleNoteCount) ->
                if (!awaitViewportPrefetchQuietWindow(visibleNoteCount)) return@collectLatest
                viewModel.prefetchViewportImages(
                    events = latestVisibleNotes,
                    firstVisibleIndex = firstVisibleNoteIndex.coerceAtLeast(0),
                    visibleCount = visibleNoteCount
                )
            }
    }

    val density = LocalDensity.current
    // Collapsing header: the hero (banner, identity, bio) scrolls away first, then the active
    // tab's list scrolls; the tab bar stays pinned. heroHeightPx is how far it can collapse.
    var heroHeightPx by remember { mutableIntStateOf(0) }
    var tabBarHeightPx by remember { mutableIntStateOf(0) }
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    val headerConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceIn(-heroHeightPx.toFloat(), 0f)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceIn(-heroHeightPx.toFloat(), 0f)
                val used = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, used)
            }
        }
    }
    // Drags that start on the header itself (it's most of the first screen) scroll too, with
    // fling: collapse the header first, then hand the rest to the active tab's list.
    val currentListState by rememberUpdatedState(listState)
    val headerScrollState = rememberScrollableState { delta ->
        if (delta < 0f) {
            val newOffset = (headerOffsetPx + delta).coerceIn(-heroHeightPx.toFloat(), 0f)
            val collapsed = newOffset - headerOffsetPx
            headerOffsetPx = newOffset
            collapsed - currentListState.dispatchRawDelta(-(delta - collapsed))
        } else {
            val listConsumed = -currentListState.dispatchRawDelta(-delta)
            val remaining = delta - listConsumed
            val newOffset = (headerOffsetPx + remaining).coerceIn(-heroHeightPx.toFloat(), 0f)
            val expanded = newOffset - headerOffsetPx
            headerOffsetPx = newOffset
            listConsumed + expanded
        }
    }
    val collapseFraction = if (heroHeightPx > 0) (-headerOffsetPx / heroHeightPx).coerceIn(0f, 1f) else 0f
    val listTopPadding = with(density) { (heroHeightPx + tabBarHeightPx).toDp() }

    // When switching tabs with the header collapsed, bring the new tab's list level with it
    // instead of showing an empty band where the header used to be.
    LaunchedEffect(pagerState.settledPage) {
        val collapsedBy = (-headerOffsetPx).toInt()
        val state = listStates.getValue(tabs[pagerState.settledPage])
        if (collapsedBy > 0 && state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < collapsedBy) {
            state.scrollToItem(0, collapsedBy)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(headerConnection)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { tabs[it].name },
            beyondViewportPageCount = 1
        ) { page ->
            val pageTab = tabs[page]
            val pageNotes = notesFor(pageTab)
            val pageThreadDepth = remember(pageNotes, eventsById) {
                buildThreadDepthByEventId(pageNotes, eventsById)
            }
            LazyColumn(
                state = listStates.getValue(pageTab),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = listTopPadding, bottom = 112.dp)
            ) {
            when (pageTab) {
                ProfileTab.NOTES,
                ProfileTab.PICTURES,
                ProfileTab.REPLIES,
                ProfileTab.PINNED -> {
                    notesFeedSection(
                        notes = pageNotes,
                        eventsById = eventsById,
                        threadDepthByEventId = pageThreadDepth,
                        profileForPubkey = resolveProfileForPubkey,
                        userRepository = viewModel.userRepositoryPublic,
                        replyCounts = state.replyCounts,
                        reactionCounts = state.reactionCounts,
                        repostCounts = state.repostCounts,
                        zapSatsForEvent = state.zapSats,
                        repostedByPubkeyForEvent = state.repostedByPubkeys,
                        repostedAtForEvent = state.repostedAtByEvent,
                        repostEventForEvent = state.repostEventByEvent,
                        pendingReposts = state.pendingReposts,
                        isLoading = pageTab in PAGED_TABS && state.isLoading,
                        isLoadingMore = pageTab in PAGED_TABS && state.isLoadingMore,
                        noOlderNotesFound = pageTab in PAGED_TABS && state.olderNotesExhausted,
                        // The tab row already shows the count; a second header line repeated it.
                        notesHeaderText = null,
                        emptyTitle = emptyTitleFor(pageTab),
                        showBottomSpacer = true,
                        torDataSourceFactory = viewModel.mediaCacheDataSourceFactory,
                        enableEventClick = true,
                        onEventClick = onEventClickStable,
                        onLike = stableLike,
                        reactionEmojis = reactionEmojis,
                        onAddReactionEmoji = onAddReactionEmoji,
                        onRemoveReactionEmoji = onRemoveReactionEmoji,
                        onRepost = stableRepost,
                        onQuote = onQuoteStable,
                        onShare = stableShare,
                        onReply = onReplyStable,
                        onProfileClick = stableProfileClick,
                        onEventReferenceClick = onEventReferenceClickStable,
                        currentUserPubkey = viewModel.currentUserPubkey(),
                        onDelete = onDeleteStable,
                        onMute = onMuteStable,
                        isPinnedForEvent = isPinnedForEventStable,
                        onPin = onPinStable,
                        getUrlMetadata = getUrlMetadata,
                        getEventJson = getEventJson
                    )
                }

                ProfileTab.FOLLOWS -> {
                    if (state.followedPubkeys.isEmpty()) {
                        item {
                            EmptyState(
                                title = stringResource(R.string.profile_no_follows),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                        }
                    } else {
                        items(
                            state.followedPubkeys,
                            key = { it },
                            contentType = { "follow_row" }
                        ) { followedPubkey ->
                            val followedProfile = state.followedProfiles[followedPubkey]
                            PersonRow(
                                pubkey = followedPubkey,
                                profile = followedProfile,
                                userRepository = viewModel.userRepositoryPublic,
                                onClick = { navController.navigate(Screen.Profile.forPubkey(followedPubkey)) }
                            )
                        }
                    }
                }

                ProfileTab.RELAYS -> {
                    if (isOwnProfile) {
                        // Own profile: show connectivity stats + full relay list
                        item {
                            RelayStatsCard(
                                stats = state.relayStats,
                                onOpenRelayConfig = { navController.navigate(Screen.RelayConfig.route) }
                            )
                        }

                        val outboxRelays = state.relays.filter { it.isWriteEnabled }
                        val inboxRelays = state.relays.filter { it.isReadEnabled && !it.isWriteEnabled }
                        val dmRelays = state.relays.filter { it.isDmEnabled }

                        if (state.relays.isEmpty()) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.no_relays_configured),
                                    modifier = Modifier.fillMaxWidth().height(180.dp)
                                )
                            }
                        } else {
                            if (outboxRelays.isNotEmpty()) {
                                item { RelaySectionHeader(stringResource(R.string.relay_subscriptions_outbox)) }
                                items(
                                    outboxRelays,
                                    key = { "out-${it.id}" },
                                    contentType = { "relay_summary_row" }
                                ) { relay ->
                                    RelaySummaryRow(relay = relay)
                                }
                            }
                            if (inboxRelays.isNotEmpty()) {
                                item { RelaySectionHeader(stringResource(R.string.relay_subscriptions_inbox)) }
                                items(
                                    inboxRelays,
                                    key = { "in-${it.id}" },
                                    contentType = { "relay_summary_row" }
                                ) { relay ->
                                    RelaySummaryRow(relay = relay)
                                }
                            }
                            if (dmRelays.isNotEmpty()) {
                                item { RelaySectionHeader(stringResource(R.string.relay_dm)) }
                                items(
                                    dmRelays,
                                    key = { "dm-${it.id}" },
                                    contentType = { "relay_summary_row" }
                                ) { relay ->
                                    RelaySummaryRow(relay = relay)
                                }
                            }
                            }
                    } else {
                        // Other user: show their published relay lists (NIP-65 + NIP-17)
                        val allTargetRelays = (
                            state.targetOutboxRelays +
                            state.targetInboxRelays +
                            state.targetDmRelays
                        ).toSet()

                        if (allTargetRelays.isEmpty()) {
                            item {
                                EmptyState(
                                    title = stringResource(R.string.profile_no_relays_published),
                                    modifier = Modifier.fillMaxWidth().height(180.dp)
                                )
                            }
                        } else {
                            if (state.targetOutboxRelays.isNotEmpty()) {
                                item { RelaySectionHeader(stringResource(R.string.relay_subscriptions_outbox)) }
                                items(
                                    state.targetOutboxRelays,
                                    key = { "tout-$it" },
                                    contentType = { "relay_url_row" }
                                ) { url ->
                                    RelayUrlRow(url = url)
                                }
                            }
                            if (state.targetInboxRelays.isNotEmpty()) {
                                item { RelaySectionHeader(stringResource(R.string.relay_subscriptions_inbox)) }
                                items(
                                    state.targetInboxRelays,
                                    key = { "tin-$it" },
                                    contentType = { "relay_url_row" }
                                ) { url ->
                                    RelayUrlRow(url = url)
                                }
                            }
                            if (state.targetDmRelays.isNotEmpty()) {
                                item { RelaySectionHeader(stringResource(R.string.relay_dm)) }
                                items(
                                    state.targetDmRelays,
                                    key = { "tdm-$it" },
                                    contentType = { "relay_url_row" }
                                ) { url ->
                                    RelayUrlRow(url = url)
                                }
                            }
                            }
                    }
                }

                ProfileTab.MUTES -> {
                    // Mutes are only meaningful for the logged-in user's own profile
                    if (state.mutedPubkeys.isEmpty()) {
                        item {
                            EmptyState(
                                title = stringResource(R.string.profile_no_mutes),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                        }
                    } else {
                        items(
                            state.mutedPubkeys,
                            key = { it },
                            contentType = { "muted_pubkey_row" }
                        ) { mutedPubkey ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                UserAvatar(userProfile = null, pubkey = mutedPubkey, size = 36.dp)
                                Text(
                                    text = Bech32Encoder.encodeNpub(mutedPubkey).truncatePublicKey(12, 8),
                                    style = MonoStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { navController.navigate(Screen.Profile.forPubkey(mutedPubkey)) }
                                )
                                TextButton(onClick = { viewModel.unmuteUser(mutedPubkey) }) {
                                    Text(stringResource(R.string.unmute_user))
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // Header: hero + pinned tab bar, translated up as the page scrolls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, headerOffsetPx.roundToInt()) }
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { heroHeightPx = it.height }
                    .scrollable(headerScrollState, Orientation.Vertical)
            ) {
                ProfileHero(
                    profile = profile,
                    pubkey = viewModel.pubkey,
                    canSign = canSign,
                    isOwnProfile = isOwnProfile,
                    isFollowing = state.isFollowing,
                    isFollowActionInFlight = state.isFollowActionInFlight,
                    followersCount = state.followersCount,
                    followingCount = state.followedPubkeys.size,
                    onFollowingClick = {
                        scope.launch { pagerState.animateScrollToPage(tabs.indexOf(ProfileTab.FOLLOWS)) }
                    },
                    onToggleFollow = { viewModel.toggleFollow() },
                    onEditProfile = { navController.navigate(Screen.EditProfile.route) },
                    onMuteUser = { viewModel.muteUser(viewModel.pubkey) },
                    npub = npub,
                    onCopyHex = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, viewModel.pubkey)))
                        }
                        Toast.makeText(context, context.getString(R.string.copy_hex_toast), Toast.LENGTH_SHORT).show()
                    },
                    onCopyNpub = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, npub)))
                        }
                        Toast.makeText(context, context.getString(R.string.copy_npub_toast), Toast.LENGTH_SHORT).show()
                    },
                    onWebsiteClick = { url -> pendingExternalUrl = normalizeExternalUrl(url) },
                    onBioUrlClick = { url -> pendingExternalUrl = normalizeExternalUrl(url) },
                    userRepository = viewModel.userRepositoryPublic
                )
                state.errorMessage?.let { message ->
                    ErrorBanner(
                        message = message.resolve(context),
                        onDismiss = { viewModel.clearError() }
                    )
                }
            }
            val relaysCount = if (isOwnProfile) {
                state.relayStats.total
            } else {
                (state.targetOutboxRelays + state.targetInboxRelays + state.targetDmRelays).toSet().size
            }
            ProfileTabBar(
                tabs = tabs,
                selectedIndex = pagerState.currentPage,
                countFor = { tab ->
                    when (tab) {
                        ProfileTab.NOTES -> state.totalNotesCount
                        ProfileTab.REPLIES -> replyNotes.size
                        ProfileTab.FOLLOWS -> state.followedPubkeys.size
                        ProfileTab.RELAYS -> relaysCount
                        ProfileTab.MUTES -> state.mutedPubkeys.size
                        ProfileTab.PINNED -> state.pinnedNotes.size
                        ProfileTab.PICTURES -> state.pictures.size
                    }
                },
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                modifier = Modifier.onSizeChanged { tabBarHeightPx = it.height }
            )
        }

        ProfileTopOverlay(
            title = profile?.getUserDisplayName() ?: viewModel.pubkey.truncatePublicKey(),
            collapseFraction = collapseFraction,
            onBack = { navController.popBackStack() }
        )

        QuickActionBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f),
            onGoTop = {
                scope.launch {
                    listState.scrollToItem(0)
                    headerOffsetPx = 0f
                }
            },
            onCompose = { navController.navigate(Screen.Composer.new()) },
            onRelays = { navController.navigate(Screen.RelayConfig.route) },
            onSettings = {
                navController.navigate(Screen.Settings.route) {
                    launchSingleTop = true
                }
            }
        )
    }
}

@Composable
internal fun ProfileHero(
    profile: UserProfile?,
    pubkey: String,
    canSign: Boolean,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    isFollowActionInFlight: Boolean,
    followersCount: Int?,
    followingCount: Int,
    onFollowingClick: () -> Unit,
    onToggleFollow: () -> Unit,
    onEditProfile: () -> Unit,
    onMuteUser: () -> Unit,
    npub: String,
    onCopyHex: () -> Unit,
    onCopyNpub: () -> Unit,
    onWebsiteClick: (String) -> Unit,
    onBioUrlClick: (String) -> Unit,
    // BUD-03 client-retrieval fallback — threaded into both the banner's and the avatar's
    // rememberRetryingAsyncImagePainter/UserAvatar calls below, giving this profile's own
    // banner/avatar Blossom-fallback candidacy now that they share the unified engine.
    userRepository: UserRepository
) {
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(ProfileBannerHeight + ProfileAvatarSize / 2)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProfileBannerHeight)
            ) {
                if (!profile?.banner.isNullOrBlank()) {
                    val bannerUrl = profile.banner
                    // Retries on Tor-circuit-build failure via the same unified engine every other
                    // image entry point uses — a plain AsyncImage(model = url) here previously
                    // got stuck on a blank banner until an unrelated recomposition created a fresh
                    // request.
                    val windowInfo = LocalWindowInfo.current
                    val bannerHeightPx = with(LocalDensity.current) { ProfileBannerHeight.roundToPx() }
                    val gatedState = rememberRetryingAsyncImagePainter(
                        url = bannerUrl,
                        targetWidthPx = windowInfo.containerSize.width.coerceAtLeast(1),
                        targetHeightPx = bannerHeightPx.coerceAtLeast(1),
                        authorPubkey = pubkey,
                        userRepository = userRepository
                    )
                    Image(
                        painter = gatedState.painter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // No banner: a faint corona glow instead of a flat colour block.
                    val corona = UmbraTheme.colors.corona
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                drawRect(
                                    Brush.radialGradient(
                                        0f to corona.copy(alpha = 0.22f),
                                        1f to Color.Transparent,
                                        center = Offset(size.width * 0.8f, size.height * 0.1f),
                                        radius = size.width * 0.8f
                                    )
                                )
                            }
                    )
                }
                // Fade the banner into the page so the header reads as one surface.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to background.copy(alpha = 0.15f),
                                1f to background
                            )
                        )
                )
            }

            UserAvatar(
                userProfile = profile,
                pubkey = pubkey,
                size = ProfileAvatarSize,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp)
                    .border(4.dp, background, CircleShape),
                authorPubkey = pubkey,
                userRepository = userRepository
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isOwnProfile) {
                    val zapLauncher = LocalZapLauncher.current
                    // Shown for every other profile: the sheet reveals what the recipient can
                    // actually be paid with, so that can't be judged from lud16/lud06 alone.
                    if (zapLauncher != null) {
                        IconButton(
                            onClick = { zapLauncher(ZapTarget(recipientPubkey = pubkey, profile = profile)) },
                            modifier = Modifier
                                .size(40.dp)
                                .border(1.dp, UmbraTheme.colors.zap.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Bolt,
                                contentDescription = stringResource(R.string.zap_cd),
                                tint = UmbraTheme.colors.zap,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    val peopleLists = LocalPeopleLists.current
                    if (canSign && peopleLists != null) {
                        IconButton(
                            onClick = { peopleLists(pubkey) },
                            modifier = Modifier
                                .size(40.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.profile_add_to_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (canSign) {
                        IconButton(
                            onClick = onMuteUser,
                            modifier = Modifier
                                .size(40.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
                                contentDescription = stringResource(R.string.mute_user),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    val followColors = if (isFollowing) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = UmbraTheme.colors.corona)
                    }
                    Button(
                        onClick = onToggleFollow,
                        enabled = canSign && !isFollowActionInFlight,
                        colors = followColors,
                        border = if (isFollowing) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                        modifier = Modifier.height(40.dp)
                    ) {
                        if (isFollowActionInFlight) {
                            LoadingSpinner(size = 16.dp)
                        } else {
                            Text(
                                text = if (isFollowing) {
                                    stringResource(R.string.profile_unfollow)
                                } else {
                                    stringResource(R.string.profile_follow)
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                } else if (canSign) {
                    OutlinedButton(
                        onClick = onEditProfile,
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text(stringResource(R.string.edit_profile_button), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CustomEmojiText(
                        text = profile?.getUserDisplayName() ?: pubkey.truncatePublicKey(),
                        customEmojis = profile?.customEmojis.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (profile?.nip05VerificationState == Nip05VerificationState.Verified && !profile.nip05.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = stringResource(R.string.nip05_verified_cd),
                            tint = UmbraTheme.colors.secure,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                val nip05 = profile?.nip05?.trim().orEmpty()
                if (nip05.isNotBlank() && profile?.nip05VerificationState == Nip05VerificationState.Verified) {
                    Text(
                        text = nip05.removePrefix("_@"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!profile?.about.isNullOrBlank()) {
                ProfileBio(
                    profile = profile,
                    modifier = Modifier.fillMaxWidth(),
                    onUrlClick = onBioUrlClick
                )
            }

            // Who they follow / who follows them — one line, tapping "following" opens that tab.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileStat(
                    count = followingCount,
                    label = stringResource(R.string.profile_stat_following),
                    onClick = onFollowingClick
                )
                // Best-effort NIP-45 COUNT across relays that advertise support; hidden until at
                // least one has actually answered, so we never flash a false "0".
                followersCount?.let { count ->
                    ProfileStat(count = count, label = stringResource(R.string.profile_stat_followers))
                }
            }

            val lightning = profile?.lud16 ?: profile?.lud06
            val website = profile?.website?.takeIf { it.isNotBlank() }
            if (website != null || !lightning.isNullOrBlank()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (website != null) {
                        IdentityTagRow(
                            icon = Icons.Outlined.Link,
                            value = website.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = { onWebsiteClick(website) }
                        )
                    }
                    if (!lightning.isNullOrBlank()) {
                        IdentityTagRow(icon = Icons.Default.Bolt, value = lightning, tint = UmbraTheme.colors.zap)
                    }
                }
            }

            if (!isOwnProfile && !canSign) {
                Text(
                    text = stringResource(R.string.profile_follow_anonymous_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyChip(
                    label = stringResource(R.string.npub_label),
                    value = npub.truncatePublicKey(9, 5),
                    onCopy = onCopyNpub,
                    modifier = Modifier.weight(1f)
                )
                KeyChip(
                    label = stringResource(R.string.hex_label),
                    value = pubkey.truncatePublicKey(6, 4),
                    onCopy = onCopyHex
                )
            }
        }
    }
}

private val ProfileBannerHeight = 150.dp

@Composable
private fun ProfileStat(count: Int, label: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = if (onClick != null) Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick) else Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatCount(count),
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private val ProfileAvatarSize = 88.dp

/** A copyable identifier: tiny label, the value in mono, and a copy affordance. */
@Composable
private fun KeyChip(
    label: String,
    value: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onCopy)
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.copy_to_clipboard_cd, label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Swipeable-pager tab bar: a scrollable row with a sliding corona indicator under the current
 * tab; tapping animates the pager, swiping the pager moves the indicator.
 */
@Composable
internal fun ProfileTabBar(
    tabs: List<ProfileTab>,
    selectedIndex: Int,
    countFor: (ProfileTab) -> Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
                width = Dp.Unspecified,
                color = UmbraTheme.colors.corona
            )
        },
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val count = countFor(tab)
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(tab.labelRes()), style = MaterialTheme.typography.titleSmall)
                        if (count > 0) {
                            Text(
                                text = formatCount(count),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }
}

private fun ProfileTab.labelRes(): Int = when (this) {
    ProfileTab.NOTES -> R.string.profile_tab_notes
    ProfileTab.REPLIES -> R.string.profile_tab_replies
    ProfileTab.FOLLOWS -> R.string.profile_tab_follows
    ProfileTab.RELAYS -> R.string.profile_tab_relays
    ProfileTab.MUTES -> R.string.profile_tab_mutes
    ProfileTab.PINNED -> R.string.profile_tab_pins
    ProfileTab.PICTURES -> R.string.profile_tab_pictures
}

/** Tabs backed by the paged note history (the others are complete in memory). */
private val PAGED_TABS = setOf(ProfileTab.NOTES, ProfileTab.REPLIES)

/**
 * Back button floating over the banner (scrim circle so it reads on any image), which becomes a
 * compact title bar with the person's name once the header has scrolled away.
 */
@Composable
internal fun ProfileTopOverlay(
    title: String,
    collapseFraction: Float,
    onBack: () -> Unit
) {
    val barAlpha = ((collapseFraction - 0.75f) / 0.25f).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = barAlpha))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.45f * (1f - barAlpha)), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = Color.White.copy(alpha = 1f - barAlpha).compositeOver(MaterialTheme.colorScheme.onSurface.copy(alpha = barAlpha))
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = barAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RelayStatsCard(
    stats: ProfileRelayStats,
    onOpenRelayConfig: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.profile_relays_stats_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(stringResource(R.string.profile_relays_connected_stat, stats.connected, stats.total))
                StatPill(stringResource(R.string.profile_relays_dm_stat, stats.dmEnabled))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(stringResource(R.string.profile_relays_outbox_stat, stats.outboxEnabled))
                StatPill(stringResource(R.string.profile_relays_inbox_stat, stats.inboxEnabled))
                StatPill(stringResource(R.string.profile_relays_onion_stat, stats.onion))
            }

            Text(
                text = stringResource(R.string.profile_relays_uptime_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(onClick = onOpenRelayConfig, contentPadding = PaddingValues(0.dp)) {
                Text(text = stringResource(R.string.configure_relays))
            }
        }
    }
}

@Composable
private fun RelaySummaryRow(relay: Relay) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = relay.relayInfo?.name?.takeIf { it.isNotBlank() } ?: relay.url,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = relay.url,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (relay.isWriteEnabled) StatPill(stringResource(R.string.relay_subscriptions_outbox))
            if (relay.isReadEnabled) StatPill(stringResource(R.string.relay_subscriptions_inbox))
            if (relay.isDmEnabled) StatPill(stringResource(R.string.relay_dm))
            if (relay.isOnion) StatPill(stringResource(R.string.relay_onion), highlight = true)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RelaySectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)
    )
}

@Composable
private fun RelayUrlRow(url: String) {
    Text(
        text = url,
        style = MonoStyle,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun StatPill(text: String, highlight: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlight) UmbraTheme.colors.secure else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
private fun IdentityTagRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    tint: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HashtagAwareBio(
    modifier: Modifier = Modifier,
    text: String,
    onUrlClick: (String) -> Unit,
    // NIP-30: the profile event's own emoji tags (shortcode → URL). A `:shortcode:` here that
    // matches one renders as an inline image at line height; unmatched ones stay literal text.
    customEmojis: Map<String, String> = emptyMap()
) {
    val emojiInlineContent = rememberEmojisInlineContent(customEmojis, text)
    val matches = (URL_REGEX.findAll(text) + HASHTAG_REGEX.findAll(text) + PROFILE_EMOJI_SPLIT_REGEX.findAll(text))
        .sortedBy { it.range.first }

    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in matches) {
            if (match.range.first < cursor) continue
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }

            val token = match.value
            val emojiShortcode = PROFILE_EMOJI_SPLIT_REGEX.matchEntire(token)
                ?.groupValues?.getOrNull(1)
            if (emojiShortcode != null && customEmojis.containsKey(emojiShortcode)) {
                appendInlineContent(customEmojiInlineContentId(emojiShortcode), ":$emojiShortcode:")
                cursor = match.range.last + 1
                continue
            }
            if (URL_REGEX.matches(token)) {
                val normalized = normalizeExternalUrl(token)
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "URL",
                        linkInteractionListener = { onUrlClick(normalized) }
                    )
                ) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append(token)
                    }
                }
            } else {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(token)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    // Long bios collapse to a few lines so the notes stay reachable; one tap expands.
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            inlineContent = emojiInlineContent,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow }
        )
        if (overflows || expanded) {
            ShowMoreLessToggle(isExpanded = expanded, onToggle = { expanded = !expanded })
        }
    }
}

private val PROFILE_EMOJI_SPLIT_REGEX = Regex(":([A-Za-z0-9_+-]+):")

/** Inline-content map for [emotes] actually present in [text] — see HashtagAwareBio. */
@Composable
private fun rememberEmojisInlineContent(
    customEmojis: Map<String, String>,
    text: String
): Map<String, InlineTextContent> {
    val context = LocalContext.current
    val used = remember(customEmojis, text) {
        customEmojis
            .filter { (shortcode, url) ->
                (url.startsWith("https://") || url.startsWith("http://")) && text.contains(":$shortcode:")
            }
            .mapValues { (shortcode, url) -> CustomEmoji(shortcode = shortcode, url = url) }
    }
    return rememberCustomEmojiInlineContent(used) { shortcode ->
        context.getString(R.string.custom_emoji_content_description, shortcode)
    }
}

@Composable
internal fun ProfileBio(
    profile: UserProfile?,
    modifier: Modifier = Modifier,
    onUrlClick: (String) -> Unit
) {
    // about is nullable in UserProfile but guarded by the caller's isNullOrBlank check, so text
    // is non-null here — pass "unsafe" call through a defaulted empty string for the compiler.
    HashtagAwareBio(
        text = profile?.about.orEmpty(),
        modifier = modifier,
        onUrlClick = onUrlClick,
        customEmojis = profile?.customEmojis.orEmpty()
    )
}


