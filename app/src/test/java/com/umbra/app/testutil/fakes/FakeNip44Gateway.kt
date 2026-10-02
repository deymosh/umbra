package com.umbra.app.testutil.fakes

import android.content.Intent
import com.umbra.app.domain.nip44.Nip44Gateway
import java.util.Base64

/**
 * [Nip44Gateway] stand-in: "encrypts" by base64-encoding a fixed-size prefix plus the plaintext,
 * so ciphertexts look like NIP-44 payloads and decrypt back. [allowBackground] false makes the
 * background path refuse, like a signer without a remembered permission; [refuse] refuses all.
 */
internal class FakeNip44Gateway(
    var allowBackground: Boolean = true,
    var refuse: Boolean = false
) : Nip44Gateway {
    var interactiveDecrypts = 0
    var encrypts = 0

    override fun createNip44EncryptIntent(plaintext: String, pubkeyHex: String, currentUserHex: String?): Intent =
        throw UnsupportedOperationException()
    override fun createNip44DecryptIntent(ciphertext: String, pubkeyHex: String, currentUserHex: String?): Intent =
        throw UnsupportedOperationException()
    override fun extractNip44ResultFromResult(data: Intent?): String? = null

    override suspend fun tryNip44EncryptInBackground(plaintext: String, pubkeyHex: String, currentUserHex: String?) =
        if (allowBackground && !refuse) encode(plaintext) else null
    override suspend fun tryNip44DecryptInBackground(ciphertext: String, pubkeyHex: String, currentUserHex: String?) =
        if (allowBackground && !refuse) decode(ciphertext) else null
    override suspend fun nip44Encrypt(plaintext: String, pubkeyHex: String, currentUserHex: String?): String? {
        encrypts++
        return if (refuse) null else encode(plaintext)
    }
    override suspend fun nip44Decrypt(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String? {
        interactiveDecrypts++
        return if (refuse) null else decode(ciphertext)
    }

    fun encode(plaintext: String): String =
        Base64.getEncoder().encodeToString((PREFIX + plaintext).toByteArray())

    private fun decode(ciphertext: String): String? =
        runCatching { String(Base64.getDecoder().decode(ciphertext)) }.getOrNull()
            ?.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)

    private companion object {
        val PREFIX = "x".repeat(100)
    }
}
