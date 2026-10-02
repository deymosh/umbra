package com.umbra.app.ui.bookmarks

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.SnapshotFixtures
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class BookmarksSnapshotTest {

    private val sets = listOf(
        BookmarkSetChip("a", "Eclipse photos"),
        BookmarkSetChip("b", "Relay operators worth following for setup tips"),
        BookmarkSetChip("c", "Recipes")
    )

    @Test
    fun bookmarks() = snapshot("Bookmarks") {
        BookmarksContent(
            state = BookmarksState(
                items = listOf(
                    BookmarkedEvent(SnapshotFixtures.textNote, isPrivate = true),
                    BookmarkedEvent(SnapshotFixtures.shortNote, isPrivate = false)
                ),
                profiles = mapOf(
                    SnapshotFixtures.textNote.pubkey to SnapshotFixtures.alice,
                    SnapshotFixtures.shortNote.pubkey to SnapshotFixtures.bob
                ),
                privateState = PrivateBookmarksState.READ,
                newBookmarksPrivate = true,
                sets = sets
            ),
            userRepository = SnapshotFixtures.userRepository,
            dataSourceFactory = SnapshotFixtures.tors,
            onNavigateBack = {}, onOpenThread = {}, onOpenProfile = {},
            onNewBookmarksPrivateChange = {}, onUnlockPrivate = {},
            onSelectSet = {}, onCreateSet = {}, onRenameSet = { _, _ -> }, onDeleteSet = {},
            animateAvatars = false
        )
    }

    @Test
    fun bookmarksLocked() = snapshot("Bookmarks_locked_empty") {
        BookmarksContent(
            state = BookmarksState(privateState = PrivateBookmarksState.LOCKED, newBookmarksPrivate = false),
            userRepository = SnapshotFixtures.userRepository,
            dataSourceFactory = SnapshotFixtures.tors,
            onNavigateBack = {}, onOpenThread = {}, onOpenProfile = {},
            onNewBookmarksPrivateChange = {}, onUnlockPrivate = {},
            onSelectSet = {}, onCreateSet = {}, onRenameSet = { _, _ -> }, onDeleteSet = {},
            animateAvatars = false
        )
    }

    @Test
    fun bookmarkSetEmpty() = snapshot("Bookmarks_set_empty") {
        BookmarksContent(
            state = BookmarksState(sets = sets, selectedSet = "a"),
            userRepository = SnapshotFixtures.userRepository,
            dataSourceFactory = SnapshotFixtures.tors,
            onNavigateBack = {}, onOpenThread = {}, onOpenProfile = {},
            onNewBookmarksPrivateChange = {}, onUnlockPrivate = {},
            onSelectSet = {}, onCreateSet = {}, onRenameSet = { _, _ -> }, onDeleteSet = {},
            animateAvatars = false
        )
    }

    @Test
    fun addToList() = snapshot("Bookmarks_add_to_list") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AddToListContent(
                choices = listOf(
                    BookmarkSetChoice("a", "Eclipse photos", contains = true),
                    BookmarkSetChoice("b", "Relay operators worth following for setup tips", contains = false),
                    BookmarkSetChoice("c", "Recipes", contains = false)
                ),
                onToggle = {},
                onNewList = {}
            )
        }
    }
}
