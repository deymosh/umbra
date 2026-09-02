package com.umbra.app.data.repository.policy

import com.umbra.app.domain.relay.Relay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRoleChangePolicyTest {

    private fun relay(
        isReadActive: Boolean = true,
        isWriteActive: Boolean = true,
        isDiscovered: Boolean = false,
        isSearchActive: Boolean = false,
        isIndexActive: Boolean = false
    ) = Relay(
        id = "wss://relay.example.com",
        url = "wss://relay.example.com",
        isReadEnabled = isReadActive,
        isWriteEnabled = isWriteActive,
        isReadActive = isReadActive,
        isWriteActive = isWriteActive,
        isDiscovered = isDiscovered,
        isSearchActive = isSearchActive,
        isIndexActive = isIndexActive
    )

    @Test
    fun `given no previous relay when checking role change then returns false`() {
        assertFalse(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous = null, current = relay()))
    }

    @Test
    fun `given previous relay with same roles when checking role change then returns false`() {
        val previous = relay()
        val current = relay()

        assertFalse(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous, current))
    }

    @Test
    fun `given previous relay with different isWriteActive when checking role change then returns true`() {
        val previous = relay(isWriteActive = false)
        val current = relay(isWriteActive = true)

        assertTrue(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous, current))
    }

    @Test
    fun `given previous relay with different isReadActive when checking role change then returns true`() {
        val previous = relay(isReadActive = false)
        val current = relay(isReadActive = true)

        assertTrue(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous, current))
    }

    @Test
    fun `given previous relay with different isDiscovered when checking role change then returns true`() {
        val previous = relay(isDiscovered = true)
        val current = relay(isDiscovered = false)

        assertTrue(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous, current))
    }

    @Test
    fun `given previous relay with different isSearchActive when checking role change then returns true`() {
        val previous = relay(isSearchActive = false)
        val current = relay(isSearchActive = true)

        assertTrue(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous, current))
    }

    @Test
    fun `given previous relay with different isIndexActive when checking role change then returns true`() {
        val previous = relay(isIndexActive = false)
        val current = relay(isIndexActive = true)

        assertTrue(RelayRoleChangePolicy.roleAffectingFieldsChanged(previous, current))
    }
}
