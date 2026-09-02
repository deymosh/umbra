package com.umbra.app.domain.repository

import com.umbra.app.domain.nip02.ContactList
import kotlinx.coroutines.flow.Flow

interface ContactListRepository {
    fun getContactList(pubkey: String): Flow<ContactList?>
    suspend fun follow(pubkey: String): Result<Unit>
    suspend fun unfollow(pubkey: String): Result<Unit>
    suspend fun getCurrentFollowedPubkeys(): Set<String>
    /** Wipes every owner's cached contact list from memory — see LogoutUseCase. */
    fun clearAll()
    /** Drops every non-signed-in owner's cached contact list — see TrimMemoryCachesUseCase. */
    fun trimMemory() {}
    /** Number of distinct owners currently cached — see ResourceUsageRepositoryImpl. */
    fun cachedOwnerCount(): Int = 0
}

