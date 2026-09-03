package com.umbra.app.data.repository

import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip01.Event
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the three contracts [EventIngestCache]'s extraction most risks
 * breaking: replaceable-event superseding via `winsReplaceableRace` (LOG-1/LOG-6), the
 * synchronous eviction-to-engagement-index contract, and the 250ms burst-coalescing snapshot
 * emitter. See [com.umbra.app.data.repository.cache.EventLruCacheTest]'s
 * `given eviction callback when it fires then mutation is visible synchronously with no lock
 * needed` for the one-level-down analog the synchronous-eviction test here mirrors.
 *
 * [EventIngestCache]'s constructor also carries the persistence half's dependencies
 * (an [OwnEventArchive], `seenEventIds`, and several gating lambdas) — [subject] and
 * [FakeOwnEventArchive] below construct it with permissive defaults so the extraction-regression
 * tests above keep passing unmodified; coverage of the persistence half itself (persist
 * eligibility, the archive write gate, deletion ownership) is added alongside them in the same
 * file.
 */
class EventIngestCacheTest {

    private val relayA = "wss://relay-a.example"
    private val relayB = "wss://relay-b.example"

    /** Constructs an [EventIngestCache] against [scope] with permissive defaults for every
     * persistence-half constructor parameter — a fresh [FakeOwnEventArchive], a fresh
     * [ConcurrentHashMap.newKeySet], an empty/non-hiding [FeedFilter], and lambdas that never
     * gate anything unless a test explicitly overrides them. */
    private fun subject(
        scope: CoroutineScope,
        maxInMemoryEvents: Int = 100,
        ownEventArchive: OwnEventArchive = FakeOwnEventArchive(),
        seenEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        activeFeedFilter: () -> FeedFilter = { permissiveFilter() },
        isCurrentUserPubkey: (String) -> Boolean = { false },
        isPendingEventLookupId: (String) -> Boolean = { false },
        isPinnedProfileAuthor: (String) -> Boolean = { false },
        isWiping: () -> Boolean = { false }
    ): EventIngestCache = EventIngestCache(
        repoScope = scope,
        maxInMemoryEvents = maxInMemoryEvents,
        ownEventArchive = ownEventArchive,
        seenEventIds = seenEventIds,
        activeFeedFilter = activeFeedFilter,
        isCurrentUserPubkey = isCurrentUserPubkey,
        isPendingEventLookupId = isPendingEventLookupId,
        isPinnedProfileAuthor = isPinnedProfileAuthor,
        isWiping = isWiping
    )

    /** A [FeedFilter] with every exclusion off — the "nothing hidden" baseline each persist-
     * eligibility test starts from and overrides explicitly (never relies on shipped defaults). */
    private fun permissiveFilter(): FeedFilter = FeedFilter(
        id = "test-filter",
        name = "test",
        hideNsfw = false,
        mutedPubkeys = emptySet(),
        excludedTags = emptySet(),
        excludedHashtags = emptySet(),
        excludedContentPrefixes = emptySet()
    )

    /** A file-private fake of [OwnEventArchive] — records every [writeBatch]/[deleteEventById]
     * call for exact-count assertions, and serves [getEventsByIds]/[getLatestAddressableEvent]
     * from settable in-memory state, so a plain JVM unit test never needs a real Room database. */
    private class FakeOwnEventArchive : OwnEventArchive {
        val writeBatchCalls = mutableListOf<List<PendingEventInsert>>()
        val deleteEventByIdCalls = mutableListOf<String>()
        var eventsById: List<EventEntity> = emptyList()
        var latestAddressableEvent: EventEntity? = null

        override suspend fun writeBatch(batch: List<PendingEventInsert>) {
            writeBatchCalls.add(batch)
        }

        override suspend fun getEventsByIds(ids: List<String>): List<EventEntity> =
            eventsById.filter { it.id in ids }

        override suspend fun deleteEventById(id: String) {
            deleteEventByIdCalls.add(id)
        }

