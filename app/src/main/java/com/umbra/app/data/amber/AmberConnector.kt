package com.umbra.app.data.amber

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog

/**
 * NIP-55 connector for any installed Android signer app (Amber is the suggested one, not the
 * only one). Every request names the signer's package explicitly — the one chosen at login — so a
 * signing intent is never resolved to some other app handling `nostrsigner:`.
 *
 * AMBER: https://github.com/greenart7c3/Amber
 *
 * Non-custodial key management pattern:
 * - Umbra never stores private keys
 * - All signing requests delegated to AMBER
 * - AMBER handles key storage in Android Keystore
 */
object AmberConnector {
    private const val TAG = "UmbraAmber"
    private val logger = UmbraLog.tag(TAG)

    // Amber's package: the signer suggested for install, and the fallback when nothing better is known.
    const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

    // NIP-55: get_public_key returns the signer's package name in this extra.
    private const val EXTRA_PACKAGE = "package"

    // NIP-55 command codes Amber's intent contract expects
    private const val TYPE_GET_PUBLIC_KEY = "get_public_key"
    private const val TYPE_SIGN_EVENT = "sign_event"
    private const val TYPE_NIP44_ENCRYPT = "nip44_encrypt"
    private const val TYPE_NIP44_DECRYPT = "nip44_decrypt"

    // Amber's SignerProvider ContentProvider authority for signing (com.greenart7c3.nostrsigner.SignerProvider).
    // No path-based routing — each NIP-55 command is its own authority string.
    private const val CONTENT_PROVIDER_SIGN_EVENT = "SIGN_EVENT"
    private const val CONTENT_PROVIDER_NIP44_ENCRYPT = "NIP44_ENCRYPT"
    private const val CONTENT_PROVIDER_NIP44_DECRYPT = "NIP44_DECRYPT"

    // Response extras
    private const val EXTRA_RETURN_LABEL = "returnLabel"
    private const val EXTRA_PUBLIC_KEY = "public_key"
    private const val EXTRA_EVENT = "event"
    private const val EXTRA_SIGNED_MESSAGE = "signedMessage"
    private const val EXTRA_TYPE = "type"
    private const val EXTRA_RESULT = "result"
    private const val EXTRA_CURRENT_USER = "current_user"
    private const val EXTRA_REJECTED = "rejected"
    // NIP-55: the other party's hex pubkey for nip44_encrypt/nip44_decrypt. For the self-
    // encrypted NIP-51 "private list" use case this is always the current user's own pubkey.
    private const val EXTRA_PUBKEY = "pubkey"

