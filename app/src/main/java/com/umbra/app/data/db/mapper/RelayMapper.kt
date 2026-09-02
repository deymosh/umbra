package com.umbra.app.data.db.mapper

import com.umbra.app.data.db.entities.RelayEntity
import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.Collections

/**
 * Mapping functions between Room RelayEntity and domain Relay model.
 */

private const val RELAY_INFO_CACHE_MAX_SIZE = 256

/**
 * getAllRelays() re-maps every relay row on every emission of Room's Flow — which fires on ANY
 * write to the `relays` table, not just a change to that specific relay's own NIP-11 doc (e.g.
 * one relay's connection-status write re-decodes every other relay's relayInfoJson too). NIP-11
 * documents themselves change rarely (~once per 24h TTL, see RelayInfoRepositoryImpl), so most of
 * those decodes reproduce a result already computed moments earlier. Memoizing by the raw JSON
 * string turns repeat emissions of an unchanged document into a cache hit; a genuinely changed
 * document naturally lands under a new key, so no explicit invalidation is needed. Bounded (not a
 * true LRU need — relay counts are small, dozens at most) purely as a safety cap.
 */
private val relayInfoCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, RelayInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RelayInfo>): Boolean =
            size > RELAY_INFO_CACHE_MAX_SIZE
    }
)

private fun decodeRelayInfo(json: String): RelayInfo? {
    relayInfoCache[json]?.let { return it }
    val decoded = runCatching { JsonUtils.NostrJson.decodeFromString<RelayInfo>(json) }.getOrNull()
    if (decoded != null) relayInfoCache[json] = decoded
    return decoded
}

fun RelayEntity.toDomain(): Relay {
    return Relay(
        id = id,
        url = url,
        isEnabled = isEnabled,
        isReadEnabled = isReadEnabled,
        isReadActive = isReadActive,
        isWriteEnabled = isWriteEnabled,
        isWriteActive = isWriteActive,
        isDmEnabled = isDmEnabled,
        isDmActive = isDmActive,
        dmRequiresAuth = dmRequiresAuth,
        isSearchEnabled = isSearchEnabled,
        isSearchActive = isSearchActive,
        isIndexEnabled = isIndexEnabled,
        isIndexActive = isIndexActive,
        isOnion = isOnion,
        isDiscovered = isDiscovered,
        connectionTimeoutMs = connectionTimeoutMs,
        addedAtMillis = addedAtMillis,
        relayInfo = relayInfoJson?.let { decodeRelayInfo(it) },
        nip11FetchedAtMillis = nip11FetchedAtMillis
    )
}

fun Relay.toEntity(): RelayEntity {
    return RelayEntity(
        id = id,
        url = url,
        isEnabled = isEnabled,
        isReadEnabled = isReadEnabled,
        isReadActive = isReadActive,
        isWriteEnabled = isWriteEnabled,
        isWriteActive = isWriteActive,
        isDmEnabled = isDmEnabled,
        isDmActive = isDmActive,
        dmRequiresAuth = dmRequiresAuth,
        isSearchEnabled = isSearchEnabled,
        isSearchActive = isSearchActive,
        isIndexEnabled = isIndexEnabled,
        isIndexActive = isIndexActive,
        isOnion = isOnion,
        isDiscovered = isDiscovered,
        connectionTimeoutMs = connectionTimeoutMs,
        addedAtMillis = addedAtMillis,
        relayInfoJson = relayInfo?.let { info ->
            try {
                JsonUtils.NostrJson.encodeToString(info)
            } catch (e: Exception) {
                null
            }
        },
        nip11FetchedAtMillis = nip11FetchedAtMillis
    )
}

fun List<RelayEntity>.toDomains(): List<Relay> = map { it.toDomain() }

fun List<Relay>.toEntities(): List<RelayEntity> = map { it.toEntity() }

