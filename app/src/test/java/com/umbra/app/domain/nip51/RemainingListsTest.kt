package com.umbra.app.domain.nip51

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemainingListsTest {

    private fun event(kind: Int, pubkey: String, tags: List<List<String>>, content: String = ""): Event = Event(
        id = "a".repeat(64),
        pubkey = pubkey,
        createdAt = 42L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128)
    )

    // Bookmark list (10003)

    @Test
    fun `given non bookmark kind when extracting bookmark list then returns null`() {
        assertNull(extractBookmarkList(event(Event.KIND_TEXT_NOTE, "b".repeat(64), emptyList())))
    }

    @Test
    fun `given e and a tags when extracting bookmark list then splits notes and articles`() {
        val noteId = "1".repeat(64)
        val address = "30023:${"2".repeat(64)}:article"
        val list = extractBookmarkList(
            event(Event.KIND_BOOKMARK_LIST, "B".repeat(64), listOf(listOf("e", noteId), listOf("a", address)))
        )

        assertEquals(setOf(noteId), list?.noteIds)
        assertEquals(setOf(address), list?.articleAddresses)
        assertEquals("b".repeat(64), list?.ownerPubkey)
    }

    // Communities list (10004)

    @Test
    fun `given non communities kind when extracting communities list then returns null`() {
        assertNull(extractCommunitiesList(event(Event.KIND_TEXT_NOTE, "b".repeat(64), emptyList())))
    }

    @Test
    fun `given a tags when extracting communities list then returns addresses`() {
        val address = "34550:${"3".repeat(64)}:community"
        val list = extractCommunitiesList(event(Event.KIND_COMMUNITIES_LIST, "b".repeat(64), listOf(listOf("a", address))))

        assertEquals(setOf(address), list?.communityAddresses)
    }

    // Blocked relays list (10006)

    @Test
    fun `given non blocked relays kind when extracting then returns null`() {
        assertNull(extractBlockedRelaysList(event(Event.KIND_TEXT_NOTE, "b".repeat(64), emptyList())))
    }

    @Test
    fun `given relay tags when extracting blocked relays list then returns urls`() {
        val list = extractBlockedRelaysList(
            event(Event.KIND_BLOCKED_RELAYS, "b".repeat(64), listOf(listOf("relay", "wss://bad.relay")))
        )

        assertEquals(setOf("wss://bad.relay"), list?.relayUrls)
    }

    // Search relays list (10007)

    @Test
    fun `given non search relays kind when extracting then returns null`() {
        assertNull(extractSearchRelaysList(event(Event.KIND_TEXT_NOTE, "b".repeat(64), emptyList())))
    }

    @Test
    fun `given relay tags when extracting search relays list then returns urls`() {
        val list = extractSearchRelaysList(
            event(Event.KIND_SEARCH_RELAYS, "b".repeat(64), listOf(listOf("relay", "wss://search.relay")))
        )

        assertEquals(setOf("wss://search.relay"), list?.relayUrls)
        assertNull(list?.encryptedContent)
    }

    @Test
    fun `given non blank content when extracting search relays list then captures encryptedContent`() {
        val list = extractSearchRelaysList(
            event(Event.KIND_SEARCH_RELAYS, "b".repeat(64), emptyList(), content = "2:some-nip44-ciphertext")
        )

        assertEquals("2:some-nip44-ciphertext", list?.encryptedContent)
    }

    // Index relays list (10086)

    @Test
    fun `given non index relays kind when extracting then returns null`() {
        assertNull(extractIndexRelaysList(event(Event.KIND_TEXT_NOTE, "b".repeat(64), emptyList())))
    }

    @Test
    fun `given relay tags when extracting index relays list then returns urls`() {
        val list = extractIndexRelaysList(
            event(Event.KIND_INDEX_RELAYS, "b".repeat(64), listOf(listOf("relay", "wss://index.relay")))
        )

        assertEquals(setOf("wss://index.relay"), list?.relayUrls)
        assertNull(list?.encryptedContent)
    }

    @Test
    fun `given non blank content when extracting index relays list then captures encryptedContent`() {
        val list = extractIndexRelaysList(
            event(Event.KIND_INDEX_RELAYS, "b".repeat(64), emptyList(), content = "2:another-nip44-ciphertext")
        )

        assertEquals("2:another-nip44-ciphertext", list?.encryptedContent)
    }

    // Interests list (10015)

    @Test
    fun `given non interests kind when extracting then returns null`() {
        assertNull(extractInterestsList(event(Event.KIND_TEXT_NOTE, "b".repeat(64), emptyList())))
    }

    @Test
    fun `given t and a tags when extracting interests list then splits hashtags and sets`() {
        val address = "30015:${"4".repeat(64)}:set"
        val list = extractInterestsList(
            event(Event.KIND_INTERESTS_LIST, "b".repeat(64), listOf(listOf("t", "Nostr"), listOf("a", address)))
        )

        assertEquals(setOf("nostr"), list?.hashtags)
        assertEquals(setOf(address), list?.interestSetAddresses)
    }
}
