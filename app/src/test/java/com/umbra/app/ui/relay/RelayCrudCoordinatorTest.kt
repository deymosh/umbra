package com.umbra.app.ui.relay

import com.umbra.app.R
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.usecase.AddRelayUseCase
import com.umbra.app.domain.usecase.RemoveRelayUseCase
import com.umbra.app.domain.usecase.UpdateRelayUseCase
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.ui.common.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [RelayCrudCoordinator]'s per-role setters — the DM dirty-flag fix
 * (a rejected/no-op DM enable must not claim the published DM relay list needs re-publishing)
 * and the per-relay-id lock that keeps two concurrent role toggles on the same relay from
 * losing one of them. Structure follows InteractionActionsCoordinatorTest: a `subject()`
 * factory, a nested private recording fake implementing only the [RelayRepository] members this
 * coordinator actually calls, plain JUnit assertions, no Mockito.
 */
class RelayCrudCoordinatorTest {

    // isEnabled/isReadEnabled/isWriteEnabled all forced false so every derived *Active flag
    // defaults to false too -- the concurrency tests below need a relay that starts with
    // isWriteActive/isSearchActive both false so a setter flipping one to true is an
    // observable, unambiguous change rather than a no-op against Relay's own true-by-default
    // outbox/inbox flags.
    private fun sampleRelay(id: String, url: String) = Relay(
        id = id,
        url = url,
        isEnabled = false,
        isReadEnabled = false,
        isWriteEnabled = false
    )

    private fun subject(
        scope: CoroutineScope,
        relays: List<Relay>,
        relayRepository: RecordingRelayRepository = RecordingRelayRepository(),
        userPreferences: FakeUserPreferences = FakeUserPreferences(initialPubkey = "a".repeat(64))
    ): Pair<RelayCrudCoordinator, MutableStateFlow<RelayConfigState>> {
        relays.forEach { relayRepository.seed(it) }
        val state = MutableStateFlow(RelayConfigState(relays = relays))
        val coordinator = RelayCrudCoordinator(
            addRelayUseCase = AddRelayUseCase(relayRepository),
            updateRelayUseCase = UpdateRelayUseCase(relayRepository),
            removeRelayUseCase = RemoveRelayUseCase(relayRepository),
            relayRepository = relayRepository,
            eventRepository = FakeEventRepository(),
            userPreferences = userPreferences,
            state = state,
            scope = scope
        )
        return coordinator to state
    }

    /** Stores relays in a plain map keyed by id — [getAllRelays] snapshots that map, the rest of
     * [RelayRepository]'s CRUD members read/write it directly. [bootstrapDefaultsOnFirstLogin]/
     * [clearUserRelayConfig] are no-ops; nothing this coordinator calls needs them to do anything.
     * [callLog] records an "enter:<id>"/"exit:<id>" pair around every [updateRelay] invocation,
     * in call order, with an optional gate a test can register via [gateInvocation] to hold a
     * specific (0-based, across all invocations) call between its "enter" and "exit" markers —
     * this is what lets a test force two [RelayCrudCoordinator.updateRelayRole] calls to
     * genuinely overlap instead of merely running one after the other. */
    private class RecordingRelayRepository : RelayRepository {
        private val relays = mutableMapOf<String, Relay>()
        val updateRelayCalls = mutableListOf<Relay>()
        val callLog = mutableListOf<String>()
        private val gatesByInvocationIndex = mutableMapOf<Int, CompletableDeferred<Unit>>()
        private var invocationCount = 0

        fun seed(relay: Relay) {
            relays[relay.id] = relay
        }

        fun get(id: String): Relay? = relays[id]

        fun gateInvocation(index: Int): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gatesByInvocationIndex[index] = it }

