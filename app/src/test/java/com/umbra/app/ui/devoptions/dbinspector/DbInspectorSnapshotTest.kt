package com.umbra.app.ui.devoptions.dbinspector

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.umbra.app.domain.model.DbEventDetail
import com.umbra.app.domain.model.DbTableSummary
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.SnapshotFixtures
import com.umbra.app.ui.snapshot.snapshot
import com.umbra.app.ui.snapshot.snapshotScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class DbInspectorSnapshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun event(id: String, kind: Int, content: String, ago: Long) = DbEventDetail(
        id = id.padEnd(64, 'f'),
        pubkey = SnapshotFixtures.BOB,
        kind = kind,
        createdAt = SnapshotFixtures.now - ago,
        content = content,
        tagsJson = """[["t","eclipse"],["p","${SnapshotFixtures.ALICE}"]]""",
        sig = "9f".repeat(64)
    )

    private val results = listOf(
        event("a1", 1, "gm. relays are fast over onion today", 14 * 60),
        event("b2", 3, "", 3 * 3600),
        event("c3", 1, "Where did you watch it from? Planning for the next one already.", 2 * 86400)
    )

    private val state = DbInspectorState(
        tableSummaries = listOf(
            DbTableSummary("events", 1284),
            DbTableSummary("relays", 12),
            DbTableSummary("feed_filters", 3),
            DbTableSummary("pending_broadcasts", 0)
        ),
        searchKind = "1",
        searchResults = results,
        hasSearched = true,
        hasMoreResults = true
    )

    @Test
    fun inspector() = snapshot("DbInspector") {
        DbInspectorContent(state, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun eventDetail() = compose.snapshotScreen("DbInspector_event") {
        DbInspectorContent(state.copy(selectedEvent = results.first().copy(createdAt = 1_750_000_000L)), {}, {}, {}, {}, {}, {}, {}, {})
    }
}
