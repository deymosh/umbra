package com.umbra.app.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umbra.app.ui.auth.LoginViewModel
import androidx.navigation.NavController
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.umbra.app.R
import com.umbra.app.ui.auth.rememberPrivacyLogout
import com.umbra.app.ui.notifications.UnreadNotificationsViewModel
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.ui.Screen
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.EmptyState
import androidx.compose.foundation.border
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.NotesTimelineContainer
import com.umbra.app.ui.components.buildThreadDepthByEventId
import com.umbra.app.ui.components.notesFeedSection
import com.umbra.app.ui.components.QuickActionBottomBar
import com.umbra.app.ui.components.shareEventUrl
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.awaitViewportPrefetchQuietWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow


private data class FeedSearchPayload(
    val query: String,
    val events: List<Event>,
    val profiles: ImmutableMapSnapshot<String, UserProfile>
)

internal fun buildSearchableFeedEvents(
    feedEvents: List<Event>,
    relaySearchResults: List<Event>,
    query: String
): List<Event> {
    return if (query.isBlank()) {
        feedEvents
    } else {
        (feedEvents + relaySearchResults).distinctBy { it.id }
    }
}

internal fun filterFeedEventsForQuery(
    events: List<Event>,
    normalizedQuery: String,
    profiles: ImmutableMapSnapshot<String, UserProfile>
): List<Event> {
    val topLevelEvents = events.filter { it.isTopLevelFeedNote() }
    if (normalizedQuery.isBlank()) return topLevelEvents

    return topLevelEvents.filter { event ->
        val profile = profiles.profileFor(event.pubkey)
        event.content.contains(normalizedQuery, ignoreCase = true) ||
            event.pubkey.contains(normalizedQuery, ignoreCase = true) ||
            (profile?.displayName?.contains(normalizedQuery, ignoreCase = true) == true) ||
            (profile?.name?.contains(normalizedQuery, ignoreCase = true) == true) ||
            (profile?.nip05?.contains(normalizedQuery, ignoreCase = true) == true)
    }
}

private fun ImmutableMapSnapshot<String, UserProfile>.profileFor(pubkey: String?): UserProfile? {
    if (pubkey.isNullOrBlank()) return null
    return this[pubkey] ?: this[pubkey.lowercase()]
}

