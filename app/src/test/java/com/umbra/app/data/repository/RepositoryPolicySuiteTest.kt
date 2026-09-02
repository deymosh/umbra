package com.umbra.app.data.repository

import com.umbra.app.data.repository.policy.OutboxProfilePolicy
import com.umbra.app.data.repository.policy.RelayConnectionPolicy
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.relay.Relay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryPolicySuiteTest {

    @Test
    fun `given cached profiles when resolving misses then only unique uncached pubkeys remain`() {
        val cached = "a".repeat(64)
        val missing = "b".repeat(64)

        val result = missingProfilePubkeys(
            requestedPubkeys = listOf(cached.uppercase(), missing, missing),
            cachedPubkeys = setOf(cached)
        )

        assertEquals(listOf(missing), result)
    }

    @Test
    fun `given all profiles cached when resolving misses then returns empty list`() {
        val cached = "a".repeat(64)

        val result = missingProfilePubkeys(
            requestedPubkeys = listOf(cached),
            cachedPubkeys = setOf(cached)
        )

        assertTrue(result.isEmpty())
    }

    private fun event(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int = Event.KIND_TEXT_NOTE,
        tags: List<List<String>> = emptyList(),
        content: String = "note"
    ) = Event(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "s"
    )

    @Test
    fun `given cache and encrypted events when merging then only encrypted own events are included`() {
        val ownPubkey = "a".repeat(64)
        val externalPubkey = "b".repeat(64)
        val cachedExternal = event("cached", externalPubkey, 20)
        val encryptedOwn = event("own", ownPubkey, 30)
        val staleEncryptedExternal = event("external-encrypted", externalPubkey, 40)

        val result = mergeHybridEvents(
            cachedEvents = listOf(cachedExternal),
            encryptedEvents = listOf(encryptedOwn, staleEncryptedExternal),
            currentUserPubkey = ownPubkey,
            limit = 10
        )

        assertEquals(listOf("own", "cached"), result.map { it.id })
    }

    @Test
    fun `given feed filters when selecting hybrid notes then muted and excluded events are removed`() {
        val mutedPubkey = "b".repeat(64)
        val visiblePubkey = "c".repeat(64)
        val events = listOf(
            event("muted", mutedPubkey, 30),
            event("nsfw", visiblePubkey, 20, tags = listOf(listOf("t", "NSFW"))),
            event("visible", visiblePubkey, 10)
        )

        val result = selectHybridFeedNotes(
            events = events,
            since = 0,
            limit = 10,
            authors = emptySet(),
            mutedPubkeys = setOf(mutedPubkey),
            excludedHashtagsLower = emptySet(),
            includeMentions = true,
            hideNsfw = true,
            currentNpub = null,
            currentUserPubkey = "a".repeat(64),
            desiredTagsLower = emptySet()
        )

        assertEquals(listOf("visible"), result.map { it.id })
    }

    @Test
    fun `given nonempty authors when selecting hybrid notes then only those authors pass`() {
        val followed = "b".repeat(64)
        val notFollowed = "c".repeat(64)
        val events = listOf(
            event("followed", followed, 20),
            event("stranger", notFollowed, 10)
        )

        val result = selectHybridFeedNotes(
            events = events,
            since = 0,
            limit = 10,
            authors = setOf(followed.uppercase()),
            mutedPubkeys = emptySet(),
            excludedHashtagsLower = emptySet(),
            includeMentions = true,
            hideNsfw = true,
            currentNpub = null,
            currentUserPubkey = null,
            desiredTagsLower = emptySet()
        )

        assertEquals(listOf("followed"), result.map { it.id })
    }

    @Test
    fun `given a repost by a followed author when selecting hybrid notes then it passes through`() {
        val reposter = "b".repeat(64)
        val targetId = "c".repeat(64)
        val repost = event(
            id = "repost",
            pubkey = reposter,
            createdAt = 20,
            kind = Event.KIND_REPOST,
            tags = listOf(listOf("e", targetId))
        )

        val result = selectHybridFeedNotes(
            events = listOf(repost),
            since = 0,
            limit = 10,
            authors = setOf(reposter),
            mutedPubkeys = emptySet(),
            excludedHashtagsLower = emptySet(),
            includeMentions = true,
            hideNsfw = true,
            currentNpub = null,
            currentUserPubkey = null,
            desiredTagsLower = emptySet()
        )

        assertEquals(listOf("repost"), result.map { it.id })
    }

    @Test
    fun `given a repost by an unfollowed author when selecting hybrid notes then it is excluded`() {
        val reposter = "b".repeat(64)
        val followed = "d".repeat(64)
        val repost = event(id = "repost", pubkey = reposter, createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", "c".repeat(64))))

        val result = selectHybridFeedNotes(
            events = listOf(repost),
            since = 0,
            limit = 10,
            authors = setOf(followed),
            mutedPubkeys = emptySet(),
            excludedHashtagsLower = emptySet(),
            includeMentions = true,
            hideNsfw = true,
            currentNpub = null,
            currentUserPubkey = null,
            desiredTagsLower = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given several reposts of the same target when collapsing then only the newest survives`() {
        val targetId = "c".repeat(64)
        val older = event(id = "older-repost", pubkey = "b".repeat(64), createdAt = 10, kind = Event.KIND_REPOST, tags = listOf(listOf("e", targetId)))
        val newer = event(id = "newer-repost", pubkey = "d".repeat(64), createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", targetId)))

        val result = collapseRepostsToLatestPerTarget(listOf(older, newer))

        assertEquals(listOf("newer-repost"), result.map { it.id })
    }

    @Test
    fun `given reposts of different targets when collapsing then both survive`() {
        val first = event(id = "r1", pubkey = "b".repeat(64), createdAt = 10, kind = Event.KIND_REPOST, tags = listOf(listOf("e", "target-1")))
        val second = event(id = "r2", pubkey = "b".repeat(64), createdAt = 10, kind = Event.KIND_REPOST, tags = listOf(listOf("e", "target-2")))

        val result = collapseRepostsToLatestPerTarget(listOf(first, second))

        assertEquals(setOf("r1", "r2"), result.map { it.id }.toSet())
    }

    @Test
    fun `given a repost with no e tag when collapsing then it is dropped`() {
        val repost = event(id = "repost", pubkey = "b".repeat(64), createdAt = 10, kind = Event.KIND_REPOST, tags = emptyList())

        val result = collapseRepostsToLatestPerTarget(listOf(repost))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given plain notes and reposts mixed when collapsing then plain notes are always kept`() {
        val note = event(id = "note", pubkey = "b".repeat(64), createdAt = 10)
        val repost = event(id = "repost", pubkey = "d".repeat(64), createdAt = 5, kind = Event.KIND_REPOST, tags = listOf(listOf("e", "some-target")))

        val result = collapseRepostsToLatestPerTarget(listOf(note, repost))

        assertEquals(setOf("note", "repost"), result.map { it.id }.toSet())
    }

    @Test
    fun `given a plain note when resolving feed events then it passes through unchanged`() {
        val note = event(id = "note", pubkey = "b".repeat(64), createdAt = 10)

        val result = resolveFeedEvents(listOf(note)) { null }

        assertEquals(1, result.resolved.size)
        assertEquals("note", result.resolved.single().targetEvent.id)
        assertEquals(null, result.resolved.single().repostedByPubkey)
        assertTrue(result.unresolvedReposts.isEmpty())
    }

    @Test
    fun `given a repost whose target is resolvable when resolving feed events then it resolves to the target`() {
        val reposter = "b".repeat(64)
        val target = event(id = "target", pubkey = "c".repeat(64), createdAt = 5)
        val repost = event(id = "repost", pubkey = reposter, createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", target.id)))

        val result = resolveFeedEvents(listOf(repost)) { id -> if (id == target.id) target else null }

        assertEquals(1, result.resolved.size)
        assertEquals(target.id, result.resolved.single().targetEvent.id)
        assertEquals(reposter, result.resolved.single().repostedByPubkey)
        assertEquals(20L, result.resolved.single().repostedAt)
        assertTrue(result.unresolvedReposts.isEmpty())
    }

    @Test
    fun `given a repost whose target cannot be resolved when resolving feed events then it is reported as unresolved, not dropped`() {
        val repost = event(id = "repost", pubkey = "b".repeat(64), createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", "missing-target")))

        val result = resolveFeedEvents(listOf(repost)) { null }

        assertTrue(result.resolved.isEmpty())
        assertEquals(listOf("repost"), result.unresolvedReposts.map { it.id })
    }

    @Test
    fun `given a repost with no e tag when resolving feed events then it is dropped from both resolved and unresolved`() {
        val repost = event(id = "repost", pubkey = "b".repeat(64), createdAt = 20, kind = Event.KIND_REPOST, tags = emptyList())

        val result = resolveFeedEvents(listOf(repost)) { null }

        assertTrue(result.resolved.isEmpty())
        assertTrue(result.unresolvedReposts.isEmpty())
    }

    @Test
    fun `given unresolved reposts when building pending reposts then each carries its target id`() {
        val reposter = "b".repeat(64)
        val repost = event(id = "repost", pubkey = reposter, createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", "target-id")))

        val result = toPendingReposts(listOf(repost))

        val pending = result.single()
        assertEquals("repost", pending.repostId)
        assertEquals(reposter, pending.repostedByPubkey)
        assertEquals(20L, pending.repostedAt)
        assertEquals("target-id", pending.targetId)
    }

    @Test
    fun `given a repost with no e tag when building pending reposts then it is dropped`() {
        val repost = event(id = "repost", pubkey = "b".repeat(64), createdAt = 20, kind = Event.KIND_REPOST, tags = emptyList())

        val result = toPendingReposts(listOf(repost))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given a repost and an independent plain occurrence of the same target when resolving then the repost-carrying entry wins`() {
        val target = event(id = "target", pubkey = "c".repeat(64), createdAt = 5)
        val repost = event(id = "repost", pubkey = "b".repeat(64), createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", target.id)))

        val result = resolveFeedEvents(listOf(target, repost)) { id -> if (id == target.id) target else null }

        assertEquals(1, result.resolved.size)
        assertEquals(target.id, result.resolved.single().targetEvent.id)
        assertEquals("b".repeat(64), result.resolved.single().repostedByPubkey)
    }

    @Test
    fun `given a resolved repost when building cached note views then engagement and profile resolve against the target`() {
        val reposter = "b".repeat(64)
        val targetAuthor = "c".repeat(64)
        val target = event(id = "target", pubkey = targetAuthor, createdAt = 5)
        val repost = event(id = "repost", pubkey = reposter, createdAt = 20, kind = Event.KIND_REPOST, tags = listOf(listOf("e", target.id)))
        val reaction = event(id = "reaction", pubkey = "d".repeat(64), createdAt = 25, kind = Event.KIND_REACTION, tags = listOf(listOf("e", target.id)))

        val result = buildCachedNoteViews(
            allEvents = listOf(target, repost, reaction),
            profilesByPubkey = emptyMap(),
            selectedNotes = listOf(repost)
        )

        val noteView = result.single()
        assertEquals(target.id, noteView.event.id)
        assertEquals(reposter, noteView.repostedByPubkey)
        assertEquals(20L, noteView.repostedAt)
        assertEquals(1, noteView.reactionCount)
    }

    private fun noteView(
        id: String,
        pubkey: String,
        createdAt: Long,
        reactionCount: Int = 0,
        repostedByPubkey: String? = null,
        repostedAt: Long? = null
    ) = NoteView(
        event = event(id = id, pubkey = pubkey, createdAt = createdAt),
        authorProfile = null,
        reactionCount = reactionCount,
        replyCount = 0,
        repostCount = 0,
        repostedByPubkey = repostedByPubkey,
        repostedByProfile = null,
        repostedAt = repostedAt
    )

    @Test
    fun `given no reposts when merging own notes and reposts then notes pass through capped at limit`() {
        val notes = listOf(
            noteView("a", "p1", 30),
            noteView("b", "p1", 20),
            noteView("c", "p1", 10)
        )

        val result = mergeOwnNotesAndReposts(notes, emptyList(), limit = 2)

        assertEquals(listOf("a", "b"), result.map { it.event.id })
    }

    @Test
    fun `given a repost of a note not among own notes when merging then it is appended and sorted`() {
        val ownNote = noteView("own", "me", 10)
        val repostView = noteView("target", "other", 5, repostedByPubkey = "me", repostedAt = 20)

        val result = mergeOwnNotesAndReposts(listOf(ownNote), listOf(repostView), limit = 10)

        assertEquals(listOf("target", "own"), result.map { it.event.id })
        assertEquals("me", result.first { it.event.id == "target" }.repostedByPubkey)
    }

    @Test
    fun `given a self-repost of an own note when merging then own engagement is kept and repost annotation is layered in`() {
        val ownNote = noteView("own", "me", 10, reactionCount = 5)
        val selfRepostView = noteView("own", "me", 10, repostedByPubkey = "me", repostedAt = 25)

        val result = mergeOwnNotesAndReposts(listOf(ownNote), listOf(selfRepostView), limit = 10)

        val merged = result.single()
        assertEquals("own", merged.event.id)
        assertEquals(5, merged.reactionCount)
        assertEquals("me", merged.repostedByPubkey)
        assertEquals(25L, merged.repostedAt)
    }

    @Test
    fun `given more combined entries than limit when merging then result is truncated after merge`() {
        val ownNotes = listOf(noteView("a", "me", 30), noteView("b", "me", 20))
        val repostViews = listOf(noteView("c", "other", 10, repostedByPubkey = "me", repostedAt = 40))

        val result = mergeOwnNotesAndReposts(ownNotes, repostViews, limit = 2)

        assertEquals(listOf("c", "a"), result.map { it.event.id })
    }

    @Test
    fun `given duplicate reference tags when building note views then engagement counts once per event`() {
        val author = "b".repeat(64)
        val note = event("note", author, 10)
        val reaction = event(
            id = "reaction",
            pubkey = "c".repeat(64),
            createdAt = 20,
            kind = Event.KIND_REACTION,
            tags = listOf(listOf("e", note.id), listOf("e", note.id))
        )

        val result = buildCachedNoteViews(
            allEvents = listOf(note, reaction),
            profilesByPubkey = emptyMap(),
            selectedNotes = listOf(note)
        )

        assertEquals(1, result.single().reactionCount)
        assertEquals(0, result.single().replyCount)
        assertEquals(0, result.single().repostCount)
    }

    @Test
    fun `given duplicate target tags when indexing engagement then event counts once`() {
        val targetId = "target"
        val reaction = event(
            id = "reaction",
            pubkey = "c".repeat(64),
            createdAt = 20,
            kind = Event.KIND_REACTION,
            tags = listOf(listOf("e", targetId), listOf("e", targetId))
        )
        val index = EventEngagementIndex()

        index.add(reaction)

        assertEquals(EngagementCounts(reactions = 1), index.snapshot()[targetId])
    }

    @Test
    fun `given indexed interaction when removed then target engagement is cleared`() {
        val targetId = "target"
        val reply = event(
            id = "reply",
            pubkey = "c".repeat(64),
            createdAt = 20,
            tags = listOf(listOf("e", targetId))
        )
        val index = EventEngagementIndex()
        index.add(reply)

        index.remove(reply.id)

        assertFalse(index.snapshot().containsKey(targetId))
    }

    @Test
    fun `given cached and encrypted interactions when merging engagement then counts are added`() {
        val targetId = "target"
        val encryptedRepost = event(
            id = "repost",
            pubkey = "a".repeat(64),
            createdAt = 20,
            kind = Event.KIND_REPOST,
            tags = listOf(listOf("e", targetId))
        )

        val result = mergeEngagementCounts(
            cachedCounts = mapOf(targetId to EngagementCounts(reactions = 2, replies = 1)),
            additionalSnapshot = buildAdditionalEngagementSnapshot(listOf(encryptedRepost))
        )

        assertEquals(EngagementCounts(reactions = 2, replies = 1, reposts = 1), result[targetId])
    }

    @Test
    fun `given unchanged additional events when building engagement snapshot twice then results are equal`() {
        val targetId = "target"
        val reaction = event(
            id = "reaction",
            pubkey = "c".repeat(64),
            createdAt = 20,
            kind = Event.KIND_REACTION,
            tags = listOf(listOf("e", targetId))
        )

        val first = buildAdditionalEngagementSnapshot(listOf(reaction))
        val second = buildAdditionalEngagementSnapshot(listOf(reaction))

        assertEquals(first, second)
        assertEquals(EngagementCounts(reactions = 1), first[targetId])
    }

    @Test
    fun `given empty additional events when building engagement snapshot then returns empty map`() {
        assertTrue(buildAdditionalEngagementSnapshot(emptyList()).isEmpty())
    }

    @Test
    fun `given current user pubkey when deciding in-memory cache then own events are excluded`() {
        val shouldStoreOwn = shouldStoreInMemoryCache(
            eventPubkey = "a".repeat(64),
            currentUserPubkey = "a".repeat(64)
        )
        val shouldStoreExternal = shouldStoreInMemoryCache(
            eventPubkey = "b".repeat(64),
            currentUserPubkey = "a".repeat(64)
        )

        assertFalse(shouldStoreOwn)
        assertTrue(shouldStoreExternal)
    }

    @Test
    fun `given anonymous session when deciding in-memory cache then events are allowed`() {
        val shouldStore = shouldStoreInMemoryCache(
            eventPubkey = "b".repeat(64),
            currentUserPubkey = null
        )

        assertTrue(shouldStore)
    }

    @Test
    fun `given current feed when newer bundle arrives then notes are inserted in stable order`() {
        val author = "b".repeat(64)
        val current = listOf(event("older", author, 10))
        val incoming = listOf(event("newer", author, 20))

        val result = updateFeedNotesIncrementally(
            currentNotes = current,
            incomingEvents = incoming,
            since = 0,
            limit = 10,
            authors = emptySet(),
            mutedPubkeys = emptySet(),
            excludedHashtagsLower = emptySet(),
            includeMentions = true,
            hideNsfw = false,
            currentNpub = null,
            currentUserPubkey = null,
            desiredTagsLower = emptySet()
        )

        assertEquals(listOf("newer", "older"), result.map { it.id })
    }

    @Test
    fun `given duplicate bundle when updating incrementally then ids stay unique and limit is enforced`() {
        val author = "b".repeat(64)
        val duplicate = event("same", author, 20)

        val result = updateFeedNotesIncrementally(
            currentNotes = listOf(duplicate, event("older", author, 10)),
            incomingEvents = listOf(duplicate, event("newest", author, 30)),
            since = 0,
            limit = 2,
            authors = emptySet(),
            mutedPubkeys = emptySet(),
            excludedHashtagsLower = emptySet(),
            includeMentions = true,
            hideNsfw = false,
            currentNpub = null,
            currentUserPubkey = null,
            desiredTagsLower = emptySet()
        )

        assertEquals(listOf("newest", "same"), result.map { it.id })
    }

    @Test
    fun `given same inputs when filtering incrementally and fully then results are equivalent`() {
        val visibleAuthor = "b".repeat(64)
        val mutedAuthor = "c".repeat(64)
        val current = listOf(event("current", visibleAuthor, 10))
        val incoming = listOf(
            event("desired", visibleAuthor, 30, tags = listOf(listOf("t", "kotlin"))),
            event("muted", mutedAuthor, 25, tags = listOf(listOf("t", "kotlin"))),
            event("excluded", visibleAuthor, 20, tags = listOf(listOf("t", "spam")))
        )

        val incremental = updateFeedNotesIncrementally(
            currentNotes = current,
            incomingEvents = incoming,
            since = 0,
            limit = 10,
            authors = emptySet(),
            mutedPubkeys = setOf(mutedAuthor),
            excludedHashtagsLower = setOf("spam"),
            includeMentions = true,
            hideNsfw = true,
            currentNpub = null,
            currentUserPubkey = "a".repeat(64),
            desiredTagsLower = setOf("kotlin")
        )
        val full = selectHybridFeedNotes(
            events = current + incoming,
            since = 0,
            limit = 10,
            authors = emptySet(),
            mutedPubkeys = setOf(mutedAuthor),
            excludedHashtagsLower = setOf("spam"),
            includeMentions = true,
            hideNsfw = true,
            currentNpub = null,
            currentUserPubkey = "a".repeat(64),
            desiredTagsLower = setOf("kotlin")
        )

        assertEquals(full, incremental)
    }

    @Test
    fun `given only additions when diffing own events then no removal is detected and delta is the new events`() {
        val previousIds = setOf("a", "b")
        val newEvents = listOf(event("a", "b".repeat(64), 10), event("b", "b".repeat(64), 20), event("c", "b".repeat(64), 30))
        val newIds = newEvents.mapTo(HashSet()) { it.id }

        assertFalse(hasRemovedOwnEvents(previousIds, newIds))
        assertEquals(listOf("c"), addedOwnEvents(newEvents, previousIds).map { it.id })
    }

    @Test
    fun `given a deletion when diffing own events then removal is detected`() {
        val previousIds = setOf("a", "b")
        val newIds = setOf("a")

        assertTrue(hasRemovedOwnEvents(previousIds, newIds))
    }

    @Test
    fun `given identical own event ids when diffing then no removal and no additions`() {
        val previousIds = setOf("a", "b")
        val sameEvents = listOf(event("a", "b".repeat(64), 10), event("b", "b".repeat(64), 20))

        assertFalse(hasRemovedOwnEvents(previousIds, previousIds))
        assertTrue(addedOwnEvents(sameEvents, previousIds).isEmpty())
    }

    @Test
    fun `given relay not tracked when deciding connection then should connect`() {
        val shouldConnect = RelayConnectionPolicy.shouldConnect(
            isTracked = false,
            isConnected = false,
            hasActiveSocket = false
        )

        assertTrue(shouldConnect)
    }

    @Test
    fun `given relay tracked and connected when deciding connection then should skip`() {
        val shouldConnect = RelayConnectionPolicy.shouldConnect(
            isTracked = true,
            isConnected = true,
            hasActiveSocket = true
        )

        assertFalse(shouldConnect)
    }

    @Test
    fun `given relay tracked with in-flight socket when deciding connection then should skip`() {
        val shouldConnect = RelayConnectionPolicy.shouldConnect(
            isTracked = true,
            isConnected = false,
            hasActiveSocket = true
        )

        assertFalse(shouldConnect)
    }

    @Test
    fun `given a connected relay no longer eligible when computing stale relays then it is returned`() {
        val staleUrls = RelayConnectionPolicy.staleRelayUrls(
            connectedUrls = setOf("wss://kept.example.com", "wss://disabled.example.com"),
            eligibleUrls = setOf("wss://kept.example.com")
        )

        assertEquals(setOf("wss://disabled.example.com"), staleUrls)
    }

    @Test
    fun `given every connected relay still eligible when computing stale relays then none are returned`() {
        val staleUrls = RelayConnectionPolicy.staleRelayUrls(
            connectedUrls = setOf("wss://kept.example.com"),
            eligibleUrls = setOf("wss://kept.example.com", "wss://not-yet-connected.example.com")
        )

        assertTrue(staleUrls.isEmpty())
    }

    @Test
    fun `given outbox profile kinds when computing social graph limit then includes metadata kind`() {
        val profileKinds = setOf(Event.KIND_METADATA)
        val socialGraphKinds = setOf(
            Event.KIND_CONTACT_LIST,
            Event.KIND_MUTED_USERS,
            Event.KIND_RELAY_LIST_METADATA,
            Event.KIND_DM_RELAY_LIST
        )

        val outboxProfileSocialGraphLimit = OutboxProfilePolicy.socialGraphLimit(
            profileKinds = profileKinds,
            socialGraphKinds = socialGraphKinds
        )

        assertEquals(profileKinds.size + socialGraphKinds.size, outboxProfileSocialGraphLimit)
    }

    private fun relay(isReadActive: Boolean = true, isWriteActive: Boolean = true, isDiscovered: Boolean = false) = Relay(
        id = "r1",
        url = "wss://relay.example.com",
        isReadActive = isReadActive,
        isWriteActive = isWriteActive,
        isDiscovered = isDiscovered
    )

    @Test
    fun `given discovered relay with read active when checking inbox channel then applies`() {
        // Discovered relays are a followed author's outbox, not strictly the user's own NIP-65
        // inbox relay set — but plenty of real-world clients aren't outbox-aware and publish a
        // reply to whatever relay they already have open (often their own outbox). Including
        // discovered relays here is a coverage win for those repliers; the #p-tag filter itself
        // stays narrow regardless of relay count.
        val discovered = relay(isReadActive = true, isWriteActive = false, isDiscovered = true)

        assertTrue(
            canApplyChannelToRelay(discovered, isInboxChannel = true, isOutboxChannel = false, isFeedChannel = false)
        )
    }

    @Test
    fun `given discovered relay with read inactive when checking inbox channel then still applies`() {
        // isReadEnabled/isReadActive now exclusively reflect a genuine kind:10002 declaration
        // (see UserRepositoryImpl.applyRelayListToLocalConfig) — a discovered relay never carries
        // a real one of its own, so isDiscovered alone must keep it eligible here, or the earlier
        // "read active" coverage-win test above would regress the moment that flag correctly
        // stops being force-true for discovered relays.
        val discovered = relay(isReadActive = false, isWriteActive = false, isDiscovered = true)

        assertTrue(
            canApplyChannelToRelay(discovered, isInboxChannel = true, isOutboxChannel = false, isFeedChannel = false)
        )
    }

    @Test
    fun `given non-discovered relay with read inactive when checking inbox channel then does not apply`() {
        val ownWriteOnlyRelay = relay(isReadActive = false, isWriteActive = true, isDiscovered = false)

        assertFalse(
            canApplyChannelToRelay(ownWriteOnlyRelay, isInboxChannel = true, isOutboxChannel = false, isFeedChannel = false)
        )
    }

    @Test
    fun `given search-active relay with read inactive when checking an unclassified channel then applies`() {
        // The "anything else" branch (e.g. NIP-50 search) — a relay declared via the user's own
        // NIP-51 search list is still fair game for search reads even though it no longer carries
        // a real isReadActive (see applySearchRelayListToLocalConfig's doc comment).
        val searchRelay = relay(isReadActive = false, isWriteActive = false, isDiscovered = false)
            .copy(isSearchActive = true)

        assertTrue(
            canApplyChannelToRelay(searchRelay, isInboxChannel = false, isOutboxChannel = false, isFeedChannel = false)
        )
    }

    @Test
    fun `given plain relay with everything inactive when checking an unclassified channel then does not apply`() {
        val plainRelay = relay(isReadActive = false, isWriteActive = false, isDiscovered = false)

        assertFalse(
            canApplyChannelToRelay(plainRelay, isInboxChannel = false, isOutboxChannel = false, isFeedChannel = false)
        )
    }

    @Test
    fun `given own read relay when checking inbox channel then applies`() {
        val ownRelay = relay(isReadActive = true, isWriteActive = false, isDiscovered = false)

        assertTrue(
            canApplyChannelToRelay(ownRelay, isInboxChannel = true, isOutboxChannel = false, isFeedChannel = false)
        )
    }

    @Test
    fun `given discovered relay when checking feed channel then still applies`() {
        val discovered = relay(isReadActive = true, isWriteActive = false, isDiscovered = true)

        assertTrue(
            canApplyChannelToRelay(discovered, isInboxChannel = false, isOutboxChannel = false, isFeedChannel = true)
        )
    }

    @Test
    fun `given write-only relay when checking feed channel then does not apply`() {
        // Feed is a read operation; a write-only relay is one the user explicitly opted
        // out of reading from — NIP-65 has no third "feed" role to justify an exception.
        val writeOnlyRelay = relay(isReadActive = false, isWriteActive = true, isDiscovered = false)

        assertFalse(
            canApplyChannelToRelay(writeOnlyRelay, isInboxChannel = false, isOutboxChannel = false, isFeedChannel = true)
        )
    }

    @Test
    fun `given search-active relay with read inactive when checking feed channel then applies`() {
        // A relay declared only for NIP-50 search is still a real, reachable relay — the
        // normal timeline should be pulled from it too, not just search queries.
        val searchOnlyRelay = relay(isReadActive = false, isWriteActive = false, isDiscovered = false)
            .copy(isSearchActive = true)

        assertTrue(
            canApplyChannelToRelay(searchOnlyRelay, isInboxChannel = false, isOutboxChannel = false, isFeedChannel = true)
        )
    }

    @Test
    fun `given index-active relay with read inactive when checking feed channel then applies`() {
        val indexOnlyRelay = relay(isReadActive = false, isWriteActive = false, isDiscovered = false)
            .copy(isIndexActive = true)

        assertTrue(
            canApplyChannelToRelay(indexOnlyRelay, isInboxChannel = false, isOutboxChannel = false, isFeedChannel = true)
        )
    }

    @Test
    fun `given write-only relay when checking outbox channel then applies`() {
        val writeOnlyRelay = relay(isReadActive = false, isWriteActive = true, isDiscovered = false)

        assertTrue(
            canApplyChannelToRelay(writeOnlyRelay, isInboxChannel = false, isOutboxChannel = true, isFeedChannel = false)
        )
    }

    @Test
    fun `given author with own outbox relays when computing authors per relay then routes to that relay only`() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice, bob),
            outboxRelaysFor = { pubkey -> if (pubkey == alice) listOf("wss://alice.relay") else emptyList() }
        )

        assertEquals(setOf(alice), result["wss://alice.relay"])
        assertTrue(result.values.none { bob in it })
    }

    @Test
    fun `given an author outbox relay on the aggregator exclude list when computing authors per relay then it is never routed to`() {
        val alice = "a".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice),
            outboxRelaysFor = { listOf("wss://feeds.nostr.band", "wss://alice.relay") }
        )

        assertTrue(result.keys.none { it.contains("feeds.nostr.band") })
        assertEquals(setOf(alice), result["wss://alice.relay"])
    }

    @Test
    fun `given a normal relay host when checking outbox routing exclusion then not excluded`() {
        assertFalse(isOutboxRoutingExcluded("wss://alice.relay"))
    }

    @Test
    fun `given each aggregator host when checking outbox routing exclusion then excluded`() {
        assertTrue(isOutboxRoutingExcluded("wss://feeds.nostr.band"))
        assertTrue(isOutboxRoutingExcluded("wss://filter.nostr.wine"))
        assertTrue(isOutboxRoutingExcluded("wss://nwc.primal.net"))
        assertTrue(isOutboxRoutingExcluded("wss://relay.getalby.com"))
    }

    @Test
    fun `given author with no outbox relays when computing authors per relay then it is absent from the map`() {
        // No fallback tier: a fixed/snapshot relay list the author isn't actually reachable
        // through routes nowhere, silently dropping their REQ everywhere and starving the very
        // NIP-65 fetch needed to learn their real outbox. Being absent here means the caller
        // (scopeAuthorsForRelay) treats them as "unknown" and broadcasts unscoped instead.
        val alice = "a".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice),
            outboxRelaysFor = { emptyList() }
        )

        assertTrue(result.values.none { alice in it })
    }

    @Test
    fun `given author with no declared outbox but a seen relay hint when computing authors per relay then falls back to the hint`() {
        val alice = "a".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice),
            outboxRelaysFor = { emptyList() },
            hintRelaysFor = { pubkey -> if (pubkey == alice) listOf("wss://hint.relay") else emptyList() }
        )

        assertEquals(setOf(alice), result["wss://hint.relay"])
    }

    @Test
    fun `given author with a declared outbox when computing authors per relay then hint tier is never consulted`() {
        val alice = "a".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice),
            outboxRelaysFor = { listOf("wss://alice.outbox") },
            hintRelaysFor = { listOf("wss://should-not-be-used.relay") }
        )

        assertEquals(setOf(alice), result["wss://alice.outbox"])
        assertTrue(result.keys.none { it.contains("should-not-be-used") })
    }

    @Test
    fun `given no outbox and no hint when computing authors per relay then still absent from the map`() {
        val alice = "a".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice),
            outboxRelaysFor = { emptyList() }
        )

        assertTrue(result.values.none { alice in it })
    }

    @Test
    fun `given relay urls with different casing when computing authors per relay then normalizes to same key`() {
        val alice = "a".repeat(64)
        val result = computeAuthorsPerRelay(
            followedPubkeys = setOf(alice),
            outboxRelaysFor = { listOf("WSS://Alice.Relay/") }
        )

        assertEquals(setOf(alice), result["wss://alice.relay"])
    }

    @Test
    fun `given relay covers only some requested authors when scoping then only covered authors remain`() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val scoped = scopeAuthorsForRelay(
            relayUrl = "wss://alice.relay",
            requestedAuthors = setOf(alice, bob),
            authorsWithKnownOutbox = setOf(alice, bob),
            authorsPerRelay = mapOf("wss://alice.relay" to setOf(alice))
        )

        assertEquals(setOf(alice), scoped)
    }

    @Test
    fun `given requested author outside known-outbox set when scoping then it is always kept`() {
        val alice = "a".repeat(64)
        val unknownOutboxAuthor = "c".repeat(64)
        val scoped = scopeAuthorsForRelay(
            relayUrl = "wss://alice.relay",
            requestedAuthors = setOf(alice, unknownOutboxAuthor),
            authorsWithKnownOutbox = setOf(alice),
            authorsPerRelay = mapOf("wss://alice.relay" to emptySet())
        )

        assertEquals(setOf(unknownOutboxAuthor), scoped)
    }

    @Test
    fun `given relay with no routing data when scoping known-outbox author then it is dropped`() {
        val alice = "a".repeat(64)
        val scoped = scopeAuthorsForRelay(
            relayUrl = "wss://unrelated.relay",
            requestedAuthors = setOf(alice),
            authorsWithKnownOutbox = setOf(alice),
            authorsPerRelay = emptyMap()
        )

        assertTrue(scoped.isEmpty())
    }

    @Test
    fun `given discovered relay when scoping unknown-outbox author then it is dropped not broadcast`() {
        // A discovered relay was added specifically because it covers a handful of known
        // authors — it shouldn't also be blasted with every other still-unresolved author on
        // every cycle, unlike a general relay (includeUnknownAuthors=true, the default).
        val alice = "a".repeat(64)
        val unknownOutboxAuthor = "c".repeat(64)
        val scoped = scopeAuthorsForRelay(
            relayUrl = "wss://alice.relay",
            requestedAuthors = setOf(alice, unknownOutboxAuthor),
            authorsWithKnownOutbox = setOf(alice),
            authorsPerRelay = mapOf("wss://alice.relay" to setOf(alice)),
            includeUnknownAuthors = false
        )

        assertEquals(setOf(alice), scoped)
    }

    @Test
    fun `given discovered relay when checking outbox sweep channel then does not apply`() {
        val discovered = relay(isReadActive = true, isWriteActive = false, isDiscovered = true)

        assertFalse(
            canApplyChannelToRelay(
                discovered,
                isInboxChannel = false,
                isOutboxChannel = false,
                isFeedChannel = false,
                isOutboxSweepChannel = true
            )
        )
    }

    @Test
    fun `given own read relay when checking outbox sweep channel then applies`() {
        val ownRelay = relay(isReadActive = true, isWriteActive = false, isDiscovered = false)

        assertTrue(
            canApplyChannelToRelay(
                ownRelay,
                isInboxChannel = false,
                isOutboxChannel = false,
                isFeedChannel = false,
                isOutboxSweepChannel = true
            )
        )
    }

    @Test
    fun `given relay advertising nip45 when checking support then returns true`() {
        val nip45Relay = relay().copy(relayInfo = RelayInfo(supportedNips = listOf(1, 11, 45)))

        assertTrue(relaySupportsNip(nip45Relay, nip = 45))
    }

    @Test
    fun `given relay info without nip45 when checking support then returns false`() {
        val relayWithoutNip45 = relay().copy(relayInfo = RelayInfo(supportedNips = listOf(1, 11, 50)))

        assertFalse(relaySupportsNip(relayWithoutNip45, nip = 45))
    }

    @Test
    fun `given relay with no fetched nip11 info when checking support then returns false`() {
        // Unknown support is never assumed-supported — a relay we haven't fetched info for
        // yet is treated the same as one that explicitly doesn't list the NIP.
        val relayWithoutInfo = relay().copy(relayInfo = null)

        assertFalse(relaySupportsNip(relayWithoutInfo, nip = 45))
    }

    @Test
    fun `given null relay when checking support then returns false`() {
        assertFalse(relaySupportsNip(null, nip = 45))
    }

    @Test
    fun `given relay max limit lower than filter limit when clamping then filter limit is reduced`() {
        val filters = listOf(EventFilter(limit = 500))

        val result = clampFiltersToRelayLimit(filters, relayMaxLimit = 100)

        assertEquals(100, result.single().limit)
    }

    @Test
    fun `given relay max limit higher than filter limit when clamping then filter is unchanged`() {
        val filters = listOf(EventFilter(limit = 50))

        val result = clampFiltersToRelayLimit(filters, relayMaxLimit = 500)

        assertEquals(50, result.single().limit)
    }

    @Test
    fun `given relay with no advertised max limit when clamping then filters are unchanged`() {
        val filters = listOf(EventFilter(limit = 500))

        val result = clampFiltersToRelayLimit(filters, relayMaxLimit = null)

        assertEquals(filters, result)
    }

    @Test
    fun `given relay advertising a non-positive max limit when clamping then filters are unchanged`() {
        val filters = listOf(EventFilter(limit = 500))

        val result = clampFiltersToRelayLimit(filters, relayMaxLimit = 0)

        assertEquals(filters, result)
    }
}
