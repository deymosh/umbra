package com.umbra.app.data.repository

import com.umbra.app.TorProxyConfig
import com.umbra.app.data.db.dao.RelayDao
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.db.mapper.toEntity
import com.umbra.app.data.network.boundedForOneShotCall
import com.umbra.app.data.network.logNetworkFailure
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.repository.RelayInfoRepository
import com.umbra.app.domain.util.isStale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import com.umbra.app.util.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import javax.inject.Inject
import javax.inject.Named

class RelayInfoRepositoryImpl @Inject constructor(
    @Named("tor") torClient: OkHttpClient,
    @Named("encrypted") private val relayDao: RelayDao
) : RelayInfoRepository {

    companion object {
        private const val TAG = "UmbraRelayInfoRepo"
        private const val NIP11_TTL_MS = 24 * 60 * 60 * 1000L
    }

    private val logger = UmbraLog.tag(TAG)

    // The shared "tor" client has readTimeout=0 (unbounded), which is correct for long-lived
    // relay websockets but would let a stalled relay's NIP-11 HTTPS GET hang forever, occupying
    // an IO thread and leaving relayInfoLoading stuck. Same bounded-call pattern already used by
    // Nip05RepositoryImpl for its one-shot HTTP fetches.
    private val httpClient: OkHttpClient =
        torClient.boundedForOneShotCall(callTimeoutSeconds = 20, readTimeoutSeconds = 15)

    // fetchAndPersist doesn't fit torGuardedCall's Result<T> contract: it returns Unit, and its
    // success path does DB work that helper has no room for — kept as a plain guard instead of
    // forcing a signature change just to reuse the wrapper.
    override suspend fun fetchAndPersist(relayUrl: String, force: Boolean) = withContext(Dispatchers.IO) {
        if (!TorProxyConfig.isReady) return@withContext

        val entity = relayDao.getRelayByUrl(relayUrl)
        if (!force) {
            val fetchedAt = entity?.nip11FetchedAtMillis ?: 0L
            if (!isStale(fetchedAt, NIP11_TTL_MS)) return@withContext
        }

        runCatching { fetchFromNetwork(relayUrl) }
            .onSuccess { info ->
                val freshEntity = (entity ?: relayDao.getRelayByUrl(relayUrl)) ?: return@onSuccess
                val updated = freshEntity.toDomain().copy(
                    relayInfo = info,
                    nip11FetchedAtMillis = System.currentTimeMillis()
                )
                relayDao.updateRelay(updated.toEntity())
            }
            .onFailure { e -> logNetworkFailure(logger, "NIP-11 fetch failed for ${scrubUrlForLogs(relayUrl)}", e) }
    }

    private fun fetchFromNetwork(relayUrl: String): RelayInfo {
        val httpUrl = relayUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')

        val request = Request.Builder()
            .url(httpUrl)
            .header("Accept", "application/nostr+json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("NIP-11 HTTP ${response.code}")
            val body = response.body.string()
            if (body.isBlank()) error("NIP-11 empty body")
            return parseNip11(body)
        }
    }

    private fun parseNip11(rawJson: String): RelayInfo {
        val obj = Json.parseToJsonElement(rawJson) as? JsonObject ?: error("Invalid NIP-11 payload")
        val limitation = obj["limitation"] as? JsonObject

        val supported = (obj["supported_nips"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            ?: emptyList()

        return RelayInfo(
            banner = primitiveText(obj["banner"] as? JsonPrimitive),
            icon = primitiveText(obj["icon"] as? JsonPrimitive),
            name = primitiveText(obj["name"] as? JsonPrimitive),
            description = primitiveText(obj["description"] as? JsonPrimitive),
            contact = primitiveText(obj["contact"] as? JsonPrimitive),
            pubkey = primitiveText(obj["pubkey"] as? JsonPrimitive),
            self = primitiveText(obj["self"] as? JsonPrimitive),
            software = primitiveText(obj["software"] as? JsonPrimitive),
            version = primitiveText(obj["version"] as? JsonPrimitive),
            supportedNips = supported,
            termsOfService = primitiveText(obj["terms_of_service"] as? JsonPrimitive),
            maxSubscriptions = primitiveText(limitation?.get("max_subscriptions") as? JsonPrimitive)?.toIntOrNull(),
            maxLimitEventCount = primitiveText(limitation?.get("max_limit") as? JsonPrimitive)?.toIntOrNull(),
            maxEventComplexity = primitiveText(limitation?.get("max_event_tags") as? JsonPrimitive)?.toIntOrNull(),
            minPoW = primitiveText(limitation?.get("min_pow_difficulty") as? JsonPrimitive)?.toIntOrNull(),
            requiresPayment = primitiveText(limitation?.get("payment_required") as? JsonPrimitive)?.toBooleanStrictOrNull() ?: false,
            requiresAuth = primitiveText(limitation?.get("auth_required") as? JsonPrimitive)?.toBooleanStrictOrNull() ?: false
        )
    }

    private fun primitiveText(value: JsonPrimitive?): String? =
        if (value == null) null else runCatching { value.content }.getOrNull()
}

