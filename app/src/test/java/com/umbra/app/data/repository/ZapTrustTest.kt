package com.umbra.app.data.repository

import com.umbra.app.domain.model.EngagementLink
import com.umbra.app.domain.model.EngagementType
import com.umbra.app.domain.nip57.LnurlPayInfo
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.LightningRepository
import com.umbra.app.testutil.fakes.FakeUserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZapTrustTest {

    private val recipient = "a".repeat(64)
    private val wallet = "b".repeat(64)

    private fun zap(target: String, sats: Long) =
        EngagementLink(target, EngagementType.ZAP, sats = sats, zapRecipient = recipient, zapSigner = wallet)

    @Test
    fun `given zaps and a reaction when reading without trust then only the reaction counts`() {
        val index = EventEngagementIndex()
        index.add("z1", listOf(zap("note", 21)))
        index.add("r1", listOf(EngagementLink("note", EngagementType.REACTION)))

        val counts = index.countsFor(listOf("note"))

        assertEquals(1, counts["note"]?.reactions)
        assertEquals(0L, counts["note"]?.zapSats)
    }

    @Test
    fun `given zaps when reading with a trust check then only accepted zaps add sats`() {
        val index = EventEngagementIndex()
        index.add("z1", listOf(zap("note", 21)))
        index.add("z2", listOf(zap("note", 100).copy(zapSigner = "f".repeat(64))))

        val counts = index.countsFor(listOf("note")) { _, link -> link.zapSigner == wallet }

        assertEquals(21L, counts["note"]?.zapSats)
    }

    @Test
    fun `given a zap is removed when reading then its sats are gone`() {
        val index = EventEngagementIndex()
        index.add("z1", listOf(zap("note", 21)))
        index.remove("z1")

        assertNull(index.countsFor(listOf("note")) { _, _ -> true }["note"])
    }

    private class FakeLightning(private val result: Result<LnurlPayInfo>) : LightningRepository {
        var calls = 0
        override suspend fun resolvePayInfo(addressOrLnurl: String): Result<LnurlPayInfo> {
            calls++
            return result
        }
        override suspend fun requestInvoice(
            payInfo: LnurlPayInfo,
            amountMsat: Long,
            signedZapRequestJson: String?,
            comment: String?
        ): Result<String> = Result.failure(UnsupportedOperationException())
    }

    private fun payInfo(nostrPubkey: String?) = LnurlPayInfo(
        callback = "https://wallet.example/cb",
        minSendableMsat = 1_000,
        maxSendableMsat = 1_000_000,
        allowsNostr = true,
        nostrPubkey = nostrPubkey,
        commentAllowed = 0,
        lnurl = "lnurl1"
    )

    @Test
    fun `given a recipient with a lightning address when requested then their wallet key becomes known`() = runTest {
        val lightning = FakeLightning(Result.success(payInfo(wallet)))
        var resolved = 0
        val directory = ZapReceiptSignerDirectory(
            scope = this,
            userRepository = FakeUserRepository(cachedProfile = UserProfile(pubkey = recipient, lud16 = "a@wallet.example")),
            lightningRepository = lightning,
            onResolved = { resolved++ }
        )

        assertEquals(ZapReceiptSignerDirectory.Lookup.Unknown, directory.signerFor(recipient))
        directory.request(setOf(recipient))
        directory.request(setOf(recipient))
        advanceUntilIdle()

        assertEquals(ZapReceiptSignerDirectory.Lookup.Known(wallet), directory.signerFor(recipient))
        assertEquals(1, lightning.calls)
        assertEquals(1, resolved)
    }

    @Test
    fun `given the lookup fails when requested then the answer expires soon and is retried`() = runTest {
        var now = 0L
        val lightning = FakeLightning(Result.failure(IllegalStateException("tor down")))
        val directory = ZapReceiptSignerDirectory(
            scope = this,
            userRepository = FakeUserRepository(cachedProfile = UserProfile(pubkey = recipient, lud16 = "a@wallet.example")),
            lightningRepository = lightning,
            onResolved = {},
            clock = { now }
        )

        directory.request(setOf(recipient))
        advanceUntilIdle()
        assertEquals(ZapReceiptSignerDirectory.Lookup.Known(null), directory.signerFor(recipient))

        now += 3 * 60 * 1_000L
        assertEquals(ZapReceiptSignerDirectory.Lookup.Unknown, directory.signerFor(recipient))
        directory.request(setOf(recipient))
        advanceUntilIdle()
        assertEquals(2, lightning.calls)
    }
}
