package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip02.ContactList
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeMuteListRepository
import com.umbra.app.testutil.fakes.FakeUserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DetermineMissingHydrationKindsUseCaseTest {

    private val candidateKinds = setOf(
        Event.KIND_METADATA,
        Event.KIND_CONTACT_LIST,
        Event.KIND_MUTED_USERS,
        Event.KIND_RELAY_LIST_METADATA,
        Event.KIND_DM_RELAY_LIST
    )

    @Test
    fun `given_nothingCachedLocally_when_determiningMissing_then_everyCandidateKindIsMissing`() = runBlocking {
        val pubkey = "a".repeat(64)
        val useCase = DetermineMissingHydrationKindsUseCase(
            FakeUserRepository(),
            FakeContactListRepository(),
            FakeMuteListRepository()
        )

        val missing = useCase(pubkey, candidateKinds)

        assertEquals(candidateKinds, missing)
    }

    @Test
    fun `given_relayListAlreadyKnown_when_determiningMissing_then_excludesOnlyThatKind`() = runBlocking {
        val pubkey = "a".repeat(64)
        val useCase = DetermineMissingHydrationKindsUseCase(
            FakeUserRepository(cachedRelayList = RelayListMetadata(pubkey = pubkey)),
            FakeContactListRepository(),
            FakeMuteListRepository()
        )

        val missing = useCase(pubkey, candidateKinds)

        assertEquals(candidateKinds - Event.KIND_RELAY_LIST_METADATA, missing)
    }

    @Test
    fun `given_everythingAlreadyKnown_when_determiningMissing_then_returnsEmpty`() = runBlocking {
        val pubkey = "a".repeat(64)
        val useCase = DetermineMissingHydrationKindsUseCase(
            FakeUserRepository(
                cachedProfile = com.umbra.app.domain.profile.UserProfile(pubkey = pubkey),
                cachedRelayList = RelayListMetadata(pubkey = pubkey),
                cachedDmRelayList = DmRelayList(pubkey = pubkey)
            ),
            object : com.umbra.app.domain.repository.ContactListRepository by FakeContactListRepository() {
                override fun getContactList(pubkey: String): Flow<ContactList?> =
                    flowOf(ContactList(ownerPubkey = pubkey, followedPubkeys = emptySet(), updatedAt = 0L))
            },
            object : com.umbra.app.domain.repository.MuteListRepository by FakeMuteListRepository() {
                override fun getMuteList(pubkey: String): Flow<com.umbra.app.domain.nip51.MuteList?> =
                    flowOf(com.umbra.app.domain.nip51.MuteList(ownerPubkey = pubkey, mutedPubkeys = emptySet(), updatedAt = 0L))
            }
        )

        val missing = useCase(pubkey, candidateKinds)

        assertEquals(emptySet<Int>(), missing)
    }
}
