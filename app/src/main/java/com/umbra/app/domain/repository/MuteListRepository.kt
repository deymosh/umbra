package com.umbra.app.domain.repository

import com.umbra.app.domain.nip51.MuteList
import kotlinx.coroutines.flow.Flow

interface MuteListRepository {
    fun getMuteList(pubkey: String): Flow<MuteList?>
    suspend fun mute(pubkey: String): Result<Unit>
    suspend fun unmute(pubkey: String): Result<Unit>
    suspend fun getCurrentMutedPubkeys(): Set<String>
    /** Wipes every owner's cached mute list from memory — see LogoutUseCase. */
    fun clearAll()
    /** Drops every non-signed-in owner's cached mute list — see TrimMemoryCachesUseCase. */
    fun trimMemory() {}
    /** Number of distinct owners currently cached — see ResourceUsageRepositoryImpl. */
    fun cachedOwnerCount(): Int = 0
}
