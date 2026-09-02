package com.umbra.app.testutil.fakes

import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip51.IndexRelaysList
import com.umbra.app.domain.nip51.SearchRelaysList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared [UserRepository] fake — see FakeUserPreferences' doc comment for why this was
 * centralized. [cachedProfile] backs [getProfile]/[observeProfile] (a fixed snapshot);
 * [emitProfile] separately drives [profileFlow], the live update stream ProfileViewModel
 * actually observes — these are deliberately different mechanisms, matching the real
 * UserRepositoryImpl split between a point-in-time read and a push stream.
 */
internal class FakeUserRepository(
    private val cachedProfile: UserProfile? = null,
    private val failClearAll: Boolean = false,
    private val signedInPubkey: String? = null,
    private val cachedRelayList: RelayListMetadata? = null,
    private val cachedDmRelayList: DmRelayList? = null,
    private val cachedServerList: UserServerList? = null
) : UserRepository {
    var clearAllCalls: Int = 0
    private val profileMutableFlow = MutableSharedFlow<UserProfile>(replay = 0)

    override suspend fun getProfile(pubkey: String): UserProfile? = cachedProfile
    override suspend fun getProfiles(pubkeys: List<String>): List<UserProfile> = emptyList()
    override fun isSignedInUser(pubkey: String): Boolean =
        signedInPubkey != null && signedInPubkey.equals(pubkey, ignoreCase = true)
    override suspend fun searchLocalProfiles(query: String, limit: Int): List<UserProfile> = emptyList()
    override fun saveProfile(profile: UserProfile) = Unit
    override fun observeProfile(pubkey: String): Flow<UserProfile?> = flowOf(cachedProfile)
    override fun getRelayList(pubkey: String): RelayListMetadata? = cachedRelayList
    override fun saveRelayList(relayList: RelayListMetadata) = Unit
    override fun discoverRelayHints(relayUrls: List<String>) = Unit
    override fun getDmRelayList(pubkey: String): DmRelayList? = cachedDmRelayList
    override fun saveDmRelayList(dmRelayList: DmRelayList) = Unit
    override fun getServerList(pubkey: String): UserServerList? = cachedServerList
    override fun saveServerList(serverList: UserServerList) = Unit
    override fun saveSearchRelaysList(list: SearchRelaysList) = Unit
    override fun saveIndexRelaysList(list: IndexRelaysList) = Unit
    override fun observeSearchRelaysList(pubkey: String): Flow<SearchRelaysList?> = flowOf(null)
    override fun observeIndexRelaysList(pubkey: String): Flow<IndexRelaysList?> = flowOf(null)
    override suspend fun applyDecryptedSearchRelays(pubkey: String, relayUrls: Set<String>) = Unit
    override suspend fun applyDecryptedIndexRelays(pubkey: String, relayUrls: Set<String>) = Unit
    override fun wasRelayListContentApplied(encryptedContent: String): Boolean = false
    override fun markRelayListContentApplied(encryptedContent: String) = Unit

    override fun clearAll() {
        clearAllCalls += 1
        if (failClearAll) throw IllegalStateException("user repo boom")
    }

    override val profileFlow: SharedFlow<UserProfile> = profileMutableFlow
    override suspend fun isProfileFresh(pubkey: String): Boolean = false

    suspend fun emitProfile(profile: UserProfile) {
        profileMutableFlow.emit(profile)
    }
}
