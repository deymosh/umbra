package com.umbra.app.domain.crypto

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.hexToBytes
import com.umbra.app.domain.util.toHex
import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.util.Locale

object EventCrypto {

    private val CURVE_PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    private val CURVE = org.bouncycastle.crypto.params.ECDomainParameters(
        CURVE_PARAMS.curve,
        CURVE_PARAMS.g,
        CURVE_PARAMS.n,
        CURVE_PARAMS.h
    )
    private val FIELD_P: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16
    )
    private val CURVE_N: BigInteger = CURVE.n

    /**
     * Verify a Nostr event Schnorr signature (BIP-340).
     * Returns true only if the signature is cryptographically valid.
     */
    fun verifySignature(event: Event): Boolean {
        return runCatching {
            val pubkeyBytes = event.pubkey.hexToBytes()
            val sigBytes = event.sig.hexToBytes()
            val idBytes = event.id.hexToBytes()

            if (pubkeyBytes.size != 32) return false
            if (sigBytes.size != 64) return false
            if (idBytes.size != 32) return false

            schnorrVerify(
                pubkeyBytes = pubkeyBytes,
                messageBytes = idBytes,
                signatureBytes = sigBytes
            )
        }.getOrDefault(false)
    }

    /**
     * BIP-340 Schnorr verification on secp256k1.
     * pubkeyBytes: 32-byte x-only public key
     * messageBytes: 32-byte message hash (event id)
     * signatureBytes: 64-byte signature (r || s)
     */
    private fun schnorrVerify(
        pubkeyBytes: ByteArray,
        messageBytes: ByteArray,
        signatureBytes: ByteArray
    ): Boolean {
        val px = BigInteger(1, pubkeyBytes)
        val r = BigInteger(1, signatureBytes.copyOfRange(0, 32))
        val s = BigInteger(1, signatureBytes.copyOfRange(32, 64))

        if (px >= FIELD_P) return false
        if (r >= FIELD_P || s >= CURVE_N) return false

        // Lift x: find y such that y^2 = x^3 + 7 (mod p), y even
        val ySquared = (px.modPow(BigInteger.valueOf(3L), FIELD_P) + BigInteger.valueOf(7L)).mod(FIELD_P)
        val y = ySquared.modPow((FIELD_P + BigInteger.ONE).divide(BigInteger.valueOf(4L)), FIELD_P)
        if (y.modPow(BigInteger.valueOf(2L), FIELD_P) != ySquared) return false
        val evenY = if (y.testBit(0)) FIELD_P - y else y

        val pubPoint: ECPoint = CURVE_PARAMS.curve.createPoint(px, evenY)
        if (!pubPoint.isValid) return false

        // e = int(hash_BIP0340/challenge(bytes(r) || bytes(P) || m)) mod n
        val rBytes = r.toFixed32()
        val pBytes = px.toFixed32()
        val challengeInput = rBytes + pBytes + messageBytes
        val eHash = taggedHash("BIP0340/challenge", challengeInput)
        val e = BigInteger(1, eHash).mod(CURVE_N)

        // R = s*G - e*P
        val sG = CURVE_PARAMS.g.multiply(s)
        val eP = pubPoint.multiply(e)
        val R = sG.subtract(eP).normalize()

        if (R.isInfinity) return false
        // BIP-340: y(R) must be even.
        if (R.affineYCoord.toBigInteger().testBit(0)) return false
        return R.affineXCoord.toBigInteger() == r
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + data)
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
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

    /**
     * Compute the event ID as SHA-256 of the canonical serialization.
     * NIP-01: id = SHA256(JSON([0,pubkey,created_at,kind,tags,content]))
     */
    fun computeEventId(event: Event): String {
        val canonical = buildCanonicalEventJson(event)
        return sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun buildCanonicalEventJson(event: Event): String {
        val tagsJson = event.tags.joinToString(separator = ",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(separator = ",", prefix = "[", postfix = "]") { value ->
                quoteJson(value)
            }
        }

        return buildString {
            append('[')
            append('0')
            append(',')
            append(quoteJson(event.pubkey))
            append(',')
            append(event.createdAt)
            append(',')
            append(event.kind)
            append(',')
            append(tagsJson)
            append(',')
            append(quoteJson(event.content))
            append(']')
        }
    }

    private fun quoteJson(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0').uppercase(Locale.US))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Verify the event ID matches the canonical serialization.
     */
    fun verifyEventId(event: Event): Boolean {
        return runCatching {
            computeEventId(event).equals(event.id, ignoreCase = true)
        }.getOrDefault(false)
    }

    /**
     * Full event verification: ID integrity + Schnorr signature.
     */
    fun verifyEvent(event: Event): Boolean {
        return verifyEventId(event) && verifySignature(event)
    }
}

