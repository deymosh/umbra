package com.umbra.app.data.repository

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.umbra.app.data.db.EncryptedUmbraDatabase
import com.umbra.app.data.db.dao.EventDao
import com.umbra.app.data.db.dao.EventTagDao
import com.umbra.app.data.db.dao.FeedFilterDao
import com.umbra.app.data.db.dao.ReactionEmojiDao
import com.umbra.app.data.db.dao.RelayDao
import com.umbra.app.data.db.dao.UserProfileDao
import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.entities.EventTagEntity
import com.umbra.app.data.db.pojo.EventIdTimestamp
import com.umbra.app.data.db.pojo.NoteWithProfile
import com.umbra.app.data.nostr.BackfillAnchorClearer
import com.umbra.app.data.nostr.NostrClient
import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip51.IndexRelaysList
import com.umbra.app.domain.nip51.SearchRelaysList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.nip77.SyncDirection
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.preferences.SyncPreferences
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-cycle relay-to-ingest-to-cache-to-feed integration test — the previously missing test this
 * codebase never had: no existing test constructs a real [EventRepositoryImpl].
 * `EventRepositoryPublishTest`/`NegentropySyncOrchestratorTest`'s `FakeNostrClient`s each
 * reimplement a slice of the relay-filter logic inline against a fake client; neither instantiates
 * the facade itself.
 *
 * Scope: synthetic relay messages in at [subscribeToEvents], assertions on cache state
 * ([EventRepositoryImpl.getEventById]/[EventRepositoryImpl.getEventRelays]) and on notes emitted
 * from [EventRepositoryImpl.observeFeedNotes]/[EventRepositoryImpl.getCachedEvents] — the full
 * relay-to-feed cycle. Every synthetic event in this file is authored by a throwaway keypair that
 * is never the harness's "signed-in user," so the debounced encrypted-archive write path
 * ([EventIngestCache.scheduleInsert] -> `RoomOwnEventArchive.writeBatch` ->
 * `EncryptedUmbraDatabase.withTransaction`) is never reached — see [TestEncryptedUmbraDatabase]'s
 * doc comment for why that scoping is what makes an all-throwing database stub safe here.
 *
 * ## Harness construction
 *
 * `EventRepositoryImpl`'s constructor takes `@Named("encrypted") EncryptedUmbraDatabase`, a real
 * `RoomDatabase` subclass. This project runs no Robolectric, so an all-throwing stub subclass of
 * that abstract class needed to be proven constructible on the plain JVM `testDebugUnitTest`
 * classpath. It is: [TestEncryptedUmbraDatabase] constructs without error — `RoomDatabase`'s own
 * base constructor does no Android-framework work, and every DAO accessor is overridden to return
 * this file's own fakes rather than ever touching `createOpenHelper`/`createInvalidationTracker`,
 * which stay throwing stubs. No constructor-narrowing was needed for `EncryptedUmbraDatabase`
 * itself (unlike `OwnEventArchive`, which narrows the transaction boundary specifically because
 * `scheduleInsert`'s debounced write needs to run inside an *actually working* transaction in
 * `EventIngestCacheTest` — that concern doesn't apply here, since this test's scenario never
 * reaches `writeBatch` at all).
 *
 * `BackfillAnchorStore` (the seventh constructor parameter) is a concrete, non-`open` class whose
 * constructor eagerly builds a `SecurePreferences` instance — itself backed by a real Android
 * `Context`/`SharedPreferences`/`AndroidKeyStore` cipher, none of which exist in a plain JVM unit
 * test. `EventRepositoryImpl`'s only call site for it is `clearBackfillAnchors(pubkey) ->
 * backfillAnchorStore.clear(pubkey)` (see [EventRepositoryImpl.clearBackfillAnchors], never called
 * by this test's scenario) — so, mirroring this package's own `OwnEventArchive`/
 * `NegentropyEventSource` narrow-interface precedent, the constructor parameter's type was widened
 * to a new `BackfillAnchorClearer` interface (`data/nostr/BackfillAnchorStore.kt`) that
 * `BackfillAnchorStore` now implements, with a `@Binds` mapping added to `RepositoryModule` —
 * production code wasn't narrowable through the test harness any other way.
 */
class EventRepositoryIngestionIntegrationTest {

    // ---------------------------------------------------------------------------------------
    // Fakes — one per dependency, file-private, per this codebase's per-file fake convention
    // (TESTING.md; no shared fake library, no Mockito/mockk/AssertJ).
    // ---------------------------------------------------------------------------------------

    /**
     * [NostrClient] fake — [eventFlow] is the one real, test-controllable flow: the relay-message
     * entry point [subscribeToEvents] collects. The `init{}` block collects [reqFlow]/
     * [subscriptionEventFlow]/[eoseFlow]/[relayOpenedFlow] synchronously at construction, so those
     * four must also be real (empty) flows rather than throwers — a throwing property fails
     * construction itself, not just an assertion later. Every other member mirrors
     * `EventRepositoryPublishTest`'s existing `FakeNostrClient` shape.
     */
    private class FakeNostrClient : NostrClient {
        val eventFlowBacking = MutableSharedFlow<Pair<String, Event>>(extraBufferCapacity = 64)
        val applyChannelCalls = mutableListOf<Triple<String, String, List<EventFilter>>>()

        override val eventFlow: kotlinx.coroutines.flow.SharedFlow<Pair<String, Event>> = eventFlowBacking
        override val reqFlow = MutableSharedFlow<com.umbra.app.domain.relay.RelayRequestInfo>()
        override val subscriptionEventFlow = MutableSharedFlow<Pair<String, String>>()
        override val eoseFlow = MutableSharedFlow<com.umbra.app.domain.nip67.EoseSignal>()
        override val countFlow get() = throw NotImplementedError()
        override val negMessageFlow get() = throw NotImplementedError()
        override val relayIssueFlow get() = throw NotImplementedError()
        override val connectedRelayUrlsFlow get() = throw NotImplementedError()
        override val relayOpenedFlow = MutableSharedFlow<String>()
        override val publishResultFlow get() = throw NotImplementedError()

        override fun connect(relayUrl: String) = Result.success(Unit)
        override fun subscribe(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) {}
        override fun negOpen(relayUrl: String, subscriptionId: String, filter: EventFilter, initialMessageHex: String) {}
        override fun negMsg(relayUrl: String, subscriptionId: String, messageHex: String) {}
        override fun negClose(relayUrl: String, subscriptionId: String) {}
        override fun publishEvent(relayUrl: String, event: Event) {}
        override fun publishAuthEvent(relayUrl: String, event: Event) {}
        override suspend fun publishEvent(event: Event) {}
        override suspend fun publishEventToRelays(event: Event, relayUrls: List<String>) {}
        override fun unsubscribe(relayUrl: String, subscriptionId: String) {}
        override fun disconnect(relayUrl: String) {}
        override fun forgetRelay(relayUrl: String) {}
        override fun disconnectAll() {}
        override fun isConnected(relayUrl: String) = false
        override fun hasActiveSocket(relayUrl: String) = false
        override fun isThrottled(relayUrl: String) = false
        override fun isReqUnsupported(relayUrl: String) = false
        override fun requiresSearchFilter(relayUrl: String) = false
        override fun isSubscriptionLimited(relayUrl: String) = false
        override fun isNegentropyUnsupported(relayUrl: String) = false
        override fun rejectsSubIdReuse(relayUrl: String) = false
        override fun applyChannel(channelId: String, relayUrl: String, filters: List<EventFilter>): Boolean {
            applyChannelCalls.add(Triple(channelId, relayUrl, filters))
            return false
        }
        override fun currentSubscriptionId(relayUrl: String, channelId: String): String? = null
        override fun subscribedChannelCount(relayUrl: String) = 0
        override fun resolveChannelId(relayUrl: String, subscriptionId: String): String? = null
        override fun subscriptionsForChannel(channelId: String): Set<Pair<String, String>> = emptySet()
        override fun clearChannelSubscription(relayUrl: String, channelId: String): String? = null
        override fun registerTrackedSubscription(relayUrl: String, channelId: String, filters: List<EventFilter>): String = ""
        override fun unregisterTrackedSubscription(relayUrl: String, channelId: String): String? = null
        override fun resetSubscriptionBookkeeping() {}
        override fun resetFailureCount(relayUrl: String) {}
        override fun resetAllBackoff() {}
    }

    /**
     * [EventDao] fake — [observeRecentEvents] always serves an empty own-archive flow (this
     * scenario never authors an own-user event, so the own-archive half of the feed merge is
     * always empty by design, not by omission). Write methods ([insertEvents]/[insertEvent])
     * record every call so a test can assert zero own-archive writes ever happened.
     */
    private class FakeEventDao : EventDao {
        val insertEventsCalls = mutableListOf<List<EventEntity>>()
        val insertEventCalls = mutableListOf<EventEntity>()
        val deleteEventByIdCalls = mutableListOf<String>()
        val eventsById = mutableMapOf<String, EventEntity>()
        var latestAddressableEvent: EventEntity? = null

        override fun observeRecentEvents(limit: Int): Flow<List<EventEntity>> = flowOf(emptyList())
        override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): EventEntity? =
            latestAddressableEvent
        override suspend fun deleteSupersededReplaceableEvents(kind: Int, pubkey: String, identifier: String): Int = 0
        override suspend fun insertEvents(events: List<EventEntity>) {
            insertEventsCalls.add(events)
        }
        override suspend fun insertEvent(event: EventEntity) {
            insertEventCalls.add(event)
        }
        override suspend fun deleteAll() {}
        override suspend fun deleteEventById(eventId: String) {
            deleteEventByIdCalls.add(eventId)
        }
        override fun observeEventsByPubkeyAndKind(pubkey: String, kind: Int, limit: Int): Flow<List<EventEntity>> =
            flowOf(emptyList())
        override fun observeOwnReposts(pubkey: String, limit: Int): Flow<List<EventEntity>> = flowOf(emptyList())
        override fun observeCountEventsByPubkeyAndKind(pubkey: String, kind: Int): Flow<Int> = flowOf(0)
        override suspend fun getEventsByIds(ids: List<String>): List<EventEntity> = ids.mapNotNull { eventsById[it] }
        override suspend fun getEventsReferencingIds(targetIds: List<String>): List<EventEntity> = emptyList()
        override suspend fun getEventById(id: String): EventEntity? = eventsById[id]
        override suspend fun getOldestTimestampByPubkeyAndKind(pubkey: String, kind: Int): Long? = null
        override suspend fun countEvents(): Int = eventsById.size
        override suspend fun searchEvents(
            kind: Int?,
            pubkey: String?,
            contentQuery: String?,
            limit: Int,
            offset: Int
        ): List<EventEntity> = emptyList()
        override suspend fun getOldestInboxNoteTimestamp(pubkey: String): Long? = null
        override suspend fun getOldestInboxReactionTimestamp(pubkey: String): Long? = null
        override suspend fun getNewestTimestampByKind(kind: Int): Long? = null
        override suspend fun getEventIdsAndTimestampsByPubkey(pubkey: String, kinds: Set<Int>): List<EventIdTimestamp> =
            emptyList()
        override fun observeProfileNotes(pubkey: String, kind: Int, limit: Int): Flow<List<NoteWithProfile>> =
            flowOf(emptyList())
    }

    /** [EventTagDao] fake — recording no-ops, matching this file's other per-dependency fakes. */
    private class FakeEventTagDao : EventTagDao {
        val insertTagsCalls = mutableListOf<List<EventTagEntity>>()
        val deleteTagsForEventCalls = mutableListOf<String>()
        override suspend fun insertTags(tags: List<EventTagEntity>) {
            insertTagsCalls.add(tags)
        }
        override suspend fun deleteTagsForEvent(eventId: String) {
            deleteTagsForEventCalls.add(eventId)
        }
        override suspend fun deleteTagsForEvents(eventIds: List<String>) {}
        override suspend fun deleteAll() {}
        override suspend fun countTags(): Int = 0
    }

    /**
     * Stub subclass of the real, abstract [EncryptedUmbraDatabase] (Path A — see this file's class
     * doc comment). DAO accessors this scenario actually reaches return this file's fakes;
     * everything else — including every `RoomDatabase`-internal member this scenario must never
     * touch (a real transaction would fail without a working `createOpenHelper`) — throws.
     */
    private class TestEncryptedUmbraDatabase(
        private val eventDaoInstance: EventDao,
        private val eventTagDaoInstance: EventTagDao
    ) : EncryptedUmbraDatabase() {
        override fun eventDao(): EventDao = eventDaoInstance
        override fun eventTagDao(): EventTagDao = eventTagDaoInstance
        override fun userProfileDao(): UserProfileDao = throw NotImplementedError()
        override fun relayDao(): RelayDao = throw NotImplementedError()
        override fun feedFilterDao(): FeedFilterDao = throw NotImplementedError()
        override fun reactionEmojiDao(): ReactionEmojiDao = throw NotImplementedError()
        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            throw NotImplementedError()
        override fun createInvalidationTracker(): InvalidationTracker = throw NotImplementedError()
        override fun clearAllTables() = throw NotImplementedError()
    }

    /**
     * [UserRepository] fake — no-ops the six `save*` methods [subscribeToEvents]'s pipeline calls
     * inside `runCatching`, serves [getProfiles] from a settable in-memory map (the feed-emission
     * half of the cycle needs author profiles), throws on the rest.
     */
    private class FakeUserRepository : UserRepository {
        private val profiles = mutableMapOf<String, UserProfile>()

        fun putProfile(profile: UserProfile) {
            profiles[profile.pubkey.lowercase()] = profile
        }

        override suspend fun getProfile(pubkey: String): UserProfile? = profiles[pubkey.lowercase()]
        override suspend fun getProfiles(pubkeys: List<String>): List<UserProfile> =
            pubkeys.mapNotNull { profiles[it.lowercase()] }
        override fun isSignedInUser(pubkey: String): Boolean = false
        override suspend fun searchLocalProfiles(query: String, limit: Int): List<UserProfile> = emptyList()
        override fun saveProfile(profile: UserProfile) {
            putProfile(profile)
        }
        override fun observeProfile(pubkey: String) = throw NotImplementedError()
        override fun getRelayList(pubkey: String): RelayListMetadata? = null
        override fun saveRelayList(relayList: RelayListMetadata) {}
        override fun discoverRelayHints(relayUrls: List<String>) {}
        override fun getDmRelayList(pubkey: String): DmRelayList? = null
        override fun saveDmRelayList(dmRelayList: DmRelayList) {}
        override fun getServerList(pubkey: String): UserServerList? = null
        override fun saveServerList(serverList: UserServerList) {}
        override fun saveSearchRelaysList(list: SearchRelaysList) {}
        override fun saveIndexRelaysList(list: IndexRelaysList) {}
        override fun observeSearchRelaysList(pubkey: String) = throw NotImplementedError()
        override fun observeIndexRelaysList(pubkey: String) = throw NotImplementedError()
        override suspend fun applyDecryptedSearchRelays(pubkey: String, relayUrls: Set<String>) {}
        override suspend fun applyDecryptedIndexRelays(pubkey: String, relayUrls: Set<String>) {}
        override fun wasRelayListContentApplied(encryptedContent: String): Boolean = false
        override fun markRelayListContentApplied(encryptedContent: String) {}
        override fun clearAll() {}
        override val profileFlow = MutableSharedFlow<UserProfile>()
        override suspend fun isProfileFresh(pubkey: String): Boolean = false
    }

    /** [UserPreferences] fake — minimal login-state bookkeeping the ingestion/feed paths read. */
    private class FakeUserPreferences : UserPreferences {
        private var pubkey: String? = null
        private val pubkeyFlow = MutableStateFlow<String?>(null)
        override fun savePublicKey(pubkey: String) {
            this.pubkey = pubkey
            pubkeyFlow.value = pubkey
        }
        override fun getPublicKey(): String? = pubkey
        override fun isLoggedIn(): Boolean = pubkey != null
        override fun isAnonymousSession(): Boolean = pubkey == null
        override fun canSignWithAmber(): Boolean = false
        override fun logout() {
            pubkey = null
            pubkeyFlow.value = null
        }
        override fun clearAll() {
            pubkey = null
            pubkeyFlow.value = null
        }
        override fun getPublicKeyFlow(): StateFlow<String?> = pubkeyFlow
    }

    /** [BackfillAnchorClearer] fake — records calls; never asserted on by this scenario since
     * [EventRepositoryImpl.clearBackfillAnchors] is never called by the ingest/cache/feed cycle. */
    private class FakeBackfillAnchorClearer : BackfillAnchorClearer {
        val clearedPubkeys = mutableListOf<String>()
        override fun clear(pubkey: String) {
            clearedPubkeys.add(pubkey)
        }
    }

    /** [SyncPreferences] fake — returns a fixed default; NIP-77 sync never fires within this
     * suite's real-wait windows (its debounce is 5 real seconds, far longer than any wait here). */
    private class FakeSyncPreferences : SyncPreferences {
        private val direction = MutableStateFlow(SyncDirection.BOTH)
        override fun getNegentropySyncDirection(): SyncDirection = direction.value
        override fun setNegentropySyncDirection(direction: SyncDirection) {
            this.direction.value = direction
        }
        override fun observeNegentropySyncDirection(): StateFlow<SyncDirection> = direction
    }

    // ---------------------------------------------------------------------------------------
    // Harness builder
    // ---------------------------------------------------------------------------------------

    private class Harness(
        val repository: EventRepositoryImpl,
        val nostrClient: FakeNostrClient,
        val eventDao: FakeEventDao,
        val eventTagDao: FakeEventTagDao,
        val userRepository: FakeUserRepository,
        val userPreferences: FakeUserPreferences,
        val backfillAnchorClearer: FakeBackfillAnchorClearer
    )

    /** Assembles a real [EventRepositoryImpl] against this file's fakes — see class doc comment
     * for the two harness-construction findings (Path A database stub, [BackfillAnchorClearer]
     * seam) that make this possible. */
    private fun buildHarness(): Harness {
        val nostrClient = FakeNostrClient()
        val eventDao = FakeEventDao()
        val eventTagDao = FakeEventTagDao()
        val userRepository = FakeUserRepository()
        val userPreferences = FakeUserPreferences()
        val backfillAnchorClearer = FakeBackfillAnchorClearer()
        val database = TestEncryptedUmbraDatabase(eventDao, eventTagDao)
        val repository = EventRepositoryImpl(
            nostrClient = nostrClient,
            encryptedDatabase = database,
            encryptedEventDao = eventDao,
            encryptedEventTagDao = eventTagDao,
            userPreferences = userPreferences,
            userRepository = userRepository,
            backfillAnchorStore = backfillAnchorClearer,
            syncPreferences = FakeSyncPreferences()
        )
        return Harness(repository, nostrClient, eventDao, eventTagDao, userRepository, userPreferences, backfillAnchorClearer)
    }

    // ---------------------------------------------------------------------------------------
    // BIP-340 Schnorr signing for synthetic test events.
    //
    // Production code has no signing implementation to reuse: Umbra signs exclusively through
    // Amber (external signer) — nsec never touches the device (see AUDIT.md / CLAUDE.md's
    // "Absolute constraints"). `EventCrypto` (data/crypto/EventCrypto.kt) implements verification
    // and event-id computation only — there is no existing BIP-340 signing path anywhere in this
    // codebase to reuse — so this test implements BIP-340 signing itself, using the same
    // BouncyCastle secp256k1 curve
    // parameters `EventCrypto.verifySignature` already uses, purely to produce a genuinely valid
    // signature `EventCrypto.verifyEvent` accepts for real. Every keypair here is generated at
    // runtime with `SecureRandom`, is never logged or asserted on, and never leaves this file.
    // ---------------------------------------------------------------------------------------

    private data class TestKeypair(val privateKey: BigInteger, val pubkeyHex: String)

    private object Bip340 {
        private val curveParams = CustomNamedCurves.getByName("secp256k1")
        private val order = curveParams.n
        private val secureRandom = SecureRandom()

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun taggedHash(tag: String, data: ByteArray): ByteArray {
            val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
            return sha256(tagHash + tagHash + data)
        }

        private fun BigInteger.toBytes32(): ByteArray {
            val raw = toByteArray()
            val trimmed = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
            return if (trimmed.size == 32) trimmed else ByteArray(32 - trimmed.size) + trimmed
        }

        private fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

        /** Generates a fresh throwaway keypair whose x-only pubkey has the BIP-340-required even
         * y-coordinate (flipping the scalar if the raw random draw landed on the odd-y point). */
        fun generateKeypair(): TestKeypair {
            var d: BigInteger
            do {
                val raw = ByteArray(32).also { secureRandom.nextBytes(it) }
                d = BigInteger(1, raw).mod(order)
            } while (d.signum() == 0)
            var point = curveParams.g.multiply(d).normalize()
            if (point.affineYCoord.toBigInteger().testBit(0)) {
                d = order.subtract(d)
                point = curveParams.g.multiply(d).normalize()
            }
            val pubkeyHex = point.affineXCoord.toBigInteger().toBytes32().toHex()
            return TestKeypair(d, pubkeyHex)
        }

        /** BIP-340 Schnorr signature over [message32] (32 bytes) using [privateKey] — assumes
         * [privateKey] already corresponds to an even-y point, guaranteed by [generateKeypair]. */
        fun sign(privateKey: BigInteger, message32: ByteArray): String {
            require(message32.size == 32) { "message must be exactly 32 bytes" }
            val pubPoint = curveParams.g.multiply(privateKey).normalize()
            val pubkeyBytes = pubPoint.affineXCoord.toBigInteger().toBytes32()
            val auxRand = ByteArray(32).also { secureRandom.nextBytes(it) }
            val t = xor(privateKey.toBytes32(), taggedHash("BIP0340/aux", auxRand))
            val nonceHash = taggedHash("BIP0340/nonce", t + pubkeyBytes + message32)
            var k = BigInteger(1, nonceHash).mod(order)
            check(k.signum() != 0) { "generated nonce was zero" }
            var rPoint = curveParams.g.multiply(k).normalize()
            if (rPoint.affineYCoord.toBigInteger().testBit(0)) {
                k = order.subtract(k)
                rPoint = curveParams.g.multiply(k).normalize()
            }
            val rBytes = rPoint.affineXCoord.toBigInteger().toBytes32()
            val challengeHash = taggedHash("BIP0340/challenge", rBytes + pubkeyBytes + message32)
            val e = BigInteger(1, challengeHash).mod(order)
            val sBytes = k.add(e.multiply(privateKey)).mod(order).toBytes32()
            return (rBytes + sBytes).toHex()
        }
    }

    /** Produces a genuinely valid signed [Event]: real event id ([EventCrypto.computeEventId]),
     * real BIP-340 signature over it, for a caller-supplied or freshly-generated [keypair]. Every
     * event this test expects to survive verification/ingest is built via this helper —
     * verification is never stubbed, weakened, or bypassed anywhere in this file. */
    private fun signedEvent(
        keypair: TestKeypair = Bip340.generateKeypair(),
        kind: Int,
        createdAt: Long = 1_700_000_000L,
        tags: List<List<String>> = emptyList(),
        content: String = ""
    ): Event {
        val unsigned = Event(
            id = "",
            pubkey = keypair.pubkeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = ""
        )
        val id = EventCrypto.computeEventId(unsigned)
        val sig = Bip340.sign(keypair.privateKey, id.hexToBytes())
        return unsigned.copy(id = id, sig = sig)
    }

    /** Produces an [Event] with a real id but a deliberately invalid signature (64 zero bytes) —
     * for the forged-signature rejection case. [EventCrypto.verifyEvent] must reject it. */
    private fun forgedEvent(
        kind: Int,
        createdAt: Long = 1_700_000_000L,
        tags: List<List<String>> = emptyList(),
        content: String = ""
    ): Event {
        val keypair = Bip340.generateKeypair()
        val unsigned = Event(
            id = "",
            pubkey = keypair.pubkeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = ""
        )
        val id = EventCrypto.computeEventId(unsigned)
        return unsigned.copy(id = id, sig = "0".repeat(128))
    }

    // ---------------------------------------------------------------------------------------
    // Smoke test — proves the harness is real, not merely compiling.
    // ---------------------------------------------------------------------------------------

    /**
     * Synchronization helper every test in this file calls after emitting into
     * [FakeNostrClient.eventFlowBacking] and after any action that schedules work on
     * [EventRepositoryImpl]'s own `repoScope`.
     *
     * `EventRepositoryImpl.repoScope` is a hardcoded `CoroutineScope(SupervisorJob() +
     * Dispatchers.Default)` field, not a constructor parameter — unlike
     * `EventIngestCacheTest`, which constructs `EventIngestCache` directly
     * against its own test-controlled `CoroutineScope` and can therefore use
     * `advanceTimeBy`/`advanceUntilIdle` alone. Driving the *real facade* means every
     * `repoScope.launch { ... }` this test triggers — the 250ms burst-coalescing snapshot emit,
     * the 200ms own-archive insert debounce, and even the per-event
     * `withContext(Dispatchers.Default) { EventCrypto.verifyEvent(event) }` hop inside
     * `subscribeToEvents` itself — runs on a REAL dispatcher `runTest`'s virtual clock has no
     * knowledge of or effect on. `advanceUntilIdle()` alone only drains work already queued on
     * this test's own `TestDispatcher`; it cannot wait for a concurrently-running real thread to
     * finish and re-enqueue its continuation. A real (short, generous) wait is what closes that
     * gap — confirmed empirically (see this file's class doc comment): without it, an
     * emitted event's cache-arrival is a genuine race the test can lose.
     */
    private suspend fun TestScope.quiesce() {
        advanceUntilIdle()
        withContext(Dispatchers.IO) { delay(400) }
        advanceTimeBy(1)
        advanceUntilIdle()
    }

    @Test
    fun `given a valid synthetic event when emitted into the relay flow then it becomes retrievable via getEventById`() = runTest {
        val harness = buildHarness()
        val note = signedEvent(kind = Event.KIND_TEXT_NOTE, content = "hello from a relay")
        assertTrue("signedEvent() must produce a signature EventCrypto.verifyEvent accepts", EventCrypto.verifyEvent(note))

        // Collector for the ingestion entry point — a real EventRepositoryImpl.subscribeToEvents()
        // Flow, started against this test's own (TestScope) coroutine scope.
        val collectJob = launch {
            harness.repository.subscribeToEvents(emptyList()).collect { }
        }
        advanceUntilIdle()

        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()

        assertEquals(note, harness.repository.getEventById(note.id))
        collectJob.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // Full behavior-coverage suite. Every test below builds its
    // own [Harness] (buildHarness()), so no state leaks between cases. Every synthetic event is
    // authored by a throwaway keypair distinct from [activateFreshSession]'s own "signed-in
    // user" keypair, so [EventIngestCache.scheduleInsert]'s current-user gate is never satisfied
    // and the encrypted own-archive is never reached — see the dedicated zero-write test below,
    // which asserts that invariant directly rather than merely relying on it by construction.
    //
    // Every test drives ingestion the same way the smoke test above does: a collector on
    // [EventRepositoryImpl.subscribeToEvents] is started (via [startIngestion]) BEFORE any event
    // is emitted into [FakeNostrClient.eventFlowBacking] — that Flow is cold, so without an
    // active collector no emitted event is ever verified/ingested at all and every downstream
    // assertion would pass vacuously (an event that was never processed is trivially "not
    // cached" and "never in the feed").
    // ---------------------------------------------------------------------------------------

    /** Activates a session with a "signed-in user" pubkey that is never used as the author of
     * any synthetic event in this suite — the scenario constraint that keeps every test
     * on the ingest/cache/feed path and off the encrypted-archive write path. */
    private fun EventRepositoryImpl.activateFreshSession(feedFilter: FeedFilter) {
        val signedInUser = Bip340.generateKeypair()
        activateUserSession(signedInUser.pubkeyHex, feedFilter, emptySet())
    }

    /** Starts (and returns the [Job] for) the real ingestion pipeline — see this section's own
     * doc comment for why every test needs this running before it emits anything.
     *
     * The [advanceUntilIdle] call is not optional: [FakeNostrClient.eventFlowBacking] is a
     * `replay = 0` [MutableSharedFlow] — a value emitted into it before a collector has actually
     * started collecting (as opposed to merely `launch`ed, which only *schedules* the collector
     * on this [TestScope]'s dispatcher) is delivered to nobody and is gone for good, exactly as
     * if the event had never arrived at all. Draining the scheduler here, mirroring the smoke
     * test's own `launch { ... }; advanceUntilIdle()` pairing, guarantees the collector is
     * actually attached before this function returns and the caller starts emitting.
     */
    private fun TestScope.startIngestion(harness: Harness): Job =
        launch { harness.repository.subscribeToEvents(emptyList()).collect { } }.also { advanceUntilIdle() }

    private fun EventRepositoryImpl.observeFeedNotesForTest(): Flow<FeedNotesResult> = observeFeedNotes(
        since = 0L,
        limit = 100,
        authors = emptySet(),
        mutedPubkeys = emptySet(),
        excludedHashtagsLower = emptySet(),
        includeMentions = true,
        hideNsfw = false,
        currentNpub = null,
        currentUserPubkey = null,
        desiredTagsLower = emptySet()
    )

    @Test
    fun `given an event with an invalid signature when emitted then it never reaches the cache or the feed`() = runTest {
        val harness = buildHarness()
        harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
        val ingestJob = startIngestion(harness)

        val collected = mutableListOf<FeedNotesResult>()
        val feedJob = launch { harness.repository.observeFeedNotesForTest().collect { collected.add(it) } }
        quiesce()
        collected.clear()

        val forged = forgedEvent(kind = Event.KIND_TEXT_NOTE, content = "forged content")
        assertTrue("forgedEvent() must produce a signature EventCrypto.verifyEvent rejects", !EventCrypto.verifyEvent(forged))

        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to forged)
        quiesce()

        assertNull("a forged event must never become retrievable from the cache", harness.repository.getEventById(forged.id))
        assertTrue(
            "a forged event must never appear in emitted feed notes",
            collected.none { result -> result.notes.any { it.event.id == forged.id } }
        )
        feedJob.cancel()
        ingestJob.cancel()
    }

    @Test
    fun `given the same valid event delivered from two relays then the cache holds one copy with both relays recorded and the feed shows it once`() =
        runTest {
            val harness = buildHarness()
            harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
            val ingestJob = startIngestion(harness)

            val collected = mutableListOf<FeedNotesResult>()
            val feedJob = launch { harness.repository.observeFeedNotesForTest().collect { collected.add(it) } }
            quiesce()
            collected.clear()

            val note = signedEvent(kind = Event.KIND_TEXT_NOTE, content = "seen twice")
            harness.nostrClient.eventFlowBacking.emit("wss://relay-one.example" to note)
            quiesce()
            harness.nostrClient.eventFlowBacking.emit("wss://relay-two.example" to note)
            quiesce()

            assertEquals(note, harness.repository.getEventById(note.id))
            assertEquals(
                setOf("wss://relay-one.example", "wss://relay-two.example"),
                harness.repository.getEventRelays(note.id)
            )
            assertEquals(1, collected.last().notes.count { it.event.id == note.id })
            feedJob.cancel()
            ingestJob.cancel()
        }

    @Test
    fun `given two revisions of a replaceable slot delivered in ascending timestamp order then only the newer revision is retrievable`() =
        runTest {
            val harness = buildHarness()
            harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
            val ingestJob = startIngestion(harness)
            val author = Bip340.generateKeypair()
            val older = signedEvent(keypair = author, kind = Event.KIND_METADATA, createdAt = 1_000L, content = "{}")
            val newer = signedEvent(keypair = author, kind = Event.KIND_METADATA, createdAt = 2_000L, content = "{}")

            harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to older)
            quiesce()
            harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to newer)
            quiesce()

            assertEquals(newer, harness.repository.getEventById(newer.id))
            assertNull("the superseded older revision must not remain retrievable", harness.repository.getEventById(older.id))
            ingestJob.cancel()
        }

    @Test
    fun `given two revisions of a replaceable slot delivered in descending timestamp order then only the newer revision is retrievable`() =
        runTest {
            val harness = buildHarness()
            harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
            val ingestJob = startIngestion(harness)
            val author = Bip340.generateKeypair()
            val older = signedEvent(keypair = author, kind = Event.KIND_METADATA, createdAt = 1_000L, content = "{}")
            val newer = signedEvent(keypair = author, kind = Event.KIND_METADATA, createdAt = 2_000L, content = "{}")

            harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to newer)
            quiesce()
            harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to older)
            quiesce()

            assertEquals(newer, harness.repository.getEventById(newer.id))
            assertNull("a stale/losing revision must never become retrievable", harness.repository.getEventById(older.id))
            ingestJob.cancel()
        }

    @Test
    fun `given a hashtag exclusion filter then a tagged note is excluded until the same filter no longer excludes it`() = runTest {
        val harness = buildHarness()
        val ingestJob = startIngestion(harness)
        val author = Bip340.generateKeypair()
        val note = signedEvent(keypair = author, kind = Event.KIND_TEXT_NOTE, tags = listOf(listOf("t", "spam")), content = "spam note")

        harness.repository.activateFreshSession(FeedFilter(id = "excluding", name = "excluding", hideNsfw = false, excludedHashtags = setOf("spam")))
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()
        assertNull("a note carrying an excluded hashtag must never reach the cache", harness.repository.getEventById(note.id))

        harness.repository.activateFreshSession(FeedFilter(id = "allowing", name = "allowing", hideNsfw = false, excludedHashtags = emptySet()))
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()
        assertEquals(
            "the same note must reach the cache once the user removes the exclusion — hiding is user-removable, not an app-side rule",
            note,
            harness.repository.getEventById(note.id)
        )
        ingestJob.cancel()
    }

    @Test
    fun `given a muted-author filter then a note from that author is excluded until the pubkey is removed from the muted set`() = runTest {
        val harness = buildHarness()
        val ingestJob = startIngestion(harness)
        val author = Bip340.generateKeypair()
        val note = signedEvent(keypair = author, kind = Event.KIND_TEXT_NOTE, content = "hello from a muted author")

        harness.repository.activateFreshSession(
            FeedFilter(id = "muting", name = "muting", hideNsfw = false, mutedPubkeys = setOf(author.pubkeyHex))
        )
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()
        assertNull("a note from a muted pubkey must never reach the cache", harness.repository.getEventById(note.id))

        harness.repository.activateFreshSession(
            FeedFilter(id = "not-muting", name = "not-muting", hideNsfw = false, mutedPubkeys = emptySet())
        )
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()
        assertEquals(
            "the same note must reach the cache once the user removes the pubkey from the muted set",
            note,
            harness.repository.getEventById(note.id)
        )
        ingestJob.cancel()
    }

    @Test
    fun `given a deletion authored by the same pubkey then the note is evicted from the cache and disappears from the feed`() = runTest {
        val harness = buildHarness()
        harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
        val ingestJob = startIngestion(harness)
        val author = Bip340.generateKeypair()

        val collected = mutableListOf<FeedNotesResult>()
        val feedJob = launch { harness.repository.observeFeedNotesForTest().collect { collected.add(it) } }
        quiesce()
        collected.clear()

        val note = signedEvent(keypair = author, kind = Event.KIND_TEXT_NOTE, content = "will be deleted")
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()
        assertEquals(note, harness.repository.getEventById(note.id))
        assertTrue(collected.last().notes.any { it.event.id == note.id })

        val deletion = signedEvent(keypair = author, kind = Event.KIND_EVENT_DELETION, tags = listOf(listOf("e", note.id)))
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to deletion)
        quiesce()

        assertNull("a deletion from the note's own author must evict it from the cache", harness.repository.getEventById(note.id))
        assertTrue(
            "the deleted note must disappear from emitted feed notes",
            collected.last().notes.none { it.event.id == note.id }
        )
        feedJob.cancel()
        ingestJob.cancel()
    }

    @Test
    fun `given a deletion authored by a different pubkey then the note is left in place`() = runTest {
        val harness = buildHarness()
        harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
        val ingestJob = startIngestion(harness)
        val author = Bip340.generateKeypair()
        val otherAuthor = Bip340.generateKeypair()

        val note = signedEvent(keypair = author, kind = Event.KIND_TEXT_NOTE, content = "protected note")
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to note)
        quiesce()
        assertEquals(note, harness.repository.getEventById(note.id))

        val deletion = signedEvent(keypair = otherAuthor, kind = Event.KIND_EVENT_DELETION, tags = listOf(listOf("e", note.id)))
        harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to deletion)
        quiesce()

        assertEquals(
            "a deletion authored by a pubkey other than the note's author must not remove it",
            note,
            harness.repository.getEventById(note.id)
        )
        ingestJob.cancel()
    }

    @Test
    fun `given a burst of valid events inside one coalescing window then the feed receives exactly one update`() = runTest {
        val harness = buildHarness()
        harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
        val ingestJob = startIngestion(harness)

        val collected = mutableListOf<FeedNotesResult>()
        val feedJob = launch { harness.repository.observeFeedNotesForTest().collect { collected.add(it) } }
        quiesce()
        collected.clear()

        val burst = List(4) { index -> signedEvent(kind = Event.KIND_TEXT_NOTE, content = "burst-$index") }
        burst.forEach { harness.nostrClient.eventFlowBacking.emit("wss://relay.example" to it) }
        quiesce()

        assertEquals(1, collected.size)
        val burstIds = burst.map { it.id }.toSet()
        assertEquals(burstIds, collected.single().notes.map { it.event.id }.toSet())
        feedJob.cancel()
        ingestJob.cancel()
    }

    @Test
    fun `across every ingestion scenario above the encrypted archive is never written to`() = runTest {
        val harness = buildHarness()
        harness.repository.activateFreshSession(FeedFilter(id = "f", name = "f", hideNsfw = false))
        val ingestJob = startIngestion(harness)

        // Happy path + duplicate delivery.
        val note = signedEvent(kind = Event.KIND_TEXT_NOTE, content = "hi")
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to note)
        quiesce()
        harness.nostrClient.eventFlowBacking.emit("wss://relay-b.example" to note)
        quiesce()
        assertEquals(note, harness.repository.getEventById(note.id))

        // Forged rejection.
        val forged = forgedEvent(kind = Event.KIND_TEXT_NOTE, content = "forged")
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to forged)
        quiesce()

        // Replaceable supersede.
        val replAuthor = Bip340.generateKeypair()
        val olderRevision = signedEvent(keypair = replAuthor, kind = Event.KIND_METADATA, createdAt = 1_000L, content = "{}")
        val newerRevision = signedEvent(keypair = replAuthor, kind = Event.KIND_METADATA, createdAt = 2_000L, content = "{}")
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to olderRevision)
        quiesce()
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to newerRevision)
        quiesce()

        // Hashtag exclusion, both directions.
        val excludedAuthor = Bip340.generateKeypair()
        val taggedNote = signedEvent(
            keypair = excludedAuthor,
            kind = Event.KIND_TEXT_NOTE,
            tags = listOf(listOf("t", "spam")),
            content = "spam note"
        )
        harness.repository.activateFreshSession(FeedFilter(id = "excl", name = "excl", hideNsfw = false, excludedHashtags = setOf("spam")))
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to taggedNote)
        quiesce()
        harness.repository.activateFreshSession(FeedFilter(id = "incl", name = "incl", hideNsfw = false))
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to taggedNote)
        quiesce()
        assertEquals(taggedNote, harness.repository.getEventById(taggedNote.id))

        // Muted author, both directions.
        val mutedAuthor = Bip340.generateKeypair()
        val mutedNote = signedEvent(keypair = mutedAuthor, kind = Event.KIND_TEXT_NOTE, content = "muted note")
        harness.repository.activateFreshSession(FeedFilter(id = "mute", name = "mute", hideNsfw = false, mutedPubkeys = setOf(mutedAuthor.pubkeyHex)))
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to mutedNote)
        quiesce()
        harness.repository.activateFreshSession(FeedFilter(id = "unmute", name = "unmute", hideNsfw = false))
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to mutedNote)
        quiesce()
        assertEquals(mutedNote, harness.repository.getEventById(mutedNote.id))

        // Deletion, both ownership outcomes.
        val deleteAuthor = Bip340.generateKeypair()
        val deletableNote = signedEvent(keypair = deleteAuthor, kind = Event.KIND_TEXT_NOTE, content = "deletable")
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to deletableNote)
        quiesce()
        val deletion = signedEvent(keypair = deleteAuthor, kind = Event.KIND_EVENT_DELETION, tags = listOf(listOf("e", deletableNote.id)))
        harness.nostrClient.eventFlowBacking.emit("wss://relay-a.example" to deletion)
        quiesce()
        assertNull(harness.repository.getEventById(deletableNote.id))

        val totalInsertCalls = harness.eventDao.insertEventsCalls.size + harness.eventDao.insertEventCalls.size
        assertEquals("no relay-delivered event authored by a non-signed-in-user pubkey may reach the encrypted archive", 0, totalInsertCalls)
        val totalInsertTagCalls = harness.eventTagDao.insertTagsCalls.size
        assertEquals(0, totalInsertTagCalls)
        val totalDeleteEventByIdCalls = harness.eventDao.deleteEventByIdCalls.size
        assertEquals(0, totalDeleteEventByIdCalls)
        ingestJob.cancel()
    }
}
