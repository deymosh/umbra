package com.umbra.app.domain.nip57

import com.umbra.app.domain.lightning.parseBolt11
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.JsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * validateZapReceipt is exercised with a fake verify lambda: the real BIP-340 verifier lives in
 * the data/ layer, which a plain JVM unit test builds receipts without. The test verifier accepts
 * exactly the fixture's signed-and-kind-9734 shape, so per-reason tests inject the failure they
 * mean rather than one shared "reject everything" stub.
 */
class ZapReceiptValidationTest {
    private val payer = "a".repeat(64)
    private val recipient = "0".repeat(64)
    private val note = "n".repeat(64)
    private val walletServer = "w".repeat(64)

    /** "Valid signature" marker the fake verifier matches on; no real BIP-340 fixture is needed. */
    private val validVerify: (Event) -> Boolean = { it.sig == "s".repeat(128) && it.kind == Event.KIND_ZAP_REQUEST }

    private fun request(tags: List<List<String>>, content: String = "great shot", sig: String = "s".repeat(128)) = Event(
        id = "q".padEnd(64, '0'),
        pubkey = payer,
        createdAt = 40L,
        kind = Event.KIND_ZAP_REQUEST,
        tags = tags,
        content = content,
        sig = sig
    )

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

    private fun receipt(
        request: Event,
        bolt11: String,
        pTag: String = recipient,
        eTag: String? = note,
        description: String? = requestJson(request)
    ) = Event(
        id = "z".padEnd(64, '0'),
        pubkey = walletServer,
        createdAt = 50L,
        kind = Event.KIND_ZAP_RECEIPT,
        tags = buildList {
            add(listOf("p", pTag))
            if (eTag != null) add(listOf("e", eTag))
            add(listOf("bolt11", bolt11))
            if (description != null) add(listOf("description", description))
        }
    )

    @Test
    fun `given valid receipt when validating then returns request payer invoice amount and comment`() {
        val request = request(listOf(listOf("p", recipient), listOf("e", note), listOf("amount", "1000000")))
        val outcome = validateZapReceipt(receipt(request, TestInvoice.invoiceForMsat(1_000_000L)), validVerify)
        val valid = outcome as ZapReceiptValidation.Valid
        assertEquals(payer, valid.senderPubkey)
        assertEquals(1_000_000L, valid.amountMsat)
        assertEquals(note, valid.targetEventId)
        assertEquals("great shot", valid.comment)
    }

