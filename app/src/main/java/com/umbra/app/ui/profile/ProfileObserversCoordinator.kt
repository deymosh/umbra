@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.umbra.app.ui.profile

import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip01.NostrValidation
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.relay.randomSubscriptionId
import com.umbra.app.domain.repository.ContactListRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.usecase.BuildHydrationAuthorSetUseCase
import com.umbra.app.domain.usecase.BuildProfileHydrationRequestsUseCase
import com.umbra.app.ui.common.ImmutableMapSnapshot
import com.umbra.app.ui.common.toImmutableSnapshot
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Promoted out of ProfileViewModel's companion object — a `private const val` in a
// Kotlin companion object is class-private, so ProfileObserversCoordinator (a sibling file/class)
// couldn't otherwise see these. Same fix RelayIssueBannerCoordinator.kt already applied once for
// FeedViewModel's own companion-object constants.
internal const val FOLLOWS_HYDRATION_DEBOUNCE_MS = 500L
internal const val FOLLOWS_HYDRATION_MAX_AUTHORS = 200
internal const val FOLLOWS_HYDRATION_CHUNK_SIZE = 100
internal const val FOLLOWS_HYDRATION_PER_AUTHOR_LIMIT = 100
// 15s, not 12s: gives slower relays enough Tor round-trip slack to return every kind
// for every author in the (correctly-sized, see BuildProfileHydrationFiltersUseCase)
// multi-kind hydration REQ before it's torn down.
internal const val FOLLOWS_HYDRATION_CHANNEL_CLOSE_MS = 15_000L

/**
 * Profile-specific observers cluster extracted from [ProfileViewModel]. Constructor shape and
 * manual-instantiation style follow
 * [com.umbra.app.data.repository.EventChannelRouting]/[com.umbra.app.ui.feed.RelayIssueBannerCoordinator]'s
 * precedent: a package-`internal class`, manually constructed by the facade (not Hilt-injected),
 * given the same shared-mutable-state instance ([state]) the facade itself retains — so writes
 * from either side stay mutually visible with no new synchronization and no duplicated state.
 *
 * Owns the follows/mutes/pins/relay-lists/NIP-45 note+follower-count observers, the
 * follows-hydration debounce/batching helper, and the local-notes-count reconciliation state that
 * feeds `recomputeTotalNotesCount`. `profileFollowsMetaChannelId` is computed inside this class's
 * own constructor rather than threaded through, since [NostrChannels.profileFollowsMeta] is a pure
 * function of [pubkey] this class already has.
 */
