package com.umbra.app.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.ui.Screen
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.ErrorBanner
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.buildThreadDepthByEventId
import com.umbra.app.ui.components.shareEventUrl
import com.umbra.app.ui.common.awaitViewportPrefetchQuietWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    navController: NavController,
    viewModel: ThreadViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reactionEmojis by viewModel.reactionEmojis.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val eventsById = remember(state.events) { state.events.associateBy { it.id } }
    val threadDepthByEventId = remember(state.events, eventsById) {
        buildThreadDepthByEventId(state.events, eventsById)
    }
    // Permanently stable closure identity (remember with no keys), reading eventsById/
    // resolvedQuotedEvents/profiles through rememberUpdatedState instead of capturing them
    // directly — a fresh lambda on every thread-graph update or viewport-prefetched quote (see
    // ThreadViewModel.prefetchViewportImages) would defeat EventCard's recomposition-skip on
    // this parameter for every row, not just the one that actually changed. Falls back to
    // resolvedQuotedEvents for a quote of a note outside this thread's own event graph.
    val eventsByIdState = rememberUpdatedState(eventsById)
    val resolvedQuotedEventsState = rememberUpdatedState(state.resolvedQuotedEvents)
    val getQuotedEvent = remember {
        { id: String -> eventsByIdState.value[id] ?: resolvedQuotedEventsState.value[id] }
    }
    val profilesState = rememberUpdatedState(state.profiles)
    // profiles is keyed by lowercase pubkey (see ThreadViewModel.processThreadGraph), but
    // quotedEvent.pubkey is the raw event field as received off the wire — NIP-01 requires
    // lowercase hex but nothing here enforces it on ingestion, so a non-conformant relay/event
    // can carry mixed-case hex and silently miss an already-cached profile. Same lowercase
    // fallback FeedScreen.profileFor() already applies for the equivalent feed-quote lookup.
    val getQuotedEventAuthorProfile = remember {
        { pubkey: String -> profilesState.value[pubkey] ?: profilesState.value[pubkey.lowercase()] }
    }
    val listState = rememberLazyListState()
    val currentNav by rememberUpdatedState(navController)
    val latestThreadEvents by rememberUpdatedState(state.events)
    val onLike = remember(viewModel) {
        { event: Event, content: String, emoji: CustomEmoji? -> viewModel.likeEvent(event, content, emoji) }
    }
    val onAddReactionEmoji = remember(viewModel) { { emoji: ReactionEmoji -> viewModel.addReactionEmoji(emoji) } }
    val onRemoveReactionEmoji = remember(viewModel) { { key: String -> viewModel.removeReactionEmoji(key) } }
    val onRepost = remember(viewModel) { { event: Event -> viewModel.repostEvent(event) } }
    val onShare = remember(viewModel) { { event: Event -> viewModel.shareEvent(event) } }
    val onDelete = remember(viewModel) { { event: Event -> viewModel.deleteEvent(event) } }
    val getUrlMetadata = remember(viewModel) { { url: String -> viewModel.getUrlMetadata(url) } }
    val getEventJson = remember(viewModel) { { event: Event -> viewModel.getEventJson(event) } }
    // Permanently stable — `currentNav` is a delegated State read, so referencing it inside these
    // lambda bodies always sees the latest NavController without needing a fresh lambda instance.
    // Previously built inline at the EventCard call site below, recreated every time this thread's
    // event/count/profile state changed (i.e. constantly while a thread is open), which defeated
    // EventCard's recomposition-skip for every visible row — same class of fix already applied to
    // FeedScreen/NotesFeedSection.
    val onProfileClickStable = remember { { pubkey: String -> currentNav.navigate(Screen.Profile.forPubkey(pubkey)) } }
    val onEventReferenceClickStable = remember { { eventId: String -> currentNav.navigate(Screen.Thread.forEvent(eventId)) } }
    val onReplyStable = remember { { event: Event -> currentNav.navigate(Screen.Composer.reply(event.id)) } }
    val onQuoteStable = remember { { event: Event -> currentNav.navigate(Screen.Composer.quote(event.id)) } }

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
                    events = latestThreadEvents,
                    firstVisibleIndex = firstVisibleIndex,
                    visibleCount = visibleCount
                )
            }
    }

    // requestSignEvent()'s Amber round trip goes through the single app-wide launcher
    // (AppSessionEffects) now — no per-screen launcher needed here.

    LaunchedEffect(viewModel) {
        viewModel.shareUrlEffect.collect { url -> shareEventUrl(context, url) }
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.thread_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onBack) }
            )
        }
    ) { innerPadding ->
        if (state.isLoading && state.events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                LoadingSpinner(size = 36.dp)
            }
            return@Scaffold
        }

        state.errorMessage?.let { message ->
            ErrorBanner(
                message = message.resolve(context),
                onDismiss = { viewModel.clearError() },
                modifier = Modifier.padding(innerPadding)
            )
        }

        if (state.anchor == null && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = stringResource(R.string.note_not_found_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.note_not_found_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@Scaffold
        }

        if (state.events.isEmpty() && !state.isLoading) {
            EmptyState(
                title = stringResource(R.string.thread_not_found),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Top
        ) {
            items(
                items = state.events,
                key = { it.id },
                contentType = { it.kind }
            ) { event ->
                val parentEvent = event.getParentEventId()?.let { eventsById[it] }
                val replyToProfile = parentEvent?.let { state.profiles[it.pubkey] }
                val replyToLabel = replyToProfile?.getUserDisplayName() ?: parentEvent?.pubkey?.take(8)

                EventCard(
                    event = event,
                    enableEventClick = false,
                    initiallyExpanded = true,
                    userProfile = state.profiles[event.pubkey],
                    replyToProfile = replyToProfile,
                    userRepository = viewModel.userRepositoryPublic,
                    replyToLabel = replyToLabel,
                    threadDepth = threadDepthByEventId[event.id] ?: 0,
                    replyCount = state.replyCounts[event.id] ?: 0,
                    reactionCount = state.reactionCounts[event.id] ?: 0,
                    repostCount = state.repostCounts[event.id] ?: 0,
                    torDataSourceFactory = viewModel.mediaDataSourceFactory,
                    onEventClick = {},
                    onProfileClick = onProfileClickStable,
                    onLike = onLike,
                    reactionEmojis = reactionEmojis,
                    onAddReactionEmoji = onAddReactionEmoji,
                    onRemoveReactionEmoji = onRemoveReactionEmoji,
                    onRepost = onRepost,
                    onQuote = onQuoteStable,
                    onShare = onShare,
                    onReply = onReplyStable,
                    onEventReferenceClick = onEventReferenceClickStable,
                    currentUserPubkey = viewModel.currentUserPubkey(),
                    onDelete = onDelete
                    , getUrlMetadata = getUrlMetadata
                    , getEventJson = getEventJson
                    , getQuotedEvent = getQuotedEvent
                    , getQuotedEventAuthorProfile = getQuotedEventAuthorProfile
                )
            }
        }
    }

}

