package com.umbra.app.domain.usecase

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.feed.mergeActiveFeedFilters
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip02.ContactList
import com.umbra.app.domain.nip51.encodePrivateTags
import com.umbra.app.testutil.fakes.FakeContactListRepository
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeNip44Gateway
import com.umbra.app.testutil.fakes.FakeUserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserveFeedAuthorsUseCaseTest {

    private val me = "a".repeat(64)
    private val followed = "b".repeat(64)
    private val inSet = "c".repeat(64)
    private val privatelyInSet = "d".repeat(64)
    private val inOtherSet = "e".repeat(64)
    private val nip44 = FakeNip44Gateway()

    private fun set(d: String, createdAt: Long, members: List<String>, content: String = "") = Event(
        id = "$d$createdAt".padEnd(64, '0'),
        pubkey = me,
        createdAt = createdAt,
        kind = Event.KIND_FOLLOW_SET,
        tags = listOf(listOf("d", d)) + members.map { listOf("p", it) },
        content = content,
        sig = "s".repeat(128)
    )

    private fun subject(events: List<Event>, follows: Set<String> = emptySet()): ObserveFeedAuthorsUseCase {
        val prefs = FakeUserPreferences(initialPubkey = me)
        val contacts = object : FakeContactListRepository() {
            override fun getContactList(pubkey: String): Flow<ContactList?> = flowOf(ContactList(me, follows, 1))
        }
        return ObserveFeedAuthorsUseCase(FakeEventRepository(recentEvents = events), contacts, prefs, DecryptOwnListItemsUseCase(nip44, prefs))
    }

    private val events
        get() = listOf(
            set("friends", 1, listOf("f".repeat(64))),
            set("friends", 2, listOf(inSet), content = nip44.encode(encodePrivateTags(listOf(listOf("p", privatelyInSet))))),
            set("work", 3, listOf(inOtherSet))
        )

    @Test
    fun `given an unscoped filter when resolving then nobody is singled out`() = runTest {
        assertEquals(emptySet<String>(), subject(events, setOf(followed))(FeedFilter(id = "f", name = "All")).first())
    }

    @Test
    fun `given follows and a set when resolving then both count, from the set's newest revision, private members included`() = runTest {
        val filter = FeedFilter(id = "f", name = "Mine", scopeToFollows = true, followSets = setOf("${Event.KIND_FOLLOW_SET}:$me:friends"))

        assertEquals(setOf(followed, inSet, privatelyInSet), subject(events, setOf(followed))(filter).first())
    }

    @Test
    fun `given a scoped filter with nobody in it when resolving then only the user is left, not everyone`() = runTest {
        val filter = FeedFilter(id = "f", name = "Gone", followSets = setOf("${Event.KIND_FOLLOW_SET}:$me:deleted"))

        assertEquals(setOf(me), subject(events)(filter).first())
    }

    @Test
    fun `given filters naming different sets when merging then the sets add up and the feed is scoped`() {
        val merged = mergeActiveFeedFilters(
            listOf(
                FeedFilter(id = "a", name = "A", followSets = setOf("x")),
                FeedFilter(id = "b", name = "B", followSets = setOf("y"))
            )
        )

        assertEquals(setOf("x", "y"), merged.followSets)
        assertTrue(merged.isScoped)
    }
}
