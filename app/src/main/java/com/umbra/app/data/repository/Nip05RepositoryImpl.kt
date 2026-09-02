package com.umbra.app.data.repository

import android.content.Context
import com.umbra.app.data.network.boundedForOneShotCall
import com.umbra.app.data.network.torGuardedCall
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.nip05.parseNip05Identifier
import com.umbra.app.domain.repository.Nip05Repository
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class Nip05RepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    @Named("tor") private val torClient: OkHttpClient
) : Nip05Repository {

    private companion object {
        private const val TAG = "UmbraNip05Repo"
        private const val VERIFIED_CACHE_TTL_SECONDS = 24 * 60 * 60L
        private const val FAILED_CACHE_TTL_SECONDS = 15 * 60L
    }

    private val logger = UmbraLog.tag(TAG)

    private val securePreferences by lazy { SecurePreferences(context, "umbra_nip05_cache") }

    // Bounded the same way RelayInfoRepositoryImpl/TorStatusRepositoryImpl bound their own
    // one-shot HTTP fetches — a stalled well-known lookup shouldn't hang an IO thread forever.
    private val requestClient: OkHttpClient by lazy {
        torClient.boundedForOneShotCall(callTimeoutSeconds = 60, connectTimeoutSeconds = 45)
    }

    override suspend fun verifyNip05(nip05: String, pubkey: String): Result<Nip05VerificationState> =
        torGuardedCall(logger, "NIP-05 verify") {
            val normalizedPubkey = pubkey.trim().lowercase()
            if (normalizedPubkey.length != 64) return@torGuardedCall Nip05VerificationState.Failed

            val identifier = parseNip05Identifier(nip05)
                ?: return@torGuardedCall Nip05VerificationState.Failed
            val normalizedNip05 = identifier.normalized

            readCache(normalizedNip05, normalizedPubkey)?.let { cached ->
                val state = if (cached) Nip05VerificationState.Verified else Nip05VerificationState.Failed
                return@torGuardedCall state
            }

            val encodedName = URLEncoder.encode(identifier.name, "UTF-8")
            val requestUrl = "https://${identifier.domain}/.well-known/nostr.json?name=$encodedName"

            verifyWithRetry(requestUrl, identifier.name, normalizedPubkey).also { state ->
                val ttl = if (state == Nip05VerificationState.Verified) {
                    VERIFIED_CACHE_TTL_SECONDS
                } else {
                    FAILED_CACHE_TTL_SECONDS
                }
                writeCache(normalizedNip05, normalizedPubkey, state == Nip05VerificationState.Verified, ttl)
            }
        }

    private suspend fun verifyWithRetry(
        requestUrl: String,
        name: String,
        normalizedPubkey: String
    ): Nip05VerificationState {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        val firstAttempt = fetchVerificationState(requestClient, request, name, normalizedPubkey)
        if (firstAttempt.isSuccess) return firstAttempt.getOrThrow()

        val error = firstAttempt.exceptionOrNull()
        if (error !is IOException) {
            throw error ?: IllegalStateException("Unknown NIP-05 verification failure")
        }

        delay(1200)
        val retryAttempt = fetchVerificationState(requestClient, request, name, normalizedPubkey)
        return retryAttempt.getOrThrow()
    }

    private fun fetchVerificationState(
        client: OkHttpClient,
        request: Request,
        name: String,
        normalizedPubkey: String
    ): Result<Nip05VerificationState> = runCatching {
        client.newCall(request).execute().use { result ->
            if (!result.isSuccessful) return@runCatching Nip05VerificationState.Failed

            val body = result.body.string()
            val json = JsonUtils.NostrJson.parseToJsonElement(body) as? JsonObject
                ?: return@runCatching Nip05VerificationState.Failed

            val names = json["names"] as? JsonObject
            val mappedPubkey = names?.get(name)?.toString()?.trim('"')?.lowercase()
            if (mappedPubkey == normalizedPubkey) {
                Nip05VerificationState.Verified
            } else {
                Nip05VerificationState.Failed
            }
        }
    }

    private fun cacheKey(nip05: String, pubkey: String): String {
        return "nip05_${nip05}_${pubkey}"
    }

    private fun readCache(nip05: String, pubkey: String): Boolean? {
        val raw = securePreferences.getString(cacheKey(nip05, pubkey)) ?: return null
        val split = raw.split("|")
        val timestamp = split.getOrNull(0)?.toLongOrNull() ?: return null
        val cachedValue = split.getOrNull(1) == "1"
        val ttl = split.getOrNull(2)?.toLongOrNull()
            ?: if (cachedValue) VERIFIED_CACHE_TTL_SECONDS else FAILED_CACHE_TTL_SECONDS
        val now = System.currentTimeMillis() / 1000
        if (now - timestamp > ttl) {
            return null
        }

        return cachedValue
    }

    private fun writeCache(nip05: String, pubkey: String, verified: Boolean, ttlSeconds: Long) {
        val now = System.currentTimeMillis() / 1000
        val value = if (verified) "1" else "0"
        securePreferences.putString(cacheKey(nip05, pubkey), "$now|$value|$ttlSeconds")
    }
}
