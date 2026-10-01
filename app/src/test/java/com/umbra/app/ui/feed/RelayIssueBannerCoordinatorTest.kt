package com.umbra.app.ui.feed

import android.content.Intent
import com.umbra.app.R
import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip42.ThrowawayAuthSigner
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.relay.AuthTrigger
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.usecase.PublishAuthEventUseCase
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.domain.util.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [RelayIssueBannerCoordinator]'s NIP-42 AUTH handling — including the
 * relay-auth policy split (own relays answer every challenge with the real key; non-own relays
 * answer only actual rejections, with the throwaway key by default) and the updated dedup rule
 * (record only AFTER responding, so an ignored challenge never swallows a later REQ_REJECTED
 * carrying the same challenge). Structure follows
 * [com.umbra.app.data.repository.EventIngestCacheTest]: a `subject()` factory, nested private
 * Fake test doubles implementing only the members this coordinator calls (`NotImplementedError`
 * for the rest), plain JUnit assertions, no Mockito.
 *
 * [PublishAuthEventUseCase] is a concrete class (not an interface), so it is exercised for real
 * here, wired to the already-centralized [FakeEventRepository] test double (`testutil.fakes`).
 */
class RelayIssueBannerCoordinatorTest {

    private val fakeSignedAuthEventJson =
        """{"id":"evt1","pubkey":"pub1","created_at":1,"kind":22242,"tags":[],"content":"","sig":"sig1"}"""

    private class FakeThrowawayAuthSigner : ThrowawayAuthSigner {
        val signedRelayUrls = mutableListOf<String>()
        private val realSigner = com.umbra.app.data.crypto.ThrowawayAuthSignerImpl()

        override fun signAuthEvent(relayUrl: String, unsignedEventJson: String): String {
            signedRelayUrls += relayUrl
            // Delegate the actual signature to the production signer so the output is a genuine,
            // BIP-340-verifiable kind-22242 event; this fake only records the call.
            if (!realSigner.hasKeyForRelay(relayUrl)) realSigner.ensureKeyForRelay(relayUrl)
            return realSigner.signAuthEvent(relayUrl, unsignedEventJson)
        }
    }

    private fun subject(
        scope: CoroutineScope,
        eventRepository: FakeEventRepository = FakeEventRepository(),
        userPreferences: FakeUserPreferences = FakeUserPreferences(initialPubkey = "a".repeat(64)),
        amberSignerGateway: FakeAmberSignerGateway = FakeAmberSignerGateway(fakeSignedAuthEventJson),
        throwawayAuthSigner: ThrowawayAuthSigner = FakeThrowawayAuthSigner(),
        uiState: MutableStateFlow<FeedState> = MutableStateFlow(FeedState()),
        latestRelays: () -> List<Relay> = { emptyList() }
    ): RelayIssueBannerCoordinator = RelayIssueBannerCoordinator(
        eventRepository = eventRepository,
        userPreferences = userPreferences,
        amberSignerGateway = amberSignerGateway,
        publishAuthEventUseCase = PublishAuthEventUseCase(eventRepository, NoOpUmbraLogger),
        throwawayAuthSigner = throwawayAuthSigner,
        uiState = uiState,
        scope = scope,
        latestRelays = latestRelays
    )

    private fun authIssue(
        relayUrl: String = "wss://relay.example",
        challenge: String = "challenge-1",
        trigger: AuthTrigger = AuthTrigger.CHALLENGE
    ): RelayIssue = RelayIssue(
        relayUrl = relayUrl,
        kind = RelayIssueKind.AUTH,
        rawMessage = challenge,
        isAuthChallenge = true,
        authTrigger = trigger
    )

    /** Nested Fake — only [signEvent] does real work; every other [AmberSignerGateway] member is
     * unreachable from [RelayIssueBannerCoordinator] and throws if ever called. */
    private class FakeAmberSignerGateway(
        private val signedEventJson: String?
    ) : AmberSignerGateway {
        val signEventCalls = mutableListOf<Pair<String, String?>>()

        override fun isAmberInstalled(): Boolean = throw NotImplementedError()
        override fun createLoginIntent(): Intent = throw NotImplementedError()
        override fun createSignEventIntent(eventJson: String, currentUserHex: String?): Intent = throw NotImplementedError()
        override fun createStoreIntent(): Intent = throw NotImplementedError()
        override fun extractPublicKeyFromResult(data: Intent?): String? = throw NotImplementedError()
        override fun extractSignedEventFromResult(data: Intent?): String? = throw NotImplementedError()
        override suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String? = throw NotImplementedError()

        override suspend fun signEvent(eventJson: String, currentUserHex: String?): String? {
            signEventCalls += eventJson to currentUserHex
            return signedEventJson
        }

        override suspend fun requestPublicKey(): String? = throw NotImplementedError()
        override fun openStore(): Boolean = throw NotImplementedError()
    }

