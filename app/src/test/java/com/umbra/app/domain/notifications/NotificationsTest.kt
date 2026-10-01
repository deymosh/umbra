package com.umbra.app.domain.notifications

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip57.TestInvoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsTest {
    private val me = "0".repeat(64)
    private val note = "n".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun ev(id: String, pubkey: String, kind: Int, at: Long, tags: List<List<String>>, content: String = "") =
        Event(id = id.padEnd(64, '0'), pubkey = pubkey, createdAt = at, kind = kind, tags = tags, content = content)

    @Test
    fun `given reactions on one note when grouping then one row lists every reactor newest first`() {
        val groups = groupNotifications(
            listOf(
                ev("r1", alice, Event.KIND_REACTION, 10, listOf(listOf("e", note), listOf("p", me)), "+"),
                ev("r2", bob, Event.KIND_REACTION, 20, listOf(listOf("e", note), listOf("p", me)), "🔥"),
                ev("r3", alice, Event.KIND_REACTION, 5, listOf(listOf("e", note), listOf("p", me)), "+")
            ),
            mutedPubkeys = emptySet()
        )
        val group = groups.single()
        assertEquals(NotificationType.REACTION, group.type)
        assertEquals(listOf(bob, alice), group.actorPubkeys)
        assertEquals(note, group.targetEventId)
        assertEquals(20L, group.latestAt)
        assertEquals(listOf("🔥", "+"), group.reactions)
    }

    @Test
    fun `given replies and mentions when grouping then each stays its own row`() {
        val groups = groupNotifications(
            listOf(
                ev("m1", alice, Event.KIND_TEXT_NOTE, 30, listOf(listOf("p", me)), "hey"),
                ev("p1", bob, Event.KIND_TEXT_NOTE, 40, listOf(listOf("e", note, "", "reply"), listOf("p", me)), "agreed")
            ),
            mutedPubkeys = emptySet()
        )
        assertEquals(listOf(NotificationType.REPLY, NotificationType.MENTION), groups.map { it.type })
    }

    @Test
    fun `given muted author when grouping then their events are dropped`() {
        val groups = groupNotifications(
            listOf(ev("m1", alice, Event.KIND_TEXT_NOTE, 30, listOf(listOf("p", me)), "spam")),
            mutedPubkeys = setOf(alice)
        )
        assertTrue(groups.isEmpty())
    }

    private fun signedZapRequest(
        pubkey: String = alice,
        tags: List<List<String>>? = null,
        content: String = "great shot",
        sig: String = VALID_REQUEST_SIG
    ): Event {
        // A real id hashed from the fields: zap validation checks id integrity before anything
        // else, so an invented id would make every zap fixture here fail as REQUEST_ID_MISMATCH.
        val unsigned = Event(
            id = "",
            pubkey = pubkey,
            createdAt = 40L,
            kind = Event.KIND_ZAP_REQUEST,
            tags = tags ?: listOf(listOf("p", me), listOf("e", note)),
            content = content,
            sig = sig
        )
        return unsigned.copy(id = EventCrypto.computeEventId(unsigned))
    }

    private fun zapReceiptEvent(
        requestJson: String,
        bolt11: String,
        pTag: String = me,
        eTag: String? = note
    ) = Event(
        id = "z".padEnd(64, '0'),
        pubkey = "w".repeat(64),
        createdAt = 50L,
        kind = Event.KIND_ZAP_RECEIPT,
        tags = buildList {
            add(listOf("p", pTag))
            if (eTag != null) add(listOf("e", eTag))
            add(listOf("bolt11", bolt11))
            add(listOf("description", requestJson))
        }
    )

    /** Shared fixture JSON for embedding a request into a receipt's `description` tag. */
    private fun Event.toJsonString(): String {
        val tagsJson = tags.joinToString(",", "[", "]") { tag ->
            tag.joinToString(",", "[", "]") { v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        }
        val contentJson = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"id":"$id","pubkey":"$pubkey","created_at":$createdAt,""" +
            """"kind":$kind,"tags":$tagsJson,"content":"$contentJson","sig":"$sig"}"""
    }

    /** The test verifier accepts exactly the fixture's signed-request shape, so a request whose
     * signature was swapped or whose kind strayed out is rejected; amount/tag checks are the
     * validator's own job, exercised separately. */
    private val verifyRequest = { event: Event ->
        event.sig == VALID_REQUEST_SIG && event.kind == Event.KIND_ZAP_REQUEST
    }

    @Test
    fun `given valid zap receipt when parsing then payer amount and comment come from the embedded request`() {
        val request = signedZapRequest().toJsonString()
        val receipt = zapReceiptEvent(request, bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        val parsed = parseZapReceipt(receipt, verifyRequest)!!
        assertEquals(alice, parsed.senderPubkey)
        assertEquals(1_000L, parsed.amountSats)
        assertEquals("great shot", parsed.comment)
        val group = groupNotifications(listOf(receipt), emptySet(), verifyEventSignature = verifyRequest).single()
        assertEquals(NotificationType.ZAP, group.type)
        assertEquals(1_000L, group.zapTotalSats)
        assertEquals(listOf(alice), group.actorPubkeys)
    }

    @Test
    fun `given receipt whose embedded request fails signature when parsing then receipt is dropped`() {
        val request = signedZapRequest(sig = "f".repeat(64)).toJsonString()
        val receipt = zapReceiptEvent(request, bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        assertNull(parseZapReceipt(receipt, verifyRequest))
        assertTrue(
            groupNotifications(listOf(receipt), emptySet(), verifyEventSignature = verifyRequest).isEmpty()
        )
    }

    @Test
    fun `given receipt amount disagrees with request amount tag when parsing then receipt is dropped`() {
        val request = signedZapRequest(tags = listOf(listOf("p", me), listOf("e", note), listOf("amount", "210000")))
        val receipt = zapReceiptEvent(request.toJsonString(), bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        assertNull(parseZapReceipt(receipt, verifyRequest))
    }

    @Test
    fun `given receipt p tag not matching request p when parsing then receipt is dropped`() {
        val request = signedZapRequest()
        val receipt = zapReceiptEvent(
            request.toJsonString(),
            bolt11 = TestInvoice.invoiceForMsat(1_000_000L),
            pTag = bob
        )
        assertNull(parseZapReceipt(receipt, verifyRequest))
    }

    @Test
    fun `given receipt e tag not matching request e when parsing then receipt is dropped`() {
        val request = signedZapRequest()
        val receipt = zapReceiptEvent(
            request.toJsonString(),
            bolt11 = TestInvoice.invoiceForMsat(1_000_000L),
            eTag = "e".repeat(64)
        )
        assertNull(parseZapReceipt(receipt, verifyRequest))
    }

    @Test
    fun `given receipt e tag missing the relay marker of the request when parsing then receipt still parses`() {
        // Only the tag id must agree: the e tag's relay hint is routinely dropped or rewritten
        // by wallet servers, so it never fails a receipt.
        val request = signedZapRequest(tags = listOf(listOf("p", me), listOf("e", note, "wss://relay.example", "root")))
        val receipt = zapReceiptEvent(request.toJsonString(), bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        assertEquals(alice, parseZapReceipt(receipt, verifyRequest)?.senderPubkey)
    }

    @Test
    fun `given receipt signed by the expected signer when parsing then receipt parses`() {
        val receipt = zapReceiptEvent(signedZapRequest().toJsonString(), bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
            .let { it.copy(pubkey = it.pubkey.uppercase()) }
        assertEquals(
            alice,
            parseZapReceipt(receipt, verifyRequest, expectedReceiptSigner = "w".repeat(64))?.senderPubkey
        )
    }

    @Test
    fun `given receipt signed by someone other than the expected signer when parsing then receipt is dropped`() {
        val receipt = zapReceiptEvent(signedZapRequest().toJsonString(), bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        assertNull(parseZapReceipt(receipt, verifyRequest, expectedReceiptSigner = "f".repeat(64)))
        assertTrue(
            groupNotifications(
                listOf(receipt),
                emptySet(),
                verifyEventSignature = verifyRequest,
                expectedReceiptSigner = "f".repeat(64)
            ).isEmpty()
        )
    }

    @Test
    fun `given no expected signer when parsing then signer check is skipped`() {
        val receipt = zapReceiptEvent(signedZapRequest().toJsonString(), bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        assertEquals(alice, parseZapReceipt(receipt, verifyRequest, expectedReceiptSigner = null)?.senderPubkey)
    }

    @Test
    fun `given description is not a signed kind-9734 request when parsing then receipt is dropped`() {
        val receipt = zapReceiptEvent("""{"pubkey":"x"}""", bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        assertNull(parseZapReceipt(receipt, verifyRequest))
    }

    @Test
    fun `given receipt without description when parsing then receipt is dropped`() {
        val receipt = Event(
            id = "z".padEnd(64, '0'),
            pubkey = "w".repeat(64),
            createdAt = 50L,
            kind = Event.KIND_ZAP_RECEIPT,
            tags = listOf(listOf("p", me), listOf("bolt11", TestInvoice.invoiceForMsat(1_000_000L)))
        )
        assertNull(parseZapReceipt(receipt, verifyRequest))
    }

    @Test
    fun `given request without amount tag when parsing then invoice amount is trusted`() {
        val request = signedZapRequest()
        val receipt = zapReceiptEvent(request.toJsonString(), bolt11 = TestInvoice.invoiceForMsat(2_100_000L))
        assertEquals(2_100L, parseZapReceipt(receipt, verifyRequest)?.amountSats)
    }

    @Test
    fun `given request without e tag when parsing then receipt groups as profile zap`() {
        val request = signedZapRequest(tags = listOf(listOf("p", me)))
        val receipt = zapReceiptEvent(request.toJsonString(), bolt11 = TestInvoice.invoiceForMsat(500_000L), eTag = null)
        val group = groupNotifications(listOf(receipt), emptySet(), verifyEventSignature = verifyRequest).single()
        assertEquals(NotificationType.ZAP, group.type)
        assertNull(group.targetEventId)
        assertEquals(500L, group.zapTotalSats)
    }

    @Test
    fun `given muted zap sender when grouping then the receipt is dropped`() {
        val request = signedZapRequest().toJsonString()
        val receipt = zapReceiptEvent(request, bolt11 = TestInvoice.invoiceForMsat(1_000_000L))
        val groups = groupNotifications(listOf(receipt), mutedPubkeys = setOf(alice), verifyEventSignature = verifyRequest)
        assertTrue(groups.isEmpty())
    }

    private companion object {
        /** Marker the fake verifier matches on — the real fixture never produces a real BIP-340
         * signature, so "valid" here means "the injected verifier said yes". */
        val VALID_REQUEST_SIG = "s".repeat(128)
    }
}
