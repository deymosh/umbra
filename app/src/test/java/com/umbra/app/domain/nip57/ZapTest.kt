package com.umbra.app.domain.nip57

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapTest {

    @Test
    fun `given lightning address when resolving then well-known lnurlp url is built`() {
        assertEquals("https://getalby.com/.well-known/lnurlp/alice", lightningAddressToPayUrl("Alice@GetAlby.com"))
        assertEquals("http://abc.onion/.well-known/lnurlp/bob", lightningAddressToPayUrl("bob@abc.onion"))
    }

    @Test
    fun `given malformed address when resolving then null`() {
        assertNull(lightningAddressToPayUrl("no-at-sign"))
        assertNull(lightningAddressToPayUrl("a@b@c.com"))
        assertNull(lightningAddressToPayUrl("alice@localhost"))
        assertNull(lightningAddressToPayUrl("al ice@x.com"))
    }

    @Test
    fun `given url when encoding lnurl then it round trips`() {
        val url = "https://getalby.com/.well-known/lnurlp/alice"
        val lnurl = Bech32Encoder.encodeLnurl(url)
        assertTrue(lnurl.startsWith("LNURL1"))
        assertEquals(url, Bech32Encoder.decodeLnurl(lnurl))
        assertEquals(url, Bech32Encoder.decodeLnurl("lightning:" + lnurl.lowercase()))
        assertNull(Bech32Encoder.decodeLnurl("npub1xyz"))
    }

    @Test
    fun `given pay info when checking zap support then nostr flag and pubkey are both required`() {
        val base = LnurlPayInfo("https://x/cb", 1_000, 10_000_000, true, "a".repeat(64), 0, "LNURL1X")
        assertTrue(base.supportsZaps)
        assertFalse(base.copy(nostrPubkey = null).supportsZaps)
        assertFalse(base.copy(allowsNostr = false).supportsZaps)
        assertTrue(base.accepts(21_000))
        assertFalse(base.accepts(500))
    }

    @Test
    fun `given maxSendable omitted or zero when checking amounts then max is unbounded`() {
        val zero = LnurlPayInfo("https://x/cb", 1_000, 0, false, null, 0, "LNURL1X")
        assertTrue(zero.accepts(1_000))
        assertTrue(zero.accepts(1_000_000))
        assertEquals(Long.MAX_VALUE, zero.effectiveMaxSendableMsat)
        val tenSatsMax = zero.copy(maxSendableMsat = 10_000)
        assertTrue(tenSatsMax.accepts(10_000))
        assertFalse(tenSatsMax.accepts(10_001))
        assertFalse(zero.accepts(0))
    }

    @Test
    fun `given note target when building zap request then required tags are present`() {
        val note = Event(id = "e".repeat(64), pubkey = "p".repeat(64), createdAt = 1, kind = 1, tags = emptyList(), content = "hi")
        val raw = NostrEventBuilder.zapRequest(
            recipientPubkey = note.pubkey,
            amountMsat = 21_000,
            lnurl = "LNURL1ABC",
            relays = listOf("wss://a", "wss://b", "wss://a"),
            target = note,
            comment = "great"
        )
        val obj = JsonUtils.NostrJson.parseToJsonElement(raw).jsonObject
        assertEquals(9734, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("great", obj.getValue("content").jsonPrimitive.content)
        val tags = obj.getValue("tags").jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
            .associateBy { it.first() }
        assertEquals(listOf("relays", "wss://a", "wss://b"), tags.getValue("relays"))
        assertEquals("21000", tags.getValue("amount")[1])
        assertEquals("LNURL1ABC", tags.getValue("lnurl")[1])
        assertEquals(note.pubkey, tags.getValue("p")[1])
        assertEquals(note.id, tags.getValue("e")[1])
        assertEquals("1", tags.getValue("k")[1])
    }
}
