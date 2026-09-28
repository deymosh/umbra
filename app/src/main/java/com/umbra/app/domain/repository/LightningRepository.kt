package com.umbra.app.domain.repository

import com.umbra.app.domain.nip57.LnurlPayInfo

/** LNURL-pay over Tor: resolve a recipient's pay endpoint, then ask it for an invoice. */
interface LightningRepository {
    /** [addressOrLnurl] is a LUD-16 lightning address or a bech32 `lnurl1…`. */
    suspend fun resolvePayInfo(addressOrLnurl: String): Result<LnurlPayInfo>

    /**
     * Requests a BOLT11 invoice for [amountMsat]. [signedZapRequestJson] turns the payment into a
     * NIP-57 zap; [comment] is sent only when the endpoint allows comments.
     */
    suspend fun requestInvoice(
        payInfo: LnurlPayInfo,
        amountMsat: Long,
        signedZapRequestJson: String? = null,
        comment: String? = null
    ): Result<String>
}