        override suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): EventEntity? =
            latestAddressableEvent
    }

    /** [scheduleInsert] hardcodes `repoScope.launch(Dispatchers.IO)`, moved verbatim from the
     * pre-extraction facade with its 200ms debounce unchanged, so
     * unlike [EventIngestCache.scheduleSnapshotEmit]'s plain `repoScope.launch { }`, its queued
     * write does NOT run on `runTest`'s virtual-time [kotlinx.coroutines.test.TestDispatcher] and
     * `advanceTimeBy`/`advanceUntilIdle` cannot fast-forward it — unrelated to
     * `INSERT_DEBOUNCE_MS`'s value, they simply can't observe or apply to Dispatchers.IO's queue.
     * A short real suspend on the same dispatcher yields real wall-clock time for that real
     * background job to finish before the assertion runs. */
    private suspend fun awaitInsertDebounce() {
        withContext(Dispatchers.IO) { delay(300) }
    }

    private fun textNote(id: String, pubkey: String = "b".repeat(64)): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = 1L,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = "text",
        sig = "c".repeat(128)
    )

    /** A regular-replaceable (NIP-01) kind-0 revision — same [pubkey] + kind = same
     * [com.umbra.app.domain.nip01.ReplaceableEventKey] slot, so two revisions with different
     * [id]/[createdAt] genuinely compete via `winsReplaceableRace`. */
    private fun metadataRevision(id: String, pubkey: String, createdAt: Long): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = Event.KIND_METADATA,
        tags = emptyList(),
        content = "{}",
        sig = "c".repeat(128)
    )

    /** A kind-7 reaction targeting [targetId] via an "e" tag — the shape
     * [EventEngagementIndex.add] actually indexes (see its own kind filter). */
    private fun reactionEvent(id: String, targetId: String, pubkey: String = "b".repeat(64)): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = 1L,
        kind = Event.KIND_REACTION,
        tags = listOf(listOf("e", targetId)),
        content = "+",
        sig = "c".repeat(128)
    )

    private var idCounter = 0

    /** A generic event builder for the persist-eligibility/deletion/normalization
     * tests, which need to vary pubkey/kind/tags/createdAt independently rather than fixing one
     * shape the way [textNote]/[metadataRevision]/[reactionEvent] above do. */
    private fun event(
        id: String = "generic-${idCounter++}",
        pubkey: String = "b".repeat(64),
        kind: Int = Event.KIND_TEXT_NOTE,
        tags: List<List<String>> = emptyList(),
        content: String = "",
        createdAt: Long = 1L
    ): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128)
    )

    private fun eventEntity(
        id: String = "entity-${idCounter++}",
        pubkey: String = "b".repeat(64),
        createdAt: Long = 1L,
        kind: Int = Event.KIND_TEXT_NOTE,
        content: String = "",
        sig: String = "c".repeat(128),
        tagsJson: String = "[]"
    ): EventEntity = EventEntity(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        content = content,
        sig = sig,
        tagsJson = tagsJson
    )

    @Test
    fun `given a newer replaceable revision when it wins the winsReplaceableRace supersede race then only the newer is retrievable`() = runTest {
        val cache = subject(this)
        val pubkey = "b".repeat(64)
        val older = metadataRevision(id = "aaa1", pubkey = pubkey, createdAt = 100L)
        val newer = metadataRevision(id = "bbb2", pubkey = pubkey, createdAt = 200L)

        val outcomeOlder = cache.ingest(older, relayA, currentUserPubkey = null)
        val outcomeNewer = cache.ingest(newer, relayA, currentUserPubkey = null)

        assertTrue(outcomeOlder.storedInMemoryCache)
        assertTrue(outcomeNewer.storedInMemoryCache)
        assertEquals(newer, cache.getCached(newer.id))
        assertNull(cache.getCached(older.id))
        // The superseded revision was evicted, not left to coexist alongside the winner — cache
        // size does not grow by two for what is logically one slot.
        assertEquals(outcomeOlder.cacheSize, outcomeNewer.cacheSize)
    }

    @Test
    fun `given an older replaceable revision arriving after a newer one when it loses the supersede race then it is dropped`() = runTest {
        val cache = subject(this)
        val pubkey = "b".repeat(64)
        val newer = metadataRevision(id = "bbb2", pubkey = pubkey, createdAt = 200L)
        val older = metadataRevision(id = "aaa1", pubkey = pubkey, createdAt = 100L)

        cache.ingest(newer, relayA, currentUserPubkey = null)
        val outcomeOlder = cache.ingest(older, relayA, currentUserPubkey = null)

        assertFalse(outcomeOlder.storedInMemoryCache)
        assertEquals(newer, cache.getCached(newer.id))
        assertNull(cache.getCached(older.id))
    }

    @Test
    fun `given the same event id ingested then re-delivered from a second relay when recordRelayForSeenEvent is called then cache size is unchanged and both relays are recorded`() = runTest {
        val cache = subject(this)
        val event = textNote("1")

        val firstOutcome = cache.ingest(event, relayA, currentUserPubkey = null)
        // Mirrors EventRepositoryImpl.subscribeToEvents' dedupe path: a redelivery of an
        // already-seen id calls recordRelayForSeenEvent, never ingest() a second time.
        cache.recordRelayForSeenEvent(event.id, relayB)

        assertEquals(setOf(relayA, relayB), cache.getCachedRelays(event.id))
        assertEquals(firstOutcome.cacheSize, cache.snapshot().size)
    }

    @Test
    fun `given an eviction triggered from inside ingest when it returns then the evicted event's engagement is already gone with no scheduler advance`() = runTest {
        val cache = subject(this, maxInMemoryEvents = 1)
        val target = "target-1"
        val firstReaction = reactionEvent(id = "r1", targetId = target)
        val secondReaction = reactionEvent(id = "r2", targetId = "target-2")

        cache.ingest(firstReaction, relayA, currentUserPubkey = null)
        assertEquals(1, cache.engagementSnapshot()[target]?.reactions)

        // maxInMemoryEvents = 1: ingesting a second, unrelated event evicts the first
        // synchronously via EventLruCache's onEvicted callback.
        cache.ingest(secondReaction, relayA, currentUserPubkey = null)

        // No advanceUntilIdle()/extra dispatch between ingest() returning and this assertion --
        // the eviction bookkeeping must already be visible synchronously, not on a later dispatch,
        // or this assertion would still see the (by-then-stale) engagement entry.
        assertNull(cache.engagementSnapshot()[target])
        assertNull(cache.getCached(firstReaction.id))
    }

    @Test
    fun `given eviction triggered while ingest already holds the mutex when it fires then it does not deadlock`() = runTest {
        val cache = subject(this, maxInMemoryEvents = 1)
        val first = textNote("1")
        val second = textNote("2")

        cache.ingest(first, relayA, currentUserPubkey = null)
        // If EventLruCache's onEvicted callback ever tried to re-acquire the mutex ingest()
        // already holds while put() triggers this eviction, this call would suspend forever and
        // runTest would fail the test for an uncompleted coroutine -- a hang is the failure mode,
        // not a wrong assertion.
        val outcome = cache.ingest(second, relayA, currentUserPubkey = null)

        assertTrue(outcome.storedInMemoryCache)
        assertNull(cache.getCached(first.id))
        assertEquals(second, cache.getCached(second.id))
    }

    @Test
    fun `given a burst of more than one enqueued event inside one window when time advances then exactly one snapshot and one bundle emission occur`() = runTest {
        val cache = subject(this)
        val snapshots = mutableListOf<List<Event>>()
        val bundles = mutableListOf<Set<Event>>()
        val snapshotJob = launch { cache.cachedEventsFlow.collect { snapshots.add(it) } }
        val bundleJob = launch { cache.cachedEventBundles.collect { bundles.add(it) } }
        advanceUntilIdle()

        val events = listOf(textNote("1"), textNote("2"), textNote("3"))
        events.forEach { event ->
            cache.ingest(event, relayA, currentUserPubkey = null)
            cache.enqueueSnapshotEvent(event)
            cache.scheduleSnapshotEmit()
        }
        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(1, snapshots.size)
        assertEquals(1, bundles.size)
        assertEquals(events.toSet(), bundles.single())

        snapshotJob.cancel()
        bundleJob.cancel()
    }

    @Test
    fun `given two emits separated by more than the coalescing window when time advances then two separate snapshot emissions occur`() = runTest {
        val cache = subject(this)
        val snapshots = mutableListOf<List<Event>>()
        val job = launch { cache.cachedEventsFlow.collect { snapshots.add(it) } }
        advanceUntilIdle()

        val first = textNote("1")
        cache.ingest(first, relayA, currentUserPubkey = null)
        cache.enqueueSnapshotEvent(first)
        cache.scheduleSnapshotEmit()
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(1, snapshots.size)

        val second = textNote("2")
        cache.ingest(second, relayA, currentUserPubkey = null)
        cache.enqueueSnapshotEvent(second)
        cache.scheduleSnapshotEmit()
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(2, snapshots.size)

        job.cancel()
    }

    @Test
    fun `given eight overlapping coroutines calling scheduleSnapshotEmit concurrently when time advances then exactly one snapshot and one bundle emission occur with no event lost`() = runTest {
        val cache = subject(this)
        val snapshots = mutableListOf<List<Event>>()
        val bundles = mutableListOf<Set<Event>>()
        val snapshotJob = launch { cache.cachedEventsFlow.collect { snapshots.add(it) } }
        val bundleJob = launch { cache.cachedEventBundles.collect { bundles.add(it) } }
        advanceUntilIdle()

        val events = (1..8).map { textNote("concurrent-$it") }
        events.forEach { cache.ingest(it, relayA, currentUserPubkey = null) }
        events.forEach { event ->
            launch {
                cache.enqueueSnapshotEvent(event)
                cache.scheduleSnapshotEmit()
            }
        }
        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(1, snapshots.size)
        assertEquals(1, bundles.size)
        assertEquals(events.toSet(), bundles.single())

        snapshotJob.cancel()
        bundleJob.cancel()
    }

    @Test
    fun `given a scheduled snapshot emit when cancelPendingSnapshotEmit runs then no emission occurs and a repeated cancel is a harmless no-op`() = runTest {
        val cache = subject(this)
        val snapshots = mutableListOf<List<Event>>()
        val job = launch { cache.cachedEventsFlow.collect { snapshots.add(it) } }
        advanceUntilIdle()

        val event = textNote("cancel-1")
        cache.ingest(event, relayA, currentUserPubkey = null)
        cache.enqueueSnapshotEvent(event)
        cache.scheduleSnapshotEmit()
        cache.cancelPendingSnapshotEmit()

        advanceTimeBy(300L)
        advanceUntilIdle()
        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(0, snapshots.size)
        // A second cancel with no pending job/state must not throw.
        cache.cancelPendingSnapshotEmit()

        job.cancel()
    }

    @Test
    fun `given cached events and engagement when clearAll is called then everything is emptied together and an empty snapshot is emitted`() = runTest {
        val cache = subject(this)
        val snapshots = mutableListOf<List<Event>>()
        val job = launch { cache.cachedEventsFlow.collect { snapshots.add(it) } }
        advanceUntilIdle()

        val reaction = reactionEvent(id = "r1", targetId = "target-1")
        cache.ingest(reaction, relayA, currentUserPubkey = null)

        cache.clearAll()
        advanceUntilIdle()

        assertEquals(0, cache.snapshot().size)
        assertTrue(cache.engagementSnapshot().isEmpty())
        assertEquals(1, snapshots.size)
        assertEquals(emptyList<Event>(), snapshots.single())

        job.cancel()
    }

    // --- Persist eligibility against the live, user-editable FeedFilter ---

    @Test
    fun `given an event authored by the signed-in user when persist eligibility is evaluated then it is included regardless of kind`() = runTest {
        val ownerPubkey = "a".repeat(64)
        val cache = subject(this, isCurrentUserPubkey = { it == ownerPubkey })
        val ev = event(pubkey = ownerPubkey, kind = Event.KIND_LONG_FORM)

        assertTrue(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given an event whose id is a pending lookup when persist eligibility is evaluated then it is included even for a kind outside the useful-kinds set`() = runTest {
        val lookupId = "lookup-target-1"
        val cache = subject(this, isPendingEventLookupId = { it == lookupId })
        val ev = event(id = lookupId, kind = Event.KIND_LONG_FORM)

        assertTrue(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given an unsolicited event of a kind outside the useful-kinds set when persist eligibility is evaluated then it is excluded`() = runTest {
        val cache = subject(this)
        val ev = event(kind = Event.KIND_LONG_FORM)

        assertFalse(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given a text note from a muted pubkey when persist eligibility is evaluated then it is excluded`() = runTest {
        val mutedAuthor = "d".repeat(64)
        val filter = permissiveFilter().copy(mutedPubkeys = setOf(mutedAuthor))
        val cache = subject(this, activeFeedFilter = { filter })
        val ev = event(pubkey = mutedAuthor, kind = Event.KIND_TEXT_NOTE)

        assertFalse(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given the same pubkey removed from mutedPubkeys when persist eligibility is evaluated then it is included`() = runTest {
        val formerlyMutedAuthor = "d".repeat(64)
        val filter = permissiveFilter().copy(mutedPubkeys = emptySet())
        val cache = subject(this, activeFeedFilter = { filter })
        val ev = event(pubkey = formerlyMutedAuthor, kind = Event.KIND_TEXT_NOTE)

        assertTrue(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given a note tagged nsfw when the filter hides nsfw then persist eligibility excludes it`() = runTest {
        val filter = permissiveFilter().copy(hideNsfw = true)
        val cache = subject(this, activeFeedFilter = { filter })
        val ev = event(kind = Event.KIND_TEXT_NOTE, tags = listOf(listOf("t", "nsfw")))

        assertFalse(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given the same nsfw-tagged note when the filter does not hide nsfw then persist eligibility includes it`() = runTest {
        val filter = permissiveFilter().copy(hideNsfw = false)
        val cache = subject(this, activeFeedFilter = { filter })
        val ev = event(kind = Event.KIND_TEXT_NOTE, tags = listOf(listOf("t", "nsfw")))

        assertTrue(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given a note carrying an excluded hashtag when persist eligibility is evaluated then it is excluded`() = runTest {
        val filter = permissiveFilter().copy(excludedHashtags = setOf("spam"))
        val cache = subject(this, activeFeedFilter = { filter })
        val ev = event(kind = Event.KIND_TEXT_NOTE, tags = listOf(listOf("t", "spam")))

        assertFalse(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given the same hashtag absent from excludedHashtags when persist eligibility is evaluated then it is included`() = runTest {
        val filter = permissiveFilter().copy(excludedHashtags = emptySet())
        val cache = subject(this, activeFeedFilter = { filter })
        val ev = event(kind = Event.KIND_TEXT_NOTE, tags = listOf(listOf("t", "spam")))

        assertTrue(cache.shouldPersistEvent(ev))
    }

    @Test
    fun `given an otherwise-ineligible event from a pinned profile author when persist eligibility is evaluated then it is included`() = runTest {
        val pinnedAuthor = "e".repeat(64)
        // Muted in the filter — would normally be excluded — but pinning overrides that.
        val filter = permissiveFilter().copy(mutedPubkeys = setOf(pinnedAuthor))
        val cache = subject(
            this,
            activeFeedFilter = { filter },
            isPinnedProfileAuthor = { it == pinnedAuthor }
        )
        val ev = event(pubkey = pinnedAuthor, kind = Event.KIND_TEXT_NOTE)

        assertTrue(cache.shouldPersistEvent(ev))
    }

    // --- Own-archive write gate (scheduleInsert) ---

    @Test
    fun `given an entity not authored by the signed-in user when scheduleInsert runs then zero writeBatch calls occur`() = runTest {
        val archive = FakeOwnEventArchive()
        val cache = subject(this, ownEventArchive = archive, isCurrentUserPubkey = { false })
        val entity = eventEntity(pubkey = "f".repeat(64))

        cache.scheduleInsert(entity)
        awaitInsertDebounce()

        val writeBatchCalls = archive.writeBatchCalls
        assertEquals(0, writeBatchCalls.size)
    }

    @Test
    fun `given an entity authored by the signed-in user when scheduleInsert runs then exactly one batched writeBatch call contains it`() = runTest {
        val archive = FakeOwnEventArchive()
        val ownerPubkey = "f".repeat(64)
        val cache = subject(this, ownEventArchive = archive, isCurrentUserPubkey = { it == ownerPubkey })
        val entity = eventEntity(pubkey = ownerPubkey)

        cache.scheduleInsert(entity)
        awaitInsertDebounce()

        assertEquals(1, archive.writeBatchCalls.size)
        assertEquals(listOf(entity), archive.writeBatchCalls.single().map { it.entity })
    }

    @Test
    fun `given the wiping flag set when scheduleInsert runs for the signed-in user's own event then zero writeBatch calls occur`() = runTest {
        val archive = FakeOwnEventArchive()
        val ownerPubkey = "f".repeat(64)
        val cache = subject(
            this,
            ownEventArchive = archive,
            isCurrentUserPubkey = { it == ownerPubkey },
            isWiping = { true }
        )
        val entity = eventEntity(pubkey = ownerPubkey)

        cache.scheduleInsert(entity)
        awaitInsertDebounce()

        val writeBatchCalls = archive.writeBatchCalls
        assertEquals(0, writeBatchCalls.size)
    }

    // --- NIP-09 deletion ownership and the a-tag created_at bound ---

    @Test
    fun `given a deletion whose pubkey differs from the target's author when applyIncomingDeletion runs then nothing is deleted from the archive or cache`() = runTest {
        val archive = FakeOwnEventArchive()
        val targetAuthor = "a".repeat(64)
        val deleterPubkey = "b".repeat(64)
        archive.eventsById = listOf(eventEntity(id = "target-1", pubkey = targetAuthor))
        val cache = subject(this, ownEventArchive = archive)
        val cachedTarget = event(id = "target-1", pubkey = targetAuthor)
        cache.ingest(cachedTarget, relayA, currentUserPubkey = null)

        val deletionEvent = event(
            pubkey = deleterPubkey,
            kind = Event.KIND_EVENT_DELETION,
            tags = listOf(listOf("e", "target-1"))
        )
        cache.applyIncomingDeletion(deletionEvent)

        assertEquals(0, archive.deleteEventByIdCalls.size)
        assertEquals(cachedTarget, cache.getCached("target-1"))
    }

    @Test
    fun `given an a-tag coordinate whose pubkey differs from the deletion's own pubkey when applyIncomingDeletion runs then it is skipped`() = runTest {
        val archive = FakeOwnEventArchive()
        val targetAuthor = "a".repeat(64)
        val deleterPubkey = "b".repeat(64)
        archive.latestAddressableEvent = eventEntity(id = "addr-1", pubkey = targetAuthor, createdAt = 50L)
        val cache = subject(this, ownEventArchive = archive)

        val coordinate = "${Event.KIND_METADATA}:$targetAuthor:"
        val deletionEvent = event(
            pubkey = deleterPubkey,
            kind = Event.KIND_EVENT_DELETION,
            tags = listOf(listOf("a", coordinate)),
            createdAt = 100L
        )
        cache.applyIncomingDeletion(deletionEvent)

        assertEquals(0, archive.deleteEventByIdCalls.size)
    }

    @Test
    fun `given an a-tag target created after the deletion's own created_at when applyIncomingDeletion runs then it is not deleted`() = runTest {
        val archive = FakeOwnEventArchive()
        val authorPubkey = "a".repeat(64)
        archive.latestAddressableEvent = eventEntity(id = "addr-2", pubkey = authorPubkey, createdAt = 200L)
        val cache = subject(this, ownEventArchive = archive)

        val coordinate = "${Event.KIND_METADATA}:$authorPubkey:"
        val deletionEvent = event(
            pubkey = authorPubkey,
            kind = Event.KIND_EVENT_DELETION,
            tags = listOf(listOf("a", coordinate)),
            createdAt = 100L
        )
        cache.applyIncomingDeletion(deletionEvent)

        assertEquals(0, archive.deleteEventByIdCalls.size)
    }

    // --- NIP-09 a-tag deletion resolves against the in-memory cache too (LOG-19/BUG-03) ---

    @Test
    fun `given a non-owned addressable event resident only in the in-memory cache when an a-tag deletion targets it then it is removed from the cache`() = runTest {
        val archive = FakeOwnEventArchive()
        val authorPubkey = "a".repeat(64)
        val dTagValue = "article-1"
        val cache = subject(this, ownEventArchive = archive, isCurrentUserPubkey = { false })
        val target = event(
            id = "addr-mem-1",
            pubkey = authorPubkey,
            kind = Event.KIND_LONG_FORM,
            tags = listOf(listOf("d", dTagValue)),
            createdAt = 100L
        )
        cache.ingest(target, relayA, currentUserPubkey = null)

        val coordinate = "${Event.KIND_LONG_FORM}:$authorPubkey:$dTagValue"
        val deletionEvent = event(
            pubkey = authorPubkey,
            kind = Event.KIND_EVENT_DELETION,
            tags = listOf(listOf("a", coordinate)),
            createdAt = 200L
        )
        cache.applyIncomingDeletion(deletionEvent)

        assertTrue(cache.snapshot().none { it.id == target.id })
    }

    @Test
    fun `given an a-tag deletion signed by a different pubkey than the coordinate's author when applied then the in-memory event is not removed`() = runTest {
        val archive = FakeOwnEventArchive()
        val authorPubkey = "a".repeat(64)
        val deleterPubkey = "b".repeat(64)
        val dTagValue = "article-2"
        val cache = subject(this, ownEventArchive = archive, isCurrentUserPubkey = { false })
        val target = event(
            id = "addr-mem-2",
            pubkey = authorPubkey,
            kind = Event.KIND_LONG_FORM,
            tags = listOf(listOf("d", dTagValue)),
            createdAt = 100L
        )
        cache.ingest(target, relayA, currentUserPubkey = null)

        val coordinate = "${Event.KIND_LONG_FORM}:$authorPubkey:$dTagValue"
        val deletionEvent = event(
            pubkey = deleterPubkey,
            kind = Event.KIND_EVENT_DELETION,
            tags = listOf(listOf("a", coordinate)),
            createdAt = 200L
        )
        cache.applyIncomingDeletion(deletionEvent)

        assertTrue(cache.snapshot().any { it.id == target.id })
    }

    @Test
    fun `given an in-memory addressable event newer than the deletion's own created_at when applied then it is not removed`() = runTest {
        val archive = FakeOwnEventArchive()
        val authorPubkey = "a".repeat(64)
        val dTagValue = "article-3"
        val cache = subject(this, ownEventArchive = archive, isCurrentUserPubkey = { false })
        val target = event(
            id = "addr-mem-3",
            pubkey = authorPubkey,
            kind = Event.KIND_LONG_FORM,
            tags = listOf(listOf("d", dTagValue)),
            createdAt = 300L
        )
        cache.ingest(target, relayA, currentUserPubkey = null)

        val coordinate = "${Event.KIND_LONG_FORM}:$authorPubkey:$dTagValue"
        val deletionEvent = event(
            pubkey = authorPubkey,
            kind = Event.KIND_EVENT_DELETION,
            tags = listOf(listOf("a", coordinate)),
            createdAt = 100L
        )
        cache.applyIncomingDeletion(deletionEvent)

        assertTrue(cache.snapshot().any { it.id == target.id })
    }

    // --- normalizeIncomingEvent ---

    @Test
    fun `given an already-lowercase pubkey when normalizeIncomingEvent runs then the event is returned unchanged`() = runTest {
        val cache = subject(this)
        val ev = event(pubkey = "a".repeat(64))

        assertEquals(ev, cache.normalizeIncomingEvent(ev))
    }

    @Test
    fun `given a pubkey containing uppercase characters when normalizeIncomingEvent runs then it is lowercased`() = runTest {
        val cache = subject(this)
        val mixedCasePubkey = "A".repeat(64)
        val ev = event(pubkey = mixedCasePubkey)

        val normalized = cache.normalizeIncomingEvent(ev)

        assertEquals(mixedCasePubkey.lowercase(), normalized.pubkey)
    }
}
