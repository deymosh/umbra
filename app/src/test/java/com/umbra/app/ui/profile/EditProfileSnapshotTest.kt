package com.umbra.app.ui.profile

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
class EditProfileSnapshotTest {

    @Test
    fun editProfile() = snapshot("EditProfile") {
        EditProfileContent(
            state = EditProfileState(
                name = "alice",
                displayName = "Alice Moreau",
                about = "Cryptographer. Night-sky photographer. Chasing totality around the world.",
                website = "https://alice.example",
                nip05 = "alice@umbra.social",
                lud16 = "",
                isLoading = false
            ),
            onNavigateBack = {},
            onSave = {},
            onFieldChange = { _, _ -> },
            onPickBanner = {},
            onPickPicture = {}
        )
    }
}
