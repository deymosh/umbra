package com.umbra.app.data.repository

import com.umbra.app.data.repository.cache.OwnerTagSetCache
import com.umbra.app.domain.nip51.PinList
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.PinListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-51 public pin list (kind 10001). Mirrors ContactListRepositoryImpl/MuteListRepositoryImpl's
 * ingestion/publish pattern (via the shared OwnerTagSetCache), but tracks event ids via "e" tags
 * instead of pubkeys via "p" tags.
 */
@Singleton
class PinListRepositoryImpl @Inject constructor(
    private val userPreferences: UserPreferences,
    private val eventRepository: EventRepository
) : PinListRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = OwnerTagSetCache(
        kind = Event.KIND_PINNED_EVENTS,
        tagName = "e",
        scope = repositoryScope,
        eventRepository = eventRepository,
        build = { owner, values, updatedAt -> PinList(ownerPubkey = owner, pinnedEventIds = values, updatedAt = updatedAt) },
        ownerOf = { it.ownerPubkey },
        updatedAtOf = { it.updatedAt }
    )

    init {
        cache.startCollecting()
        cache.startOwnerSync(userPreferences.getPublicKeyFlow())
    }

    override fun getPinList(pubkey: String): Flow<PinList?> = cache.observe(pubkey)

    override suspend fun pin(eventId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerPubkey = currentUserPubkeyOrThrow()
            val target = eventId.lowercase()
            val current = cache.resolve(ownerPubkey)
            val updated = current.copy(
                pinnedEventIds = current.pinnedEventIds + target,
                updatedAt = System.currentTimeMillis() / 1000
            )
            cache.updateCache(updated)
        }
    }

    override suspend fun unpin(eventId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerPubkey = currentUserPubkeyOrThrow()
            val target = eventId.lowercase()
            val current = cache.resolve(ownerPubkey)
            val updated = current.copy(
                pinnedEventIds = current.pinnedEventIds - target,
                updatedAt = System.currentTimeMillis() / 1000
            )
            cache.updateCache(updated)
        }
    }

    override suspend fun isPinned(eventId: String): Boolean = withContext(Dispatchers.IO) {
        val owner = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase() ?: return@withContext false
        cache.resolve(owner).pinnedEventIds.contains(eventId.lowercase())
    }

    override suspend fun getCurrentPinnedEventIds(): Set<String> = withContext(Dispatchers.IO) {
        val owner = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase() ?: return@withContext emptySet()
        cache.resolve(owner).pinnedEventIds
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
