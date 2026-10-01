package com.umbra.app.domain.relay

/**
 * User-owned policy for how the app authenticates (NIP-42, kind 22242) to relays that are NOT
 * the user's own relay configuration. Applies only to non-own relays: own relays (present in the
 * user's relay config, not auto-discovered, with at least one assigned role) always keep the
 * real-key external-signer behavior because they carry the user's own traffic (DM inbox, list
 * publishing).
 *
 * Applies to AUTH only — the user's own events are always signed by the external signer.
 */
enum class RelayAuthMode {
    /**
     * Sign AUTH with a random throwaway key held in memory for the life of that relay's
     * connection. Relays the user never chose learn only a random identity, or the real one if
     * the relay rejected one of the user's own publishes (it already saw that pubkey).
     */
    THROWAWAY_KEY,

    /** Sign AUTH with the user's real key through the external signer, but still lazily. */
    OWN_KEY,

    /** Never answer AUTH from non-own relays. */
    NEVER;

    companion object {
        fun fromStored(value: String?): RelayAuthMode =
            entries.firstOrNull { it.name == value } ?: THROWAWAY_KEY
    }
}

/**
 * What prompted a NIP-42 AUTH response on a given [RelayIssue].
 */
enum class AuthTrigger {
    /** The relay sent a real ["AUTH", challenge] frame unprompted. */
    CHALLENGE,

    /** A REQ the client sent was CLOSED with "auth-required:". */
    REQ_REJECTED,

    /** A ["OK", ..., false, "auth-required: ..."] rejected one of the user's own publishes. */
    PUBLISH_REJECTED
}

/** What the policy says to do for a given relay/mode/trigger combination. */
sealed interface RelayAuthDecision {
    /** Answer AUTH, signed with the user's key through the external signer or with the in-app throwaway key. */
    data class Sign(val useExternalSigner: Boolean) : RelayAuthDecision

    /** Do not respond to this trigger. */
    data object Ignore : RelayAuthDecision

    companion object {
        /**
         * Pure decision function.
         *
         * [isOwnRelay]: present in the user's relay config, not auto-discovered, with at least one
         * assigned role — see [isOwnRelay]. Unknown relays (not in the DB at all, e.g. relay hints
         * from event tags) are treated as non-own.
         */
        fun decide(mode: RelayAuthMode, isOwnRelay: Boolean, trigger: AuthTrigger): RelayAuthDecision =
            when {
                isOwnRelay -> RelayAuthDecision.Sign(useExternalSigner = true)
                mode == RelayAuthMode.NEVER -> RelayAuthDecision.Ignore
                // Non-own relays are answered lazily in every mode: many send a challenge on
                // connect but never require it, and answering would reveal a key for nothing.
                trigger == AuthTrigger.CHALLENGE -> RelayAuthDecision.Ignore
                // The rejected event is the user's own signed event, so this relay already knows
                // the real pubkey — answering with it gets replies and mentions through.
                trigger == AuthTrigger.PUBLISH_REJECTED -> RelayAuthDecision.Sign(useExternalSigner = true)
                else -> RelayAuthDecision.Sign(useExternalSigner = mode == RelayAuthMode.OWN_KEY)
            }
    }
}

/**
 * "Own relay" definition shared by the AUTH policy and its UI: the relay must be present in the
 * user's declared relay config (i.e. [augmented relays][Relay.isDiscovered] excluded) and actually
 * assigned a role. Unknown relays (not in the stored config) are never own relays.
 */
fun isOwnRelay(relay: Relay?): Boolean =
    relay != null && !relay.isDiscovered && relay.hasAnyAssignedRole()

/** Convenience overload for relays not present in the stored configuration. */
fun isOwnRelayByConfig(relays: List<Relay>, relayUrl: String): Boolean {
    val normalized = normalizeRelayUrl(relayUrl)
    return relays.any { normalizeRelayUrl(it.url) == normalized && isOwnRelay(it) }
}
