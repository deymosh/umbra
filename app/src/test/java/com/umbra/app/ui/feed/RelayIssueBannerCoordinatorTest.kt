package com.umbra.app.ui.feed

import android.content.Intent
import com.umbra.app.R
import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.usecase.PublishAuthEventUseCase
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [RelayIssueBannerCoordinator]'s extraction from [FeedViewModel] — the
 * two contracts most at risk of breaking silently during that extraction: NIP-42 AUTH-challenge
 * dedup (`recentAuthChallengeByRelay`, keyed on relay+challenge) and the
 * `userPreferences.canSignWithAmber()` guard preserved unreordered. Structure follows
 * [com.umbra.app.data.repository.EventIngestCacheTest]: a `subject()` factory, nested private
 * Fake test doubles implementing only the methods this coordinator calls (`NotImplementedError`
 * for the rest), plain JUnit assertions, no Mockito.
 *
 * [PublishAuthEventUseCase] is a concrete class (not an interface), so rather than a literal
 * "FakePublishAuthEventUseCase" it is exercised for real here, wired to the already-centralized
 * [FakeEventRepository] test double (`testutil.fakes` — reused rather than hand-rolling a fresh
 * ~40-method override in this file, the exact duplication that shared fake was created to avoid).
 * Its own `publishAuthEvent` default (`Result.success(Unit)`) is enough for every test below,
 * since what's being asserted is the sign-gateway invocation count, not the publish outcome.
 */
class RelayIssueBannerCoordinatorTest {

    /** A structurally valid (if minimal) signed-event JSON — just enough for
     * [PublishAuthEventUseCase]'s own `parseSignedEvent`/`require(...isNotBlank())` checks to
     * pass, so the round trip completes as a genuine success rather than being swallowed by an
     * unrelated parse failure. */
    private val fakeSignedAuthEventJson =
        """{"id":"evt1","pubkey":"pub1","created_at":1,"kind":22242,"tags":[],"content":"","sig":"sig1"}"""

    private fun subject(
        scope: CoroutineScope,
        eventRepository: FakeEventRepository = FakeEventRepository(),
        userPreferences: FakeUserPreferences = FakeUserPreferences(initialPubkey = "a".repeat(64)),
        amberSignerGateway: FakeAmberSignerGateway = FakeAmberSignerGateway(fakeSignedAuthEventJson),
        uiState: MutableStateFlow<FeedState> = MutableStateFlow(FeedState()),
        latestRelays: () -> List<Relay> = { emptyList() }
    ): RelayIssueBannerCoordinator = RelayIssueBannerCoordinator(
        eventRepository = eventRepository,
        userPreferences = userPreferences,
        amberSignerGateway = amberSignerGateway,
        publishAuthEventUseCase = PublishAuthEventUseCase(eventRepository, NoOpUmbraLogger),
        uiState = uiState,
        scope = scope,
        latestRelays = latestRelays
    )

    private fun authIssue(
        relayUrl: String = "wss://relay.example",
        challenge: String = "challenge-1"
    ): RelayIssue = RelayIssue(
        relayUrl = relayUrl,
        kind = RelayIssueKind.AUTH,
        rawMessage = challenge,
        isAuthChallenge = true
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

    @Test
    fun `given a NIP-42 AUTH challenge when canSignWithAmber is true then exactly one sign-and-publish-AUTH round trip occurs`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val coordinator = subject(scope = this, amberSignerGateway = gateway)

        coordinator.maybeHandleRelayAuthChallenge(authIssue())
        advanceUntilIdle()

        assertEquals(1, gateway.signEventCalls.size)
    }

    @Test
    fun `given the same relay and identical challenge delivered twice when maybeHandleRelayAuthChallenge runs then only one sign round trip occurs`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val coordinator = subject(scope = this, amberSignerGateway = gateway)
        val issue = authIssue(relayUrl = "wss://relay.example", challenge = "same-challenge")

        coordinator.maybeHandleRelayAuthChallenge(issue)
        coordinator.maybeHandleRelayAuthChallenge(issue)
        advanceUntilIdle()

        assertEquals(1, gateway.signEventCalls.size)
    }

    @Test
    fun `given the same relay with a rotated challenge when maybeHandleRelayAuthChallenge runs twice then two separate sign round trips occur`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val coordinator = subject(scope = this, amberSignerGateway = gateway)
        val relayUrl = "wss://relay.example"

        coordinator.maybeHandleRelayAuthChallenge(authIssue(relayUrl = relayUrl, challenge = "challenge-1"))
        coordinator.maybeHandleRelayAuthChallenge(authIssue(relayUrl = relayUrl, challenge = "challenge-2"))
        advanceUntilIdle()

        assertEquals(2, gateway.signEventCalls.size)
    }

    @Test
    fun `given canSignWithAmber is false when maybeHandleRelayAuthChallenge runs then the sign gateway is never called`() = runTest {
        val gateway = FakeAmberSignerGateway(fakeSignedAuthEventJson)
        val userPreferences = FakeUserPreferences(initialPubkey = null)
        val coordinator = subject(scope = this, userPreferences = userPreferences, amberSignerGateway = gateway)

        coordinator.maybeHandleRelayAuthChallenge(authIssue())
        advanceUntilIdle()

        assertEquals(0, gateway.signEventCalls.size)
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
