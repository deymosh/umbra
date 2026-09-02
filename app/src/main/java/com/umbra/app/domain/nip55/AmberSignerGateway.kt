package com.umbra.app.domain.nip55

import android.content.Intent

/**
 * NIP-55 (Android Signer Application) gateway — the only path through which Umbra ever signs a
 * Nostr event. See [com.umbra.app.domain.nip44.Nip44Gateway] for the sibling NIP-44 encrypt/decrypt
 * contract Amber also exposes.
 */
interface AmberSignerGateway {
    fun isAmberInstalled(): Boolean
    fun createLoginIntent(): Intent
    fun createSignEventIntent(eventJson: String, currentUserHex: String? = null): Intent
    fun createStoreIntent(): Intent
    fun extractPublicKeyFromResult(data: Intent?): String?
    fun extractSignedEventFromResult(data: Intent?): String?

    /**
     * Tries to sign without ever showing Amber's UI, via its ContentProvider — succeeds
     * immediately when the permission for this event kind is already approved. Returns null
     * when Amber needs user interaction to decide, in which case the caller must fall back to
     * createSignEventIntent() + the Activity-result launcher as before.
     */
    suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String?

    /**
     * One-shot, high-level sign_event round trip: tries the background/ContentProvider fast path
     * first, then falls back to an interactive Amber request dispatched through a single
     * app-wide launcher — which works regardless of which screen is currently open, unlike the
     * previous per-screen launcher pattern. Returns null on any failure/timeout/rejection;
     * callers no longer need their own SharedFlow<Intent>/pending-queue/handleXResult plumbing.
     */
    suspend fun signEvent(eventJson: String, currentUserHex: String?): String?

    /**
     * One-shot public-key (login) request — always interactive, there's no background/silent
     * path for establishing trust with a signer for the first time.
     */
    suspend fun requestPublicKey(): String?

    /** Fire-and-forget: opens Amber's Play Store listing. No result expected/awaited. */
    fun openStore(): Boolean
}
