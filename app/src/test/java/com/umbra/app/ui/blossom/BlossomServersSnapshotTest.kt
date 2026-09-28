package com.umbra.app.ui.blossom

import android.app.Application
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class BlossomServersSnapshotTest {

    @Test
    fun servers() = snapshot("BlossomServers") {
        BlossomServersContent(
            state = BlossomServersState(
                servers = listOf("https://blossom.primal.net", "https://cdn.satellite.earth", "https://nostr.download/media"),
                newServerInput = "https://blossom.",
                isLoading = false
            ),
            onNavigateBack = {}, onSave = {}, onInputChange = {}, onAdd = {},
            onMoveUp = {}, onMoveDown = {}, onRemove = {}
        )
    }

    @Test
    fun empty() = snapshot("BlossomServers_empty") {
        BlossomServersContent(
            state = BlossomServersState(isLoading = false),
            onNavigateBack = {}, onSave = {}, onInputChange = {}, onAdd = {},
            onMoveUp = {}, onMoveDown = {}, onRemove = {}
        )
    }
}
