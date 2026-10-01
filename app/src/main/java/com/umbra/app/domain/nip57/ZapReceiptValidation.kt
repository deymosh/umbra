package com.umbra.app.domain.nip57

import com.umbra.app.domain.lightning.Bolt11Invoice
import com.umbra.app.domain.lightning.parseBolt11
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.domain.util.toHex
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The outcome of validating a NIP-57 zap receipt per NIP-57 Appendix F, before its amount,
 * sender or message may be shown anywhere.
 */
sealed interface ZapReceiptValidation {
    /**
     * [senderPubkey] is the zap REQUEST's author — the payer — never the receipt's own pubkey
     * (that is the recipient's wallet server). [amountMsat] comes from the invoice itself,
     * already cross-checked against the request's `amount` tag when that tag exists.
     */
    data class Valid(
        val senderPubkey: String,
        val amountMsat: Long,
        val targetEventId: String?,
        val comment: String?
    ) : ZapReceiptValidation

    enum class Reason {
        NOT_A_ZAP_RECEIPT,
        MISSING_DESCRIPTION,
        DESCRIPTION_NOT_A_ZAP_REQUEST,
        SIGNATURE_INVALID,
        AMOUNT_MISMATCH,
        TARGET_P_MISMATCH,
        TARGET_E_MISMATCH,
        DESCRIPTION_HASH_MISMATCH,
        MISSING_AMOUNT
    }

    data class Invalid(val reason: Reason) : ZapReceiptValidation
}

/**
 * Validates a kind-9735 zap receipt (NIP-57 Appendix F) before trusting what it claims.
 *
 * An unvalidated receipt is trivially forgeable: its sender, amount and message are just tags,
 * and a relay will happily hand a kind-9735 event over with arbitrary content. Every check here
 * ties the receipt back to a zap request the payer actually signed — the request is the only
 * document whose author is really the payer, so `senderPubkey` in [ZapReceiptValidation.Valid]
 * comes from the request, and the amount from the bolt11 invoice, which the request's `amount`
 * tag (when present) must agree with.
 *
 * Checks performed, per Appendix F plus the tagged-field integrity Appendix A implies:
 * - the `p`/`e`/`a` targets the receipt carries must match the request's (re-tagged by the wallet
 *   server, so any divergence means the receipt isn't a receipt for this payment);
 * - the invoice amount must equal the request's `amount` tag when that tag exists;
 * - the invoice's description-hash ('h') field must equal the SHA-256 of the `description` JSON
 *   (SHOULD-level in the spec, enforced here because a mismatch means the description parsed
 *   and the invoice were never issued together — i.e. a spliced receipt);
 * - the request's signature must verify, via the injected [verifySignature]. The BIP-340 verifier
 *   lives in the data/ layer, which domain/ can't import, so callers wire the real one
 *   (data/crypto EventCrypto verifySignature) in — tests pass a lambda instead.
 *
 * Not checked: that the receipt's own pubkey equals the recipient's LNURL endpoint `nostrPubkey`.
 * That is the spec's MUST-level signer check but it needs a live LNURL lookup, and this function
 * is deliberately pure/offline. Callers that have an LNURL-pay context available can layer it on.
 *
 * Anonymous zaps (no `P` tag on the request) are not rejected: they validate like any other
 * receipt, and callers show them as anonymous since no payer identity is proven or recoverable.
 */
fun validateZapReceipt(
    receipt: Event,
    verifySignature: (Event) -> Boolean
): ZapReceiptValidation {
    if (receipt.kind != Event.KIND_ZAP_RECEIPT) return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.NOT_A_ZAP_RECEIPT)

    val description = receipt.getTagValue("description")
        ?: return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.MISSING_DESCRIPTION)
    val request = runCatching { JsonUtils.NostrJson.parseToJsonElement(description) as? JsonObject }
        .getOrNull()
        ?: return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.DESCRIPTION_NOT_A_ZAP_REQUEST)
    if ((request["kind"] as? JsonPrimitive)?.content != Event.KIND_ZAP_REQUEST.toString()) {
        return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.DESCRIPTION_NOT_A_ZAP_REQUEST)
    }

    val requestEvent = runCatching { Event.fromJsonObject(request) }.getOrNull()
        ?: return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.DESCRIPTION_NOT_A_ZAP_REQUEST)
    if (!verifySignature(requestEvent)) {
        return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.SIGNATURE_INVALID)
    }

    val invoice = receipt.getTagValue("bolt11")?.let { runCatching { parseBolt11(it) }.getOrNull() }
        ?: return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.MISSING_AMOUNT)
    if (invoice.amountMsat == null) {
        return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.MISSING_AMOUNT)
    }

    // The wallet server re-tags the recipient/event onto the receipt from what the request said;
    // if the two ever disagree the receipt isn't a receipt for this payment (or is spliced).
    val requestAmountMsat = requestEvent.getTagValue("amount")?.toLongOrNull()
    if (requestAmountMsat != null && requestAmountMsat != invoice.amountMsat) {
        return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.AMOUNT_MISMATCH)
    }

    val pTarget = requestEvent.getTagValue("p")?.lowercase()
    if (pTarget != null && !receipt.getTagValues("p").any { it.lowercase() == pTarget }) {
        return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.TARGET_P_MISMATCH)
    }

    val requestEventTarget = requestEvent.getTagValue("e")?.lowercase()
    if (requestEventTarget != null) {
        val requestETagMarker = eventTagMarker(requestEvent, "e")
        val receiptTag = receipt.tags.firstOrNull {
            it.size >= 2 && it[0] == "e" && it[1].lowercase() == requestEventTarget
        }
        if (receiptTag == null || eventTagMarker(receipt, "e") != requestETagMarker) {
            return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.TARGET_E_MISMATCH)
        }
    }
    val aTarget = requestEvent.getTagValue("a")?.lowercase()
    if (aTarget != null && !receipt.getTagValues("a").any { it.lowercase() == aTarget }) {
        return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.TARGET_E_MISMATCH)
    }

    // A receipt whose invoice was issued against a different description is spliced; the 'h'
    // field only exists when the wallet hashed its description at invoice creation, so its
    // absence (older/careless wallet servers) can't be checked offline and is tolerated.
    if (invoice.descriptionHashHex != null) {
        val computed = MessageDigest.getInstance("SHA-256")
            .digest(description.toByteArray(Charsets.UTF_8))
            .toHex()
        if (!computed.equals(invoice.descriptionHashHex, ignoreCase = true)) {
            return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.DESCRIPTION_HASH_MISMATCH)
        }
    }

    val sender = requestEvent.pubkey.lowercase().takeIf { it.isNotBlank() }
        ?: return ZapReceiptValidation.Invalid(ZapReceiptValidation.Reason.DESCRIPTION_NOT_A_ZAP_REQUEST)

    return ZapReceiptValidation.Valid(
        senderPubkey = sender,
        amountMsat = invoice.amountMsat,
        targetEventId = eventTargetId(requestEvent),
        comment = requestEvent.content.takeIf { it.isNotBlank() }
    )
}

private fun eventTagMarker(event: Event, tagName: String): String? =
    event.tags.firstOrNull { it.size >= 2 && it[0] == tagName }?.getOrNull(2)

/** A zap on a specific note carries an `e` on the request; a profile zap has none. Reply
 * markers ("root"/"mention") also match, so the last `e` is what the payment was for. */
private fun eventTargetId(request: Event): String? =
    request.getTagValues("e").lastOrNull()?.lowercase()
