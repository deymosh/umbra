package com.umbra.app.domain.relay

/**
 * Bootstrap relays used when the user has no saved relay configuration yet. Seeded as pure
 * DISCOVER relays (see RelayRepositoryImpl.buildFirstLoginRelaySet) — not the user's own
 * outbox/inbox until their real kind:10002 declaration arrives or they configure one themselves.
 */
object DefaultRelays {
    val DEFAULT_RELAYS = listOf(
        Relay(
            id = "damus.io",
            url = "wss://relay.damus.io",
            isOnion = false
        ),
        Relay(
            id = "primal.net",
            url = "wss://relay.primal.net",
            isOnion = false
        ),
        Relay(
            id = "nos.lol",
            url = "wss://nos.lol",
            isOnion = false
        ),
        Relay(
            id = "nostr.mom",
            url = "wss://nostr.mom",
            isOnion = false
        ),
        Relay(
            id = "profiles.nostr1.com",
            url = "wss://profiles.nostr1.com",
            isOnion = false
        ),
        Relay(
            id = "nostr.oxtr.dev",
            url = "wss://nostr.oxtr.dev",
            isOnion = false
        ),
        Relay(
            id = "relay.ditto.pub",
            url = "wss://relay.ditto.pub",
            isOnion = false
        )
    )
}


