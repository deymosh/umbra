package com.umbra.app.domain.nipb7

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class UserServerListTest {

    private fun event(tags: List<List<String>>): Event = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 42L,
        kind = Event.KIND_BLOSSOM_SERVER_LIST,
        tags = tags,
        content = "",
        sig = "c".repeat(128)
    )

    @Test
    fun `given server tags when parsing then preserves priority order`() {
        val parsed = UserServerList.fromEvent(
            event(
                listOf(
                    listOf("server", "https://cdn.self.hosted"),
                    listOf("server", "https://cdn.satellite.earth")
                )
            )
        )

        assertEquals("b".repeat(64), parsed.pubkey)
        assertEquals(listOf("https://cdn.self.hosted", "https://cdn.satellite.earth"), parsed.servers)
        assertEquals(42L, parsed.lastUpdated)
    }

    @Test
    fun `given duplicate and unrelated tags when parsing then dedupes and ignores others`() {
        val parsed = UserServerList.fromEvent(
            event(
                listOf(
                    listOf("server", "https://cdn.example.com"),
                    listOf("server", "https://cdn.example.com"),
                    listOf("relay", "wss://relay.example.com")
                )
            )
        )

        assertEquals(listOf("https://cdn.example.com"), parsed.servers)
    }

    @Test
    fun `given no server tags when parsing then returns empty list`() {
        assertEquals(emptyList<String>(), UserServerList.fromEvent(event(emptyList())).servers)
    }

    @Test
    fun `given list with servers when resolving preferred upload server then returns the first one`() {
        val list = UserServerList(
            pubkey = "b".repeat(64),
            servers = listOf("https://cdn.self.hosted", "https://cdn.satellite.earth")
        )

        assertEquals("https://cdn.self.hosted", list.preferredUploadServer())
    }

    @Test
    fun `given list with no servers when resolving preferred upload server then falls back to default`() {
        val list = UserServerList(pubkey = "b".repeat(64), servers = emptyList())

        assertEquals(DefaultBlossomServer.URL, list.preferredUploadServer())
    }

    @Test
    fun `given no server list when resolving preferred upload server then falls back to default`() {
        val noList: UserServerList? = null

        assertEquals(DefaultBlossomServer.URL, noList.preferredUploadServer())
    }
}
