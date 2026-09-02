package com.umbra.app.testutil.fakes

import com.umbra.app.domain.nip51.MuteList
import com.umbra.app.domain.repository.MuteListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Shared [MuteListRepository] fake — see FakeUserPreferences' doc comment for why. */
internal class FakeMuteListRepository : MuteListRepository {
    var clearAllCalls: Int = 0
    override fun getMuteList(pubkey: String): Flow<MuteList?> = flowOf(null)
    override suspend fun mute(pubkey: String): Result<Unit> = Result.success(Unit)
    override suspend fun unmute(pubkey: String): Result<Unit> = Result.success(Unit)
    override suspend fun getCurrentMutedPubkeys(): Set<String> = emptySet()
    override fun clearAll() {
        clearAllCalls += 1
    }
}
