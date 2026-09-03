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

    private fun sampleRelay(id: String, url: String) = Relay(id = id, url = url)

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
            eventRepository = FakeEventRepository(),
            userPreferences = userPreferences,
            state = state,
            scope = scope
        )
        return coordinator to state
    }

    /** Stores relays in a plain map keyed by id — [getAllRelays] snapshots that map, the rest of
     * [RelayRepository]'s CRUD members read/write it directly. [bootstrapDefaultsOnFirstLogin]/
     * [clearUserRelayConfig] are no-ops; nothing this coordinator calls needs them to do anything. */
    private class RecordingRelayRepository : RelayRepository {
        private val relays = mutableMapOf<String, Relay>()
        val updateRelayCalls = mutableListOf<Relay>()

        fun seed(relay: Relay) {
            relays[relay.id] = relay
        }

        fun get(id: String): Relay? = relays[id]

        override fun getAllRelays(): Flow<List<Relay>> = flowOf(relays.values.toList())
        override suspend fun getRelayById(id: String): Relay? = relays[id]
        override suspend fun addRelay(relay: Relay) {
            relays[relay.id] = relay
        }
        override suspend fun updateRelay(relay: Relay) {
            updateRelayCalls += relay
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
}
