package com.umbra.app.testutil.fakes

import com.umbra.app.domain.nip51.PinList
import com.umbra.app.domain.repository.PinListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Shared [PinListRepository] fake — see FakeUserPreferences' doc comment for why. */
internal class FakePinListRepository : PinListRepository {
    var clearAllCalls: Int = 0
    override fun getPinList(pubkey: String): Flow<PinList?> = flowOf(null)
    override suspend fun pin(eventId: String): Result<Unit> = Result.success(Unit)
    override suspend fun unpin(eventId: String): Result<Unit> = Result.success(Unit)
    override suspend fun isPinned(eventId: String): Boolean = false
    override suspend fun getCurrentPinnedEventIds(): Set<String> = emptySet()
    override fun clearAll() {
        clearAllCalls += 1
    }
}
