package com.umbra.app.domain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure domain policy tests for the NIP-42 relay AUTH decision matrix — own/discovered/unknown
 * relay × each [RelayAuthMode] × each [AuthTrigger].
 */
class RelayAuthPolicyTest {

    private fun configuredRelay() = Relay(
        id = "r1",
        url = "wss://own.relay",
        isReadEnabled = true,
        isWriteEnabled = true
    )

    private fun discoveredRelay() = Relay(
        id = "r2",
        url = "wss://discovered.relay",
        isReadEnabled = true,
        isDiscovered = true
    )

    private fun configuredButNoRole() = Relay(
        id = "r3",
        url = "wss://norole.relay",
        isReadEnabled = false,
        isWriteEnabled = false,
        isDmEnabled = false,
        isSearchEnabled = false,
        isIndexEnabled = false
    )

    @Test
    fun `own relay is present in config not discovered with any role`() {
        assertTrue(isOwnRelay(configuredRelay()))
        assertFalse(isOwnRelay(discoveredRelay()))
        assertFalse(isOwnRelay(configuredButNoRole()))
        assertFalse(isOwnRelay(null))
    }

    @Test
    fun `own relay always signs with the external signer for every mode and trigger`() {
        RelayAuthMode.entries.forEach { mode ->
            AuthTrigger.entries.forEach { trigger ->
                val decision = RelayAuthDecision.decide(mode, isOwnRelay = true, trigger = trigger)
                assertEquals(
                    "mode=$mode trigger=$trigger",
                    RelayAuthDecision.Sign(useExternalSigner = true),
                    decision
                )
            }
        }
    }

    @Test
    fun `NEVER mode ignores every non-own trigger`() {
        AuthTrigger.entries.forEach { trigger ->
            assertEquals(
                RelayAuthDecision.Ignore,
                RelayAuthDecision.decide(RelayAuthMode.NEVER, isOwnRelay = false, trigger = trigger)
            )
        }
    }

    @Test
    fun `OWN_KEY mode answers non-own relays lazily with the external signer`() {
        assertEquals(
            RelayAuthDecision.Ignore,
            RelayAuthDecision.decide(RelayAuthMode.OWN_KEY, isOwnRelay = false, trigger = AuthTrigger.CHALLENGE)
        )
        listOf(AuthTrigger.REQ_REJECTED, AuthTrigger.PUBLISH_REJECTED).forEach { trigger ->
            assertEquals(
                RelayAuthDecision.Sign(useExternalSigner = true),
                RelayAuthDecision.decide(RelayAuthMode.OWN_KEY, isOwnRelay = false, trigger = trigger)
            )
        }
    }

    @Test
    fun `THROWAWAY_KEY mode ignores Challenges and signs rejections with the in-app throwaway key`() {
        assertEquals(
            RelayAuthDecision.Ignore,
            RelayAuthDecision.decide(RelayAuthMode.THROWAWAY_KEY, isOwnRelay = false, AuthTrigger.CHALLENGE)
        )
        assertEquals(
            RelayAuthDecision.Sign(useExternalSigner = false),
            RelayAuthDecision.decide(RelayAuthMode.THROWAWAY_KEY, isOwnRelay = false, AuthTrigger.REQ_REJECTED)
        )
        // A rejected publish already exposed the real pubkey, so escalation uses the real key.
        assertEquals(
            RelayAuthDecision.Sign(useExternalSigner = true),
            RelayAuthDecision.decide(RelayAuthMode.THROWAWAY_KEY, isOwnRelay = false, AuthTrigger.PUBLISH_REJECTED)
        )
    }

    @Test
    fun `fromStored maps unknown values to the default THROWAWAY_KEY`() {
        assertEquals(RelayAuthMode.THROWAWAY_KEY, RelayAuthMode.fromStored(null))
        assertEquals(RelayAuthMode.THROWAWAY_KEY, RelayAuthMode.fromStored("garbage"))
        assertEquals(RelayAuthMode.OWN_KEY, RelayAuthMode.fromStored("OWN_KEY"))
        assertEquals(RelayAuthMode.NEVER, RelayAuthMode.fromStored("NEVER"))
    }
}
