package com.umbra.app.data.crypto

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.domain.util.hexToBytes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the in-app throwaway AUTH signer [ThrowawayAuthSignerImpl] — AUDIT.md 1.2's
 * narrow exception, exercised here against the official BIP-340 vectors plus a round trip through
 * [EventCrypto.verifyEvent] and the key-lifecycle contract used by UmbraNostrClient.
 */
class ThrowawayAuthSignerTest {

    private val signer = ThrowawayAuthSignerImpl()

    // secp256k1 group order n
    private val n = java.math.BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
    )

    /**
     * BIP-340 official vectors 0-3 (with their given aux_rand), via a fixed-key injection point:
     * the signer's normal path uses SecureRandom for both keygen and aux; for the vectors we drive
     * the underlying schnorr math through the public API by pre-seeding the relay key and
     * signing a synthetic kind-22242 event whose event id (SHA-256 over the canonical json) IS the
     * vector message. That requires controlling the message, which canonical event json can't do,
     * so vectors are checked at the signature level using a targeted signer instance whose aux
     * randomness and key are pinned via the documented test hook below.
     */
    private fun signedOutputBip340(seckeyHex: String, msgHex: String, auxHex: String): String =
        signer.signWithFixedInputs(
            privateKey = java.math.BigInteger(1, seckeyHex.hexToBytes()),
            auxBytes = auxHex.hexToBytes(),
            message = msgHex.hexToBytes()
        ).toHex()

    @Test
    fun `given BIP-340 vector 0 when signing then signature matches`() {
        val sig = signedOutputBip340(
            "0000000000000000000000000000000000000000000000000000000000000003",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertEquals(
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0".lowercase(),
            sig
        )
    }

    @Test
    fun `given BIP-340 vector 1 when signing then signature matches`() {
        val sig = signedOutputBip340(
            "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        assertEquals(
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A".lowercase(),
            sig
        )
    }

    @Test
    fun `given BIP-340 vector 2 when signing then signature matches`() {
        val sig = signedOutputBip340(
            "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906"
        )
        assertEquals(
            "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7".lowercase(),
            sig
        )
    }

    @Test
    fun `given BIP-340 vector 3 when signing then signature matches`() {
        val sig = signedOutputBip340(
            "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        )
        assertEquals(
            "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3".lowercase(),
            sig
        )
    }

    @Test
    fun `given a random key when signing an AUTH event then it verifies with BIP-340`() {
        val relayUrl = "wss://example.relay.test"
        val pub = signer.ensureKeyForRelay(relayUrl).let(::unused)
        val unsigned = unsignedAuthJson(relayUrl, challenge = "challenge-1")
        val signed = signer.signAuthEvent(relayUrl, unsigned)

        val parsed = JsonUtils.NostrJson.parseToJsonElement(signed).jsonObject
        val event = Event(
            id = (parsed["id"] as JsonPrimitive).content,
            pubkey = (parsed["pubkey"] as JsonPrimitive).content,
            createdAt = (parsed["created_at"] as JsonPrimitive).content.toLong(),
            kind = (parsed["kind"] as JsonPrimitive).content.toInt(),
            tags = (parsed["tags"] as kotlinx.serialization.json.JsonArray).map { tag ->
                (tag as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
            },
            content = (parsed["content"] as JsonPrimitive).content,
            sig = (parsed["sig"] as JsonPrimitive).content
        )
        assertTrue(EventCrypto.verifyEvent(event))
        assertEquals(event.pubkey, pub.toHexPubkey())
        assertEquals(
            64,
            event.sig.hexToBytes().size
        )
    }

    @Test
    fun `given no active key when signing then it throws`() {
        val unsigned = unsignedAuthJson("wss://example.relay.test", "challenge-1")
        val e = runCatching { signer.signAuthEvent("wss://other.relay.test", unsigned) }
            .exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test
    fun `given kind other than 22242 when signing then it is rejected`() {
        val relayUrl = "wss://example.relay.test"
        val pub = signer.ensureKeyForRelay(relayUrl)
        val notAuth = buildJsonObject {
            put("id", "")
            put("pubkey", "")
            put("created_at", 1000L)
            put("kind", 1)
            put("tags", JsonUtils.NostrJson.parseToJsonElement("[]"))
            put("content", "")
            put("sig", "")
        }.toString()
        val e = runCatching { signer.signAuthEvent(relayUrl, notAuth) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message.orEmpty().contains("22242"))
    }

    @Test
    fun `given key discarded when signing then throws until a new key is ensured`() {
        val relayUrl = "wss://example.relay.test"
        signer.ensureKeyForRelay(relayUrl)
        signer.discardKeyForRelay(relayUrl)
        val unsigned = unsignedAuthJson(relayUrl, "challenge-x")
        assertTrue(runCatching { signer.signAuthEvent(relayUrl, unsigned) }.isFailure)
        assertFalse(signer.hasKeyForRelay(relayUrl))
        signer.ensureKeyForRelay(relayUrl)
        assertTrue(signer.hasKeyForRelay(relayUrl))
        signer.discardAllKeys()
        assertFalse(signer.hasKeyForRelay(relayUrl))
    }

    @Test
    fun `given reconnect when re-ensuring key then old key is replaced`() {
        val relayUrl = "wss://example.relay.test"
        val first = signer.ensureKeyForRelay(relayUrl)
        val second = signer.ensureKeyForRelay(relayUrl)
        assertNotEquals(first, second)
        // Old key must be gone: sign twice, the pubkey in the output must stay the same random
        // identity only because it's the CURRENT key — the first can no longer be recovered.
        val unsigned = unsignedAuthJson(relayUrl, "challenge-x")
        val signed = signer.signAuthEvent(relayUrl, unsigned)
        val parsed = JsonUtils.NostrJson.parseToJsonElement(signed).jsonObject
        val pubkeyHex = (parsed["pubkey"] as JsonPrimitive).content
        assertTrue(pubkeyHex.isNotEmpty())
        assertTrue(pubkeyHex != first.toHexPubkey())
    }

    @Test
    fun `given all keys when discardAllKeys then nothing survives`() {
        signer.ensureKeyForRelay("wss://a.relay")
        signer.ensureKeyForRelay("wss://b.relay")
        assertTrue(signer.hasKeyForRelay("wss://a.relay"))
        signer.discardAllKeys()
        assertFalse(signer.hasKeyForRelay("wss://a.relay"))
        assertFalse(signer.hasKeyForRelay("wss://b.relay"))
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun unsignedAuthJson(relayUrl: String, challenge: String): String =
        NostrEventBuilder.relayAuth(challenge = challenge, relayUrl = relayUrl)

    private fun unused(big: java.math.BigInteger) = big

    private fun java.math.BigInteger.toHexPubkey(): String {
        val pubPoint = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
            .g.multiply(this).normalize()
        return pubPoint.affineXCoord.toBigInteger().toFixed32Test().toHex()
    }

    private fun java.math.BigInteger.toFixed32Test(): ByteArray {
        val bytes = toByteArray()
        val normalized = if (bytes.size == 33 && bytes[0].toInt() == 0) bytes.copyOfRange(1, 33) else bytes
        return if (normalized.size == 32) normalized else ByteArray(32 - normalized.size) + normalized
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