    @Test
    fun `given invoice amount disagrees with request amount tag when validating then amount mismatch`() {
        val request = request(listOf(listOf("p", recipient), listOf("amount", "210000")))
        val outcome = validateZapReceipt(receipt(request, TestInvoice.invoiceForMsat(1_000_000L)), validVerify)
        assertEquals(ZapReceiptValidation.Reason.AMOUNT_MISMATCH, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given receipt p tag missing the request p target when validating then target p mismatch`() {
        val request = request(listOf(listOf("p", recipient)))
        val badReceipt = Event(
            id = "z".padEnd(64, '0'),
            pubkey = walletServer,
            createdAt = 50L,
            kind = Event.KIND_ZAP_RECEIPT,
            tags = listOf(
                listOf("p", "b".repeat(64)),
                listOf("bolt11", TestInvoice.invoiceForMsat(1_000_000L)),
                listOf("description", requestJson(request))
            )
        )
        val outcome = validateZapReceipt(badReceipt, validVerify)
        assertEquals(ZapReceiptValidation.Reason.TARGET_P_MISMATCH, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given receipt missing the request e target when validating then target e mismatch`() {
        val request = request(listOf(listOf("p", recipient), listOf("e", note)))
        val noETagReceipt = Event(
            id = "z".padEnd(64, '0'),
            pubkey = walletServer,
            createdAt = 50L,
            kind = Event.KIND_ZAP_RECEIPT,
            tags = listOf(
                listOf("p", recipient),
                listOf("bolt11", TestInvoice.invoiceForMsat(1_000_000L)),
                listOf("description", requestJson(request))
            )
        )
        val outcome = validateZapReceipt(noETagReceipt, validVerify)
        assertEquals(ZapReceiptValidation.Reason.TARGET_E_MISMATCH, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given different case receipt e tag when validating then matches case-insensitively`() {
        val request = request(listOf(listOf("p", recipient), listOf("e", note)))
        val receipt = Event(
            id = "z".padEnd(64, '0'),
            pubkey = walletServer,
            createdAt = 50L,
            kind = Event.KIND_ZAP_RECEIPT,
            tags = listOf(
                listOf("p", recipient.uppercase()),
                listOf("e", note.uppercase()),
                listOf("bolt11", TestInvoice.invoiceForMsat(1_000_000L)),
                listOf("description", requestJson(request))
            )
        )
        assertTrue(validateZapReceipt(receipt, validVerify) is ZapReceiptValidation.Valid)
    }

    @Test
    fun `given description is kind other than 9734 when validating then not a zap request`() {
        val request = Event(
            id = "q".padEnd(64, '0'),
            pubkey = payer,
            createdAt = 40L,
            kind = 1,
            content = "",
            sig = "s".repeat(128)
        )
        val outcome = validateZapReceipt(receipt(request, TestInvoice.invoiceForMsat(1_000_000L)), validVerify)
        assertEquals(
            ZapReceiptValidation.Reason.DESCRIPTION_NOT_A_ZAP_REQUEST,
            (outcome as ZapReceiptValidation.Invalid).reason
        )
    }

    @Test
    fun `given description not valid json when validating then not a zap request`() {
        val outcome = validateZapReceipt(
            receipt(request(listOf(listOf("p", recipient))), TestInvoice.invoiceForMsat(1_000_000L), description = "{not json"),
            validVerify
        )
        assertEquals(
            ZapReceiptValidation.Reason.DESCRIPTION_NOT_A_ZAP_REQUEST,
            (outcome as ZapReceiptValidation.Invalid).reason
        )
    }

    @Test
    fun `given request signature rejected by the verifier when validating then signature invalid`() {
        val request = request(listOf(listOf("p", recipient)), sig = "f".repeat(64))
        val outcome = validateZapReceipt(receipt(request, TestInvoice.invoiceForMsat(1_000_000L)), validVerify)
        assertEquals(ZapReceiptValidation.Reason.SIGNATURE_INVALID, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given receipt without description tag when validating then missing description`() {
        val noDescription = Event(
            id = "z".padEnd(64, '0'),
            pubkey = walletServer,
            createdAt = 50L,
            kind = Event.KIND_ZAP_RECEIPT,
            tags = listOf(listOf("p", recipient), listOf("bolt11", TestInvoice.invoiceForMsat(1_000_000L)))
        )
        val outcome = validateZapReceipt(noDescription, validVerify)
        assertEquals(ZapReceiptValidation.Reason.MISSING_DESCRIPTION, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given invoice description hash matches description when validating then valid`() {
        val request = request(listOf(listOf("p", recipient)))
        val description = requestJson(request)
        val hashHex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(description.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val invoice = TestInvoice.encode("lnbc10u", descriptionHashHex = hashHex)
        val outcome = validateZapReceipt(receipt(request, invoice), validVerify)
        assertTrue(outcome is ZapReceiptValidation.Valid)
        assertEquals(1_000_000L, (outcome as ZapReceiptValidation.Valid).amountMsat)
    }

    @Test
    fun `given invoice description hash differs from description when validating then hash mismatch`() {
        val request = request(listOf(listOf("p", recipient)))
        val invoice = TestInvoice.encode("lnbc10u", descriptionHashHex = "ab".repeat(32))
        val outcome = validateZapReceipt(receipt(request, invoice), validVerify)
        assertEquals(
            ZapReceiptValidation.Reason.DESCRIPTION_HASH_MISMATCH,
            (outcome as ZapReceiptValidation.Invalid).reason
        )
    }

    @Test
    fun `given bolt11 without amount and when validating then missing amount reason`() {
        val request = request(listOf(listOf("p", recipient)))
        val outcome = validateZapReceipt(receipt(request, "lnbc1qqqqqqqqqq"), validVerify)
        assertEquals(ZapReceiptValidation.Reason.MISSING_AMOUNT, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given request amount tag absent when validating then invoice amount is the trusted amount`() {
        val request = request(listOf(listOf("p", recipient)))
        val outcome = validateZapReceipt(receipt(request, TestInvoice.invoiceForMsat(2_100_000L)), validVerify)
        assertEquals(2_100_000L, (outcome as ZapReceiptValidation.Valid).amountMsat)
    }

    @Test
    fun `given kind other than 9735 when validating then not a zap receipt`() {
        val outcome = validateZapReceipt(
            Event(id = "z".padEnd(64, '0'), pubkey = payer, createdAt = 1L, kind = Event.KIND_TEXT_NOTE),
            validVerify
        )
        assertEquals(ZapReceiptValidation.Reason.NOT_A_ZAP_RECEIPT, (outcome as ZapReceiptValidation.Invalid).reason)
    }

    @Test
    fun `given anonymous zap request without p tag when validating then still valid with receipt p untouched`() {
        val request = request(emptyList())
        val outcome = validateZapReceipt(
            receipt(request, TestInvoice.invoiceForMsat(1_000_000L), pTag = "b".repeat(64)),
            validVerify
        )
        assertEquals(payer, (outcome as ZapReceiptValidation.Valid).senderPubkey)
    }

    // ---- fixture-level checks for the TestInvoice builder itself ----

    @Test
    fun `given fixture invoice when parsing then amount decodes to the requested msat`() {
        assertEquals(1_000_000L, parseBolt11(TestInvoice.invoiceForMsat(1_000_000L))?.amountMsat)
    }

    @Test
    fun `given fixture invoice with description hash when parsing then h field round trips`() {
        val hashHex = "ab".repeat(32)
        val decoded = parseBolt11(TestInvoice.encode("lnbc10u", descriptionHashHex = hashHex))
        assertEquals(hashHex, decoded?.descriptionHashHex)
        assertEquals(1_000_000L, decoded?.amountMsat)
    }

    @Test
    fun `given fixture invoice without description hash when parsing then h is null`() {
        assertNull(parseBolt11(TestInvoice.invoiceForMsat(1_000_000L))?.descriptionHashHex)
    }

}