        override fun getAllRelays(): Flow<List<Relay>> = flowOf(relays.values.toList())
        override suspend fun getRelayById(id: String): Relay? = relays[id]
        override suspend fun addRelay(relay: Relay) {
            relays[relay.id] = relay
        }
        override suspend fun updateRelay(relay: Relay) {
            val myIndex = invocationCount++
            callLog += "enter:${relay.id}"
            gatesByInvocationIndex[myIndex]?.await()
            updateRelayCalls += relay
            callLog += "exit:${relay.id}"
            relays[relay.id] = relay
        }
        override suspend fun removeRelay(id: String) {
            relays.remove(id)
        }
        override suspend fun bootstrapDefaultsOnFirstLogin() = Unit
        override suspend fun clearUserRelayConfig() = Unit
    }

    @Test
    fun `given a plaintext non-onion relay when setDmEnabled(true) runs then dmRelayListDirty stays false and the transport error is surfaced`() = runTest {
        val relay = sampleRelay(id = "r1", url = "ws://plain.example")
        val (coordinator, state) = subject(scope = this, relays = listOf(relay))

        coordinator.setDmEnabled("r1", enabled = true)
        advanceUntilIdle()

        assertFalse(state.value.dmRelayListDirty)
        val error = state.value.errorMessage
        assertTrue(error is UiMessage.Res && error.id == R.string.relay_dm_wss_required)
    }

    @Test
    fun `given a wss relay when setDmEnabled(true) runs then dmRelayListDirty is set and the persisted relay is DM-active`() = runTest {
        val relay = sampleRelay(id = "r1", url = "wss://relay.example")
        val repository = RecordingRelayRepository()
        val (coordinator, state) = subject(scope = this, relays = listOf(relay), relayRepository = repository)

        coordinator.setDmEnabled("r1", enabled = true)
        advanceUntilIdle()

        assertTrue(state.value.dmRelayListDirty)
        val stored = repository.get("r1")
        assertTrue(stored?.isDmActive == true)
        assertTrue(stored?.dmRequiresAuth == true)
    }

    @Test
    fun `given an unknown relayId when setDmEnabled(true) runs then dmRelayListDirty stays false and nothing is persisted`() = runTest {
        val repository = RecordingRelayRepository()
        val (coordinator, state) = subject(scope = this, relays = emptyList(), relayRepository = repository)

        coordinator.setDmEnabled("missing", enabled = true)
        advanceUntilIdle()

        assertFalse(state.value.dmRelayListDirty)
        assertNull(repository.get("missing"))
        assertEquals(0, repository.updateRelayCalls.size)
    }

    @Test
    fun `given two overlapping role toggles on the same relay when both resolve then neither update is lost`() = runTest {
        val relay = sampleRelay(id = "relayA", url = "wss://relay.example")
        val repository = RecordingRelayRepository()
        val (coordinator, _) = subject(scope = this, relays = listOf(relay), relayRepository = repository)
        // Only the first updateRelay call is gated — the second call must still be blocked by
        // the per-relay Mutex itself, not merely queued behind this gate.
        val gate = repository.gateInvocation(0)

        coordinator.setOutboxEnabled("relayA", enabled = true)
        coordinator.setSearchEnabled("relayA", enabled = true)
        advanceUntilIdle()

        assertEquals(listOf("enter:relayA"), repository.callLog)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("enter:relayA", "exit:relayA", "enter:relayA", "exit:relayA"),
            repository.callLog
        )
        val stored = repository.get("relayA")
        assertTrue(stored?.isWriteActive == true)
        assertTrue(stored?.isSearchActive == true)
    }

    @Test
    fun `given overlapping role toggles on different relays when advanced then the un-gated relay is not serialized behind the gated one`() = runTest {
        val relayA = sampleRelay(id = "relayA", url = "wss://relay.example")
        val relayB = sampleRelay(id = "relayB", url = "wss://relay2.example")
        val repository = RecordingRelayRepository()
        val (coordinator, _) = subject(scope = this, relays = listOf(relayA, relayB), relayRepository = repository)
        // relayA's write (the first call overall) is gated; relayB's write is not.
        val gate = repository.gateInvocation(0)

        coordinator.setOutboxEnabled("relayA", enabled = true)
        coordinator.setSearchEnabled("relayB", enabled = true)
        advanceUntilIdle()

        // relayB's write both entered and exited while relayA's was still gated mid-write --
        // proving the lock is per-relay-id, not a single lock shared across all relay writes.
        assertEquals(listOf("enter:relayA", "enter:relayB", "exit:relayB"), repository.callLog)
        assertTrue(repository.get("relayB")?.isSearchActive == true)
        assertFalse(repository.get("relayA")?.isWriteActive == true)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.get("relayA")?.isWriteActive == true)
        assertTrue(repository.get("relayB")?.isSearchActive == true)
    }
}