/**
 * Main feed screen
 * Displays Nostr events from configured relays
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    navController: NavController,
    viewModel: FeedViewModel,
    loginViewModel: LoginViewModel
) {
    val feedState by viewModel.feedState.collectAsStateWithLifecycle()
    val reactionEmojis by viewModel.reactionEmojis.collectAsStateWithLifecycle()
    val relaySearchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Merges in quoted events resolved by viewport prefetch (see FeedViewModel.
    // prefetchViewportImages) that aren't part of the feed's own visible list — otherwise a
    // quote of a note outside the feed never resolves to an inline QuotedNoteCard even after
    // it's been fetched.
    val eventsById = remember(feedState.events, feedState.resolvedQuotedEvents) {
        feedState.events.associateBy { it.id } + feedState.resolvedQuotedEvents.toMap()
    }
    val currentNavController by rememberUpdatedState(navController)
    val onLike = remember(viewModel) {
        { event: Event, content: String, emoji: CustomEmoji? -> viewModel.likeEvent(event, content, emoji) }
    }
    val onAddReactionEmoji = remember(viewModel) { { emoji: ReactionEmoji -> viewModel.addReactionEmoji(emoji) } }
    val onRemoveReactionEmoji = remember(viewModel) { { key: String -> viewModel.removeReactionEmoji(key) } }
    val onRepost = remember(viewModel) { { event: Event -> viewModel.repostEvent(event) } }
    val onShare = remember(viewModel) { { event: Event -> viewModel.shareEvent(event) } }
    val onDelete = remember(viewModel) { { event: Event -> viewModel.deleteEvent(event) } }
    val onMute = remember(viewModel) { { pubkey: String -> viewModel.muteUser(pubkey) } }
    val getUrlMetadata = remember(viewModel) { { url: String -> viewModel.getUrlMetadata(url) } }
    val getEventJson = remember(viewModel) { { event: Event -> viewModel.getEventJson(event) } }
    // Remembered so a fresh lambda identity here doesn't defeat EventCard's recomposition-skip
    // on every feedState emission (engagement counts, etc. that don't touch profiles) — mirrors
    // onLike/onRepost/etc. above.
    val profileForPubkey = remember(feedState.profiles) { { pubkey: String -> feedState.profiles.profileFor(pubkey) } }
    // rememberSaveable: navigating to a Thread/Profile from a search result disposes this
    // composition (the Feed backstack entry stays alive but its composable is torn down), so
    // plain remember reset the query/visibility to blank on the way back — the search "state"
    // the user had appeared to vanish even though the backstack entry itself was preserved.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val logout = rememberPrivacyLogout(navController, loginViewModel, onFinished = { scope.launch { drawerState.close() } })
    val panicWipeEnabled by loginViewModel.panicWipeEnabled.collectAsStateWithLifecycle()
    // Permanently stable (remember with no keys) — `feedState`/`currentNavController` are
    // delegated State reads, so referencing them *inside* these lambda bodies (rather than
    // capturing a snapshot via a remember key) always sees the latest value without needing a
    // fresh lambda instance. Previously these were built as raw inline lambdas at the
    // notesFeedSection() call site below, recreated on every feedState emission (a new note, a
    // reaction count changing, a profile updating, anywhere in the feed) — same recomposition-
    // skip-defeating issue already fixed for onLike/onRepost/getQuotedEvent/etc., just not yet
    // applied to these.
    val isLikedForEvent = remember { { eventId: String -> feedState.interactions[eventId]?.liked ?: false } }
    val isRepostedForEvent = remember { { eventId: String -> feedState.interactions[eventId]?.shared ?: false } }
    val isPinnedForEvent = remember { { eventId: String -> feedState.pinnedEventIds.contains(eventId) } }
    val onEventClickStable = remember { { event: Event -> currentNavController.navigate(Screen.Thread.forEvent(event.id)) } }
    val onProfileClickStable = remember { { pubkey: String -> currentNavController.navigate(Screen.Profile.forPubkey(pubkey)) } }
    val onEventReferenceClickStable = remember { { eventId: String -> currentNavController.navigate(Screen.Thread.forEvent(eventId)) } }
    val onReplyStable = remember {
        { event: Event ->
            currentNavController.navigate(Screen.Composer.reply(event.id))
            viewModel.replyToEvent(event)
        }
    }
    val onQuoteStable = remember {
        { event: Event -> currentNavController.navigate(Screen.Composer.quote(event.id)) }
    }
    val onPinStable = remember(viewModel) { { event: Event -> viewModel.togglePin(event) } }
    val searchableEvents = remember(feedState.events, relaySearchResults, searchQuery) {
        buildSearchableFeedEvents(
            feedEvents = feedState.events,
            relaySearchResults = relaySearchResults,
            query = searchQuery
        )
    }
    val searchPayload = FeedSearchPayload(
        query = searchQuery.trim().lowercase(),
        events = searchableEvents,
        profiles = feedState.profiles
    )
    val filteredEvents = remember(searchPayload.query, searchPayload.events, searchPayload.profiles) {
        filterFeedEventsForQuery(
            events = searchPayload.events,
            normalizedQuery = searchPayload.query,
            profiles = searchPayload.profiles
        )
    }
    val threadDepthByEventId = remember(filteredEvents, eventsById) {
        buildThreadDepthByEventId(filteredEvents, eventsById)
    }
    val latestFilteredEvents by rememberUpdatedState(filteredEvents)
    val shouldLoadMore by remember(filteredEvents, listState, feedState.isLoading, feedState.isLoadingMore) {
        derivedStateOf {
            if (feedState.isLoading || feedState.isLoadingMore) return@derivedStateOf false
            if (filteredEvents.size < 20) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= (filteredEvents.lastIndex - 4)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTorStatus()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadOlderFeed()
    }
    LaunchedEffect(viewModel) {
        viewModel.shareUrlEffect.collect { url -> shareEventUrl(context, url) }
    }
    LaunchedEffect(searchQuery) {
        delay(250)
        viewModel.searchNotes(searchQuery)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val firstVisibleIndex = visibleItems.firstOrNull()?.index ?: 0
            val visibleCount = visibleItems.size
            firstVisibleIndex to visibleCount
        }
            .conflate()
            .distinctUntilChanged()
            .collectLatest { (firstVisibleIndex, visibleCount) ->
                if (!awaitViewportPrefetchQuietWindow(visibleCount)) return@collectLatest
                viewModel.prefetchViewportImages(
                    events = latestFilteredEvents,
                    firstVisibleIndex = firstVisibleIndex,
                    visibleCount = visibleCount
                )
            }
    }
    val currentPubkey = feedState.currentUserPubkey
    val unreadNotificationsViewModel: UnreadNotificationsViewModel = hiltViewModel()
    val hasUnreadNotifications by unreadNotificationsViewModel.hasUnread.collectAsStateWithLifecycle()
    val currentProfile = feedState.currentUserProfile ?: feedState.profiles.profileFor(currentPubkey)

    // Amber sign round trips go through the single app-wide launcher (AppSessionEffects) now —
    // no per-screen launcher needed here.


    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FeedDrawerContent(
                currentProfile = currentProfile,
                currentPubkey = currentPubkey,
                userRepository = viewModel.userRepositoryPublic,
                onProfile = {
                    val pubkey = feedState.currentUserPubkey
                    if (!pubkey.isNullOrBlank()) {
                        navController.navigate(Screen.Profile.forPubkey(pubkey))
                    }
                    scope.launch { drawerState.close() }
                },
                onRelays = {
                    navController.navigate(Screen.RelayConfig.route)
                    scope.launch { drawerState.close() }
                },
                onBookmarks = if (currentPubkey.isNullOrBlank() || !viewModel.canSignEvents()) null else {
                    {
                        navController.navigate(Screen.Bookmarks.route)
                        scope.launch { drawerState.close() }
                    }
                },
                onReadLater = {
                    navController.navigate(Screen.ReadLater.route)
                    scope.launch { drawerState.close() }
                },
                onFilters = {
                    navController.navigate(Screen.FeedConfig.route)
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    navController.navigate(Screen.Settings.route) {
                        launchSingleTop = true
                    }
                    scope.launch { drawerState.close() }
                },
                onLogout = logout
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            FeedTopBar(
                currentProfile = currentProfile,
                currentPubkey = currentPubkey,
                userRepository = viewModel.userRepositoryPublic,
                searchVisible = searchVisible,
                relayCount = feedState.relayCount,
                isConnected = feedState.isConnected,
                isTorConnected = feedState.isTorConnected,
                isTorStarting = feedState.torStatus == "STARTING_TOR",
                onAvatarClick = { scope.launch { drawerState.open() } },
                onStatusClick = { navController.navigate(Screen.RelayConfig.route) },
                onNotifications = if (currentPubkey.isNullOrBlank() || !viewModel.canSignEvents()) null else {
                    { navController.navigate(Screen.Notifications.route) }
                },
                hasUnreadNotifications = hasUnreadNotifications,
                onWordmarkLongPress = if (panicWipeEnabled) logout else null,
                onToggleSearch = {
                    val nowVisible = !searchVisible
                    searchVisible = nowVisible
                    if (!nowVisible) {
                        searchQuery = ""
                        viewModel.closeSearch()
                    }
                }
            )

            if (searchVisible) {
                FeedSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClear = { searchQuery = "" },
                    autoFocus = true
                )
                if (searchQuery.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.search_nip50_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (feedState.showFeedErrorBanner) {
                feedState.errorMessage?.let { message ->
                    val bannerRelayId = feedState.errorRelayId
                    val onBannerClick: (() -> Unit)? = if (bannerRelayId != null) {
                        { currentNavController.navigate(Screen.RelayDetails.forRelay(bannerRelayId)) }
                    } else {
                        null
                    }
                    ErrorBanner(
                        message = message.resolve(context),
                        onDismiss = { viewModel.clearError() },
                        onClick = onBannerClick
                    )
                }
            }

            if (filteredEvents.isEmpty()) {
                // No dedicated full-screen "Connecting to relays…" blocking state anymore — a
                // cold start with nothing cached yet used to sit on that spinner until relay
                // connections caught up (observed taking as long as ~120 relays connecting) —
                // showing whatever's available immediately and connecting in the background
                // avoids that wait entirely. The still-loading hint
                // is now a subtitle on the same empty state instead of gating what's shown.
                //
                // QuickActionBottomBar stays floating here too — an empty feed (cold start,
                // no-results search) is exactly when the user most needs the compose/relays/
                // settings shortcuts still reachable, not just once notes exist.
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    EmptyState(
                        title = if (searchQuery.isBlank()) {
                            stringResource(R.string.no_events_yet)
                        } else {
                            stringResource(R.string.search_no_results, searchQuery)
                        },
                        message = if (searchQuery.isBlank() && feedState.isLoading) {
                            stringResource(R.string.connecting_relays)
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    QuickActionBottomBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .zIndex(2f),
                        onGoTop = { scope.launch { listState.scrollToTopImmediate() } },
                        onCompose = { currentNavController.navigate(Screen.Composer.new()) },
                        onRelays = { navController.navigate(Screen.RelayConfig.route) },
                        onFilters = { navController.navigate(Screen.FeedConfig.route) },
                        onSettings = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            } else {
                val showScrollToTop by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 5 }
                }
                NotesTimelineContainer(
                    listState = listState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                    listHorizontalPadding = 0.dp,
                    listVerticalPadding = 0.dp,
                    verticalArrangement = Arrangement.Top,
                    topOverlay = {
                        ScrollToTopPill(
                            visible = showScrollToTop,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                            onClick = { scope.launch { listState.scrollToTopImmediate() } }
                        )
                    },
                    bottomOverlay = {
                        // NotesTimelineContainer owns BottomCenter alignment for this slot (so
                        // it can measure the bar's real height and reserve matching list
                        // padding) — don't re-align here.
                        QuickActionBottomBar(
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .zIndex(2f),
                            onGoTop = { scope.launch { listState.scrollToTopImmediate() } },
                            onCompose = { currentNavController.navigate(Screen.Composer.new()) },
                            onRelays = { navController.navigate(Screen.RelayConfig.route) },
                            onFilters = { navController.navigate(Screen.FeedConfig.route) },
                            onSettings = {
                                navController.navigate(Screen.Settings.route) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                ) {
                        notesFeedSection(
                            notes = filteredEvents,
                            eventsById = eventsById,
                            threadDepthByEventId = threadDepthByEventId,
                            profileForPubkey = profileForPubkey,
                            userRepository = viewModel.userRepositoryPublic,
                            replyCounts = feedState.replyCounts,
                            reactionCounts = feedState.reactionCounts,
                            repostCounts = feedState.repostCounts,
                            repostedByPubkeyForEvent = feedState.repostedByPubkeys,
                            repostedAtForEvent = feedState.repostedAtByEvent,
                            repostEventForEvent = feedState.repostEventByEvent,
                            pendingReposts = feedState.pendingReposts,
                            isLoading = false,
                            isLoadingMore = feedState.isLoadingMore,
                            noOlderNotesFound = feedState.olderNotesExhausted,
                            notesHeaderText = null,
                            emptyTitle = null,
                            showBottomSpacer = true,
                            torDataSourceFactory = viewModel.mediaCacheDataSourceFactory,
                            isLikedForEvent = isLikedForEvent,
                            isRepostedForEvent = isRepostedForEvent,
                            onEventClick = onEventClickStable,
                            onLike = onLike,
                            reactionEmojis = reactionEmojis,
                            onAddReactionEmoji = onAddReactionEmoji,
                            onRemoveReactionEmoji = onRemoveReactionEmoji,
                            onRepost = onRepost,
                            onQuote = onQuoteStable,
                            onShare = onShare,
                            onReply = onReplyStable,
                            onProfileClick = onProfileClickStable,
                            onEventReferenceClick = onEventReferenceClickStable,
                            currentUserPubkey = feedState.currentUserPubkey,
                            onDelete = onDelete,
                            onMute = onMute,
                            isPinnedForEvent = isPinnedForEvent,
                            onPin = onPinStable,
                            getUrlMetadata = getUrlMetadata,
                            getEventJson = getEventJson
                        )
                }
            }
        }
    }
}

@Composable
private fun ScrollToTopPill(
    modifier: Modifier = Modifier,
    visible: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 8.dp,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.back_to_top),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private suspend fun LazyListState.scrollToTopImmediate() {
    scrollToItem(0)
}

