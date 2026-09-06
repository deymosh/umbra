package com.umbra.app.ui.feed

import com.umbra.app.R
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIssue
import com.umbra.app.domain.relay.RelayIssueKind
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.usecase.PublishAuthEventUseCase
import com.umbra.app.domain.util.isStale
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Promoted out of FeedViewModel's companion object — a `private const val` in a
// Kotlin companion object is class-private, so RelayIssueBannerCoordinator (a sibling file/class)
// couldn't otherwise see it. Same fix EventChannelRouting.kt already applied once for
// EventRepositoryImpl's own companion-object constants.
internal const val RELAY_ISSUE_BANNER_MAX_AGE_MS = 12_000L

/**
 * Relay-issue/banner collaborator extracted from [FeedViewModel]. Constructor shape and
 * manual-instantiation style follow
 * [com.umbra.app.data.repository.EventChannelRouting]'s precedent: a package-`internal class`,
 * manually constructed by the facade (not Hilt-injected), given the same shared-mutable-state
 * instance ([uiState]) the facade itself retains and a getter lambda ([latestRelays]) over a
 * facade-owned `var` — so writes from either side stay mutually visible with no new
 * synchronization and no duplicated state.
 *
 * [requestSignAndPublishAuth] (NIP-42 relay AUTH sign-and-publish) is folded into this
 * collaborator too: its only caller is [maybeHandleRelayAuthChallenge] below, and it has no
 * `ProfileViewModel` equivalent, so it belongs with the AUTH-response logic it serves.
 */
