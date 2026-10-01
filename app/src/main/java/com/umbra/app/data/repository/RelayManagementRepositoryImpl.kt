package com.umbra.app.data.repository

import com.umbra.app.TorProxyConfig
import com.umbra.app.data.network.boundedForOneShotCall
import com.umbra.app.data.network.logNetworkFailure
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nip86.RelayManagementMethod
import com.umbra.app.domain.nip86.RelayManagementResult
import com.umbra.app.domain.nip86.RelayManagementRpc
import com.umbra.app.domain.nip98.Nip98HttpAuth
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.RelayManagementRepository
import com.umbra.app.util.logging.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * NIP-86 relay management over HTTPS through Tor: every call POSTs the rpc body to the relay's
 * URL (ws->http / wss->https) with a freshly signed NIP-98 kind-27235 event in Authorization.
 * Signing goes through the external NIP-55 signer (Amber) — this class never sees keys.
 */
class RelayManagementRepositoryImpl @Inject constructor(
    @Named("tor") torClient: OkHttpClient,
    private val amberSignerGateway: AmberSignerGateway,
    private val userPreferences: UserPreferences
) : RelayManagementRepository {

    companion object {
        private const val TAG = "UmbraNip86Repo"
        private const val RPC_CONTENT_TYPE = "application/nostr+json+rpc"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }

    private val logger = UmbraLog.tag(TAG)

    // Same pattern as RelayInfoRepositoryImpl / Nip05RepositoryImpl: the shared Tor client's
    // readTimeout is unbounded (correct for stream relays), and one-shot management calls need
    // bounding or a stalled relay hangs the screen's refresh forever.
    private val httpClient: OkHttpClient =
        torClient.boundedForOneShotCall(callTimeoutSeconds = 25, readTimeoutSeconds = 20)

    override suspend fun call(
        relayUrl: String,
        method: RelayManagementMethod,
        params: List<String>
    ): RelayManagementResult {
        if (!TorProxyConfig.isReady) return RelayManagementResult.Transport("Tor not ready")

        // The URL the NIP-98 `u` tag names MUST be the exact URL posted to (NIP-86): the same
        // ws->http / wss->https rewrite the relay-info fetch already does.
        val httpUrl = relayUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')
        val bodyString = RelayManagementRpc.encodeRequest(method, params)
        val bodyBytes = bodyString.toByteArray(Charsets.UTF_8)

        val unsignedAuthEvent = Nip98HttpAuth.buildUnsignedEvent(
            url = httpUrl,
            method = "POST",
            body = bodyBytes,
            nowEpochSeconds = System.currentTimeMillis() / 1000
        )
        val signed = amberSignerGateway.signEvent(unsignedAuthEvent, userPreferences.getPublicKey())
            ?: return RelayManagementResult.Transport("signing cancelled by user")

        val authorization = Nip98HttpAuth.authorizationHeader(signed)

        val request = Request.Builder()
            .url(httpUrl)
            .header("Authorization", authorization)
            .header("Content-Type", RPC_CONTENT_TYPE)
            .post(bodyBytes.toRequestBody(RPC_CONTENT_TYPE.toMediaType()))
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                when {
                    response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN ->
                        RelayManagementResult.NotAuthorized
                    !response.isSuccessful ->
                        RelayManagementResult.Transport("NIP-86 HTTP ${response.code}")
                    else -> RelayManagementRpc.decodeResponse(responseBody)
                }
            }
        }.getOrElse { e ->
            logNetworkFailure(logger, "NIP-86 call failed for ${scrubUrlForLogs(httpUrl)}", e)
            RelayManagementResult.Transport(e.message ?: "network error")
        }
    }
}
