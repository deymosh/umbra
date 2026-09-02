package com.umbra.app.domain.nip77

/**
 * Which direction(s) a NIP-77 negentropy sync should act on once reconciliation has determined
 * what differs between the client and a relay. The handshake itself always yields both sides
 * (there's no way to ask a relay to reconcile only one direction) — this only gates what the
 * orchestrator *does* with each side afterward. Named by data-flow direction (not e.g.
 * "client-only"/"relay-only") since those names are ambiguous about which way data actually
 * moves.
 */
enum class SyncDirection {
    DOWNLOAD_ONLY,
    UPLOAD_ONLY,
    BOTH;

    /** True when events the relay has but the client doesn't should be fetched. */
    val pullsFromRelay: Boolean get() = this != UPLOAD_ONLY

    /** True when events the client has but the relay doesn't should be published. */
    val pushesToRelay: Boolean get() = this != DOWNLOAD_ONLY
}