internal class ProfileObserversCoordinator(
    private val pubkey: String,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val contactListRepository: ContactListRepository,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository,
    private val relayRepository: RelayRepository,
    private val userPreferences: UserPreferences,
    private val buildHydrationAuthorSetUseCase: BuildHydrationAuthorSetUseCase,
    private val buildProfileHydrationRequestsUseCase: BuildProfileHydrationRequestsUseCase,
    private val state: MutableStateFlow<ProfileState>,
    private val scope: CoroutineScope
) {
    private val logger = UmbraLog.tag("ProfileObserversCoordinator")

    private val profileFollowsMetaChannelId = NostrChannels.profileFollowsMeta(pubkey)

    // Wire-level NIP-45 COUNT subscription ids — random, not descriptive (see
    // randomSubscriptionId's doc comment), generated once and kept stable for this coordinator's
    // lifetime since observeNip45NoteCounts/observeNip45FollowersCount below filter by equality
    // against these.
    private val profileNoteCountSubscriptionId = randomSubscriptionId()
    private val profileFollowersCountSubscriptionId = randomSubscriptionId()

    // internal (not private) so ProfileObserversCoordinatorTest can assert isActive directly after
    // cancelScheduledWork() — a regression guard ensuring the debounce/close-job cleanup this
    // coordinator inherited from ProfileViewModel.onCleared() genuinely stops the job, not just
    // that cancelScheduledWork() was called.
    internal var followsHydrationJob: Job? = null
    private var followsHydrationCloseJob: Job? = null
    private var queuedFollowHydrationAuthors: Set<String> = emptySet()
    private val localNotesCount = MutableStateFlow(0)
    private val remoteNotesCountByRelay = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val remoteFollowersCountByRelay = MutableStateFlow<Map<String, Long>>(emptyMap())

    private data class FollowedProfilesSnapshot(
        val followedPubkeys: List<String>,
        val followedProfiles: ImmutableMapSnapshot<String, UserProfile>,
        val missingPubkeys: List<String>
    )

    /** Wraps the local-notes-count collector that used to run inline inside ProfileViewModel's
     * `init{}` — reads/writes cluster-owned state ([localNotesCount], [recomputeTotalNotesCount]),
     * so it must run as a coordinator method rather than raw against fields that no longer exist
     * on [ProfileViewModel]. */
    internal fun observeLocalNotesCount() {
        scope.launch {
            eventRepository.observeCountEventsByPubkeyAndKind(pubkey, Event.KIND_TEXT_NOTE)
                .collect { total ->
                    localNotesCount.value = total
                    recomputeTotalNotesCount()
                }
        }
    }

    internal fun observeFollowsForViewedProfile() {
        scope.launch {
            contactListRepository.getContactList(pubkey)
                .mapLatest { contactList ->
                    val followed = withContext(Dispatchers.Default) {
                        (contactList?.followedPubkeys ?: emptySet())
                            .map { it.lowercase() }
                            .distinct()
                            .sorted()
                    }

                    val followedProfiles = if (followed.isNotEmpty()) {
                        userRepository.getProfiles(followed).associateBy { it.pubkey.lowercase() }
                    } else {
                        emptyMap()
                    }

                    val missingPubkeys = NostrValidation.validate64HexSet(
                        followed.filter { it !in followedProfiles }
                    ).toList()

                    FollowedProfilesSnapshot(
                        followedPubkeys = followed,
                        followedProfiles = followedProfiles.toImmutableSnapshot(),
                        missingPubkeys = missingPubkeys
                    )
                }
                .collect { snapshot ->
                    state.update {
                        it.copy(
                            followedPubkeys = snapshot.followedPubkeys,
                            followedProfiles = snapshot.followedProfiles
                        )
                    }

                    if (snapshot.missingPubkeys.isNotEmpty()) {
                        enqueueFollowProfileHydration(snapshot.missingPubkeys)
                    }
                }
        }

        // Keep followedProfiles map updated as new metadata arrives from network
        scope.launch {
            userRepository.profileFlow.collect { updated ->
                val pk = updated.pubkey.lowercase()
                if (pk in state.value.followedPubkeys) {
                    state.update { current ->
                        current.copy(followedProfiles = current.followedProfiles + (pk to updated))
                    }
                }
            }
        }
    }

    internal fun observeMutedAuthorsForCurrentSession() {
        // The Mutes tab reflects the NIP-51 published mute list (kind 10000) — the same
        // globally-synced source that gates the feed — not any single feed filter's local excludes.
        scope.launch {
            userPreferences.getPublicKeyFlow()
                .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
                .distinctUntilChanged()
                .flatMapLatest { ownerPubkey ->
                    if (ownerPubkey == null) flowOf(null) else muteListRepository.getMuteList(ownerPubkey)
                }
                .collect { muteList ->
                    val muted = muteList?.mutedPubkeys.orEmpty().sorted()
                    state.update { it.copy(mutedPubkeys = muted) }
                }
        }
    }

    internal fun observePinnedNotesForCurrentSession() {
        // The Pinned tab reflects the NIP-51 published pin list (kind 10001), synced across
        // the owner's clients — same shape as observeMutedAuthorsForCurrentSession(), but
        // resolves event ids to full Event objects for rendering via notesFeedSection.
        scope.launch {
            userPreferences.getPublicKeyFlow()
                .map { it?.takeIf { key -> key.length == 64 }?.lowercase() }
                .distinctUntilChanged()
                .flatMapLatest { ownerPubkey ->
                    if (ownerPubkey == null) flowOf(null) else pinListRepository.getPinList(ownerPubkey)
                }
                .collect { pinList ->
                    val pinnedIds = pinList?.pinnedEventIds.orEmpty()
                    val pinnedNotes = if (pinnedIds.isEmpty()) {
                        emptyList()
                    } else {
                        eventRepository.getEventsByIds(pinnedIds.toList())
                            .sortedByDescending { it.createdAt }
                    }
                    state.update { it.copy(pinnedNotes = pinnedNotes) }
                }
        }
    }

    internal fun enqueueFollowProfileHydration(authors: Collection<String>) {
        queuedFollowHydrationAuthors = buildHydrationAuthorSetUseCase(
            existing = queuedFollowHydrationAuthors,
            incoming = authors,
            maxAuthors = FOLLOWS_HYDRATION_MAX_AUTHORS
        )

        followsHydrationJob?.cancel()
        followsHydrationJob = scope.launch {
            delay(FOLLOWS_HYDRATION_DEBOUNCE_MS)

            if (queuedFollowHydrationAuthors.isEmpty()) return@launch
            val batch = queuedFollowHydrationAuthors
            queuedFollowHydrationAuthors = emptySet()

            eventRepository.subscribeChannel(
                profileFollowsMetaChannelId,
                buildProfileHydrationRequestsUseCase(
                    authors = batch,
                    chunkSize = FOLLOWS_HYDRATION_CHUNK_SIZE,
                    perAuthorLimit = FOLLOWS_HYDRATION_PER_AUTHOR_LIMIT
                )
            )

            // Close the REQ as soon as every relay it was sent to reports EOSE, falling back to
            // the fixed delay as a backstop for relays that never send it.
            followsHydrationCloseJob?.cancel()
            followsHydrationCloseJob = scope.launch {
                eventRepository.awaitChannelEoseOrTimeout(profileFollowsMetaChannelId, FOLLOWS_HYDRATION_CHANNEL_CLOSE_MS)
                eventRepository.clearChannel(profileFollowsMetaChannelId)
            }
        }
    }

    // Only called for profiles other than the logged user's.
    internal fun observeTargetUserRelayLists() {

        // Reload cached relay lists whenever new relay list events arrive in DB
        scope.launch {
            combine(
                eventRepository.observeCountEventsByPubkeyAndKind(pubkey, Event.KIND_RELAY_LIST_METADATA),
                eventRepository.observeCountEventsByPubkeyAndKind(pubkey, Event.KIND_DM_RELAY_LIST)
            ) { _, _ -> Unit }
                .onStart { emit(Unit) }
                .collect {
                    val relayList = userRepository.getRelayList(pubkey)
                    val dmRelayList = userRepository.getDmRelayList(pubkey)
                    state.update { current ->
                        current.copy(
                            targetOutboxRelays = relayList?.getOutboxRelays() ?: emptyList(),
                            targetInboxRelays = relayList?.getInboxRelays() ?: emptyList(),
                            targetDmRelays = dmRelayList?.relays ?: emptyList()
                        )
                    }
                    // No separate discovery call needed here: this profile's outbox relays were
                    // already added as "discovered" relays (if new) the moment its kind:10002
                    // arrived, by UserRepositoryImpl.saveRelayList() — the single choke point
                    // every relay list flows through regardless of which screen is open.
                }
        }
    }

    internal fun observeRelayStats() {
        scope.launch {
            combine(
                relayRepository.getAllRelays(),
                eventRepository.observeConnectedRelayUrls()
            ) { allRelays, connectedUrls ->
                // This tab shows the logged-in user's own configured relay setup (what would
                // be published as their NIP-65 relay list) — not relays auto-added (see
                // UserRepositoryImpl.saveRelayList()) to reach other authors, which have their
                // own "Discovered relays" section in RelayConfigScreen.
                val relays = allRelays.filterNot { it.isDiscovered }
                val connectedNormalized = connectedUrls
                    .map { normalizeRelayUrl(it) }
                    .toSet()

                val stats = ProfileRelayStats(
                    total = relays.size,
                    connected = relays.count { connectedNormalized.contains(normalizeRelayUrl(it.url)) },
                    outboxEnabled = relays.count { it.isWriteEnabled },
                    inboxEnabled = relays.count { it.isReadEnabled },
                    dmEnabled = relays.count { it.isDmEnabled },
                    onion = relays.count { it.isOnion },
                    withNip11Info = relays.count { it.relayInfo != null }
                )

                relays to stats
            }.collect { (relays, stats) ->
                state.update { it.copy(relays = relays, relayStats = stats) }
            }
        }
    }

    internal fun observeNip45NoteCounts() {
        scope.launch {
            eventRepository.observeRelayCounts()
                .filter { it.subscriptionId == profileNoteCountSubscriptionId }
                .collect { result ->
                    val sanitizedCount = result.count.coerceAtLeast(0L)
                    remoteNotesCountByRelay.update { previous ->
                        previous + (result.relayUrl.lowercase() to sanitizedCount)
                    }
                    recomputeTotalNotesCount()
                }
        }
    }

    internal fun requestNip45NoteCountsOnRelayChanges() {
        scope.launch {
            combine(
                relayRepository.getAllRelays(),
                eventRepository.observeConnectedRelayUrls()
            ) { allRelays, connectedUrls ->
                val connectedNormalized = connectedUrls.map { normalizeRelayUrl(it) }.toSet()
                // NIP-45 COUNT is an optional relay feature — only send it to relays that have
                // actually advertised support via their NIP-11 document. A relay we haven't
                // fetched info for yet, or one that doesn't list 45, gets skipped rather than
                // assumed-supported: an unsupported COUNT is a wasted REQ at best and, per relay,
                // sometimes an error/NOTICE response.
                allRelays
                    .asSequence()
                    .map { normalizeRelayUrl(it.url) to it }
                    .filter { (url, _) -> url in connectedNormalized }
                    .filter { (_, relay) -> relay.relayInfo?.supportedNips?.contains(45) == true }
                    .map { (url, _) -> url }
                    .toSet()
            }
                .distinctUntilChanged()
                .collect { nip45Relays ->
                    if (nip45Relays.isEmpty()) return@collect

                    val countFilters = listOf(
                        EventFilter(
                            authors = setOf(pubkey),
                            kinds = setOf(Event.KIND_TEXT_NOTE)
                        )
                    )

                    nip45Relays.forEach { relayUrl ->
                        eventRepository.requestCount(
                            relayUrl = relayUrl,
                            subscriptionId = profileNoteCountSubscriptionId,
                            filters = countFilters
                        )
                    }
                }
        }
    }

    internal fun recomputeTotalNotesCount() {
        val local = localNotesCount.value
        val remoteBest = bestRemoteCount(remoteNotesCountByRelay.value.values)
        val merged = maxOf(local, remoteBest)
        state.update { current ->
            if (current.totalNotesCount == merged) current else current.copy(totalNotesCount = merged)
        }
    }

    // Follower count has no local equivalent (we don't index the whole network's kind-3
    // contact lists), so unlike notes count it's remote-COUNT-only, and stays null until at
    // least one relay has actually answered.
    internal fun observeNip45FollowersCount() {
        scope.launch {
            eventRepository.observeRelayCounts()
                .filter { it.subscriptionId == profileFollowersCountSubscriptionId }
                .collect { result ->
                    val sanitizedCount = result.count.coerceAtLeast(0L)
                    remoteFollowersCountByRelay.update { previous ->
                        previous + (result.relayUrl.lowercase() to sanitizedCount)
                    }
                    recomputeFollowersCount()
                }
        }
    }

    internal fun requestNip45FollowersCountOnRelayChanges() {
        scope.launch {
            combine(
                relayRepository.getAllRelays(),
                eventRepository.observeConnectedRelayUrls()
            ) { allRelays, connectedUrls ->
                val connectedNormalized = connectedUrls.map { normalizeRelayUrl(it) }.toSet()
                allRelays
                    .asSequence()
                    .map { normalizeRelayUrl(it.url) to it }
                    .filter { (url, _) -> url in connectedNormalized }
                    .filter { (_, relay) -> relay.relayInfo?.supportedNips?.contains(45) == true }
                    .map { (url, _) -> url }
                    .toSet()
            }
                .distinctUntilChanged()
                .collect { nip45Relays ->
                    if (nip45Relays.isEmpty()) return@collect

                    val countFilters = listOf(
                        EventFilter(
                            kinds = setOf(Event.KIND_CONTACT_LIST),
                            tagFilters = mapOf("p" to setOf(pubkey))
                        )
                    )

                    nip45Relays.forEach { relayUrl ->
                        eventRepository.requestCount(
                            relayUrl = relayUrl,
                            subscriptionId = profileFollowersCountSubscriptionId,
                            filters = countFilters
                        )
                    }
                }
        }
    }

    internal fun recomputeFollowersCount() {
        val remoteBest = bestRemoteCount(remoteFollowersCountByRelay.value.values)
        state.update { current ->
            if (current.followersCount == remoteBest) current else current.copy(followersCount = remoteBest)
        }
    }

    /** Cancels the follows-hydration debounce/close jobs and clears the follows-meta channel —
     * mirrors [com.umbra.app.data.repository.EventIngestCache]'s `cancelPendingSnapshotEmit()`
     * precedent for preserving pre-extraction cleanup. Called from [ProfileViewModel.onCleared]. */
    internal fun cancelScheduledWork() {
        followsHydrationJob?.cancel()
        followsHydrationCloseJob?.cancel()
        eventRepository.clearChannel(profileFollowsMetaChannelId)
    }
}
