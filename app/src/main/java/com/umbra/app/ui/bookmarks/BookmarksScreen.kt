package com.umbra.app.ui.bookmarks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.ui.saved.SavedNotesScreen

/** Bookmark/unbookmark from any note's menu; null when signed out or outside the root. */
class BookmarkActions(val isBookmarked: (String) -> Boolean, val toggle: (Event) -> Unit)

val LocalBookmarks = staticCompositionLocalOf<BookmarkActions?> { null }

@Composable
fun BookmarksScreen(
    viewModel: BookmarksViewModel,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    SavedNotesScreen(
        title = stringResource(R.string.bookmarks_title),
        emptyTitle = stringResource(R.string.bookmarks_empty),
        emptyMessage = stringResource(R.string.bookmarks_empty_message),
        notes = notes,
        profiles = profiles,
        userRepository = viewModel.userRepositoryPublic,
        dataSourceFactory = viewModel.mediaDataSourceFactory,
        onNavigateBack = onNavigateBack,
        onOpenThread = onOpenThread,
        onOpenProfile = onOpenProfile
    )
}