    /** Packages of every installed app that handles NIP-55 `nostrsigner:` intents. */
    fun installedSignerPackages(context: Context): List<String> {
        val probe = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
        return try {
            context.packageManager.queryIntentActivities(probe, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        } catch (e: Exception) {
            logger.d { "Signer lookup failed: ${scrubThrowableMessageForLogs(e)}" }
            emptyList()
        }
    }

    /** Whether any NIP-55 signer is installed. */
    fun isSignerInstalled(context: Context): Boolean = installedSignerPackages(context).isNotEmpty()

    /** The signer package a NIP-55 get_public_key result names, if it names one. */
    fun extractSignerPackageFromResult(data: Intent?): String? =
        data?.getStringExtra(EXTRA_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Attempts to sign via Amber's ContentProvider (content://<amber-package>.SIGN_EVENT) — a
     * synchronous IPC call with no visible UI, answered immediately when the permission for this
     * event kind is already approved (e.g. "auto-approve" / "fully trust"). Returns null when
     * Amber can't answer without user interaction — a brand-new permission, or one it has an
     * explicit reject policy for — in which case the caller must fall back to the existing
     * `nostrsigner:` Intent flow (createSignEventIntent + launcher), which shows Amber's UI.
     * This never makes signing *less* reliable than the Intent-only flow: any failure here
     * (exception, unapproved, rejected, malformed response) just means "fall back to what Umbra
     * already does today."
     *
     * Blocking (ContentResolver.query()) — must be called off the main thread.
     */
    fun trySignEventContentResolver(context: Context, signerPackage: String, eventJson: String, currentUserHex: String?): String? {
        if (currentUserHex.isNullOrBlank()) return null
        return try {
            val npub = Bech32Encoder.encodeNpub(currentUserHex)
            val uri = "content://$signerPackage.$CONTENT_PROVIDER_SIGN_EVENT".toUri()
            // Projection is Amber's SignerProvider contract: [event JSON, event pubkey (unused
            // by Amber's handler), npub of the signing account].
            context.contentResolver.query(uri, arrayOf(eventJson, "", npub), null, null, null)?.use { cursor ->
                if (cursor.getColumnIndex(EXTRA_REJECTED) >= 0) return null
                if (!cursor.moveToFirst()) return null
                val eventIndex = cursor.getColumnIndex("event")
                val resultIndex = cursor.getColumnIndex("result")
                when {
                    eventIndex >= 0 -> cursor.getString(eventIndex)
                    resultIndex >= 0 -> cursor.getString(resultIndex)
                    else -> null
                }
            }
        } catch (e: Exception) {
            logger.d { "Content-resolver sign unavailable, falling back to intent: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Same ContentProvider fast path as [trySignEventContentResolver], for NIP-44 encryption —
     * answered synchronously with no visible UI when the user has previously approved
     * "remember" for nip44_encrypt. Returns null (fall back to [createNip44EncryptIntent] +
     * launcher) on any failure, unapproved permission, or rejection.
     *
     * Blocking (ContentResolver.query()) — must be called off the main thread.
     */
    fun tryNip44EncryptContentResolver(context: Context, signerPackage: String, plaintext: String, pubkeyHex: String, currentUserHex: String?): String? =
        tryNip44ContentResolver(context, signerPackage, CONTENT_PROVIDER_NIP44_ENCRYPT, plaintext, pubkeyHex, currentUserHex)

    /** Same as [tryNip44EncryptContentResolver], for nip44_decrypt. */
    fun tryNip44DecryptContentResolver(context: Context, signerPackage: String, ciphertext: String, pubkeyHex: String, currentUserHex: String?): String? =
        tryNip44ContentResolver(context, signerPackage, CONTENT_PROVIDER_NIP44_DECRYPT, ciphertext, pubkeyHex, currentUserHex)

    private fun tryNip44ContentResolver(
        context: Context,
        signerPackage: String,
        contentProviderAuthority: String,
        payload: String,
        pubkeyHex: String,
        currentUserHex: String?
    ): String? {
        if (currentUserHex.isNullOrBlank()) return null
        return try {
            val npub = Bech32Encoder.encodeNpub(currentUserHex)
            val uri = "content://$signerPackage.$contentProviderAuthority".toUri()
            // Projection per NIP-55's Content Resolver contract: [payload, counterparty pubkey, npub of the signing account].
            context.contentResolver.query(uri, arrayOf(payload, pubkeyHex, npub), null, null, null)?.use { cursor ->
                if (cursor.getColumnIndex(EXTRA_REJECTED) >= 0) return null
                if (!cursor.moveToFirst()) return null
                val resultIndex = cursor.getColumnIndex("result")
                if (resultIndex >= 0) cursor.getString(resultIndex) else null
            }
        } catch (e: Exception) {
            logger.d { "Content-resolver $contentProviderAuthority unavailable, falling back to intent: ${scrubThrowableMessageForLogs(e)}" }
            null
        }
    }

    /**
     * Get AMBER app URI
     */
    fun getAmberAppUri(): String {
        return "https://play.google.com/store/apps/details?id=$AMBER_PACKAGE"
    }

    /**
     * Creates a NIP-55 login intent that asks a signer for the public key. With [signerPackage]
     * null (several signers installed) Android lets the user pick one; the result names it.
     */
    fun createLoginIntent(signerPackage: String?): Intent {
        return Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri()).apply {
            signerPackage?.let { `package` = it }
            putExtra(EXTRA_TYPE, TYPE_GET_PUBLIC_KEY)
            putExtra(EXTRA_RETURN_LABEL, "Umbra")
        }
    }

    /**
     * Creates a NIP-55 sign event intent.
     * current_user must be npub format so AMBER can match the account.
     */
    fun createSignEventIntent(signerPackage: String, eventJson: String, currentUserHex: String? = null): Intent {
        return Intent(Intent.ACTION_VIEW, "nostrsigner:$eventJson".toUri()).apply {
            `package` = signerPackage
            putExtra(EXTRA_TYPE, TYPE_SIGN_EVENT)
            putExtra(EXTRA_EVENT, eventJson)
            putExtra(EXTRA_RETURN_LABEL, "Umbra")
            if (!currentUserHex.isNullOrBlank()) {
                // NIP-55: current_user is npub format so AMBER pre-selects the correct account
                val npub = Bech32Encoder.encodeNpub(currentUserHex)
                putExtra(EXTRA_CURRENT_USER, npub)
            }
        }
    }

    /**
     * Creates a NIP-55 nip44_encrypt intent. [pubkeyHex] is the other party's hex pubkey — for
     * the self-encrypted NIP-51 "private list" use case, this is the current user's own pubkey.
     * current_user must be npub format so AMBER can match the account.
     */
    fun createNip44EncryptIntent(signerPackage: String, plaintext: String, pubkeyHex: String, currentUserHex: String? = null): Intent =
        createNip44Intent(signerPackage, TYPE_NIP44_ENCRYPT, plaintext, pubkeyHex, currentUserHex)

    /** Same as [createNip44EncryptIntent], for nip44_decrypt. */
    fun createNip44DecryptIntent(signerPackage: String, ciphertext: String, pubkeyHex: String, currentUserHex: String? = null): Intent =
        createNip44Intent(signerPackage, TYPE_NIP44_DECRYPT, ciphertext, pubkeyHex, currentUserHex)

    private fun createNip44Intent(signerPackage: String, type: String, payload: String, pubkeyHex: String, currentUserHex: String?): Intent {
        return Intent(Intent.ACTION_VIEW, "nostrsigner:$payload".toUri()).apply {
            `package` = signerPackage
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_PUBKEY, pubkeyHex)
            putExtra(EXTRA_RETURN_LABEL, "Umbra")
            if (!currentUserHex.isNullOrBlank()) {
                val npub = Bech32Encoder.encodeNpub(currentUserHex)
                putExtra(EXTRA_CURRENT_USER, npub)
            }
        }
    }

    /**
     * Extracts public key from either legacy AMBER extras or NIP-55 style result payload.
     */
    fun extractPublicKeyFromResult(data: Intent?): String? {
        if (data == null) return null
        if (data.getBooleanExtra(EXTRA_REJECTED, false)) return null

        val rawKey = data.getStringExtra(EXTRA_PUBLIC_KEY)
            ?: data.getStringExtra(EXTRA_RESULT)
            ?: data.getStringExtra("pubkey")

        return normalizePublicKey(rawKey)
    }

    /**
     * Extracts signed event JSON from either legacy AMBER extras or NIP-55 style result payload.
     */
    fun extractSignedEventFromResult(data: Intent?): String? {
        if (data == null) return null
        if (data.getBooleanExtra(EXTRA_REJECTED, false)) return null

        return data.getStringExtra(EXTRA_SIGNED_MESSAGE)
            ?: data.getStringExtra(EXTRA_EVENT)
    }

    /**
     * Extracts a nip44_encrypt/nip44_decrypt result (ciphertext or plaintext) from the intent
     * result — NIP-55's table returns these via the `result` extra, not `event`/`signedMessage`.
     */
    fun extractNip44ResultFromResult(data: Intent?): String? {
        if (data == null) return null
        if (data.getBooleanExtra(EXTRA_REJECTED, false)) return null

        return data.getStringExtra(EXTRA_RESULT)
    }

    private fun normalizePublicKey(value: String?): String? {
        val candidate = value?.trim()?.lowercase().orEmpty()
        if (candidate.isBlank()) {
            return null
        }

        val hex = if (candidate.startsWith("npub1")) Bech32Encoder.decodeNpub(candidate)?.lowercase() else candidate
        // Anything else the signer sends back (a garbled npub, another network's key) isn't an
        // identity Umbra can sign in as.
        return hex?.takeIf { key -> key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } }
    }
}

