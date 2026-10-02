package com.umbra.app.data.repository

import com.umbra.app.data.repository.cache.OwnerTagSetCache
import com.umbra.app.domain.nip51.MuteList
import com.umbra.app.domain.nip51.muteListOf
import com.umbra.app.domain.usecase.DecryptOwnListItemsUseCase
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-51 mute list (kind 10000), synced across the owner's clients instead of staying
 * device-local.
 *
 * The signed-in user's own list is read whole from their archive: people, hashtags, words and
 * threads, public and private (once the signer lets Umbra read them; this never makes it ask).
 * Anyone else's is only ever needed for its public people, kept by the shared OwnerTagSetCache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MuteListRepositoryImpl @Inject constructor(
    private val userPreferences: UserPreferences,
    private val eventRepository: EventRepository,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase
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

    override fun getMuteList(pubkey: String): Flow<MuteList?> {
        val owner = pubkey.lowercase()
        return if (owner == signedInPubkey()) ownMuteList(owner) else cache.observe(pubkey)
    }

    private fun ownMuteList(owner: String): Flow<MuteList?> =
        combine(
            eventRepository.observeEventsByPubkeyAndKind(owner, Event.KIND_MUTED_USERS, 1),
            decryptOwnListItems.unlocks
        ) { events, _ -> events.firstOrNull() }
            .mapLatest { event -> event?.let { muteListOf(it, decryptOwnListItems(it.content, interactive = false)) } }
            .distinctUntilChanged()

    override suspend fun getCurrentMutedPubkeys(): Set<String> = withContext(Dispatchers.IO) {
        val owner = signedInPubkey() ?: return@withContext emptySet()
        ownMuteList(owner).first()?.mutedPubkeys.orEmpty()
    }

    private fun signedInPubkey(): String? = userPreferences.getPublicKey()?.takeIf { it.length == 64 }?.lowercase()

    override fun clearAll() = cache.clearAll()

    override fun trimMemory() = cache.trimToOwner(userPreferences.getPublicKey())

    override fun cachedOwnerCount(): Int = cache.cachedOwnerCount()
}
