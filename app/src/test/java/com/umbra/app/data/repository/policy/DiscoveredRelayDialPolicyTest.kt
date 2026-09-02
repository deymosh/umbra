package com.umbra.app.data.repository.policy

import com.umbra.app.domain.relay.Relay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredRelayDialPolicyTest {

    private fun relay(url: String, isDiscovered: Boolean) = Relay(
        id = url,
        url = url,
        isDiscovered = isDiscovered
    )

    @Test
    fun `given a mix of own and discovered relays when sorting for dialing then own relays sort first`() {
        val own = relay("wss://own.example.com", isDiscovered = false)
        val discoveredCovered = relay("wss://covered.example.com", isDiscovered = true)
        val discoveredUnknown = relay("wss://unknown.example.com", isDiscovered = true)

        val sorted = DiscoveredRelayDialPolicy.sortForDialing(
            relays = listOf(discoveredUnknown, discoveredCovered, own),
            authorCoveredRelayUrls = setOf("wss://covered.example.com")
        )

        assertEquals(own, sorted.first())
    }

    @Test
    fun `given discovered relays when sorting for dialing then author-covered relays sort before coverage-unknown ones`() {
        val discoveredCovered = relay("wss://covered.example.com", isDiscovered = true)
        val discoveredUnknown = relay("wss://unknown.example.com", isDiscovered = true)

        val sorted = DiscoveredRelayDialPolicy.sortForDialing(
            relays = listOf(discoveredUnknown, discoveredCovered),
            authorCoveredRelayUrls = setOf("wss://covered.example.com")
        )

        assertEquals(listOf(discoveredCovered, discoveredUnknown), sorted)
    }

    @Test
    fun `given a non-discovered relay when deciding whether to defer dial then never deferred regardless of pass count`() {
        assertFalse(
            DiscoveredRelayDialPolicy.shouldDeferDial(
                isDiscovered = false,
                discoveredDialsThisPass = Int.MAX_VALUE,
                maxDialsPerPass = 1
            )
        )
    }

    @Test
    fun `given a discovered relay under the per-pass cap when deciding whether to defer dial then not deferred`() {
        assertFalse(
            DiscoveredRelayDialPolicy.shouldDeferDial(
                isDiscovered = true,
                discoveredDialsThisPass = 0,
                maxDialsPerPass = 1
            )
        )
    }

    @Test
    fun `given a discovered relay at the per-pass cap when deciding whether to defer dial then deferred`() {
        assertTrue(
            DiscoveredRelayDialPolicy.shouldDeferDial(
                isDiscovered = true,
                discoveredDialsThisPass = 1,
                maxDialsPerPass = 1
            )
        )
    }
}
