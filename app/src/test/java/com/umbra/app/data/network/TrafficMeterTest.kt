package com.umbra.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMeterTest {

    @Test
    fun `given relay frames when recording then counts are per normalized relay and sorted by total`() {
        val meter = TrafficMeter()
        meter.recordRelaySent("wss://relay.damus.io", 100)
        meter.recordRelayReceived("wss://relay.damus.io/", 900)
        meter.recordRelayReceived("wss://nos.lol", 50)

        val relays = meter.relaySnapshot()
        assertEquals(2, relays.size)
        val damus = relays.first()
        assertEquals(100L, damus.bytesSent)
        assertEquals(900L, damus.bytesReceived)
        assertEquals(1L, damus.messagesSent)
        assertEquals(1L, damus.messagesReceived)
        assertEquals(1_050L, meter.totalBytes())
    }

    @Test
    fun `given http traffic when recording then media and other are split and reset clears all`() {
        val meter = TrafficMeter()
        meter.recordHttpReceived(isMedia = true, bytes = 4_000)
        meter.recordHttpReceived(isMedia = false, bytes = 300)
        meter.recordHttpReceived(isMedia = false, bytes = -5)
        meter.recordHttpSent(20)
        assertEquals(4_000L, meter.mediaBytes())
        assertEquals(300L, meter.otherHttpBytes())
        assertEquals(20L, meter.httpSentBytes())

        meter.reset()
        assertEquals(0L, meter.totalBytes())
        assertTrue(meter.relaySnapshot().isEmpty())
    }
}
