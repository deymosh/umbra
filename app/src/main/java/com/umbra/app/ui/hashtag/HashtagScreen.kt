package com.umbra.app.ui.hashtag

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.shareEventUrl
import com.umbra.app.ui.feed.EventCard

/**
 * Opens the feed for a hashtag. Provided once at the navigation root so every note card — feed,
 * thread, profile, notifications — gets tappable hashtags without each screen wiring it.
 */
val LocalHashtagNavigator = compositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun HashtagScreen(
    viewModel: HashtagViewModel,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: (Event) -> Unit,
    onQuote: (Event) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.shareUrl.collect { url -> shareEventUrl(context, url) }
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("#") }
                            append(state.tag)
                        }
                    )
                },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.notes.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        title = stringResource(if (state.isLoading) R.string.hashtag_loading else R.string.hashtag_empty, state.tag),
                        message = stringResource(R.string.hashtag_empty_message),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.notes, key = { it.id }, contentType = { "note" }) { note ->
                    EventCard(
                        event = note,
                        userProfile = state.profiles[note.pubkey.lowercase()],
                        userRepository = viewModel.userRepositoryPublic,
                        torDataSourceFactory = viewModel.mediaDataSourceFactory,
                        currentUserPubkey = viewModel.currentUserPubkey(),
                        onEventClick = { onOpenThread(it.id) },
                        onProfileClick = onOpenProfile,
                        onLike = viewModel::like,
                        onRepost = viewModel::repost,
                        onQuote = onQuote,
                        onShare = viewModel::share,
                        onReply = onReply,
                        onEventReferenceClick = onOpenThread,
                        getEventJson = viewModel::eventJson
                    )
                }
            }
        }
    }
}
