package com.umbra.app.domain.nip51

/**
 * Which NIP-51 "private" relay-list kind a NIP-44 encrypt/decrypt round trip is for — kind:10007
 * (search relays, see [SearchRelaysList]) or kind:10086 (index relays, see [IndexRelaysList]).
 * Shared between [com.umbra.app.data.repository.RelayListDecryptionCoordinator] (decrypt-on-
 * arrival, session-lifetime) and RelayConfigViewModel (the publish/Save flow's encrypt step).
 */
enum class RelayListKind { SEARCH, INDEX }
