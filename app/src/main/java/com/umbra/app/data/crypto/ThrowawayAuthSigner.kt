package com.umbra.app.data.crypto

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip42.ThrowawayAuthSigner
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint

/**
 * The single in-app Schnorr signer — the narrow AUDIT.md 1.2 exception: it signs ONLY NIP-42
 * kind-22242 relay AUTH events, ONLY with per-connection throwaway keys, ONLY when the user's
 * RelayAuthMode setting allows it. No user-authored event may ever pass through here; those go
 * exclusively through the external signer gateway (AmberSignerGateway).
 *
 * Keys live only in [keysByRelay]: one random secp256k1 scalar per relay URL per connection,
 * generated with SecureRandom, never persisted, never logged, used for nothing but kind 22242.
 * They are discarded when that relay's connection closes — UmbraNostrClient calls
 * [discardKeyForRelay] right beside its storedAuthChallenges eviction.
 */
@Singleton
class ThrowawayAuthSignerImpl @Inject constructor() : ThrowawayAuthSigner {

    private val keysByRelay = ConcurrentHashMap<String, BigInteger>()

    override fun signAuthEvent(relayUrl: String, unsignedEventJson: String): String {
        val privateKey = keysByRelay[relayUrl]
            ?: throw IllegalStateException("No throwaway AUTH key active for this relay connection")
        val unsigned = JsonUtils.NostrJson.parseToJsonElement(unsignedEventJson) as? JsonObject
            ?: throw IllegalArgumentException("Unsigned AUTH event is not a JSON object")

        val kind = unsigned["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: Event.KIND_CLIENT_AUTH
        require(kind == Event.KIND_CLIENT_AUTH) {
            "Throwaway signer may only sign kind 22242 relay AUTH events"
        }
        val createdAt = unsigned["created_at"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000L)

        val event = Event(
            id = "",
            pubkey = publicKeyHexFor(privateKey),
            createdAt = createdAt,
            kind = kind,
            tags = parseTags(unsigned),
            content = unsigned["content"]?.jsonPrimitive?.content.orEmpty(),
            sig = ""
        )
        val eventId = EventCrypto.computeEventId(event)
        val signature = schnorrSign(privateKey, eventId.hexToBytes())

        return buildJsonObject {
            put("id", eventId)
            put("pubkey", event.pubkey)
            put("created_at", event.createdAt)
            put("kind", event.kind)
            put("tags", buildJsonArray { event.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) } })
            put("content", event.content)
            put("sig", signature.toHex())
        }.toString()
    }

    /** Replaces any existing key for [relayUrl] with a fresh one; called when a connection opens. */
    fun ensureKeyForRelay(relayUrl: String): BigInteger {
        val generated = generatePrivateKey()
        keysByRelay[relayUrl] = generated
        return generated
    }

    /** Drop the key — called when that relay's connection closes; nothing is persisted anywhere. */
    fun discardKeyForRelay(relayUrl: String) {
        keysByRelay.remove(relayUrl)
    }

    /** Wipes every active throwaway key — used on disconnectAll/logout. */
    fun discardAllKeys() {
        keysByRelay.clear()
    }

    internal fun hasKeyForRelay(relayUrl: String): Boolean = keysByRelay.containsKey(relayUrl)

    /**
     * BIP-340 keygen: uniform via SecureRandom, rejecting 0 and anything >= curve n (both invalid
     * private keys). Loop terminates with certainty: n < 2^256, so rejection probability < 2^-128.
     */
    private fun generatePrivateKey(): BigInteger {
        val n = CURVE_PARAMS.n
        val candidate = ByteArray(32)
        while (true) {
            SECURE_RANDOM.nextBytes(candidate)
            val key = BigInteger(1, candidate)
            if (key.signum() > 0 && key < n) return key
        }
    }

    private fun publicKeyHexFor(privateKey: BigInteger): String {
        val publicPoint: ECPoint = CURVE_PARAMS.g.multiply(privateKey).normalize()
        return publicPoint.affineXCoord.toBigInteger().toFixed32().toHex()
    }

    /**
     * Test-only hook (internal, exercised by ThrowawayAuthSignerTest): signs an arbitrary 32-byte
     * message with a FIXED key and FIXED aux randomness so the BIP-340 official vectors can be
     * asserted byte-for-byte. Never called from production code — production only ever signs via
     * [signAuthEvent], which goes through the random-key path above.
     */
    internal fun signWithFixedInputs(privateKey: BigInteger, auxBytes: ByteArray, message: ByteArray): ByteArray =
        schnorrSignInternal(privateKey, message, auxBytes)

    /** BIP-340 Schnorr signing of [message] (the 32-byte event id) with fresh aux randomness. */
    private fun schnorrSign(privateKey: BigInteger, message: ByteArray): ByteArray {
        val aux = ByteArray(32)
        SECURE_RANDOM.nextBytes(aux)
        return schnorrSignInternal(privateKey, message, aux)
    }

    private fun schnorrSignInternal(privateKey: BigInteger, message: ByteArray, aux: ByteArray): ByteArray {
        val p0 = CURVE_PARAMS.g.multiply(privateKey).normalize()
        val d = if (p0.affineYCoord.toBigInteger().testBit(0)) CURVE_PARAMS.n - privateKey else privateKey
        val pBytes = p0.affineXCoord.toBigInteger().toFixed32()

        val t = BigInteger(1, taggedHash("BIP0340/aux", aux)).xor(d)
        val kPrime = BigInteger(1, taggedHash("BIP0340/nonce", t.toFixed32() + pBytes + message))
            .mod(CURVE_PARAMS.n)
        require(kPrime.signum() != 0) { "BIP-340 nonce derivation produced zero — retry" }

        val rPoint = CURVE_PARAMS.g.multiply(kPrime).normalize()
        require(!rPoint.isInfinity) { "BIP-340 nonce point at infinity — retry" }
        // BIP-340: R's y must be even — if odd, sign with k = n - k' (same R, flipped y).
        val k = if (rPoint.affineYCoord.toBigInteger().testBit(0)) CURVE_PARAMS.n - kPrime else kPrime
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rBytes32(rPoint) + pBytes + message))
            .mod(CURVE_PARAMS.n)
        val s = (k + e.multiply(d)).mod(CURVE_PARAMS.n)
        require(s.signum() != 0) { "BIP-340 signature scalar is zero — retry" }
        return rBytes32(rPoint) + s.toFixed32()
    }

    private fun rBytes32(point: ECPoint): ByteArray =
        point.affineXCoord.toBigInteger().toFixed32()

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
        return MessageDigest.getInstance("SHA-256").digest(tagHash + tagHash + data)
    }

    private fun BigInteger.toFixed32(): ByteArray {
        val bytes = toByteArray()
        val normalized = when {
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
            bytes.size <= 32 -> bytes
            else -> throw IllegalArgumentException("Unsigned integer does not fit in 32 bytes")
        }
        return if (normalized.size == 32) normalized else ByteArray(32 - normalized.size) + normalized
    }

    private fun parseTags(unsigned: JsonObject): List<List<String>> {
        val tagsJson = unsigned["tags"] as? JsonArray ?: return emptyList()
        return tagsJson.map { tag ->
            (tag as? JsonArray)?.map { v -> v.jsonPrimitive.content }.orEmpty()
        }
    }

    private companion object {
        val CURVE_PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
        val SECURE_RANDOM = SecureRandom()
    }
}
