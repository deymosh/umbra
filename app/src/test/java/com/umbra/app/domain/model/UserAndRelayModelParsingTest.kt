package com.umbra.app.domain.model

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.profile.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAndRelayModelParsingTest {

    @Test
    fun `given_userProfileWithVaryingNames_when_gettingDisplay_then_correctBehavior`() {
        val withDisplay = UserProfile(pubkey = "a".repeat(64), displayName = "  Alice  ", name = "Name")
        val withNameOnly = UserProfile(pubkey = "b".repeat(64), displayName = " ", name = " Bob ")
        val fallback = UserProfile(pubkey = "12345678abcdef")

        assertEquals("Alice", withDisplay.getUserDisplayName())
        assertEquals("Bob", withNameOnly.getUserDisplayName())
        assertEquals("12345678", fallback.getUserDisplayName())

        assertTrue(UserProfile(pubkey = "x", nip05 = "alice@example.com").isVerified())
        assertFalse(UserProfile(pubkey = "x", nip05 = " ").isVerified())
    }

    @Test
    fun `given_jsonWithProfileFieldsOrInvalid_when_parsing_then_handlesCorrectly`() {
        val json = """
            {
                            "name": "alice",
                            "display_name": "Alice",
                            "picture": "https://example.com/a.png",
                            "nip05": "alice@example.com",
                            "lud16": "alice@ln.example"
            }
        """.trimIndent()

        val parsed = UserProfile.fromJSON("p".repeat(64), json, 1_700_000_000L)
        val fallback = UserProfile.fromJSON("q".repeat(64), "not-json", 1_700_000_000L)

        assertEquals("alice", parsed.name)
        assertEquals("Alice", parsed.displayName)
        assertEquals("https://example.com/a.png", parsed.picture)
        assertEquals("alice@example.com", parsed.nip05)
        assertEquals("alice@ln.example", parsed.lud16)
        assertEquals("q".repeat(64), fallback.pubkey)
    }

    @Test
    fun `given_eventWithRelayTags_when_parsing_then_expandsOutboxInbox`() {
        val event = Event(
            id = "e".repeat(64),
            pubkey = "p".repeat(64),
            createdAt = 123L,
            kind = Event.KIND_RELAY_LIST_METADATA,
            tags = listOf(
                listOf("r", "wss://write.one", "write"),
                listOf("r", "wss://read.one", "read"),
                listOf("r", "wss://general.one")
            ),
            content = "",
            sig = "s".repeat(128)
        )

        val metadata = RelayListMetadata.fromEvent(event)

        assertEquals(listOf("wss://write.one"), metadata.writeRelays)
        assertEquals(listOf("wss://read.one"), metadata.readRelays)
        assertEquals(listOf("wss://general.one"), metadata.allRelays)
        assertEquals(
            listOf("wss://write.one", "wss://general.one"),
            metadata.getOutboxRelays()
        )
        assertEquals(
            listOf("wss://read.one", "wss://general.one"),
            metadata.getInboxRelays()
        )
        assertEquals(
            listOf("wss://write.one", "wss://read.one", "wss://general.one"),
            metadata.getAllDeclaredRelays()
        )
    }

    @Test
    fun `given_eventWithDuplicateRelayTags_when_extracting_then_distinct`() {
        val event = Event(
            id = "d".repeat(64),
            pubkey = "u".repeat(64),
            createdAt = 99L,
            kind = Event.KIND_DM_RELAY_LIST,
            tags = listOf(
                listOf("relay", "wss://dm.one"),
                listOf("relay", "wss://dm.one"),
                listOf("relay", "wss://dm.two"),
                listOf("p", "ignored")
            ),
            content = "",
            sig = "s".repeat(128)
        )

        val dm = DmRelayList.fromEvent(event)

        assertEquals("u".repeat(64), dm.pubkey)
        assertEquals(listOf("wss://dm.one", "wss://dm.two"), dm.relays)
        assertEquals(99L, dm.lastUpdated)
    }
}
