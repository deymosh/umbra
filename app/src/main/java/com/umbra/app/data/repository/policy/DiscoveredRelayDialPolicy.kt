package com.umbra.app.data.repository.policy

import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.normalizeRelayUrl

/**
 * Dial ordering and pacing for `EventRepositoryImpl.connectToEnabledRelays()`'s discovered-relay
 * subset. Own (non-discovered) relays always dial first, unaffected by anything here. Within the
 * discovered group, relays already known to cover a followed author sort ahead of coverage-unknown
 * ones, so [MAX_DISCOVERED_RELAY_DIALS_PER_PASS] — a belt-and-suspenders bound on worst-case
 * simultaneous socket/thread count during a fast-growing session, independent of
 * `UserRepositoryImpl.MAX_TOTAL_DISCOVERED_RELAYS`'s pool-size ceiling on how many can ever be
 * *known* — defers the least valuable connections first rather than an arbitrary subset.
 * Deferred relays aren't dropped: they're still eligible and untracked, so the next connect pass
 * (relay-list changes are frequent during active use) retries them, same as any relay that failed
 * to connect this pass — no reconnect churn.
 */
internal object DiscoveredRelayDialPolicy {
    const val MAX_DISCOVERED_RELAY_DIALS_PER_PASS = 150

    fun sortForDialing(relays: List<Relay>, authorCoveredRelayUrls: Set<String>): List<Relay> =
        relays.sortedWith(
            compareBy<Relay> { it.isDiscovered }
                .thenBy { relay -> if (normalizeRelayUrl(relay.url) in authorCoveredRelayUrls) 0 else 1 }
        )

    fun shouldDeferDial(
        isDiscovered: Boolean,
        discoveredDialsThisPass: Int,
        maxDialsPerPass: Int = MAX_DISCOVERED_RELAY_DIALS_PER_PASS
    ): Boolean = isDiscovered && discoveredDialsThisPass >= maxDialsPerPass
}
