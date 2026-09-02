package com.umbra.app.ui.feed

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umbra.app.ui.auth.LoginViewModel
import androidx.navigation.NavController
import androidx.compose.material3.ExperimentalMaterial3Api
import com.umbra.app.R
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.ui.Screen
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.KeyValueCopyRow
import com.umbra.app.ui.components.MenuItemRow
import com.umbra.app.ui.components.NotesTimelineContainer
import com.umbra.app.ui.components.NostrTextRenderer
import com.umbra.app.ui.components.PrivacyLogoutProgressDialog
import com.umbra.app.ui.components.buildThreadDepthByEventId
import com.umbra.app.ui.components.notesFeedSection
import com.umbra.app.ui.components.QuickActionBottomBar
import com.umbra.app.ui.components.shareEventUrl
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.UserIdentityBadge
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.awaitViewportPrefetchQuietWindow
import coil3.compose.AsyncImage
import kotlin.math.max
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import androidx.compose.runtime.snapshotFlow
import com.umbra.app.util.logging.UmbraLog

private val feedScreenLogger = UmbraLog.tag("FeedScreen")

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
    var isLoggingOut by remember { mutableStateOf(false) }
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
    val currentProfile = feedState.currentUserProfile ?: feedState.profiles.profileFor(currentPubkey)

    // Amber sign round trips go through the single app-wide launcher (AppSessionEffects) now —
    // no per-screen launcher needed here.

    if (isLoggingOut) {
        PrivacyLogoutProgressDialog()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!currentPubkey.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    navController.navigate(Screen.Profile.forPubkey(currentPubkey))
                                    scope.launch { drawerState.close() }
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(
                                userProfile = currentProfile,
                                pubkey = currentPubkey,
                                size = 40.dp,
                                shape = CircleShape,
                                authorPubkey = currentPubkey,
                                userRepository = viewModel.userRepositoryPublic
                            )

                            UserIdentityBadge(
                                userProfile = currentProfile,
                                pubkey = currentPubkey,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider()

                    MenuItemRow(
                        icon = Icons.Default.AccountCircle,
                        title = stringResource(R.string.menu_profile),
                        subtitle = stringResource(R.string.menu_profile_subtitle),
                        onClick = {
                            val pubkey = feedState.currentUserPubkey
                            if (!pubkey.isNullOrBlank()) {
                                navController.navigate(Screen.Profile.forPubkey(pubkey))
                            }
                            scope.launch { drawerState.close() }
                        }
                    )
                    MenuItemRow(
                        icon = Icons.Default.Hub,
                        title = stringResource(R.string.menu_relays),
                        subtitle = stringResource(R.string.menu_relays_subtitle),
                        onClick = {
                            navController.navigate(Screen.RelayConfig.route)
                            scope.launch { drawerState.close() }
                        }
                    )
                    MenuItemRow(
                        icon = Icons.Default.Tune,
                        title = stringResource(R.string.menu_feed_filters),
                        subtitle = stringResource(R.string.menu_feed_filters_subtitle),
                        onClick = {
                            navController.navigate(Screen.FeedConfig.route)
                            scope.launch { drawerState.close() }
                        }
                    )
                    MenuItemRow(
                        icon = Icons.Default.Settings,
                        title = stringResource(R.string.menu_settings),
                        subtitle = stringResource(R.string.menu_settings_subtitle),
                        onClick = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                            scope.launch { drawerState.close() }
                        }
                    )
                    MenuItemRow(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        title = stringResource(R.string.menu_logout),
                        subtitle = stringResource(R.string.menu_logout_subtitle),
                        danger = true,
                        onClick = {
                            if (isLoggingOut) return@MenuItemRow
                            scope.launch {
                                try {
                                    isLoggingOut = true
                                    loginViewModel.logout()
                                } catch (e: Exception) {
                                    // Logout failing (e.g. a database wipe leaving stale key
                                    // material behind) must not be silently indistinguishable
                                    // from success — still proceed to the login screen below
                                    // since there's no in-app state left to usefully retry from,
                                    // but at least record that it happened.
                                    feedScreenLogger.e(e) { "Logout failed" }
                                }
                                isLoggingOut = false
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(0) { inclusive = true }
                                    launchSingleTop = true
                                }
                                drawerState.close()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = stringResource(R.string.drawer_title_orbot_powered),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
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
internal fun RelayStatusBadge(
    relayCount: Int,
    isConnected: Boolean
) {
    if (relayCount <= 0) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isConnected) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isConnected) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Text(
                text = if (isConnected) "$relayCount" else "--",
                style = MaterialTheme.typography.labelSmall,
                color = if (isConnected) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
        }
    }
}

@Composable
internal fun TorStatusBadge(isTorConnected: Boolean, isTorStarting: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isTorConnected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    text = stringResource(R.string.tor_onion_symbol),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isTorConnected -> MaterialTheme.colorScheme.tertiary
                            // Actively retrying (STARTING_TOR) reads differently from "not
                            // connected and not doing anything about it" — amber vs. red.
                            isTorStarting -> Color(0xFFF9A825)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
            )
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
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.clickable(onClick = onClick)
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
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.back_to_top),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private suspend fun LazyListState.scrollToTopImmediate() {
    scrollToItem(0)
}

