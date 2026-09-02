package com.umbra.app.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lightweight encrypted key-value wrapper backed by SharedPreferences.
 * Values are encrypted with AES/GCM using a key stored in AndroidKeyStore.
 */
class SecurePreferences(context: Context, name: String) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "umbra_secure_prefs_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE_BITS = 128
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val keyAlias: String = KEY_ALIAS_PREFIX + name

    fun putString(key: String, value: String) {
        val encrypted = encrypt(value)
        val persisted = prefs.edit().putString(key, encrypted).commit()
        check(persisted) { "Failed to persist secure preference '$key'" }
    }

    fun getString(key: String): String? {
        val encrypted = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()
    }

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    fun remove(key: String) {
        val persisted = prefs.edit().remove(key).commit()
        check(persisted) { "Failed to remove secure preference '$key'" }
    }

    fun clear() {
        val persisted = prefs.edit().clear().commit()
        check(persisted) { "Failed to clear secure preferences '$keyAlias'" }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))

        val packed = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()

        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packedBase64: String): String {
        val packed = Base64.decode(packedBase64, Base64.NO_WRAP)
        require(packed.size > IV_SIZE) { "Invalid encrypted payload" }

        val iv = packed.copyOfRange(0, IV_SIZE)
        val ciphertext = packed.copyOfRange(IV_SIZE, packed.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_SIZE_BITS, iv)
        )

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null)
        if (existing is SecretKey) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
