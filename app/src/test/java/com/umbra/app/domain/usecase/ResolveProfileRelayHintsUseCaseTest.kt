package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveProfileRelayHintsUseCaseTest {

    private val pubkey = "a".repeat(64)

    @Test
    fun `given only a declared relay list when resolving then returns its relays`() {
        val userRepo = FakeUserRepository(
            cachedRelayList = RelayListMetadata(pubkey = pubkey, writeRelays = listOf("wss://declared.example"))
        )
        val eventRepo = FakeEventRepository()
        val useCase = ResolveProfileRelayHintsUseCase(userRepo, eventRepo)

        assertEquals(listOf("wss://declared.example"), useCase(pubkey))
    }

    @Test
    fun `given no declared relay list but a recorded hint when resolving then returns the hint`() {
        // The cold-start case this use case exists for: a profile viewed for the first time has no
        // cached kind:10002 yet, so relying on the declared list alone would resolve to nothing.
        val userRepo = FakeUserRepository(cachedRelayList = null)
        val eventRepo = FakeEventRepository(relayHintsByPubkey = mapOf(pubkey to listOf("wss://hinted.example")))
        val useCase = ResolveProfileRelayHintsUseCase(userRepo, eventRepo)

        assertEquals(listOf("wss://hinted.example"), useCase(pubkey))
    }

    @Test
    fun `given both a declared relay list and a recorded hint when resolving then returns the union`() {
        val userRepo = FakeUserRepository(
            cachedRelayList = RelayListMetadata(pubkey = pubkey, writeRelays = listOf("wss://declared.example"))
        )
        val eventRepo = FakeEventRepository(relayHintsByPubkey = mapOf(pubkey to listOf("wss://hinted.example")))
        val useCase = ResolveProfileRelayHintsUseCase(userRepo, eventRepo)

        val result = useCase(pubkey)
        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf("wss://declared.example", "wss://hinted.example")))
    }

    @Test
    fun `given neither a declared relay list nor a recorded hint when resolving then returns empty`() {
        val userRepo = FakeUserRepository(cachedRelayList = null)
        val eventRepo = FakeEventRepository()
        val useCase = ResolveProfileRelayHintsUseCase(userRepo, eventRepo)

        assertTrue(useCase(pubkey).isEmpty())
    }
}
