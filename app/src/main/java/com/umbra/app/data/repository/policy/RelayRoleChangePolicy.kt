package com.umbra.app.data.repository.policy

import com.umbra.app.domain.relay.Relay

/**
 * Detects when a relay's [canApplyChannelToRelay]-affecting role fields changed since the last
 * reconcile pass while the relay stayed connected the whole time — e.g. a bootstrap/discovered
 * default that gets named a real outbox relay once the user's actual kind:10002 arrives. That
 * relay never gets a fresh socket-open event (see [EventRepositoryImpl.connectToEnabledRelays]'s
 * `relayOpenedFlow` re-apply), so without this check the session-gated channels
 * (OUTBOX_PROFILE/OUTBOX_NOTES/FEED_NOTES/INBOX_NOTES) that only reapply on a fresh connect or a
 * role change would never actually be (re-)sent to it — its live subscription for the user's own
 * data is never armed, even though its role now allows it.
 */
internal object RelayRoleChangePolicy {
    fun roleAffectingFieldsChanged(previous: Relay?, current: Relay): Boolean {
        if (previous == null) return false
        return previous.isReadActive != current.isReadActive ||
            previous.isWriteActive != current.isWriteActive ||
            previous.isDiscovered != current.isDiscovered ||
            previous.isSearchActive != current.isSearchActive ||
            previous.isIndexActive != current.isIndexActive
    }
}