internal class RelayIssueBannerCoordinator(
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishAuthEventUseCase: PublishAuthEventUseCase,
    private val uiState: MutableStateFlow<FeedState>,
    private val scope: CoroutineScope,
    private val latestRelays: () -> List<Relay>
) {
    private val logger = UmbraLog.tag("RelayIssueBannerCoordinator")

    private var lastRelayIssueFingerprint: String? = null
    private var lastRelayIssueAtMs: Long = 0L

    // Last-seen AUTH challenge per relay — only one can ever be "current" for a given relay at a
    // time (a new challenge always supersedes the old one), so this only needs one entry per
    // relay, not a bounded history. A global-cap LinkedHashSet of "relayUrl|challenge" fingerprints
    // (the previous design) could evict an older relay's entry once enough OTHER relays' challenges
    // arrived in the same burst (a real risk now that the relay pool can reach ~250, e.g. on a
    // cold-start reconnect storm) — causing that relay's *unchanged* challenge to look "new" again
    // and re-trigger a duplicate Amber sign prompt for a relay already authenticated to. Naturally
    // bounded by relay count, same as other relay-keyed maps in this codebase — no cap needed.
    // Owned exclusively by this coordinator (not threaded through the constructor): grep confirms
    // it is read/written only inside maybeHandleRelayAuthChallenge, both now inside this class.
    private val recentAuthChallengeByRelay = ConcurrentHashMap<String, String>()

    internal fun observeRelayIssues() {
        scope.launch {
            eventRepository.observeRelayIssues().collect { issue ->
                // Both fire on essentially every relay per reconnect cycle — CONNECTING even more
                // often than CONNECTED, since it fires on every dial attempt regardless of outcome.
                // Neither is actionable enough to interrupt the feed; both are still logged
                // unconditionally in Relay Details/Active Subscriptions' issue log.
                if (issue.kind == RelayIssueKind.CONNECTED || issue.kind == RelayIssueKind.CONNECTING) {
                    return@collect
                }

                if (issue.kind == RelayIssueKind.AUTH) {
                    maybeHandleRelayAuthChallenge(issue)
                }

                // If AMBER can sign and we received a concrete challenge, AUTH is handled
                // automatically; avoid showing a blocking error banner in this path.
                if (
                    issue.kind == RelayIssueKind.AUTH &&
                    userPreferences.canSignWithAmber() &&
                    issue.rawMessage.isNotBlank()
                ) {
                    return@collect
                }

                if (isStale(issue.timestampMs, RELAY_ISSUE_BANNER_MAX_AGE_MS)) {
                    return@collect
                }

                val fingerprint = "${issue.relayUrl}|${issue.kind}|${issue.rawMessage}"
                val now = System.currentTimeMillis()
                // Avoid repeating the same banner every reconnect tick.
                if (fingerprint == lastRelayIssueFingerprint && now - lastRelayIssueAtMs < 4000) {
                    return@collect
                }

                val state = uiState.value
                val shouldRenderBanner = when (issue.kind) {
                    // Fires per-relay on every failed connect attempt, which during a discovery
                    // burst (dozens of newly-added relays connecting/failing at once) meant this
                    // banner kept popping regardless of the isConnected/events.isEmpty() guard
                    // below. Per-relay connection health is what Relay Config/Details already
                    // show; the feed banner isn't the right place for it. Still logged
                    // unconditionally there — same reasoning as NOTICE/DUPLICATE_SUBSCRIPTION.
                    // The "show all relay banners" dev toggle bypasses this suppression for
                    // in-app debugging without adb/logcat.
                    RelayIssueKind.NETWORK -> state.verboseRelayBanners
                    RelayIssueKind.TLS -> !state.isConnected && state.events.isEmpty()
                    // Deterministic, one-shot, paired with AUTO_DISABLED — worth surfacing once.
                    RelayIssueKind.CLEARTEXT_BLOCKED -> true
                    // Self-healing (see EventRepositoryImpl.getOrCreateSubId) and purely a relay-
                    // compliance quirk, not something the user needs interrupted for — still
                    // logged unconditionally in the Relay Details/Active Subscriptions issue log.
                    RelayIssueKind.DUPLICATE_SUBSCRIPTION -> state.verboseRelayBanners
                    // A raw NOTICE is whatever free-text a relay feels like sending — anything from
                    // benign keepalive chatter to a genuine problem RelayNoticeClassifier didn't
                    // recognize a specific pattern for (see classifyRelayNotice's `else` branch).
                    // Since it's unclassified by definition, it's not reliably actionable/important
                    // enough to interrupt the feed with a banner — same reasoning as
                    // DUPLICATE_SUBSCRIPTION above. Still logged unconditionally in Relay Details/
                    // Active Subscriptions for anyone who wants to see it.
                    RelayIssueKind.NOTICE -> state.verboseRelayBanners
                    else -> true
                }
                if (!shouldRenderBanner) return@collect

                lastRelayIssueFingerprint = fingerprint
                lastRelayIssueAtMs = now
                val normalizedIssueUrl = normalizeRelayUrl(issue.relayUrl)
                val relayId = latestRelays().firstOrNull { normalizeRelayUrl(it.url) == normalizedIssueUrl }?.id
                uiState.update {
                    it.copy(errorMessage = formatRelayIssue(issue), errorRelayId = relayId) }
            }
        }
    }

    private fun formatRelayIssue(issue: RelayIssue): UiMessage {
        val relay = relayDisplayLabel(issue.relayUrl)
        return when (issue.kind) {
            RelayIssueKind.RATE_LIMIT -> {
                val cooldown = issue.cooldownSeconds
                if (cooldown != null) {
                    UiMessage.ResWithArgs(R.string.error_relay_rate_limited_cooldown, relay, cooldown)
                } else {
                    UiMessage.ResWithArgs(R.string.error_relay_rate_limited, relay)
                }
            }
            RelayIssueKind.AUTH -> UiMessage.ResWithArgs(R.string.error_relay_auth, relay)
            RelayIssueKind.BLOCKED -> UiMessage.ResWithArgs(R.string.error_relay_blocked, relay)
            RelayIssueKind.TLS -> UiMessage.ResWithArgs(R.string.error_relay_tls, relay)
            RelayIssueKind.CLEARTEXT_BLOCKED -> UiMessage.ResWithArgs(R.string.error_relay_cleartext_blocked, relay)
            RelayIssueKind.NETWORK -> issue.cooldownSeconds?.let {
                UiMessage.ResWithArgs(R.string.error_relay_network_cooldown, relay, it)
            } ?: UiMessage.ResWithArgs(R.string.error_relay_network, relay)
            RelayIssueKind.REQ_UNSUPPORTED -> UiMessage.ResWithArgs(R.string.error_relay_req_unsupported, relay, issue.rawMessage)
            RelayIssueKind.SEARCH_REQUIRED -> UiMessage.ResWithArgs(R.string.error_relay_search_required, relay, issue.rawMessage)
            RelayIssueKind.SUBSCRIPTION_LIMIT -> UiMessage.ResWithArgs(R.string.error_relay_subscription_limit, relay, issue.rawMessage)
            RelayIssueKind.DUPLICATE_SUBSCRIPTION -> UiMessage.ResWithArgs(R.string.error_relay_duplicate_subscription, relay, issue.rawMessage)
            RelayIssueKind.NEGENTROPY_UNSUPPORTED -> UiMessage.ResWithArgs(R.string.error_relay_negentropy_unsupported, relay, issue.rawMessage)
            RelayIssueKind.TOR_CIRCUITS_LIKELY_DEAD -> UiMessage.Res(R.string.error_tor_circuits_likely_dead)
            RelayIssueKind.TOR_CIRCUITS_RECOVERED -> UiMessage.Res(R.string.info_tor_circuits_recovered)
            RelayIssueKind.NOTICE -> {
                if (issue.rawMessage.isBlank()) UiMessage.ResWithArgs(R.string.error_relay_notice, relay)
                else UiMessage.ResWithArgs(R.string.error_relay_notice_with_detail, relay, issue.rawMessage)
            }
            RelayIssueKind.CONNECTED -> UiMessage.ResWithArgs(R.string.relay_connected, relay)
            RelayIssueKind.CONNECTING -> UiMessage.ResWithArgs(R.string.relay_connecting, relay)
            RelayIssueKind.AUTO_DISABLED -> UiMessage.ResWithArgs(R.string.error_relay_auto_disabled, relay)
            RelayIssueKind.UNKNOWN -> {
                if (issue.rawMessage.isBlank()) UiMessage.ResWithArgs(R.string.error_relay_unknown, relay)
                else UiMessage.ResWithArgs(R.string.error_relay_unknown_with_detail, relay, issue.rawMessage)
            }
        }
    }

    private fun relayDisplayLabel(relayUrl: String): String =
        runCatching { java.net.URI(relayUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: relayUrl

    internal fun maybeHandleRelayAuthChallenge(issue: RelayIssue) {
        // Only respond to real ["AUTH", challenge] frames (NIP-42).
        // CLOSED/OK messages with "auth-required:" text are NOT challenges — the client
        // must use the previously stored challenge for that relay, which is already handled
        // at the network layer by re-emitting the stored challenge with isAuthChallenge=true.
        if (!issue.isAuthChallenge) return

        val challenge = issue.rawMessage.trim()
        if (challenge.isBlank()) return
        if (!userPreferences.canSignWithAmber()) return

        if (recentAuthChallengeByRelay[issue.relayUrl] == challenge) return
        recentAuthChallengeByRelay[issue.relayUrl] = challenge

        requestSignAndPublishAuth(issue.relayUrl, challenge)
    }

    internal fun shouldClearNetworkBanner(state: FeedState): Boolean {
        return when (val relayError = state.errorMessage) {
            is UiMessage.ResWithArgs -> relayError.id == R.string.error_relay_network ||
                relayError.id == R.string.error_relay_network_cooldown
            else -> false
        }
    }

    /** Same Amber round trip as FeedViewModel's `requestSignAndPublish`, but for a NIP-42 relay AUTH event — published to just [relayUrl], never broadcast. */
    private fun requestSignAndPublishAuth(relayUrl: String, challenge: String) {
        scope.launch {
            val authJson = NostrEventBuilder.relayAuth(challenge = challenge, relayUrl = relayUrl)
            val signedEvent = try {
                amberSignerGateway.signEvent(authJson, userPreferences.getPublicKey())
            } catch (e: Exception) {
                logger.d { "Error requesting signed AUTH event: ${scrubThrowableMessageForLogs(e)}" }
                null
            } ?: return@launch
            publishAuthEventUseCase(signedEvent, relayUrl)
                .onSuccess {
                    uiState.update { state ->
                        val isAuthError = (state.errorMessage as? UiMessage.ResWithArgs)?.id == R.string.error_relay_auth
                        if (isAuthError) state.copy(errorMessage = null, errorRelayId = null) else state
                    }
                    // NIP-42: the relay likely rejected our original REQs pre-auth (or
                    // never got them), so replay every active channel now that we're
                    // authenticated instead of leaving that relay's subscriptions dead.
                    eventRepository.reapplyChannelsToRelay(relayUrl)
                }
                .onFailure { e ->
                    logger.d { "Error publishing AUTH event: ${scrubThrowableMessageForLogs(e)}" }
                    val normalizedPendingUrl = normalizeRelayUrl(relayUrl)
                    val pendingRelayId = latestRelays().firstOrNull { normalizeRelayUrl(it.url) == normalizedPendingUrl }?.id
                    uiState.update {
                        it.copy(
                            errorMessage = UiMessage.ResWithArgs(R.string.error_relay_auth, relayDisplayLabel(relayUrl)),
                            errorRelayId = pendingRelayId
                        )
                    }
                }
        }
    }
}
