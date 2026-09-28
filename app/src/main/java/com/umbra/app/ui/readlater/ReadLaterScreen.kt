package com.umbra.app.ui.readlater

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.ui.saved.SavedNotesScreen

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
    val items by viewModel.items.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    SavedNotesScreen(
        title = stringResource(R.string.read_later_title),
        emptyTitle = stringResource(R.string.read_later_empty),
        emptyMessage = stringResource(R.string.read_later_empty_message),
        notes = items,
        profiles = profiles,
        userRepository = viewModel.userRepositoryPublic,
        dataSourceFactory = viewModel.mediaDataSourceFactory,
        onNavigateBack = onNavigateBack,
        onOpenThread = onOpenThread,
        onOpenProfile = onOpenProfile
    )
}
