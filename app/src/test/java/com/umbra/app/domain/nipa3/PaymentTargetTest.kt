package com.umbra.app.domain.nipa3

import com.umbra.app.domain.nip01.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentTargetTest {

    private fun event(kind: Int, vararg tags: List<String>) =
        Event(id = "i".repeat(64), pubkey = "p".repeat(64), createdAt = 1, kind = kind, tags = tags.toList(), content = "")

    @Test
    fun `given payto tags when parsing then valid targets are kept, lowercased and deduplicated`() {
        val targets = parsePaymentTargets(
            event(
                KIND_PAYMENT_TARGETS,
                listOf("payto", "Bitcoin", "bc1qxyz"),
                listOf("payto", "bitcoin", "bc1qxyz"),
                listOf("payto", "nano", "nano_1abc"),
                listOf("payto", "", "missing-type"),
                listOf("payto", "short"),
                listOf("p", "someone")
            )
        )
        assertEquals(listOf(PaymentTarget("bitcoin", "bc1qxyz"), PaymentTarget("nano", "nano_1abc")), targets)
    }

    @Test
    fun `given other kind when parsing then empty`() {
        assertTrue(parsePaymentTargets(event(1, listOf("payto", "bitcoin", "bc1q"))).isEmpty())
    }

    @Test
    fun `given target when building uri then dedicated scheme or rfc 8905 payto is used`() {
        assertEquals("bitcoin:bc1qxyz", PaymentTarget("bitcoin", "bc1qxyz").toUri())
        assertEquals("payto://paypal/alice", PaymentTarget("paypal", "alice").toUri())
        assertEquals("payto://unknowntype/abc", PaymentTarget("unknowntype", "abc").toUri())
        assertEquals("Cash App", PaymentTarget("cashme", "\$alice").label)
    }
}