    private fun ownRelay(): Relay = Relay(
        id = "own1",
        url = "wss://own.relay.example",
        isReadEnabled = true,
        isWriteEnabled = true,
        isDiscovered = false
    )

    private fun discoveredRelay(): Relay = Relay(
        id = "disc1",
        url = "wss://discovered.relay.example",
        isReadEnabled = true,
        isDiscovered = true
    )

    @Test
    fun `given a challenge on an own relay when Amber can sign then the real key signs it`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            latestRelays = { listOf(ownRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(relayUrl = "wss://own.relay.example", challenge = "challenge-1")
        )
        advanceUntilIdle()

        assertEquals(1, gateway.signEventCalls.size)
    }

    @Test
    fun `given a bare challenge relay when the relay is not in config and mode is default THROWAWAY then no sign round trip occurs`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val throwaway = FakeThrowawayAuthSigner()
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            throwawayAuthSigner = throwaway,
            latestRelays = { emptyList() }
        )

        coordinator.maybeHandleRelayAuthChallenge(authIssue(relayUrl = "wss://relay.example"))
        advanceUntilIdle()

        assertEquals(0, gateway.signEventCalls.size)
        assertEquals(0, throwaway.signedRelayUrls.size)
    }

    @Test
    fun `given a REQ rejection on a non-own relay with the default THROWAWAY mode when handled then the throwaway key signs it and Amber is untouched`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val throwaway = FakeThrowawayAuthSigner()
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            throwawayAuthSigner = throwaway,
            latestRelays = { listOf(discoveredRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(
                relayUrl = "wss://discovered.relay.example",
                challenge = "challenge-1",
                trigger = AuthTrigger.REQ_REJECTED
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("wss://discovered.relay.example"), throwaway.signedRelayUrls)
        assertEquals(0, gateway.signEventCalls.size)
    }

    @Test
    fun `given OWN_KEY mode when a non-own relay rejects a REQ then the real key signs it`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val throwaway = FakeThrowawayAuthSigner()
        val userPreferences = FakeUserPreferences(
            initialPubkey = "a".repeat(64),
            initialRelayAuthMode = com.umbra.app.domain.relay.RelayAuthMode.OWN_KEY
        )
        val coordinator = subject(
            scope = this,
            userPreferences = userPreferences,
            amberSignerGateway = gateway,
            throwawayAuthSigner = throwaway,
            latestRelays = { listOf(discoveredRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(
                relayUrl = "wss://discovered.relay.example",
                trigger = AuthTrigger.REQ_REJECTED
            )
        )
        advanceUntilIdle()

        assertEquals(1, gateway.signEventCalls.size)
        assertEquals(0, throwaway.signedRelayUrls.size)
    }

    @Test
    fun `given NEVER mode when a non-own relay rejects a REQ then nothing signs`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val throwaway = FakeThrowawayAuthSigner()
        val userPreferences = FakeUserPreferences(
            initialPubkey = "a".repeat(64),
            initialRelayAuthMode = com.umbra.app.domain.relay.RelayAuthMode.NEVER
        )
        val coordinator = subject(
            scope = this,
            userPreferences = userPreferences,
            amberSignerGateway = gateway,
            throwawayAuthSigner = throwaway,
            latestRelays = { listOf(discoveredRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(relayUrl = "wss://discovered.relay.example", trigger = AuthTrigger.REQ_REJECTED)
        )
        advanceUntilIdle()

        assertEquals(0, gateway.signEventCalls.size)
        assertEquals(0, throwaway.signedRelayUrls.size)
    }

    @Test
    fun `given a PUBLISH rejection on a non-own relay with THROWAWAY mode when handled then the real key escalates (the relay already saw that pubkey)`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val throwaway = FakeThrowawayAuthSigner()
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            throwawayAuthSigner = throwaway,
            latestRelays = { listOf(discoveredRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(
                relayUrl = "wss://discovered.relay.example",
                trigger = AuthTrigger.PUBLISH_REJECTED
            )
        )
        advanceUntilIdle()

        assertEquals(1, gateway.signEventCalls.size)
        assertEquals(0, throwaway.signedRelayUrls.size)
    }

    @Test
    fun `given canSignWithAmber is false when an own relay challenge arrives then the sign gateway is never called`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val userPreferences = FakeUserPreferences(initialPubkey = null)
        val coordinator = subject(
            scope = this,
            userPreferences = userPreferences,
            amberSignerGateway = gateway,
            latestRelays = { listOf(ownRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(relayUrl = "wss://own.relay.example")
        )
        advanceUntilIdle()

        assertEquals(0, gateway.signEventCalls.size)
    }

    @Test
    fun `given the same relay and identical challenge delivered twice when both respond then only one round trip occurs`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            latestRelays = { listOf(ownRelay()) }
        )
        val issue = authIssue(relayUrl = "wss://own.relay.example", challenge = "same-challenge")

        coordinator.maybeHandleRelayAuthChallenge(issue)
        coordinator.maybeHandleRelayAuthChallenge(issue)
        advanceUntilIdle()

        assertEquals(1, gateway.signEventCalls.size)
    }

    @Test
    fun `given the same relay with a rotated challenge when handled twice then two separate round trips occur`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            latestRelays = { listOf(ownRelay()) }
        )
        val relayUrl = "wss://own.relay.example"

        coordinator.maybeHandleRelayAuthChallenge(authIssue(relayUrl = relayUrl, challenge = "challenge-1"))
        coordinator.maybeHandleRelayAuthChallenge(authIssue(relayUrl = relayUrl, challenge = "challenge-2"))
        advanceUntilIdle()

        assertEquals(2, gateway.signEventCalls.size)
    }

    @Test
    fun `given an ignored challenge when a later REQ rejection arrives with the SAME challenge then it still responds (dedup did not swallow it)`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val throwaway = FakeThrowawayAuthSigner()
        val coordinator = subject(
            scope = this,
            amberSignerGateway = gateway,
            throwawayAuthSigner = throwaway,
            latestRelays = { listOf(discoveredRelay()) }
        )
        val relayUrl = "wss://discovered.relay.example"
        val challenge = "same-challenge"

        // The unrequested CHALLENGE alone must not record the dedup entry.
        coordinator.maybeHandleRelayAuthChallenge(authIssue(relayUrl = relayUrl, challenge = challenge))
        advanceUntilIdle()
        assertEquals(0, throwaway.signedRelayUrls.size)

        // ... so the later actual rejection with the same challenge still gets a response.
        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(relayUrl = relayUrl, challenge = challenge, trigger = AuthTrigger.REQ_REJECTED)
        )
        advanceUntilIdle()
        assertEquals(listOf(relayUrl), throwaway.signedRelayUrls)
        assertEquals(0, gateway.signEventCalls.size)
    }

    @Test
    fun `given a throwaway-signed AUTH when it round-trips through PublishAuthEventUseCase then the event is cryptographically valid`() = runTest {
        val relayUrl = "wss://discovered.relay.example"
        val eventRepository = FakeEventRepository()
        val coordinator = subject(
            scope = this,
            eventRepository = eventRepository,
            throwawayAuthSigner = FakeThrowawayAuthSigner(),
            latestRelays = { listOf(discoveredRelay()) }
        )

        coordinator.maybeHandleRelayAuthChallenge(
            authIssue(relayUrl = relayUrl, trigger = AuthTrigger.REQ_REJECTED)
        )
        advanceUntilIdle()

        // PublishAuthEventUseCase runs its parse/publish inside withContext(Dispatchers.Default),
        // a real dispatcher whose work doesn't advance under the test scheduler — poll briefly
        // for the (fast, purely local) recording instead of assuming advanceUntilIdle covers it.
        var published = eventRepository.publishedAuthEvents[relayUrl]
        var waitedMs = 0L
        while (published == null && waitedMs < 5_000) {
            kotlinx.coroutines.delay(50)
            waitedMs += 50
            published = eventRepository.publishedAuthEvents[relayUrl]
        }
        assertTrue("expected a published AUTH event", published != null)
        assertTrue(EventCrypto.verifyEvent(published!!))
        assertEquals(22242, published.kind)
        val tags = published.tags
        assertTrue(tags.any { it.firstOrNull() == "challenge" })
        assertTrue(tags.any { it.firstOrNull() == "relay" })
    }

    @Test
    fun `given various FeedState errorMessage shapes when shouldClearNetworkBanner is evaluated then only the network error variants return true`() = runTest {
        val coordinator = subject(scope = this)

        assertTrue(
            coordinator.shouldClearNetworkBanner(
                FeedState(errorMessage = UiMessage.ResWithArgs(R.string.error_relay_network, "relay.example"))
            )
        )
        assertTrue(
            coordinator.shouldClearNetworkBanner(
                FeedState(errorMessage = UiMessage.ResWithArgs(R.string.error_relay_network_cooldown, "relay.example", 30))
            )
        )
        assertFalse(
            coordinator.shouldClearNetworkBanner(
                FeedState(errorMessage = UiMessage.ResWithArgs(R.string.error_relay_auth, "relay.example"))
            )
        )
        assertFalse(
            coordinator.shouldClearNetworkBanner(
                FeedState(errorMessage = UiMessage.Literal("something else"))
            )
        )
        assertFalse(coordinator.shouldClearNetworkBanner(FeedState(errorMessage = null)))
    }
}
