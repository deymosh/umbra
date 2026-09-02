package com.umbra.app.testutil.fakes

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.model.FeedNotesResult
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip45.RelayCountResult
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayRequestInfo
import com.umbra.app.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared [EventRepository] fake — see FakeUserPreferences' doc comment for why this was
 * centralized (four near-identical ~40-method copies across BackfillDeleteLogoutUseCaseTest,
 * ProfileNip05VerificationStateTest, MuteListRepositoryImplTest, PinListRepositoryImplTest meant
 * every interface change had to be applied four times). [recentEvents] backs both
 * [observeRecentEvents] and the pubkey/kind-filtered [observeEventsByPubkeyAndKind] — the latter
 * is what OwnerTagSetCache.startOwnerSync actually reads, so Contact/Mute/PinListRepositoryImpl
 * tests need this derived filtering, not just the raw list. The recording fields
 * (pinnedPubkeys, subscriptions, lastLoadOlderCall, ...) are free to ignore when a test doesn't
 * care about them.
 */
internal class FakeEventRepository(
    private val oldestAuthorTimestamp: Long? = null,
    private val failClearAllData: Boolean = false,
    private val recentEvents: List<Event> = emptyList(),
    private val relayHintsByPubkey: Map<String, List<String>> = emptyMap(),
    // Backs getEventById/fetchEventById for tests that need a specific by-id lookup to resolve
    // (e.g. ComposerViewModel's quote/reply prefill) — empty by default so every existing caller
    // keeps the prior always-null behavior.
    private val eventsById: Map<String, Event> = emptyMap(),
    // Overrides observeRecentEvents' emissions when a test needs multiple sequential batches
    // (e.g. OwnerTagSetCache's ingest staleness-guard) instead of the single flowOf(recentEvents)
    // snapshot every other caller of this fake gets by default.
    private val recentEventsFlow: Flow<List<Event>>? = null
) : EventRepository {
    val pinnedPubkeys: MutableList<String> = mutableListOf()
    val unpinnedPubkeys: MutableList<String> = mutableListOf()
    val subscriptions: MutableList<Pair<String, List<EventFilter>>> = mutableListOf()
    var lastLoadOlderCall: LoadOlderCall? = null
    var deletedEventId: String? = null
    var clearAllDataCalls: Int = 0
    var disconnectFromAllCalls: Int = 0
    val disconnectRelayCalls: MutableList<String> = mutableListOf()
    val clearedBackfillAnchorPubkeys: MutableList<String> = mutableListOf()
    val connectToRelayHintsCalls: MutableList<List<String>> = mutableListOf()

    data class LoadOlderCall(
        val channelId: String,
        val untilTimestamp: Long,
        val windowSeconds: Long,
        val limit: Int
    )

    override fun pinProfileAuthorForPersistence(pubkey: String) {
        pinnedPubkeys += pubkey
    }

    override fun unpinProfileAuthorForPersistence(pubkey: String) {
        unpinnedPubkeys += pubkey
    }

    override fun connectToRelayHints(relayUrls: List<String>) {
        connectToRelayHintsCalls += relayUrls
    }

    override fun getRelayHints(pubkey: String): List<String> =
        relayHintsByPubkey[pubkey.lowercase()].orEmpty()

    override suspend fun clearAllData() {
        clearAllDataCalls += 1
        if (failClearAllData) throw IllegalStateException("boom")
    }

    override fun clearBackfillAnchors(pubkey: String) {
        clearedBackfillAnchorPubkeys += pubkey
    }

    override fun activateUserSession(pubkey: String?, feedFilter: FeedFilter, authors: Set<String>) = Unit
    override fun setSubscriptionNamespace(namespace: String) = Unit
    override fun subscribeToEvents(filters: List<EventFilter>): Flow<Event> = emptyFlow()

    override fun subscribeChannel(channelId: String, filters: List<EventFilter>) {
        subscriptions += channelId to filters
    }

    override fun setChannelOverlay(channelId: String, overlayFilters: List<EventFilter>) = Unit
    override fun clearChannel(channelId: String) = Unit
    override fun reapplyChannelsToRelay(relayUrl: String) = Unit
    override suspend fun awaitChannelEoseOrTimeout(channelId: String, timeoutMs: Long) = Unit

    override fun loadOlderEvents(channelId: String, untilTimestamp: Long, windowSeconds: Long, limit: Int) {
        lastLoadOlderCall = LoadOlderCall(channelId, untilTimestamp, windowSeconds, limit)
    }

    override fun resyncRecentHistory(channelId: String, sinceTimestamp: Long, untilTimestamp: Long, limit: Int) = Unit

    override suspend fun getOldestAuthorNoteTimestamp(pubkey: String): Long? = oldestAuthorTimestamp
    override suspend fun getOldestInboxNoteTimestamp(pubkey: String): Long? = null
    override suspend fun getOldestInboxReactionTimestamp(pubkey: String): Long? = null
    override fun getCachedEvents(): Flow<List<Event>> = flowOf(emptyList())
    override fun observeRecentEvents(limit: Int): Flow<List<Event>> = recentEventsFlow ?: flowOf(recentEvents)
    override suspend fun getEventById(id: String): Event? = eventsById[id]
    override suspend fun getEventRelays(eventId: String): Set<String> = emptySet()
    override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): Event? = null
    override suspend fun getEventsByIds(ids: List<String>): List<Event> = emptyList()
    override suspend fun getEventsReferencingIds(targetIds: List<String>): List<Event> = emptyList()

    override fun observeEventsByPubkeyAndKind(pubkey: String, kind: Int, limit: Int): Flow<List<Event>> =
        flowOf(
            recentEvents
                .filter { it.pubkey.equals(pubkey, ignoreCase = true) && it.kind == kind }
                .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
                .take(limit)
        )

    override fun observeCountEventsByPubkeyAndKind(pubkey: String, kind: Int): Flow<Int> = flowOf(0)
    override suspend fun clearCache() = Unit

    override suspend fun deleteEvent(eventId: String) {
        deletedEventId = eventId
    }

    override suspend fun connectToEnabledRelays(relays: List<Relay>): Result<Unit> = Result.success(Unit)
    override fun disconnectFromAll() {
        disconnectFromAllCalls += 1
    }
    override fun disconnectRelay(relayUrl: String) {
        disconnectRelayCalls += relayUrl
    }
    override suspend fun publishEvent(event: Event): Result<Set<String>> = Result.success(emptySet())
    override suspend fun publishAuthEvent(relayUrl: String, event: Event): Result<Unit> = Result.success(Unit)
    override fun observeRelayRequests(): Flow<List<RelayRequestInfo>> = flowOf(emptyList())
    override fun observeRelayIssues(): Flow<RelayIssue> = emptyFlow()
    override fun observeConnectedRelayUrls(): Flow<Set<String>> = flowOf(emptySet())
    override fun resetRelayFailureCount(relayUrl: String) = Unit
    override fun resetAllRelayBackoff() = Unit
    override fun requestCount(relayUrl: String, subscriptionId: String, filters: List<EventFilter>) = Unit
    override fun observeRelayCounts(): Flow<RelayCountResult> = emptyFlow()

    override fun observeFeedNotes(
        since: Long,
        limit: Int,
        authors: Set<String>,
        mutedPubkeys: Set<String>,
        excludedHashtagsLower: Set<String>,
        includeMentions: Boolean,
        hideNsfw: Boolean,
        currentNpub: String?,
        currentUserPubkey: String?,
        desiredTagsLower: Set<String>
    ): Flow<FeedNotesResult> = flowOf(FeedNotesResult())

    override fun observeProfileNotes(pubkey: String, kind: Int, limit: Int): Flow<FeedNotesResult> = flowOf(FeedNotesResult())
    override suspend fun searchNotes(query: String): Flow<List<Event>> = flowOf(emptyList())
}
