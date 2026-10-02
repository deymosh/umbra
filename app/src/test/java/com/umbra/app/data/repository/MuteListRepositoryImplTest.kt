package com.umbra.app.data.repository

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.encodePrivateTags
import com.umbra.app.domain.usecase.DecryptOwnListItemsUseCase
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeNip44Gateway
import com.umbra.app.testutil.fakes.FakeUserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteListRepositoryImplTest {

    private val nip44 = FakeNip44Gateway()

    private fun muteListEvent(
        id: String,
        owner: String,
        createdAt: Long,
        mutedPubkeys: List<String>
    ): Event = Event(
        id = id,
        pubkey = owner,
        createdAt = createdAt,
        kind = Event.KIND_MUTED_USERS,
        tags = mutedPubkeys.map { listOf("p", it) },
        content = "",
        sig = "s".repeat(128)
    )

    @Test
    fun `given owner event stream when ingesting then latest event wins per owner`() = runBlocking {
        val owner = "a".repeat(64)
        val stale = muteListEvent("e1".padEnd(64, '0'), owner, createdAt = 100L, mutedPubkeys = listOf("b".repeat(64)))
        val fresh = muteListEvent("e2".padEnd(64, '0'), owner, createdAt = 200L, mutedPubkeys = listOf("c".repeat(64), "d".repeat(64)))
        val repo = FakeEventRepository(recentEvents = listOf(stale, fresh))
        val prefs = FakeUserPreferences(initialPubkey = owner)

        val muteListRepository = MuteListRepositoryImpl(prefs, repo, DecryptOwnListItemsUseCase(nip44, prefs))

        val list = muteListRepository.getMuteList(owner).first { it != null }
        assertEquals(setOf("c".repeat(64), "d".repeat(64)), list?.mutedPubkeys)
        assertEquals(200L, list?.updatedAt)
    }

    @Test
    fun `given fresh event cache entry evicted when a stale event resurfaces in a later batch then cache keeps the fresh version`() = runBlocking {
        // Simulates EventLruCache eviction: a correctly-ingested fresh event's cache entry can be
        // evicted by unrelated feed activity before an older, slow-to-arrive event for the same
        // author surfaces in a later, independent observeRecentEvents batch. That later batch must
        // not be allowed to downgrade the cache back to the older event's content.
        val currentUser = "z".repeat(64)
        val author = "a".repeat(64)
        val fresh = muteListEvent("e2".padEnd(64, '0'), author, createdAt = 200L, mutedPubkeys = listOf("c".repeat(64)))
        val stale = muteListEvent("e1".padEnd(64, '0'), author, createdAt = 100L, mutedPubkeys = listOf("b".repeat(64)))
        val repo = FakeEventRepository(
            recentEvents = listOf(fresh),
            recentEventsFlow = flow {
                emit(listOf(fresh))
                emit(listOf(stale))
            }
        )
        val prefs = FakeUserPreferences(initialPubkey = currentUser)

        val muteListRepository = MuteListRepositoryImpl(prefs, repo, DecryptOwnListItemsUseCase(nip44, prefs))

        // .first{it != null} alone would race the second (stale) batch: it returns — and cancels
        // upstream collection — as soon as the first non-null value (fresh) appears, whether or not
        // the buggy overwrite from the stale batch has landed yet. Wait past that, then re-read the
        // now-settled current value so this test actually exercises the guard against both batches
        // having been ingested, not just the first one.
        muteListRepository.getMuteList(author).first { it != null }
        delay(100)
        val list = muteListRepository.getMuteList(author).first()

        assertEquals(setOf("c".repeat(64)), list?.mutedPubkeys)
        assertEquals(200L, list?.updatedAt)
    }

    @Test
    fun `given the user's own list when reading then hashtags, words, threads and private items all count`() = runBlocking {
        val owner = "a".repeat(64)
        val privatePerson = "b".repeat(64)
        val publicPerson = "c".repeat(64)
        val thread = "d".repeat(64)
        val event = Event(
            id = "e1".padEnd(64, '0'),
            pubkey = owner,
            createdAt = 10L,
            kind = Event.KIND_MUTED_USERS,
            tags = listOf(listOf("p", publicPerson), listOf("t", "Spam"), listOf("word", "Giveaway"), listOf("e", thread)),
            content = nip44.encode(encodePrivateTags(listOf(listOf("p", privatePerson), listOf("word", "airdrop")))),
            sig = "s".repeat(128)
        )
        val prefs = FakeUserPreferences(initialPubkey = owner)
        val muteListRepository = MuteListRepositoryImpl(prefs, FakeEventRepository(recentEvents = listOf(event)), DecryptOwnListItemsUseCase(nip44, prefs))

        val list = muteListRepository.getMuteList(owner).first { it != null }!!

        assertEquals(setOf(privatePerson, publicPerson), list.mutedPubkeys)
        assertEquals(setOf("spam"), list.mutedHashtags)
        assertEquals(setOf("giveaway", "airdrop"), list.mutedWords)
        assertEquals(setOf(thread), list.mutedThreads)
        assertTrue(list.items.single { it.value == privatePerson }.isPrivate)
        assertEquals(setOf(privatePerson, publicPerson), muteListRepository.getCurrentMutedPubkeys())
    }
}
