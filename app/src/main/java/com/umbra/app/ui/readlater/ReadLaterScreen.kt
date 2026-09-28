package com.umbra.app.ui.readlater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.feed.EventCard

/** Save/unsave from any note's menu; null outside the navigation root. */
class ReadLaterActions(val isSaved: (String) -> Boolean, val toggle: (Event) -> Unit)

val LocalReadLater = staticCompositionLocalOf<ReadLaterActions?> { null }

@Composable
fun ReadLaterScreen(
    viewModel: ReadLaterViewModel,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val items by viewModel.items.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.read_later_title)) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = stringResource(R.string.read_later_empty),
                    message = stringResource(R.string.read_later_empty_message),
                    modifier = Modifier.fillMaxSize()
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items, key = { it.id }, contentType = { "note" }) { note ->
                EventCard(
                    event = note,
                    userProfile = profiles[note.pubkey.lowercase()],
                    userRepository = viewModel.userRepositoryPublic,
                    torDataSourceFactory = viewModel.mediaDataSourceFactory,
                    onEventClick = { onOpenThread(it.id) },
                    onProfileClick = onOpenProfile,
                    onEventReferenceClick = onOpenThread
                )
            }
        }
    }
}
