package com.umbra.app.testutil.fakes

import com.umbra.app.domain.nip02.ContactList
import com.umbra.app.domain.repository.ContactListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Shared [ContactListRepository] fake — see FakeUserPreferences' doc comment for why. */
internal class FakeContactListRepository : ContactListRepository {
    var clearAllCalls: Int = 0
    override fun getContactList(pubkey: String): Flow<ContactList?> = flowOf(null)
    override suspend fun follow(pubkey: String): Result<Unit> = Result.success(Unit)
    override suspend fun unfollow(pubkey: String): Result<Unit> = Result.success(Unit)
    override suspend fun getCurrentFollowedPubkeys(): Set<String> = emptySet()
    override fun clearAll() {
        clearAllCalls += 1
    }
}
