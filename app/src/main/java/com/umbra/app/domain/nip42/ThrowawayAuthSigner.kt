package com.umbra.app.domain.nip42

/**
 * Signs NIP-42 relay AUTH events (kind 22242) in-app with a random, connection-scoped throwaway
 * key — the narrow AUDIT.md part 1.2 exception, permitted ONLY for kind-22242 AUTH against
 * non-own relays and only behind the user's RelayAuthMode setting. Every user-authored event
 * still goes exclusively through the external signer gateway.
 */
interface ThrowawayAuthSigner {
    /**
     * Signs the given unsigned kind-22242 event JSON (as produced by NostrEventBuilder.relayAuth)
     * with the throwaway key currently bound to [relayUrl]. IllegalStateException if no key is
     * active for that relay (meaning the connection is gone — the caller should not send AUTH).
     */
    fun signAuthEvent(relayUrl: String, unsignedEventJson: String): String
}
