package com.umbra.app.data.repository

import com.umbra.app.data.network.boundedForOneShotCall
import com.umbra.app.data.network.torGuardedCall
import com.umbra.app.domain.repository.TorStatusRepository
import com.umbra.app.domain.usecase.TorStatusResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.logging.UmbraLog
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TorStatusRepositoryImpl @Inject constructor(
    @Named("tor") torClient: OkHttpClient
) : TorStatusRepository {

    private companion object {
        private const val TAG = "UmbraTorStatusRepo"
    }

    private val logger = UmbraLog.tag(TAG)

    // The shared "tor" client has readTimeout=0 (unbounded), correct for long-lived relay
    // websockets but not for this one-shot status check — without a bound, a stalled circuit
    // (e.g. right after the app resumes from background, before Orbot's circuits are rebuilt)
    // would leave this call hanging indefinitely. Same bounded-call pattern already used by
    // RelayInfoRepositoryImpl/Nip05RepositoryImpl for their own one-shot HTTP fetches.
    private val okHttpClient: OkHttpClient =
        torClient.boundedForOneShotCall(callTimeoutSeconds = 20, readTimeoutSeconds = 15)

    override suspend fun checkTorStatus(): Result<TorStatusResult> = torGuardedCall(logger, "Tor status check") {
        val request = Request.Builder()
            .url("https://check.torproject.org/api/ip")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body.string()
            val json = JsonUtils.NostrJson.parseToJsonElement(body) as JsonObject
            TorStatusResult(
                isTor = json["IsTor"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                exitIp = json["IP"]?.jsonPrimitive?.content,
                countryCode = json["CountryCode"]?.jsonPrimitive?.content
            )
        }
    }
}
