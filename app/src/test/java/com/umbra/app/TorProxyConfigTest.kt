package com.umbra.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorProxyConfigTest {

    @After
    fun tearDown() {
        TorProxyConfig.reset()
    }

    @Test
    fun `given_validLoopbackEndpoints_when_validating_then_accepted`() {
        assertTrue(TorProxyConfig.isValidSocksEndpoint("127.0.0.1", 9050))
        assertTrue(TorProxyConfig.isValidSocksEndpoint("localhost", 9150))
        assertTrue(TorProxyConfig.isValidSocksEndpoint("::1", 9050))
        assertTrue(TorProxyConfig.isValidSocksEndpoint("[::1]", 9150))
    }

    @Test
    fun `given_invalidEndpoints_when_validating_then_rejected`() {
        assertFalse(TorProxyConfig.isValidSocksEndpoint("8.8.8.8", 9050))
        assertFalse(TorProxyConfig.isValidSocksEndpoint("example.com", 9150))
        assertFalse(TorProxyConfig.isValidSocksEndpoint("127.0.0.1", 1080))
    }

    @Test
    fun `given_invalidEndpoint_when_updating_then_failsClosed`() {
        val updated = TorProxyConfig.update("evil.proxy.local", 9050)

        assertFalse(updated)
        assertFalse(TorProxyConfig.isReady)
        assertEquals(TorProxyConfig.DEFAULT_HOST, TorProxyConfig.host)
        assertEquals(TorProxyConfig.DEFAULT_PORT, TorProxyConfig.port)
    }

    @Test
    fun `given_validEndpoint_when_updating_then_normalizesAndMarksReady`() {
        val updated = TorProxyConfig.update(" LOCALHOST ", 9150)

        assertTrue(updated)
        assertTrue(TorProxyConfig.isReady)
        assertEquals("localhost", TorProxyConfig.host)
        assertEquals(9150, TorProxyConfig.port)
    }
}
