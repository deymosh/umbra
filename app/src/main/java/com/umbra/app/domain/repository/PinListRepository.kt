package com.umbra.app.domain.repository

import com.umbra.app.domain.nip51.PinList
import kotlinx.coroutines.flow.Flow

interface PinListRepository {
    fun getPinList(pubkey: String): Flow<PinList?>
    suspend fun pin(eventId: String): Result<Unit>
    suspend fun unpin(eventId: String): Result<Unit>
    suspend fun isPinned(eventId: String): Boolean
    suspend fun getCurrentPinnedEventIds(): Set<String>
    /** Wipes every owner's cached pin list from memory — see LogoutUseCase. */
    fun clearAll()
    /** Drops every non-signed-in owner's cached pin list — see TrimMemoryCachesUseCase. */
    fun trimMemory() {}
    /** Number of distinct owners currently cached — see ResourceUsageRepositoryImpl. */
    fun cachedOwnerCount(): Int = 0
}
