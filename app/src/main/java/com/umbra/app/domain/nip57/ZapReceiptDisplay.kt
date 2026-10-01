package com.umbra.app.domain.nip57

import com.umbra.app.domain.lightning.parseBolt11
import com.umbra.app.domain.nip01.Event

/**
 * UI-facing view of a NIP-57 zap receipt, built by [mapZapReceiptToDisplay] just before a zap
 * card renders — one model both the verified and "shows as claimed" (invalid/unverifiable)
 * paths flow into, so the card never has to re-derive trust state from the raw event.
 *
 * A validated receipt claims nothing the payer didn't sign: [senderPubkey] comes from the zap
 * request, [amountSats] from the invoice, [comment] from the request's content. An invalid one
 * carries only what the receipt's own tags literally state — never shown as fact (see
 * [isVerified]).
 */
data class ZapReceiptDisplay(
    /** The payer, when the receipt attributes one. Null means anonymous — a Valid receipt whose
     * payer asked to stay anonymous (no `P` tag on the receipt), and any Invalid one, whose
     * claimed payer (if any) is unverifiable and never promoted to fact. */
    val senderPubkey: String?,
    /** Claimed/validated recipient (`p` target). For a Valid receipt this is check-equal to the
     * request's own `p` (TARGET_P_MISMATCH otherwise); for an Invalid one it is as stated only. */
    val recipientPubkey: String?,
    /** Amount in sats. For an Invalid receipt, the amount stated in the receipt's own invoice —
     * displayed with secondary styling, never presented as verified fact. */
    val amountSats: Long?,
    /** The payer's comment. Only ever non-null on a verified receipt — an unverified one carries
     * no trustworthy message. */
    val comment: String?,
    /** The note the zap targeted (`e` target), when any. As-stated for an Invalid receipt. */
    val targetEventId: String?,
    /** True when [validateZapReceipt] returned [ZapReceiptValidation.Valid]. */
    val isVerified: Boolean
)

/**
 * Maps a kind-9735 event (NIP-57 Appendix F validation already built in
 * [validateZapReceipt]) into the display model above, usable anywhere the event can appear by
 * reference (an inline quote, a thread opened on its id, a notification row).
 *
 * Anonymity rule: a Valid receipt still shows "Someone zapped" (no sender) when the receipt
 * carries no `P` tag naming an even-y pubkey equal to the request's own author — NIP-57's
 * anonymous-zap signal. The request's own signature keeps proving the payment happened; only the
 * payer's displayed identity is withheld, which is what anonymity means here.
 *
 * An Invalid receipt is never dropped outright when the user has referenced it explicitly (a
 * quote or a thread) — it renders as its claimed contents, muted, under an explicit unverified
 * label, so a user who opened a forgery sees what it pretends to be rather than nothing. The one
 * exception to building a display at all is [ZapReceiptValidation.Reason.NOT_A_ZAP_RECEIPT]
 * (not a kind-9735 event at all), which returns null — callers guard on kind first; this is the
 * paranoid backstop.
 */
fun mapZapReceiptToDisplay(
    receipt: Event,
    verifySignature: (Event) -> Boolean
): ZapReceiptDisplay? {
    if (receipt.kind != Event.KIND_ZAP_RECEIPT) return null

    return when (val validation = validateZapReceipt(receipt, verifySignature)) {
        is ZapReceiptValidation.Valid -> ZapReceiptDisplay(
            senderPubkey = validation.senderPubkey.takeIf { ebp ->
                receipt.tags.any { tag -> tag.size >= 2 && tag[0] == "P" && tag[1].equals(ebp, ignoreCase = true) }
            },
            recipientPubkey = receipt.getTagValue("p")?.lowercase()?.takeIf { it.isNotBlank() },
            amountSats = validation.amountMsat / 1_000,
            comment = validation.comment,
            targetEventId = validation.targetEventId,
            isVerified = true
        )
        is ZapReceiptValidation.Invalid -> ZapReceiptDisplay(
            senderPubkey = receipt.tags.firstOrNull { it.size >= 2 && it[0] == "P" && it[1].matchesHex64() }
                ?.get(1)?.lowercase(),
            recipientPubkey = receipt.tags.firstOrNull { it.size >= 2 && it[0] == "p" && it[1].matchesHex64() }
                ?.get(1)?.lowercase(),
            amountSats = receipt.getTagValue("bolt11")
                ?.let { bolt11 -> runCatching { parseBolt11(bolt11) }.getOrNull() }
                ?.amountMsat
                ?.let { msat -> msat / 1_000 },
            comment = null,
            targetEventId = receipt.getTagValues("e").lastOrNull()?.lowercase()
                ?.takeIf { it.isNotBlank() },
            isVerified = false
        )
    }
}

private fun String.matchesHex64(): Boolean =
    isNotBlank() && length == 64 && all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
