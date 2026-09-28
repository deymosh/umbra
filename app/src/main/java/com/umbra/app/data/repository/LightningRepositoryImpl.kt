package com.umbra.app.data.repository

import com.umbra.app.data.network.boundedForOneShotCall
import com.umbra.app.data.network.torGuardedCall
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip57.LnurlPayInfo
import com.umbra.app.domain.nip57.lightningAddressToPayUrl
import com.umbra.app.domain.repository.LightningRepository
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.logging.UmbraLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * LNURL-pay (LUD-06/16) and NIP-57 invoice requests, always over the shared Tor client — the
 * recipient's wallet server never learns the payer's IP.
 */
@Singleton
class LightningRepositoryImpl @Inject constructor(
    @Named("tor") torClient: OkHttpClient
) : LightningRepository {

    private val logger = UmbraLog.tag("UmbraLightningRepo")

    private val client: OkHttpClient by lazy {
        torClient.boundedForOneShotCall(callTimeoutSeconds = 60, connectTimeoutSeconds = 45)
    }

    override suspend fun resolvePayInfo(addressOrLnurl: String): Result<LnurlPayInfo> =
        torGuardedCall(logger, "LNURL pay info") {
            val input = addressOrLnurl.trim()
            val url = lightningAddressToPayUrl(input)
                ?: Bech32Encoder.decodeLnurl(input)
                ?: throw IllegalArgumentException("Not a lightning address or LNURL")
            val json = getJson(url)
            if (json["status"]?.jsonPrimitive?.content.equals("ERROR", ignoreCase = true)) {
                throw IllegalStateException("LNURL endpoint returned an error")
            }
            val callback = json["callback"]?.jsonPrimitive?.content
                ?.takeIf { it.toHttpUrlOrNull() != null }
                ?: throw IllegalStateException("LNURL response has no valid callback")
            LnurlPayInfo(
                callback = callback,
                minSendableMsat = json["minSendable"]?.jsonPrimitive?.longOrNull ?: 1_000,
                maxSendableMsat = json["maxSendable"]?.jsonPrimitive?.longOrNull ?: 0,
                allowsNostr = json["allowsNostr"]?.jsonPrimitive?.booleanOrNull ?: false,
                nostrPubkey = json["nostrPubkey"]?.jsonPrimitive?.content?.lowercase()?.takeIf { it.length == 64 },
                commentAllowed = json["commentAllowed"]?.jsonPrimitive?.intOrNull ?: 0,
                lnurl = Bech32Encoder.encodeLnurl(url)
            )
        }

    override suspend fun requestInvoice(
        payInfo: LnurlPayInfo,
        amountMsat: Long,
        signedZapRequestJson: String?,
        comment: String?
    ): Result<String> = torGuardedCall(logger, "LNURL invoice") {
        require(payInfo.accepts(amountMsat)) { "Amount outside the recipient's limits" }
        val url = payInfo.callback.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("amount", amountMsat.toString())
            .apply {
                if (signedZapRequestJson != null) {
                    addQueryParameter("nostr", signedZapRequestJson)
                    addQueryParameter("lnurl", payInfo.lnurl)
                } else if (!comment.isNullOrBlank() && payInfo.commentAllowed > 0) {
                    addQueryParameter("comment", comment.take(payInfo.commentAllowed))
                }
            }
            .build()
        val json = getJson(url.toString())
        val invoice = (json["pr"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("LNURL callback returned no invoice")
        if (!invoice.startsWith("ln", ignoreCase = true)) throw IllegalStateException("Not a BOLT11 invoice")
        invoice
    }

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return JsonUtils.NostrJson.parseToJsonElement(response.body.string()) as? JsonObject
                ?: throw IllegalStateException("Not a JSON object")
        }
    }
}
