package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nip57.LnurlPayInfo
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.LightningRepository
import com.umbra.app.domain.repository.RelayRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

sealed interface ZapOutcome {
    /** A BOLT11 invoice for the user's wallet; [isZap] is false for a plain LNURL payment. */
    data class Invoice(val bolt11: String, val isZap: Boolean) : ZapOutcome
    data object SignCancelled : ZapOutcome
    data class Failed(val reason: ZapFailure) : ZapOutcome
}

enum class ZapFailure { NO_LIGHTNING_ADDRESS, ENDPOINT_UNREACHABLE, AMOUNT_OUT_OF_RANGE, INVOICE_FAILED }

/**
 * NIP-57 zap, end to end up to the invoice:
 * 1. resolve the recipient's LNURL-pay endpoint (over Tor),
 * 2. if it supports nostr and the user can sign, build a kind-9734 zap request naming the user's
 *    read relays for the receipt and have Amber sign it,
 * 3. request the invoice from the callback.
 *
 * Paying stays with the user's own wallet app. Anonymous sessions (or endpoints without nostr
 * support) still get a plain LNURL invoice, just no zap receipt.
 */
class SendZapUseCase @Inject constructor(
    private val lightningRepository: LightningRepository,
    private val amberSignerGateway: AmberSignerGateway,
    private val userPreferences: UserPreferences,
    private val relayRepository: RelayRepository
) {
    suspend fun resolve(lightningAddress: String?): Result<LnurlPayInfo> {
        val address = lightningAddress?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("no lightning address"))
        return lightningRepository.resolvePayInfo(address)
    }

    suspend operator fun invoke(
        payInfo: LnurlPayInfo,
        recipientPubkey: String,
        amountSats: Long,
        target: Event? = null,
        comment: String = ""
    ): ZapOutcome {
        val amountMsat = amountSats * 1_000
        if (!payInfo.accepts(amountMsat)) return ZapOutcome.Failed(ZapFailure.AMOUNT_OUT_OF_RANGE)

        val signedRequest = if (payInfo.supportsZaps && userPreferences.canSignWithAmber()) {
            val relays = relayRepository.getAllRelays().first()
                .filter { it.isEnabled && it.isReadActive }
                .map { it.url }
            val unsigned = NostrEventBuilder.zapRequest(
                recipientPubkey = recipientPubkey,
                amountMsat = amountMsat,
                lnurl = payInfo.lnurl,
                relays = relays,
                target = target,
                comment = comment
            )
            amberSignerGateway.signEvent(unsigned, userPreferences.getPublicKey())
                ?: return ZapOutcome.SignCancelled
        } else {
            null
        }

        return lightningRepository.requestInvoice(
            payInfo = payInfo,
            amountMsat = amountMsat,
            signedZapRequestJson = signedRequest,
            comment = comment.takeIf { signedRequest == null }
        ).fold(
            onSuccess = { ZapOutcome.Invoice(it, isZap = signedRequest != null) },
            onFailure = { ZapOutcome.Failed(ZapFailure.INVOICE_FAILED) }
        )
    }
}
