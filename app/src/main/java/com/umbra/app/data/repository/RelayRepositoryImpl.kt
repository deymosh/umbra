package com.umbra.app.data.repository

import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.DefaultRelays
import com.umbra.app.domain.relay.RelayIdGenerator
import com.umbra.app.data.db.dao.RelayDao
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.db.mapper.toDomains
import com.umbra.app.data.db.mapper.toEntity
import com.umbra.app.data.db.mapper.toEntities
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Room-based implementation of RelayRepository with SSoT architecture.
 * All relay state is persisted to Room database and flows through relayDao.
 */
@Singleton
class RelayRepositoryImpl @Inject constructor(
    @Named("encrypted") private val relayDao: RelayDao
) : RelayRepository {

    companion object {
        private const val TAG = "UmbraRelayRepo"
        // How long the shared upstream (Room query + JSON remap) stays alive after the last
        // subscriber leaves before being torn down — long enough to survive a quick navigation
        // hop between screens that all observe getAllRelays() (Relay Config <-> Relay Details
        // <-> Active Subscriptions, or a ViewModel recreated across a config change) without
        // paying a fresh query+remap, short enough not to keep it running indefinitely once
        // nothing is actually observing it.
        private const val SHARED_RELAYS_STOP_TIMEOUT_MS = 5_000L
    }

    private val logger = UmbraLog.tag(TAG)

    // Guards only bootstrapDefaultsOnFirstLogin's read-then-write (countRelays() check +
    // insertRelays() seed) against a second concurrent first-login bootstrap — the compound
    // operation an app-level lock actually needs to cover. Single-statement writes (addRelay,
    // updateRelay, removeRelay, clearUserRelayConfig) don't take this lock: Room already
    // serializes writes to the single SQLite connection internally, so wrapping one DAO call in
    // an extra Mutex added a suspension point without adding correctness — and most call sites
    // (UserRepositoryImpl's applyXToLocalConfig methods) already hold their own coarser
    // relayConfigMutex before calling in here, so it was frequently a redundant second lock per
    // write.
    private val relaysMutex = Mutex()
    // Own scope, not tied to any single caller's lifecycle — this repository is a @Singleton and
    // NostrSessionManager alone keeps a live subscription for the whole app session, so the
    // shared flow below effectively lives for as long as the process does anyway.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // shareIn: with a large discovered-relay pool (can reach the hundreds), getAllRelays() is
    // independently collected by several long-lived subscribers at once — NostrSessionManager
    // (whole app session), FeedViewModel (feed visible), ProfileViewModel (three separate
    // combine()s while the profile tab is open), RelayConfigViewModel (relay screens). Each was
    // its own cold Flow collection, so every single write to the relays table (e.g. one of the
    // many staggered per-relay NIP-11 background refreshes) re-ran Room's query AND RelayMapper's
    // full entity->domain remap once per active collector — redundant work multiplied by however
    // many screens/services happened to be alive at once. shareIn turns that into one Room query
    // and one remap pass per write, replayed to every collector. RelayMapper's own relayInfo
    // decode cache (keyed by the raw JSON string) still matters on top of this — it's what keeps
    // even that single remap pass cheap when most rows' NIP-11 doc hasn't changed since last time.
    //
    // Must be a single val (not re-derived inside getAllRelays()'s body) — shareIn() starts a new
    // independent upstream collection + SharedFlow every time it's called, so computing this
    // pipeline fresh per call would silently recreate one cold subscription per caller again,
    // defeating the whole point.
    private val allRelaysFlow: Flow<List<Relay>> =
        relayDao.getAllRelays()
            .map { entities -> entities.toDomains() }
            .flowOn(Dispatchers.IO)
            .shareIn(
                scope = repoScope,
                started = SharingStarted.WhileSubscribed(SHARED_RELAYS_STOP_TIMEOUT_MS),
                replay = 1
            )

    /**
     * SSoT: getAllRelays() directly returns Room Flow converted to domain models.
     * Invalidation happens automatically when relayDao updates the underlying table.
     */
    override fun getAllRelays(): Flow<List<Relay>> = allRelaysFlow

    override suspend fun getRelayById(id: String): Relay? =
        relayDao.getRelayById(id)?.toDomain()

    override suspend fun addRelay(relay: Relay) {
        relayDao.insertRelay(relay.toEntity())
    }

    override suspend fun updateRelay(relay: Relay) {
        relayDao.updateRelay(relay.toEntity())
    }

    override suspend fun removeRelay(id: String) {
        relayDao.deleteRelayById(id)
    }

    override suspend fun bootstrapDefaultsOnFirstLogin() {
        relaysMutex.withLock {
            // If Room already has relays, skip
            if (relayDao.countRelays() > 0) return@withLock

            val defaultSet = buildFirstLoginRelaySet()
            relayDao.insertRelays(defaultSet.toEntities())
            logger.d { "Seeded default relays for first login" }
        }
    }

    private fun buildFirstLoginRelaySet(): List<Relay> {
        val baseTime = System.currentTimeMillis()
        return DefaultRelays.DEFAULT_RELAYS.mapIndexed { index, relay ->
            val seed = baseTime + index
            relay.copy(
                id = RelayIdGenerator.create(seed),
                // Pure DISCOVER, not a real outbox/inbox declaration — isReadEnabled/isWriteEnabled
                // exclusively reflect the user's own genuine kind:10002 relay list (see
                // UserRepositoryImpl.applyRelayListToLocalConfig). These bootstrap relays stay
                // usable for feed/inbox/search reads via isDiscovered (see canApplyChannelToRelay)
                // until the user's real kind:10002 arrives or they configure relays themselves —
                // but publishing requires at least one relay with write explicitly enabled.
                isReadEnabled = false,
                isReadActive = false,
                isWriteEnabled = false,
                isWriteActive = false,
                isDmEnabled = false,
                isDmActive = false,
                dmRequiresAuth = false,
                isEnabled = true,
                isDiscovered = true,
                addedAtMillis = seed
            )
        }
    }

    override suspend fun clearUserRelayConfig() {
        relayDao.deleteAll()
    }

}

