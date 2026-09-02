package com.umbra.app.ui.composer

import androidx.lifecycle.SavedStateHandle
import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.MediaUploadRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.usecase.BuildProfileHydrationFiltersUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.domain.usecase.DetermineMissingHydrationKindsUseCase
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.UploadBlossomBlobUseCase
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeMuteListRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.testutil.fakes.FakeUserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun sampleEvent(
        id: String = "1".repeat(64),
        pubkey: String = "2".repeat(64),
        kind: Int = Event.KIND_TEXT_NOTE
    ): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = 100L,
        kind = kind,
        tags = emptyList(),
        content = "original note",
        sig = "s".repeat(128)
    )

    private fun createViewModel(
        quoteEventId: String? = null,
        eventRepository: FakeEventRepository = FakeEventRepository()
    ): ComposerViewModel {
        val userRepository = FakeUserRepository()
        val userPreferences = FakeUserPreferences(initialPubkey = "3".repeat(64))
        val amberSignerGateway = FakeAmberSignerGateway()
        val hydrationRequestsUseCase = BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase())
        val missingHydrationKindsUseCase = DetermineMissingHydrationKindsUseCase(
            userRepository,
            FakeContactListRepository(),
            FakeMuteListRepository()
        )
        return ComposerViewModel(
            savedStateHandle = SavedStateHandle(
                buildMap { quoteEventId?.let { put("quote", it) } }
            ),
            eventRepository = eventRepository,
            userRepository = userRepository,
            userPreferences = userPreferences,
            amberSignerGateway = amberSignerGateway,
            publishSignedEventUseCase = PublishSignedEventUseCase(eventRepository, FakeBroadcastRepository(), NoOpUmbraLogger),
            trackReferencedAuthorUseCase = TrackReferencedAuthorUseCase(
                eventRepository,
                userRepository,
                hydrationRequestsUseCase,
                missingHydrationKindsUseCase
            ),
            uploadBlossomBlobUseCase = UploadBlossomBlobUseCase(FakeMediaUploadRepository(), amberSignerGateway, userPreferences),
            videoCacheDataSourceProvider = FakeVideoCacheDataSourceProvider()
        )
    }

    @Test
    fun `given a quote target when composer opens then prefills a nevent reference with cursor at start`() = runTest(dispatcher.scheduler) {
        val target = sampleEvent()
        val eventRepository = FakeEventRepository(
            eventsById = mapOf(target.id to target),
            relayHintsByPubkey = mapOf(target.pubkey.lowercase() to listOf("wss://relay.example"))
        )

        val viewModel = createViewModel(quoteEventId = target.id, eventRepository = eventRepository)
        dispatcher.scheduler.advanceUntilIdle()

        val text = viewModel.textState.text.toString()
        assertTrue("expected an embedded nostr:nevent1 reference, got: $text", text.contains("nostr:nevent1"))
        assertEquals(0, viewModel.textState.selection.start)
        assertEquals(0, viewModel.textState.selection.end)
    }

    @Test
    fun `given no quote target when composer opens then text stays empty`() = runTest(dispatcher.scheduler) {
        val viewModel = createViewModel(quoteEventId = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("", viewModel.textState.text.toString())
    }

    private class FakeBroadcastRepository : BroadcastRepository {
        override val activeBroadcasts: StateFlow<List<com.umbra.app.domain.broadcast.BroadcastEvent>> =
            MutableStateFlow(emptyList())
        override fun trackPublish(event: Event, targetRelays: Set<String>) = Unit
        override fun retryFailedRelays(broadcastId: String) = Unit
        override fun dismiss(broadcastId: String) = Unit
    }

    private class FakeAmberSignerGateway : AmberSignerGateway {
        override fun isAmberInstalled(): Boolean = true
        override fun createLoginIntent(): android.content.Intent = android.content.Intent("umbra.test.login")
        override fun createSignEventIntent(eventJson: String, currentUserHex: String?): android.content.Intent =
            android.content.Intent("umbra.test.sign")
        override fun createStoreIntent(): android.content.Intent = android.content.Intent("umbra.test.store")
        override fun extractPublicKeyFromResult(data: android.content.Intent?): String? = null
        override fun extractSignedEventFromResult(data: android.content.Intent?): String? = null
        override suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String? = null
        override suspend fun signEvent(eventJson: String, currentUserHex: String?): String? = null
        override suspend fun requestPublicKey(): String? = null
        override fun openStore(): Boolean = false
    }

    private class FakeVideoCacheDataSourceProvider : VideoCacheDataSourceProvider {
        override fun getCacheDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
            throw UnsupportedOperationException("Not needed for this test")
        }
    }

    private class FakeMediaUploadRepository : MediaUploadRepository {
        override suspend fun uploadBlob(
            serverUrl: String,
            bytes: ByteArray,
            mimeType: String,
            authorizationHeaderValue: String
        ): Result<com.umbra.app.domain.nipb7.BlossomBlobDescriptor> =
            Result.failure(UnsupportedOperationException("Not needed for this test"))

        override suspend fun headUpload(
            serverUrl: String,
            sha256Hex: String,
            mimeType: String,
            sizeBytes: Long,
            authorizationHeaderValue: String?
        ): Result<Unit> = Result.failure(UnsupportedOperationException("Not needed for this test"))

        override suspend fun headBlob(
            serverUrl: String,
            sha256Hex: String,
            authorizationHeaderValue: String?
        ): Result<Unit> = Result.failure(UnsupportedOperationException("Not needed for this test"))

        override suspend fun listBlobs(
            serverUrl: String,
            pubkeyHex: String,
            authorizationHeaderValue: String?
        ): Result<List<com.umbra.app.domain.nipb7.BlossomBlobDescriptor>> =
            Result.failure(UnsupportedOperationException("Not needed for this test"))

        override suspend fun deleteBlob(
            serverUrl: String,
            sha256Hex: String,
            authorizationHeaderValue: String
        ): Result<Unit> = Result.failure(UnsupportedOperationException("Not needed for this test"))

        override suspend fun mirrorBlob(
            serverUrl: String,
            sourceUrl: String,
            authorizationHeaderValue: String
        ): Result<com.umbra.app.domain.nipb7.BlossomBlobDescriptor> =
            Result.failure(UnsupportedOperationException("Not needed for this test"))
    }
}
