package com.umbra.app.ui.saved

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.EmptyState
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.feed.EventCard

/** A titled list of saved notes (Read later, Bookmarks), with an explanatory empty state. */
@Composable
fun SavedNotesScreen(
    title: String,
    emptyTitle: String,
    emptyMessage: String,
    notes: List<Event>,
    profiles: Map<String, UserProfile>,
    userRepository: UserRepository,
    dataSourceFactory: DataSource.Factory,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(title) },
                navigationIcon = { UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack) }
            )
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(title = emptyTitle, message = emptyMessage, modifier = Modifier.fillMaxSize())
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(notes, key = { it.id }, contentType = { "note" }) { note ->
                EventCard(
                    event = note,
                    userProfile = profiles[note.pubkey.lowercase()],
                    userRepository = userRepository,
                    torDataSourceFactory = dataSourceFactory,
                    onEventClick = { onOpenThread(it.id) },
                    onProfileClick = onOpenProfile,
                    onEventReferenceClick = onOpenThread
                )
            }
        }
    }
}
