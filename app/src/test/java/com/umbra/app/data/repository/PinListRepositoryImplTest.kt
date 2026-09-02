package com.umbra.app.data.repository

import com.umbra.app.domain.nip01.Event
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinListRepositoryImplTest {

    private fun pinListEvent(
        id: String,
        owner: String,
        createdAt: Long,
        pinnedEventIds: List<String>
    ): Event = Event(
        id = id,
        pubkey = owner,
        createdAt = createdAt,
        kind = Event.KIND_PINNED_EVENTS,
        tags = pinnedEventIds.map { listOf("e", it) },
        content = "",
        sig = "s".repeat(128)
    )

    @Test
    fun `given owner event stream when ingesting then latest event wins per owner`() = runBlocking {
        val owner = "a".repeat(64)
        val stale = pinListEvent("e1".padEnd(64, '0'), owner, createdAt = 100L, pinnedEventIds = listOf("b".repeat(64)))
        val fresh = pinListEvent("e2".padEnd(64, '0'), owner, createdAt = 200L, pinnedEventIds = listOf("c".repeat(64), "d".repeat(64)))
        val repo = FakeEventRepository(recentEvents = listOf(stale, fresh))
        val prefs = FakeUserPreferences(initialPubkey = owner)

        val pinListRepository = PinListRepositoryImpl(prefs, repo)

        val list = pinListRepository.getPinList(owner).first { it != null }
        assertEquals(setOf("c".repeat(64), "d".repeat(64)), list?.pinnedEventIds)
        assertEquals(200L, list?.updatedAt)
    }

    @Test
    fun `given no prior pins when pinning then adds event id to current set`() = runBlocking {
        val owner = "a".repeat(64)
        val target = "b".repeat(64)
        val repo = FakeEventRepository(recentEvents = emptyList())
        val prefs = FakeUserPreferences(initialPubkey = owner)
        val pinListRepository = PinListRepositoryImpl(prefs, repo)

        val result = pinListRepository.pin(target)

        assertTrue(result.isSuccess)
        assertEquals(setOf(target), pinListRepository.getCurrentPinnedEventIds())
        assertTrue(pinListRepository.isPinned(target))
    }

    @Test
    fun `given pinned event when unpinning then removes it from current set`() = runBlocking {
        val owner = "a".repeat(64)
        val target = "b".repeat(64)
        val repo = FakeEventRepository(recentEvents = emptyList())
        val prefs = FakeUserPreferences(initialPubkey = owner)
        val pinListRepository = PinListRepositoryImpl(prefs, repo)

        pinListRepository.pin(target)
        val result = pinListRepository.unpin(target)

        assertTrue(result.isSuccess)
        assertFalse(pinListRepository.isPinned(target))
        assertEquals(emptySet<String>(), pinListRepository.getCurrentPinnedEventIds())
    }

    @Test
    fun `given no authenticated user when pinning then fails`() = runBlocking {
        val repo = FakeEventRepository(recentEvents = emptyList())
        val prefs = FakeUserPreferences(initialPubkey = null)
        val pinListRepository = PinListRepositoryImpl(prefs, repo)

        val result = pinListRepository.pin("b".repeat(64))

        assertTrue(result.isFailure)
    }
}
