package com.umbra.app.data.repository

import com.umbra.app.data.repository.cache.OwnerTagSetCache
import com.umbra.app.domain.nip51.MuteList
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-51 public mute list (kind 10000). Mirrors ContactListRepositoryImpl's ingestion/publish
 * pattern (via the shared OwnerTagSetCache) so mutes are synced across the owner's clients
 * instead of staying device-local.
 */
@Singleton
class MuteListRepositoryImpl @Inject constructor(
    private val userPreferences: UserPreferences,
    private val eventRepository: EventRepository
) : MuteListRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = OwnerTagSetCache(
        kind = Event.KIND_MUTED_USERS,
        tagName = "p",
        scope = repositoryScope,
        eventRepository = eventRepository,
        build = { owner, values, updatedAt -> MuteList(ownerPubkey = owner, mutedPubkeys = values, updatedAt = updatedAt) },
        ownerOf = { it.ownerPubkey },
        updatedAtOf = { it.updatedAt }
    )

    init {
        cache.startCollecting()
        cache.startOwnerSync(userPreferences.getPublicKeyFlow())
    }

    override fun getMuteList(pubkey: String): Flow<MuteList?> = cache.observe(pubkey)

    override suspend fun mute(pubkey: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerPubkey = currentUserPubkeyOrThrow()
            val target = pubkey.lowercase()
            val current = cache.resolve(ownerPubkey)
            val updated = current.copy(
                mutedPubkeys = current.mutedPubkeys + target,
                updatedAt = System.currentTimeMillis() / 1000
            )
            cache.updateCache(updated)
        }
    }

    override suspend fun unmute(pubkey: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerPubkey = currentUserPubkeyOrThrow()
            val target = pubkey.lowercase()
            val current = cache.resolve(ownerPubkey)
            val updated = current.copy(
                mutedPubkeys = current.mutedPubkeys - target,
                updatedAt = System.currentTimeMillis() / 1000
            )
            cache.updateCache(updated)
        }
    }

    override suspend fun getCurrentMutedPubkeys(): Set<String> = withContext(Dispatchers.IO) {
        val owner = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase() ?: return@withContext emptySet()
        cache.resolve(owner).mutedPubkeys
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
