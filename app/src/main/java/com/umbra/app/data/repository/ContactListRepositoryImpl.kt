package com.umbra.app.data.repository

import com.umbra.app.data.repository.cache.OwnerTagSetCache
import com.umbra.app.domain.nip02.ContactList
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactListRepositoryImpl @Inject constructor(
    private val userPreferences: UserPreferences,
    private val eventRepository: EventRepository
) : ContactListRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = OwnerTagSetCache(
        kind = Event.KIND_CONTACT_LIST,
        tagName = "p",
        scope = repositoryScope,
        eventRepository = eventRepository,
        build = { owner, values, updatedAt -> ContactList(ownerPubkey = owner, followedPubkeys = values, updatedAt = updatedAt) },
        ownerOf = { it.ownerPubkey },
        updatedAtOf = { it.updatedAt }
    )

    init {
        cache.startCollecting()
        cache.startOwnerSync(userPreferences.getPublicKeyFlow())
    }

    override fun getContactList(pubkey: String): Flow<ContactList?> = cache.observe(pubkey)

    override suspend fun follow(pubkey: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerPubkey = currentUserPubkeyOrThrow()
            val target = pubkey.lowercase()
            val current = cache.resolve(ownerPubkey)
            val updated = current.copy(
                followedPubkeys = current.followedPubkeys + target,
                updatedAt = System.currentTimeMillis() / 1000
            )
            cache.updateCache(updated)
        }
    }

    override suspend fun unfollow(pubkey: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerPubkey = currentUserPubkeyOrThrow()
            val target = pubkey.lowercase()
            val current = cache.resolve(ownerPubkey)
            val updated = current.copy(
                followedPubkeys = current.followedPubkeys - target,
                updatedAt = System.currentTimeMillis() / 1000
            )
            cache.updateCache(updated)
        }
    }

    override suspend fun getCurrentFollowedPubkeys(): Set<String> = withContext(Dispatchers.IO) {
        val owner = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase() ?: return@withContext emptySet()
        cache.resolve(owner).followedPubkeys
    }

    override fun clearAll() = cache.clearAll()

    override fun trimMemory() = cache.trimToOwner(userPreferences.getPublicKey())

    override fun cachedOwnerCount(): Int = cache.cachedOwnerCount()

    private fun currentUserPubkeyOrThrow(): String {
        val owner = userPreferences.getPublicKey()?.takeIf { it.length == 64 }
            ?: throw IllegalStateException("No authenticated user")
        return owner.lowercase()
    }
}
