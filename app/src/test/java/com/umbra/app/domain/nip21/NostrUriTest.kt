package com.umbra.app.domain.nip21

import com.umbra.app.domain.nip19.Bech32Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrUriTest {

    private val pubkey = "a".repeat(64)
    private val eventId = "b".repeat(64)

    @Test
    fun `given nostr prefixed npub when resolving then returns profile`() {
        val npub = Bech32Encoder.encodeNpub(pubkey)

        val entity = resolveNostrUri("nostr:$npub")

        assertEquals(NostrUriEntity.Profile(pubkey), entity)
    }

    @Test
    fun `given nostr double slash prefixed nprofile when resolving then returns profile with its relay hints`() {
        val nprofile = Bech32Encoder.encodeNprofile(pubkey, relayUrls = listOf("wss://relay.example"))

        val entity = resolveNostrUri("nostr://$nprofile")

        assertEquals(NostrUriEntity.Profile(pubkey, relays = listOf("wss://relay.example")), entity)
    }

    @Test
    fun `given bare note when resolving then returns note without a nostr prefix`() {
        val note = Bech32Encoder.encodeNote(eventId)

        val entity = resolveNostrUri(note)

        assertEquals(NostrUriEntity.Note(eventId), entity)
    }

    @Test
    fun `given nostr prefixed nevent with relay hints when resolving then returns note with those relays`() {
        val nevent = Bech32Encoder.encodeNevent(eventId, relayUrls = listOf("wss://relay.example", "wss://relay2.example"))

        val entity = resolveNostrUri("nostr:$nevent")

        assertEquals(NostrUriEntity.Note(eventId, relays = listOf("wss://relay.example", "wss://relay2.example")), entity)
    }

    @Test
    fun `given bare 64 hex when resolving then returns null since it is ambiguous`() {
        assertNull(resolveNostrUri(pubkey))
    }

    @Test
    fun `given garbage when resolving then returns null`() {
        assertNull(resolveNostrUri("not a nostr uri"))
    }

    @Test
    fun `given trailing punctuation when stripping prefix then removed`() {
        assertEquals("npub1abc", stripNostrUriPrefix("nostr:npub1abc."))
        assertEquals("npub1abc", stripNostrUriPrefix("nostr://npub1abc,"))
    }

    @Test
    fun `given at-prefixed npub when resolving then returns profile`() {
        // Not NIP-21 (which only defines nostr:/nostr://), but some clients write mentions this
        // way instead — see stripNostrUriPrefix's doc comment.
        val npub = Bech32Encoder.encodeNpub(pubkey)

        val entity = resolveNostrUri("@$npub")

        assertEquals(NostrUriEntity.Profile(pubkey), entity)
    }

    @Test
    fun `given at-prefixed nprofile when resolving then returns profile with its relay hints`() {
        val nprofile = Bech32Encoder.encodeNprofile(pubkey, relayUrls = listOf("wss://relay.example"))

        val entity = resolveNostrUri("@$nprofile")

        assertEquals(NostrUriEntity.Profile(pubkey, relays = listOf("wss://relay.example")), entity)
    }

    @Test
    fun `given at prefix when stripping then removed`() {
        assertEquals("npub1abc", stripNostrUriPrefix("@npub1abc"))
    }
}
