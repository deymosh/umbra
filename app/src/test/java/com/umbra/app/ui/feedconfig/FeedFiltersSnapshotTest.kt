package com.umbra.app.ui.feedconfig

import android.app.Application
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.feed.FeedFilter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class FeedFiltersSnapshotTest {
    @Test
    fun filters() = snapshot("FeedFilters_list") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActiveFilterCard(
                filter = FeedFilter(id = "a", name = "Default", hideNsfw = true, excludedHashtags = setOf("airdrop", "giveaway"), isActive = true),
                onEdit = {},
                onDeactivate = {}
            )
            FeedFilterCard(
                filter = FeedFilter(id = "b", name = "Follows only", scopeToFollows = true),
                isSelected = true, onSelect = {}, onActivate = {}, onEdit = {}, onDelete = {}
            )
            FeedFilterCard(
                filter = FeedFilter(id = "c", name = "Everything", hideNsfw = false),
                isSelected = false, onSelect = {}, onActivate = {}, onEdit = {}, onDelete = {}
            )
        }
    }
}
