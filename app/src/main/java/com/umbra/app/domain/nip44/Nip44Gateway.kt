package com.umbra.app.domain.nip44

import android.content.Intent

/**
 * NIP-44 (versioned encryption) gateway. Umbra never implements the ChaCha20/HKDF/HMAC
 * cryptography locally — every encrypt/decrypt goes through Amber's nip44_encrypt/nip44_decrypt,
 * mirroring how [com.umbra.app.domain.nip55.AmberSignerGateway] is the only path to signing.
 */
interface Nip44Gateway {
    /**
     * Creates a NIP-55 nip44_encrypt intent. [pubkeyHex] is the other party's hex pubkey — for
     * self-encrypted NIP-51 "private list" content, this is the current user's own pubkey.
     */
    fun createNip44EncryptIntent(plaintext: String, pubkeyHex: String, currentUserHex: String? = null): Intent

    /** Same as [createNip44EncryptIntent], for nip44_decrypt. */
    fun createNip44DecryptIntent(ciphertext: String, pubkeyHex: String, currentUserHex: String? = null): Intent

    /** Extracts a nip44_encrypt/nip44_decrypt result (ciphertext or plaintext) from an Amber activity result. */
    fun extractNip44ResultFromResult(data: Intent?): String?

    /**
     * Tries nip44_encrypt without ever showing Amber's UI, via its ContentProvider — succeeds
     * immediately when the permission is already approved. Returns null when Amber needs user
     * interaction, in which case the caller must fall back to createNip44EncryptIntent() + the
     * Activity-result launcher.
     */
    suspend fun tryNip44EncryptInBackground(plaintext: String, pubkeyHex: String, currentUserHex: String?): String?

    /** Background/ContentProvider fast path for nip44_decrypt — see [tryNip44EncryptInBackground]'s doc for the contract. */
    suspend fun tryNip44DecryptInBackground(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String?

    /**
     * One-shot, high-level nip44_encrypt round trip: tries the background/ContentProvider fast
     * path first, then falls back to an interactive Amber request dispatched through a single
     * app-wide launcher — works regardless of which screen is currently open. Returns null on
     * any failure/timeout/rejection.
     */
    suspend fun nip44Encrypt(plaintext: String, pubkeyHex: String, currentUserHex: String?): String?

    /** Same as [nip44Encrypt], for nip44_decrypt. */
    suspend fun nip44Decrypt(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String?
}
