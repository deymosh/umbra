package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nostr.NostrSessionController
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeMuteListRepository
import com.umbra.app.testutil.fakes.FakeNostrSessionController
import com.umbra.app.testutil.fakes.FakePinListRepository
import com.umbra.app.testutil.fakes.FakeUmbraLogger
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.testutil.fakes.FakeUserRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillDeleteLogoutUseCaseTest {

    @Test
    fun `given_validPubkey_when_backfillingOtherProfile_then_subscribesWithoutOwnerOnlyKinds`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = 1234L)
        val userRepo = FakeUserRepository(signedInPubkey = "z".repeat(64))
        val useCase = BackfillProfileUseCase(
            repo,
            userRepo,
            BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
            DetermineMissingHydrationKindsUseCase(userRepo, FakeContactListRepository(), FakeMuteListRepository()),
            ResolveProfileRelayHintsUseCase(userRepo, repo)
        )
        val rawPubkey = "A".repeat(64)

        val result = useCase(rawPubkey)

        assertTrue(result.isSuccess)
        val pubkey = "a".repeat(64)
        assertEquals(listOf(pubkey), repo.pinnedPubkeys)
        assertEquals(2, repo.subscriptions.size)

        val metadataSub = repo.subscriptions.first { it.first == NostrChannels.profileBackfillMetadata(pubkey) }
        val metadataFilters = metadataSub.second
        assertEquals(1, metadataFilters.size)
        assertEquals(setOf(pubkey), metadataFilters.first().authors)
        assertTrue(metadataFilters.first().kinds.contains(Event.KIND_METADATA))
        assertTrue(metadataFilters.first().kinds.contains(Event.KIND_DM_RELAY_LIST))
        assertTrue(metadataFilters.first().kinds.contains(Event.KIND_BLOSSOM_SERVER_LIST))
        // Non-owner profile (signed-in user is a different pubkey): search (10007) and index
        // (10086) relay-list kinds must be excluded — see BuildProfileHydrationFiltersUseCase's
        // doc comment on includeOwnerOnlyKinds.
        assertTrue(!metadataFilters.first().kinds.contains(Event.KIND_SEARCH_RELAYS))
        assertTrue(!metadataFilters.first().kinds.contains(Event.KIND_INDEX_RELAYS))
        // limit floor = authors.size(1) * kinds.size — kept in sync with
        // BuildProfileHydrationFiltersUseCase.BASE_HYDRATION_KINDS (6: metadata, contacts,
        // muted, relay list, DM relays, Blossom server list).
        assertEquals(6, metadataFilters.first().kinds.size)
        assertEquals(metadataFilters.first().kinds.size, metadataFilters.first().limit)

        val notesSub = repo.subscriptions.first { it.first == NostrChannels.profileBackfillNotes(pubkey) }
        val noteFilter = notesSub.second.first()
        // Includes NIP-09 deletion requests (kind 5) so a deletion this author published is
        // actually requested from relays, and NIP-18 reposts (kind 6/16) so this profile's Notes
        // tab can show what they've reposted — see BackfillProfileUseCase.
        assertEquals(
            setOf(Event.KIND_TEXT_NOTE, Event.KIND_EVENT_DELETION, Event.KIND_REPOST, Event.KIND_GENERIC_REPOST),
            noteFilter.kinds
        )
        assertEquals(setOf(pubkey), noteFilter.authors)
        assertEquals(1000, noteFilter.limit)
        // Bounded by since, not limit alone — same 365-day window loadOlderEvents' pages use.
        assertNotNull(noteFilter.since)
        val expectedSinceFloor = System.currentTimeMillis() / 1000L - 365L * 24 * 60 * 60L - 5L
        assertTrue(noteFilter.since!! >= expectedSinceFloor)

        assertEquals(NostrChannels.profileBackfillNotes(pubkey), repo.lastLoadOlderCall?.channelId)
        assertEquals(1234L, repo.lastLoadOlderCall?.untilTimestamp)
        assertEquals(365L * 24 * 60 * 60L, repo.lastLoadOlderCall?.windowSeconds)
        assertEquals(1000, repo.lastLoadOlderCall?.limit)

        // No cached relay list for this pubkey — nothing to proactively dial.
        assertTrue(repo.connectToRelayHintsCalls.isEmpty())
    }

    @Test
    fun `given_alreadyCachedRelayList_when_backfilling_then_proactivelyConnectsToItsRelays`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = 1234L)
        val pubkey = "a".repeat(64)
        val cachedRelayList = com.umbra.app.domain.nip65.RelayListMetadata(
            pubkey = pubkey,
            writeRelays = listOf("wss://write.example.com"),
            readRelays = listOf("wss://read.example.com")
        )
        val userRepo = FakeUserRepository(signedInPubkey = "z".repeat(64), cachedRelayList = cachedRelayList)
        val useCase = BackfillProfileUseCase(
            repo,
            userRepo,
            BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
            DetermineMissingHydrationKindsUseCase(userRepo, FakeContactListRepository(), FakeMuteListRepository()),
            ResolveProfileRelayHintsUseCase(userRepo, repo)
        )

        val result = useCase(pubkey)

        assertTrue(result.isSuccess)
        assertEquals(1, repo.connectToRelayHintsCalls.size)
        assertEquals(
            setOf("wss://write.example.com", "wss://read.example.com"),
            repo.connectToRelayHintsCalls.first().toSet()
        )
    }

    @Test
    fun `given_noCachedRelayListButARecordedHint_when_backfilling_then_proactivelyConnectsToTheHint`() = runBlocking {
        // Cold-start case: a profile viewed for the first time has no cached kind:10002 yet, so
        // the declared-relay-list-only path alone would have nothing to dial even though a relay
        // hint for this exact pubkey is already known (e.g. seen in a NIP-19 nprofile TLV).
        val repo = FakeEventRepository(
            oldestAuthorTimestamp = 1234L,
            relayHintsByPubkey = mapOf("a".repeat(64) to listOf("wss://hinted.example.com"))
        )
        val pubkey = "a".repeat(64)
        val userRepo = FakeUserRepository(signedInPubkey = "z".repeat(64), cachedRelayList = null)
        val useCase = BackfillProfileUseCase(
            repo,
            userRepo,
            BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
            DetermineMissingHydrationKindsUseCase(userRepo, FakeContactListRepository(), FakeMuteListRepository()),
            ResolveProfileRelayHintsUseCase(userRepo, repo)
        )

        val result = useCase(pubkey)

        assertTrue(result.isSuccess)
        assertEquals(1, repo.connectToRelayHintsCalls.size)
        assertEquals(listOf("wss://hinted.example.com"), repo.connectToRelayHintsCalls.first())
    }

    @Test
    fun `given_invalidPubkey_when_backfilling_then_failsWithoutAction`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null)
        val userRepo = FakeUserRepository()
        val useCase = BackfillProfileUseCase(
            repo,
            userRepo,
            BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
            DetermineMissingHydrationKindsUseCase(userRepo, FakeContactListRepository(), FakeMuteListRepository()),
            ResolveProfileRelayHintsUseCase(userRepo, repo)
        )

        val result = useCase("invalid")

        assertTrue(result.isFailure)
        assertTrue(repo.pinnedPubkeys.isEmpty())
        assertTrue(repo.subscriptions.isEmpty())
        assertEquals(null, repo.lastLoadOlderCall)
    }

    @Test
    fun `given_signedInUsersOwnPubkey_when_backfilling_then_isANoOp`() = runBlocking {
        // Own notes/metadata/relay-lists are already kept continuously in sync from login onward
        // via OUTBOX_NOTES/OUTBOX_PROFILE — BackfillProfileUseCase must not duplicate that work
        // (or pin/dial/subscribe anything) for the signed-in user's own pubkey.
        val repo = FakeEventRepository(oldestAuthorTimestamp = 1234L)
        val rawPubkey = "A".repeat(64)
        val pubkey = "a".repeat(64)
        val userRepo = FakeUserRepository(signedInPubkey = pubkey)
        val useCase = BackfillProfileUseCase(
            repo,
            userRepo,
            BuildProfileHydrationRequestsUseCase(BuildProfileHydrationFiltersUseCase()),
            DetermineMissingHydrationKindsUseCase(userRepo, FakeContactListRepository(), FakeMuteListRepository()),
            ResolveProfileRelayHintsUseCase(userRepo, repo)
        )

        val result = useCase(rawPubkey)

        assertTrue(result.isSuccess)
        assertTrue(repo.pinnedPubkeys.isEmpty())
        assertTrue(repo.subscriptions.isEmpty())
        assertTrue(repo.connectToRelayHintsCalls.isEmpty())
        assertEquals(null, repo.lastLoadOlderCall)
    }

    @Test
    fun `given_validAndInvalidPubkey_when_stopping_then_unpinsValid`() {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null)
        val useCase = StopProfileBackfillUseCase(repo)

        useCase("B".repeat(64))
        useCase("bad")

        assertEquals(listOf("b".repeat(64)), repo.unpinnedPubkeys)
    }

    @Test
    fun `given_eventWithMatchingOwner_when_deleting_then_buildsDeletionEvent`() {
        val useCase = DeleteNoteUseCase()
        val eventId = "e".repeat(64)
        val owner = "f".repeat(64)
        val event = Event(
            id = eventId,
            pubkey = owner,
            createdAt = 10L,
            kind = Event.KIND_TEXT_NOTE,
            tags = emptyList(),
            content = "note",
            sig = "s".repeat(128)
        )

        val result = useCase(event, owner.uppercase())

        assertTrue(result.isSuccess)
        val raw = result.getOrNull()
        assertNotNull(raw)

        val json = parseObject(raw!!)
        assertEquals(Event.KIND_EVENT_DELETION, json.getValue("kind").jsonPrimitive.content.toInt())
        val firstTag = json.getValue("tags").jsonArray.first().jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("e", eventId), firstTag)
    }

    @Test
    fun `given_eventWithDifferentOwner_when_deleting_then_returnsFailure`() {
        val useCase = DeleteNoteUseCase()
        val event = Event(
            id = "e".repeat(64),
            pubkey = "a".repeat(64),
            createdAt = 1L,
            kind = Event.KIND_TEXT_NOTE,
            tags = emptyList(),
            content = "",
            sig = "s".repeat(128)
        )

        val result = useCase(event, "b".repeat(64))

        assertTrue(result.isFailure)
    }

    @Test
    fun `given_eventId_when_deletingFromRepository_then_delegates`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null)
        val useCase = RemoveDeletedNoteFromCacheUseCase(repo)

        val result = useCase("deadbeef")

        assertTrue(result.isSuccess)
        assertEquals("deadbeef", repo.deletedEventId)
    }

    @Test
    fun `given_failingRepository_when_logging_then_clearsUserRepositoryAndPreferencesAnyway`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null, failClearAllData = true)
        val userRepo = FakeUserRepository()
        val prefs = FakeUserPreferences()
        val contactListRepo = FakeContactListRepository()
        val muteListRepo = FakeMuteListRepository()
        val pinListRepo = FakePinListRepository()
        val useCase = LogoutUseCase(repo, userRepo, prefs, contactListRepo, muteListRepo, pinListRepo, FakeNostrSessionController(), FakeUmbraLogger())

        useCase()

        assertEquals(1, repo.clearAllDataCalls)
        assertEquals(1, userRepo.clearAllCalls)
        assertEquals(1, prefs.clearAllCalls)
        assertEquals(1, contactListRepo.clearAllCalls)
        assertEquals(1, muteListRepo.clearAllCalls)
        assertEquals(1, pinListRepo.clearAllCalls)
    }

    @Test
    fun `given_throwingPreferences_when_logging_then_swallowsExceptions`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null, failClearAllData = false)
        val userRepo = FakeUserRepository()
        val prefs = FakeUserPreferences(throwOnClearAll = true)
        val contactListRepo = FakeContactListRepository()
        val muteListRepo = FakeMuteListRepository()
        val pinListRepo = FakePinListRepository()
        val useCase = LogoutUseCase(repo, userRepo, prefs, contactListRepo, muteListRepo, pinListRepo, FakeNostrSessionController(), FakeUmbraLogger())

        useCase()

        assertEquals(1, repo.clearAllDataCalls)
        assertEquals(1, userRepo.clearAllCalls)
        assertEquals(1, prefs.clearAllCalls)
        assertEquals(1, contactListRepo.clearAllCalls)
        assertEquals(1, muteListRepo.clearAllCalls)
        assertEquals(1, pinListRepo.clearAllCalls)
    }

    @Test
    fun `given_failingUserRepository_when_logging_then_clearsPreferencesAnyway`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null)
        val userRepo = FakeUserRepository(failClearAll = true)
        val prefs = FakeUserPreferences()
        val contactListRepo = FakeContactListRepository()
        val muteListRepo = FakeMuteListRepository()
        val pinListRepo = FakePinListRepository()
        val useCase = LogoutUseCase(repo, userRepo, prefs, contactListRepo, muteListRepo, pinListRepo, FakeNostrSessionController(), FakeUmbraLogger())

        useCase()

        assertEquals(1, repo.clearAllDataCalls)
        assertEquals(1, userRepo.clearAllCalls)
        assertEquals(1, prefs.clearAllCalls)
        assertEquals(1, contactListRepo.clearAllCalls)
        assertEquals(1, muteListRepo.clearAllCalls)
        assertEquals(1, pinListRepo.clearAllCalls)
    }

    @Test
    fun `given_loggingOut_then_disconnectsAllRelaysBeforeWipe`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null)
        val userRepo = FakeUserRepository()
        val prefs = FakeUserPreferences().apply { savePublicKey("c".repeat(64)) }
        val sessionController = FakeNostrSessionController()
        val useCase = LogoutUseCase(
            repo,
            userRepo,
            prefs,
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            sessionController,
            FakeUmbraLogger()
        )

        useCase()

        assertEquals(1, sessionController.stopCalls)
        assertEquals(1, repo.clearAllDataCalls)
    }

    @Test
    fun `given_loggedInPubkey_when_logging_then_clearsThatPubkeysBackfillAnchors`() = runBlocking {
        val repo = FakeEventRepository(oldestAuthorTimestamp = null)
        val userRepo = FakeUserRepository()
        val pubkey = "c".repeat(64)
        val prefs = FakeUserPreferences().apply { savePublicKey(pubkey) }
        val useCase = LogoutUseCase(
            repo,
            userRepo,
            prefs,
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            FakeNostrSessionController(),
            FakeUmbraLogger()
        )

        useCase()

        assertEquals(listOf(pubkey), repo.clearedBackfillAnchorPubkeys)
    }

    @Test
    fun `given_eventRepositoryClearAllDataThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("clear all data boom")
        val repo = ThrowingClearAllDataEventRepository(
            FakeEventRepository(oldestAuthorTimestamp = null),
            thrown
        )
        val userRepo = FakeUserRepository()
        val prefs = FakeUserPreferences()
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            repo,
            userRepo,
            prefs,
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            FakeNostrSessionController(),
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_nostrSessionControllerStopThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("session stop boom")
        val sessionController = ThrowingStopNostrSessionController(FakeNostrSessionController(), thrown)
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            FakeEventRepository(oldestAuthorTimestamp = null),
            FakeUserRepository(),
            FakeUserPreferences(),
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            sessionController,
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_userRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("user repository clear all boom")
        val userRepo = ThrowingClearAllUserRepository(FakeUserRepository(), thrown)
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            FakeEventRepository(oldestAuthorTimestamp = null),
            userRepo,
            FakeUserPreferences(),
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            FakeNostrSessionController(),
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_contactListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("contact list clear all boom")
        val contactListRepo = ThrowingClearAllContactListRepository(FakeContactListRepository(), thrown)
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            FakeEventRepository(oldestAuthorTimestamp = null),
            FakeUserRepository(),
            FakeUserPreferences(),
            contactListRepo,
            FakeMuteListRepository(),
            FakePinListRepository(),
            FakeNostrSessionController(),
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_muteListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("mute list clear all boom")
        val muteListRepo = ThrowingClearAllMuteListRepository(FakeMuteListRepository(), thrown)
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            FakeEventRepository(oldestAuthorTimestamp = null),
            FakeUserRepository(),
            FakeUserPreferences(),
            FakeContactListRepository(),
            muteListRepo,
            FakePinListRepository(),
            FakeNostrSessionController(),
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_pinListRepositoryClearAllThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("pin list clear all boom")
        val pinListRepo = ThrowingClearAllPinListRepository(FakePinListRepository(), thrown)
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            FakeEventRepository(oldestAuthorTimestamp = null),
            FakeUserRepository(),
            FakeUserPreferences(),
            FakeContactListRepository(),
            FakeMuteListRepository(),
            pinListRepo,
            FakeNostrSessionController(),
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    @Test
    fun `given_clearBackfillAnchorsThrows_when_logging_then_loggerRecordsErrorWithSameThrowable`() = runBlocking {
        val thrown = IllegalStateException("clear backfill anchors boom")
        val repo = ThrowingClearBackfillAnchorsEventRepository(
            FakeEventRepository(oldestAuthorTimestamp = null),
            thrown
        )
        val prefs = FakeUserPreferences().apply { savePublicKey("d".repeat(64)) }
        val logger = FakeUmbraLogger()
        val useCase = LogoutUseCase(
            repo,
            FakeUserRepository(),
            prefs,
            FakeContactListRepository(),
            FakeMuteListRepository(),
            FakePinListRepository(),
            FakeNostrSessionController(),
            logger
        )

        useCase()

        assertEquals(1, logger.errorCalls.size)
        assertSame(thrown, logger.errorCalls.first().throwable)
    }

    private fun parseObject(raw: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
}

/**
 * Wraps a real fake so `clearAllData()` throws a specific, caller-controlled instance —
 * [FakeEventRepository]'s own `failClearAllData` flag throws a fresh exception each call, which
 * can't be asserted against by identity.
 */
private class ThrowingClearAllDataEventRepository(
    delegate: EventRepository,
    private val thrown: Throwable
) : EventRepository by delegate {
    override suspend fun clearAllData() {
        throw thrown
    }
}

/** Wraps a real fake so `stop()` throws a specific, caller-controlled instance. */
private class ThrowingStopNostrSessionController(
    delegate: NostrSessionController,
    private val thrown: Throwable
) : NostrSessionController by delegate {
    override fun stop() {
        throw thrown
    }
}

/** Wraps a real fake so `clearAll()` throws a specific, caller-controlled instance. */
private class ThrowingClearAllUserRepository(
    delegate: UserRepository,
    private val thrown: Throwable
) : UserRepository by delegate {
    override fun clearAll() {
        throw thrown
    }
}

/** Wraps a real fake so `clearAll()` throws a specific, caller-controlled instance. */
private class ThrowingClearAllContactListRepository(
    delegate: ContactListRepository,
    private val thrown: Throwable
) : ContactListRepository by delegate {
    override fun clearAll() {
        throw thrown
    }
}

/** Wraps a real fake so `clearAll()` throws a specific, caller-controlled instance. */
private class ThrowingClearAllMuteListRepository(
    delegate: MuteListRepository,
    private val thrown: Throwable
) : MuteListRepository by delegate {
    override fun clearAll() {
        throw thrown
    }
}

/** Wraps a real fake so `clearAll()` throws a specific, caller-controlled instance. */
private class ThrowingClearAllPinListRepository(
    delegate: PinListRepository,
    private val thrown: Throwable
) : PinListRepository by delegate {
    override fun clearAll() {
        throw thrown
    }
}

/** Wraps a real fake so `clearBackfillAnchors()` throws a specific, caller-controlled instance. */
private class ThrowingClearBackfillAnchorsEventRepository(
    delegate: EventRepository,
    private val thrown: Throwable
) : EventRepository by delegate {
    override fun clearBackfillAnchors(pubkey: String) {
        throw thrown
    }
}
