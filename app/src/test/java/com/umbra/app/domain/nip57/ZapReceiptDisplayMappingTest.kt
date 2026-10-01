package com.umbra.app.domain.nip57

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mapZapReceiptToDisplay is exercised with the same fake verify lambda ZapReceiptValidationTest
 * uses ("valid" iff the request carries the all-'s' sig marker and kind 9734), so each display
 * case is built by injecting the failure it means, not one shared reject-everything stub.
 *
 * Requests likewise carry real ids hashed from their own fields ([request]), since validation
 * checks id integrity independently of that lambda and an invented id would turn every
 * "verified" case into REQUEST_ID_MISMATCH.
 */
class ZapReceiptDisplayMappingTest {
    private val payer = "a".repeat(64)
    private val recipient = "0".repeat(64)
    private val note = "n".repeat(64)
    private val walletServer = "w".repeat(64)
    private val payerNpubKey = "A".repeat(64)

    private val validVerify: (Event) -> Boolean = { it.sig == "s".repeat(128) && it.kind == Event.KIND_ZAP_REQUEST }

    private fun request(
        tags: List<List<String>>,
        content: String = "great shot",
        pubkey: String = payer,
        sig: String = "s".repeat(128)
    ): Event {
        val unsigned = Event(
            id = "",
            pubkey = pubkey,
            createdAt = 40L,
            kind = Event.KIND_ZAP_REQUEST,
            tags = tags,
            content = content,
            sig = sig
        )
        return unsigned.copy(id = EventCrypto.computeEventId(unsigned))
    }

    private fun requestJson(request: Event): String {
        val tagsJson = request.tags.joinToString(",", "[", "]") { tag ->
            tag.joinToString(",", "[", "]") { v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        }
        val contentJson = request.content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"id":"${request.id}","pubkey":"${request.pubkey}","created_at":${request.createdAt},""" +
            """"kind":${request.kind},"tags":$tagsJson,"content":"$contentJson","sig":"${request.sig}"}"""
    }

    /** Builds a receipt for [request]; wallet servers may or may not copy the payer into `P`. */
    private fun receipt(
        request: Event,
        bolt11: String = TestInvoice.invoiceForMsat(1_000_000L),
        withPayerTag: Boolean = true,
        eTag: String? = note,
        description: String? = requestJson(request)
    ) = Event(
        id = "z".padEnd(64, '0'),
        pubkey = walletServer,
        createdAt = 50L,
        kind = Event.KIND_ZAP_RECEIPT,
        tags = buildList {
            add(listOf("p", recipient))
            if (eTag != null) add(listOf("e", eTag))
            add(listOf("bolt11", bolt11))
            if (withPayerTag) add(listOf("P", request.pubkey))
            if (description != null) add(listOf("description", description))
        }
    )

    @Test
    fun `given valid receipt when mapping then verified with payer amount and comment`() {
        val display = mapZapReceiptToDisplay(receipt(request(listOf(listOf("p", recipient), listOf("e", note)))), validVerify)!!
        assertTrue(display.isVerified)
        assertEquals(payer, display.senderPubkey)
        assertEquals(recipient, display.recipientPubkey)
        assertEquals(1_000L, display.amountSats)
        assertEquals("great shot", display.comment)
        assertEquals(note, display.targetEventId)
    }

    @Test
    fun `given verified receipt without P tag when mapping then payer still comes from the request`() {
        val display = mapZapReceiptToDisplay(receipt(request(listOf(listOf("p", recipient))), withPayerTag = false), validVerify)!!
        assertTrue(display.isVerified)
        assertEquals(payer, display.senderPubkey)
    }

    @Test
    fun `given verified anonymous request when mapping then sender is null but valid`() {
        val display = mapZapReceiptToDisplay(receipt(request(listOf(listOf("p", recipient), listOf("anon")))), validVerify)!!
        assertTrue(display.isVerified)
        assertNull(display.senderPubkey)
        assertEquals(1_000L, display.amountSats)
        assertEquals(recipient, display.recipientPubkey)
    }

    @Test
    fun `given profile zap without e tag when mapping then target event is null`() {
        val display = mapZapReceiptToDisplay(
            receipt(request(listOf(listOf("p", recipient)), content = "welcome"), eTag = null),
            validVerify
        )!!
        assertTrue(display.isVerified)
        assertNull(display.targetEventId)
        assertEquals("welcome", display.comment)
    }

    @Test
    fun `given invalid signature when mapping then unverified and no comment`() {
        val display = mapZapReceiptToDisplay(
            receipt(request(listOf(listOf("p", recipient)), sig = "f".repeat(128))),
            validVerify
        )!!
        assertFalse(display.isVerified)
        assertNull(display.comment)
        // Amount and targets still surface as stated, for secondary styling only.
        assertEquals(1_000L, display.amountSats)
        assertEquals(recipient, display.recipientPubkey)
        assertEquals(note, display.targetEventId)
    }

    @Test
    fun `given unverified receipt with malformed P tag when mapping then sender stays null`() {
        val badReceipt = Event(
            id = "z".padEnd(64, '0'),
            pubkey = walletServer,
            createdAt = 50L,
            kind = Event.KIND_ZAP_RECEIPT,
            tags = listOf(
                listOf("p", recipient),
                listOf("e", note),
                listOf("bolt11", TestInvoice.invoiceForMsat(1_000_000L)),
                listOf("P", "not-hex"),
                // Malformed JSON description — the reason the whole map lands Invalid.
                listOf("description", "{not json")
            )
        )
        val display = mapZapReceiptToDisplay(badReceipt, validVerify)!!
        assertFalse(display.isVerified)
        assertNull(display.senderPubkey)
        assertEquals(1_000L, display.amountSats)
    }

    @Test
    fun `given non kind-9735 event when mapping then null`() {
        val noteEvent = Event(id = "x".repeat(64), pubkey = payer, createdAt = 1L, kind = Event.KIND_TEXT_NOTE, content = "hi")
        assertNull(mapZapReceiptToDisplay(noteEvent, validVerify))
    }
}
