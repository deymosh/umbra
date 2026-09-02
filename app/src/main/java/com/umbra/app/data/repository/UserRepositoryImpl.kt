package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.UserProfileDao
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.db.mapper.toEntity
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip51.IndexRelaysList
import com.umbra.app.domain.nip51.SearchRelaysList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.relay.RelayIdGenerator
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.relay.normalizeRelayUrl
import com.umbra.app.domain.relay.selectNewDiscoverableRelayUrls
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.repository.Nip05Repository
import com.umbra.app.domain.util.thresholdMillisBefore
import com.umbra.app.util.ImagePrefetcher
import com.umbra.app.util.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal fun missingProfilePubkeys(
    requestedPubkeys: List<String>,
    cachedPubkeys: Set<String>
): List<String> = requestedPubkeys.asSequence()
    .map { it.lowercase() }
    .distinct()
    .filterNot { it in cachedPubkeys }
    .toList()

/**
 * In-memory implementation for profile and relay-list caches.
 * Auto-verifies NIP-05 identifiers when profiles are saved/obtained to ensure SSOT.
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    @Named("encrypted") private val userProfileDao: UserProfileDao,
    private val userPreferences: UserPreferences,
    private val relayRepository: RelayRepository,
    private val nip05Repository: Nip05Repository,
    private val imagePrefetcher: ImagePrefetcher
) : UserRepository {
    companion object {
        private const val TAG = "UmbraUserRepo"
        private const val PROFILE_FRESH_TTL_MS = 24 * 60 * 60 * 1000L
        private const val STALE_PROFILE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

        // Total isDiscovered=true relays allowed to accumulate across the whole session. Not a
        // real-world usage limit — a follow list large enough to actually reach this would be
        // extraordinary — purely a sanity ceiling against a single malformed/malicious kind:10002
        // (thousands of fake relay tags) freezing the pool. Sized in the same ballpark as a
        // production OkHttp dispatcher's own request ceiling for the same reason, not derived
        // from any expected relay count.
        const val MAX_TOTAL_DISCOVERED_RELAYS = 1000
    }

    private val logger = UmbraLog.tag(TAG)

    private val relayLists = ConcurrentHashMap<String, RelayListMetadata>()
    private val dmRelayLists = ConcurrentHashMap<String, DmRelayList>()
    private val serverLists = ConcurrentHashMap<String, UserServerList>()
    // StateFlow rather than a plain map (unlike relayLists/dmRelayLists above) — RelayConfigScreen
    // needs to react to a fresh SearchRelaysList.encryptedContent worth decrypting as soon as it
    // arrives while the screen is open, not just read whatever was cached at screen-open time.
    private val _searchRelayLists = MutableStateFlow<Map<String, SearchRelaysList>>(emptyMap())
    private val _indexRelayLists = MutableStateFlow<Map<String, IndexRelaysList>>(emptyMap())
    // See wasRelayListContentApplied's doc comment — session-lifetime only (not persisted), since
    // the actual result of decryption (which relays are search/index-enabled) is already durably
    // persisted via applySearchRelayListToLocalConfig/applyIndexRelayListToLocalConfig; this set
    // exists purely to avoid re-prompting Amber for a ciphertext already resolved this session.
    private val appliedRelayListContent: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val profiles = ConcurrentHashMap<String, UserProfile>()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards every read-existing-then-write local relay-config sequence in this repository:
    // addDiscoveredRelays, and the four applyXToLocalConfig functions for the current user's own
    // kind:10002/10050/10007/10086 declarations. All five follow the same shape — snapshot
    // relayRepository.getAllRelays().first(), decide add/update/remove per relay based on that
    // snapshot, then write it back call by call — and every one of them can run concurrently with
    // any other: EventRepositoryImpl.subscribeToEvents() launches a fresh, independent
    // repoScope.launch(Dispatchers.IO) coroutine per incoming event, and the OUTBOX_PROFILE/
    // BootstrapOwnProfileUseCase filter requests all four of the current user's own social-graph
    // kinds in one REQ — so a relay's response burst routinely delivers kind:10002, 10050, 10007,
    // and 10086 within the same second. Without a shared lock, two of these functions racing on
    // the same relay URL is a classic lost update: both snapshot the row before either write
    // lands, both compute their own modified copy unaware of the other's change, and whichever
    // writes last silently discards the other's role flag (e.g. a relay declared in both the
    // user's real kind:10002 AND their kind:10007 search list could end up missing isSearchEnabled
    // or isReadEnabled depending on interleaving) — this is exactly what made a relay that should
    // land in the Search/Index sections sometimes never show up there despite the underlying
    // kind:10007/10086 event and NIP-44 decrypt both succeeding. relaysMutex inside
    // RelayRepositoryImpl only ever protects the individual DB write, not this whole
    // read-then-write span across potentially several relay rows.
    private val relayConfigMutex = Mutex()

    private val _profileFlow = MutableSharedFlow<UserProfile>(replay = 0)
    override val profileFlow: SharedFlow<UserProfile> = _profileFlow.asSharedFlow()

    init {
        scheduleStaleProfileCleanup()
    }

    override suspend fun getProfile(pubkey: String): UserProfile? =
        withContext(Dispatchers.IO) {
            val normalizedPubkey = pubkey.lowercase()
            val profile = profiles[normalizedPubkey] ?: userProfileDao.getProfile(normalizedPubkey)?.toDomain()
            profile?.let { profiles[normalizedPubkey] = it }

            // Trigger NIP-05 verification in background if needed (SSOT)
            profile?.let { triggerNip05VerificationIfNeeded(it) }

            profile
        }
    override suspend fun getProfiles(pubkeys: List<String>): List<UserProfile> {
        if (pubkeys.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val normalizedRequested = pubkeys.map { it.lowercase() }
            val missingPubkeys = missingProfilePubkeys(normalizedRequested, profiles.keys)

            if (missingPubkeys.isNotEmpty()) {
                userProfileDao.getProfiles(missingPubkeys)
                    .map { it.toDomain() }
                    .forEach {
                        profiles[it.pubkey.lowercase()] = it
                        // Trigger NIP-05 verification in background if needed (SSOT) — mirrors
                        // getProfile(); without this, batch-loaded profiles (e.g. follow lists)
                        // never kick off verification at all.
                        triggerNip05VerificationIfNeeded(it)
                    }
            }

            normalizedRequested.mapNotNull(profiles::get)
        }
    }

    override suspend fun searchLocalProfiles(query: String, limit: Int): List<UserProfile> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            userProfileDao.searchProfilesByName(trimmed, limit).map { it.toDomain() }
        }
    }

    override fun saveProfile(profile: UserProfile) {
        repoScope.launch {
            val existing = userProfileDao.getProfile(profile.pubkey)?.toDomain()
            if (existing != null) {
                // Ignore stale or identical metadata updates to avoid useless recompositions/log spam.
                val hasNewerData = profile.lastUpdated >= existing.lastUpdated
                val changed = profile.name != existing.name ||
                    profile.displayName != existing.displayName ||
                    profile.picture != existing.picture ||
                    profile.about != existing.about ||
                    profile.nip05 != existing.nip05 ||
                    profile.website != existing.website ||
                    profile.banner != existing.banner ||
                    profile.lud06 != existing.lud06 ||
                    profile.lud16 != existing.lud16

                if (!hasNewerData || !changed) {
                    return@launch
                }
            }

            userProfileDao.insertProfile(profile.toEntity())
            profiles[profile.pubkey.lowercase()] = profile
            _profileFlow.tryEmit(profile)

            // Auto-verify NIP-05 if profile has nip05 identifier and not yet verified (SSOT pattern)
            triggerNip05VerificationIfNeeded(profile)

            // Prefetch avatar image to reduce UI jank on first render (concurrency-limited, Tor-guarded)
            val pictureUrl = profile.picture?.takeIf { it.isNotBlank() }
            if (pictureUrl != null) {
                try {
                    // Fire-and-forget prefetch; ImagePrefetcher handles Tor readiness and concurrency
                    imagePrefetcher.prefetchAsync(pictureUrl)
                } catch (e: Exception) {
                    logger.d { "Avatar prefetch scheduling failed: ${scrubThrowableMessageForLogs(e)}" }
                }
            }
            logger.d { "Profile updated" }
        }
    }

    override fun getRelayList(pubkey: String): RelayListMetadata? {
        // Defensive lowercase — relayLists is only ever written with an already-lowercased key
        // (RelayListMetadata.fromEvent normalizes event.pubkey), but this read side trusted every
        // caller to do the same. Every current call site happens to already normalize, but a
        // future caller passing a raw/mixed-case pubkey would silently get null — the exact same
        // shape of bug as the relay-URL comparisons fixed elsewhere.
        return relayLists[pubkey.lowercase()]
    }

    override fun getDmRelayList(pubkey: String): DmRelayList? {
        return dmRelayLists[pubkey.lowercase()]
    }

    override fun saveDmRelayList(dmRelayList: DmRelayList) {
        val existing = dmRelayLists[dmRelayList.pubkey]
        if (existing != null && existing.lastUpdated >= dmRelayList.lastUpdated) return
        dmRelayLists[dmRelayList.pubkey] = dmRelayList
        if (isCurrentUser(dmRelayList.pubkey)) {
            repoScope.launch(Dispatchers.IO) {
                runCatching { applyDmRelayListToLocalConfig(dmRelayList) }
                    .onFailure { e ->
                        logger.d { "Failed to apply DM relay list automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        }
        logger.d { "DM relay list updated (${dmRelayList.relays.size} relays)" }
    }

    override fun getServerList(pubkey: String): UserServerList? {
        return serverLists[pubkey.lowercase()]
    }

    override fun saveServerList(serverList: UserServerList) {
        val existing = serverLists[serverList.pubkey]
        if (existing != null && existing.lastUpdated >= serverList.lastUpdated) return
        serverLists[serverList.pubkey] = serverList
        logger.d { "Blossom server list updated (${serverList.servers.size} servers)" }
    }

    override fun saveRelayList(relayList: RelayListMetadata) {
        // Same staleness guard as saveDmRelayList: relays don't all answer at once, so an older
        // kind:10002 from a slow relay can arrive after a newer one from a fast relay. Applying
        // it anyway would regress the cached list — for a followed author that's just stale
        // data, but for the current user it would also (see applyRelayListToLocalConfig) undo
        // the outbox/inbox roles the newer event just set.
        //
        // compute() (not a plain read-then-write) so the staleness check and the map write are
        // one atomic step per pubkey: two concurrent deliveries of the same pubkey's kind:10002
        // (e.g. from a fast relay and a slow relay during post-login hydration) can otherwise both
        // read the same "existing" value before either writes, both pass the check, and both
        // schedule their own applyRelayListToLocalConfig — with only launch/mutex-acquisition
        // order, not recency, deciding which one's DB write lands last. Only the value that wins
        // this compute() is ever scheduled; see the `relayLists[relayList.pubkey] != relayList`
        // guard in applyRelayListToLocalConfig for why this alone isn't sufficient.
        val accepted = relayLists.compute(relayList.pubkey) { _, existing ->
            if (existing != null && existing.lastUpdated >= relayList.lastUpdated) existing else relayList
        } === relayList
        if (!accepted) return
        if (isCurrentUser(relayList.pubkey)) {
            repoScope.launch(Dispatchers.IO) {
                runCatching { applyRelayListToLocalConfig(relayList) }
                    .onFailure { e ->
                        logger.d { "Failed to apply relay list automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        } else {
            // Any other tracked author's (follow, quoted/mentioned/thread author, viewed
            // profile — whoever this relay list was fetched for) declared relays — write, read,
            // AND unmarked — are candidates to auto-add as read-only "discovered" relays. Not
            // just getOutboxRelays(): a read-only (inbox) relay is a real relay too, and skipping
            // it here meant "discover every relay we can" silently missed a third of what a
            // kind:10002 can actually declare. Done directly here, on arrival, rather than as a
            // separate periodic/batch pass over the whole follow list: this is the single choke
            // point every kind:10002 already flows through, so there is nothing left to
            // "discover" by revisiting it later.
            repoScope.launch(Dispatchers.IO) {
                runCatching { addDiscoveredRelays(relayList.getAllDeclaredRelays()) }
                    .onFailure { e ->
                        logger.d { "Failed to add discovered relays automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        }
        logger.d { "Relay list updated" }
    }

    override fun observeSearchRelaysList(pubkey: String): Flow<SearchRelaysList?> {
        val normalizedPubkey = pubkey.lowercase()
        return _searchRelayLists.map { it[normalizedPubkey] }.distinctUntilChanged()
    }

    override fun observeIndexRelaysList(pubkey: String): Flow<IndexRelaysList?> {
        val normalizedPubkey = pubkey.lowercase()
        return _indexRelayLists.map { it[normalizedPubkey] }.distinctUntilChanged()
    }

    override suspend fun applyDecryptedSearchRelays(pubkey: String, relayUrls: Set<String>) {
        val normalizedPubkey = pubkey.lowercase()
        if (!isCurrentUser(normalizedPubkey)) return
        runCatching {
            applySearchRelayListToLocalConfig(
                SearchRelaysList(ownerPubkey = normalizedPubkey, relayUrls = relayUrls, updatedAt = System.currentTimeMillis() / 1000)
            )
        }.onFailure { e ->
            logger.d { "Failed to apply decrypted search relays: ${scrubThrowableMessageForLogs(e)}" }
        }
    }

    override suspend fun applyDecryptedIndexRelays(pubkey: String, relayUrls: Set<String>) {
        val normalizedPubkey = pubkey.lowercase()
        if (!isCurrentUser(normalizedPubkey)) return
        runCatching {
            applyIndexRelayListToLocalConfig(
                IndexRelaysList(ownerPubkey = normalizedPubkey, relayUrls = relayUrls, updatedAt = System.currentTimeMillis() / 1000)
            )
        }.onFailure { e ->
            logger.d { "Failed to apply decrypted index relays: ${scrubThrowableMessageForLogs(e)}" }
        }
    }

    override fun saveSearchRelaysList(list: SearchRelaysList) {
        // Same staleness guard as saveRelayList/saveDmRelayList.
        val existing = _searchRelayLists.value[list.ownerPubkey]
        if (existing != null && existing.updatedAt >= list.updatedAt) return
        _searchRelayLists.update { it + (list.ownerPubkey to list) }
        if (isCurrentUser(list.ownerPubkey)) {
            // Applied as a first-class Relay role (isSearchEnabled/isSearchActive) the same way
            // DM is, so RelayConfigScreen's Search section — driven by the same relay-table Flow
            // as Outbox/Inbox/DM — reflects it reliably instead of only when addDiscoveredRelays
            // below happens to insert a brand new row (see applySearchRelayListToLocalConfig doc).
            repoScope.launch(Dispatchers.IO) {
                runCatching { applySearchRelayListToLocalConfig(list) }
                    .onFailure { e ->
                        logger.d { "Failed to apply search relay list automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        } else {
            // Any other tracked author's declared search relays are candidates to auto-add, same
            // as an outbox relay — informational discovery only, not a role on their relay rows.
            repoScope.launch(Dispatchers.IO) {
                runCatching { addDiscoveredRelays(list.relayUrls.toList()) }
                    .onFailure { e ->
                        logger.d { "Failed to add discovered search relays automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        }
        logger.d { "Search relay list updated (${list.relayUrls.size} relays)" }
    }

    override fun saveIndexRelaysList(list: IndexRelaysList) {
        val existing = _indexRelayLists.value[list.ownerPubkey]
        if (existing != null && existing.updatedAt >= list.updatedAt) return
        _indexRelayLists.update { it + (list.ownerPubkey to list) }
        if (isCurrentUser(list.ownerPubkey)) {
            repoScope.launch(Dispatchers.IO) {
                runCatching { applyIndexRelayListToLocalConfig(list) }
                    .onFailure { e ->
                        logger.d { "Failed to apply index relay list automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        } else {
            repoScope.launch(Dispatchers.IO) {
                runCatching { addDiscoveredRelays(list.relayUrls.toList()) }
                    .onFailure { e ->
                        logger.d { "Failed to add discovered index relays automatically: ${scrubThrowableMessageForLogs(e)}" }
                    }
            }
        }
        logger.d { "Index relay list updated (${list.relayUrls.size} relays)" }
    }

    override fun discoverRelayHints(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        repoScope.launch(Dispatchers.IO) {
            runCatching { addDiscoveredRelays(relayUrls) }
                .onFailure { e ->
                    logger.d { "Failed to add discovered relays from hints: ${scrubThrowableMessageForLogs(e)}" }
                }
        }
    }

    /**
     * Auto-adds any of [candidateUrls] not already configured as a read-only isDiscovered relay
     * — shared by [saveRelayList] (a tracked author's outbox relays) and [discoverRelayHints]
     * (NIP-19 relay hints), so both agree on what's safe to add. Bounded by
     * [MAX_TOTAL_DISCOVERED_RELAYS] across the whole session (neither a malformed relay list nor
     * a malicious relay hint can run away the pool), and never adds a relay whose host is a
     * loopback/private/link-local address — see [isLocalNetworkRelayUrl].
     */
    private suspend fun addDiscoveredRelays(candidateUrls: List<String>) = relayConfigMutex.withLock {
        val allRelays = relayRepository.getAllRelays().first()
        val existingUrls = allRelays.mapTo(HashSet()) { normalizeRelayUrl(it.url) }
        val discoveredCount = allRelays.count { it.isDiscovered }
        val budget = (MAX_TOTAL_DISCOVERED_RELAYS - discoveredCount).coerceAtLeast(0)

        val toAdd = selectNewDiscoverableRelayUrls(
            outboxRelayUrls = candidateUrls,
            existingUrls = existingUrls,
            budget = budget
        )

        toAdd.forEachIndexed { index, url ->
            relayRepository.addRelay(
                Relay(
                    id = RelayIdGenerator.create(System.currentTimeMillis() + index),
                    url = url,
                    isEnabled = true,
                    // isReadEnabled/isReadActive stay false — they exclusively reflect a genuine
                    // kind:10002 inbox declaration now (see applyRelayListToLocalConfig's doc
                    // comment). isDiscovered alone is what makes this relay eligible for feed/
                    // inbox/search reads (see canApplyChannelToRelay); it's still fully usable,
                    // just no longer misclassified as the user's own configured inbox in the UI.
                    isReadEnabled = false,
                    isReadActive = false,
                    isWriteEnabled = false,
                    isWriteActive = false,
                    isOnion = url.contains(".onion", ignoreCase = true),
                    isDiscovered = true
                )
            )
        }
    }

    override fun wasRelayListContentApplied(encryptedContent: String): Boolean =
        encryptedContent in appliedRelayListContent

    override fun markRelayListContentApplied(encryptedContent: String) {
        appliedRelayListContent.add(encryptedContent)
    }

    override fun clearAll() {
        relayLists.clear()
        dmRelayLists.clear()
        serverLists.clear()
        _searchRelayLists.value = emptyMap()
        _indexRelayLists.value = emptyMap()
        appliedRelayListContent.clear()
        profiles.clear()
        repoScope.launch(Dispatchers.IO) {
            userProfileDao.deleteAll()
        }
        logger.d { "Cleared in-memory user cache" }
    }

    override fun cachedProfileCount(): Int = profiles.size

    override fun cachedRelayListCount(): Int = relayLists.size + dmRelayLists.size + serverLists.size

    override fun observeProfile(pubkey: String): Flow<UserProfile?> {
        val normalizedPubkey = pubkey.lowercase()
        return userProfileDao.observeProfile(normalizedPubkey).map { it?.toDomain() }
    }

    override suspend fun isProfileFresh(pubkey: String): Boolean {
        val freshThreshold = thresholdMillisBefore(PROFILE_FRESH_TTL_MS)
        return withContext(Dispatchers.IO) {
            userProfileDao.isFresh(pubkey, freshThreshold) > 0
        }
    }

    override fun isSignedInUser(pubkey: String): Boolean = isCurrentUser(pubkey)

    private fun isCurrentUser(pubkey: String): Boolean {
        return normalizeCurrentUserPubkey(userPreferences.getPublicKey()) == pubkey.lowercase()
    }

    private fun normalizedCurrentUserPubkey(): String? {
        return normalizeCurrentUserPubkey(userPreferences.getPublicKey())
    }

    private fun scheduleStaleProfileCleanup() {
        repoScope.launch {
            while (true) {
                delay(24 * 60 * 60 * 1000L) // every 24 hours
                pruneStaleData()
            }
        }
    }

    /**
     * Sweeps [profiles] (Room-backed) plus the pure in-memory [relayLists]/[dmRelayLists]/
     * [serverLists] maps of anyone but the signed-in user, dropping entries older than
     * [STALE_PROFILE_TTL_MS]. Extracted from [scheduleStaleProfileCleanup]'s loop body so a
     * real-memory-pressure trim (see `TrimMemoryCachesUseCase`) can trigger the same sweep
     * on demand instead of waiting up to 24h for it.
     */
    override suspend fun pruneStaleData() = withContext(Dispatchers.IO) {
        val threshold = thresholdMillisBefore(STALE_PROFILE_TTL_MS)
        val currentUserPubkey = normalizedCurrentUserPubkey()
        val deleted = userProfileDao.deleteStaleProfiles(threshold, currentUserPubkey)
        if (deleted > 0) {
            profiles.keys.removeIf { it != currentUserPubkey }
        }
        if (deleted > 0) {
            logger.d { "Cleaned up $deleted stale profiles" }
        }
        // relayLists/dmRelayLists are pure in-memory (unlike profiles, which is
        // Room-backed) — populated by every NIP-65/NIP-17 relay-list event ever seen
        // this session and otherwise only cleared by full logout. Prune the same way,
        // using each entry's own event timestamp (lastUpdated, Unix seconds) against
        // the same 7-day threshold.
        val thresholdSeconds = threshold / 1000
        relayLists.entries.removeIf { (pubkey, list) ->
            pubkey != currentUserPubkey && list.lastUpdated < thresholdSeconds
        }
        dmRelayLists.entries.removeIf { (pubkey, list) ->
            pubkey != currentUserPubkey && list.lastUpdated < thresholdSeconds
        }
        serverLists.entries.removeIf { (pubkey, list) ->
            pubkey != currentUserPubkey && list.lastUpdated < thresholdSeconds
        }
        Unit
    }

    private suspend fun applyRelayListToLocalConfig(relayList: RelayListMetadata): Unit = relayConfigMutex.withLock {
        // saveRelayList's compute() guarantees relayLists[pubkey] always holds the most recently
        // accepted list, but not that this coroutine runs before one scheduled for a newer list —
        // mutex acquisition order isn't recency order. If a newer list has since won the map slot,
        // this call is provably stale (superseded before it got the lock) and must not mutate
        // relayRepository, or it would wrongly strip/delete relays the newer list just declared.
        if (relayLists[relayList.pubkey] != relayList) return@withLock
        val outbox = relayList.getOutboxRelays()
            .map(::normalizeRelayUrl)
            .filter { it.isNotBlank() }
            .toSet()
        val inbox = relayList.getInboxRelays()
            .map(::normalizeRelayUrl)
            .filter { it.isNotBlank() }
            .toSet()
        val desired = (outbox + inbox)

        if (desired.isEmpty()) return

        val existing = relayRepository.getAllRelays().first()
        val existingByUrl = existing
            .groupBy { normalizeRelayUrl(it.url) }
            .mapValues { (_, relaysWithSameUrl) ->
                val canonical = relaysWithSameUrl.first()
                if (relaysWithSameUrl.size > 1) {
                    val mergedRead = relaysWithSameUrl.any { it.isReadEnabled }
                    val mergedWrite = relaysWithSameUrl.any { it.isWriteEnabled }
                    val mergedDm = relaysWithSameUrl.any { it.isDmEnabled }
                    val mergedRelay = canonical.copy(
                        url = normalizeRelayUrl(canonical.url),
                        isEnabled = mergedRead || mergedWrite || mergedDm,
                        isReadEnabled = mergedRead,
                        isReadActive = mergedRead,
                        isWriteEnabled = mergedWrite,
                        isWriteActive = mergedWrite,
                        isDmEnabled = mergedDm,
                        isDmActive = mergedDm,
                        isOnion = canonical.url.contains(".onion", ignoreCase = true)
                    )
                    relayRepository.updateRelay(mergedRelay)
                    relaysWithSameUrl.drop(1).forEach { duplicate ->
                        relayRepository.removeRelay(duplicate.id)
                    }
                    mergedRelay
                } else {
                    canonical
                }
            }
            .toMutableMap()

        desired.forEachIndexed { index, relayUrl ->
            val normalizedUrl = normalizeRelayUrl(relayUrl)
            val isWrite = relayUrl in outbox
            val isRead = relayUrl in inbox
            val current = existingByUrl[normalizedUrl]

            if (current == null) {
                val newRelay = Relay(
                        id = RelayIdGenerator.create(System.currentTimeMillis() + index),
                        url = normalizedUrl,
                        isEnabled = true,
                        isReadEnabled = isRead,
                        isReadActive = isRead,
                        isWriteEnabled = isWrite,
                        isWriteActive = isWrite,
                        isOnion = normalizedUrl.contains(".onion", ignoreCase = true)
                    )
                relayRepository.addRelay(newRelay)
                existingByUrl[normalizedUrl] = newRelay
            } else {
                val keepDmEnabled = current.isDmEnabled
                val keepDmActive = current.isDmActive
                relayRepository.updateRelay(
                    current.copy(
                        url = normalizedUrl,
                        isEnabled = isRead || isWrite || keepDmEnabled,
                        isReadEnabled = isRead,
                        isReadActive = isRead,
                        isWriteEnabled = isWrite,
                        isWriteActive = isWrite,
                        isDmEnabled = keepDmEnabled,
                        isDmActive = keepDmActive,
                        isOnion = normalizedUrl.contains(".onion", ignoreCase = true),
                        // A relay that was only auto-discovered (e.g. from a followed author's
                        // outbox) is now also the current user's own declared relay — it must
                        // stop being classified as merely "discovered" or RelayConfigScreen's
                        // Outbox/Inbox sections (which skip isDiscovered rows entirely) would
                        // never show it, leaving it stuck in the Discovered section forever.
                        isDiscovered = false
                    )
                )
            }
        }

        // NIP-65 is authoritative for the user's own read/write roles: a relay that was
        // previously enabled (e.g. a bootstrap default, or a role from an older relay list)
        // but is absent from this fresh declaration is no longer part of the user's real relay
        // list and must stop being treated as one — otherwise defaults just keep accumulating
        // instead of being replaced. isDiscovered relays are a separate mechanism (followed
        // authors' outbox coverage, not the current user's own list) and are left untouched;
        // isDmEnabled/isSearchEnabled/isIndexEnabled (orthogonal roles from other kinds) are
        // preserved either way — only removed entirely once no role at all is left.
        existingByUrl.values.toList().forEach { relay ->
            val normalizedUrl = normalizeRelayUrl(relay.url)
            if (relay.isDiscovered || normalizedUrl in desired) return@forEach
            if (!relay.isReadEnabled && !relay.isWriteEnabled) return@forEach

            if (relay.isDmEnabled || relay.isSearchEnabled || relay.isIndexEnabled) {
                relayRepository.updateRelay(
                    relay.copy(
                        isEnabled = true,
                        isReadEnabled = false,
                        isReadActive = false,
                        isWriteEnabled = false,
                        isWriteActive = false
                    )
                )
            } else {
                relayRepository.removeRelay(relay.id)
            }
        }
    }

    /**
     * Applies the user's kind 10050 DM relay list to the local relay config.
     * Adds missing relays with isDmEnabled=true and enables the DM flag on existing ones. Always
     * force-clears isReadEnabled/isWriteEnabled on the relays it touches — those exclusively
     * reflect a genuine kind:10002 declaration, applied independently by
     * [applyRelayListToLocalConfig] — so a relay this function later re-declares as read/write
     * (because it's also the user's real inbox/outbox) is corrected again on that function's own
     * next run, not by this one.
     */
    private suspend fun applyDmRelayListToLocalConfig(dmRelayList: DmRelayList): Unit = relayConfigMutex.withLock {
        val dmUrls = dmRelayList.relays
            .map(::normalizeRelayUrl)
            .filter { it.isNotBlank() }
            .toSet()

        if (dmUrls.isEmpty()) return

        val existing = relayRepository.getAllRelays().first()
        val existingByUrl = existing.associateBy { normalizeRelayUrl(it.url) }.toMutableMap()

        dmUrls.forEachIndexed { index, normalizedUrl ->
            val current = existingByUrl[normalizedUrl]
            if (current == null) {
                val newRelay = Relay(
                    id = RelayIdGenerator.create(System.currentTimeMillis() + index),
                    url = normalizedUrl,
                    isEnabled = true,
                    isReadEnabled = false,
                    isReadActive = false,
                    isWriteEnabled = false,
                    isWriteActive = false,
                    isDmEnabled = true,
                    isDmActive = true,
                    isOnion = normalizedUrl.contains(".onion", ignoreCase = true)
                )
                relayRepository.addRelay(newRelay)
                existingByUrl[normalizedUrl] = newRelay
            } else if (!current.isDmEnabled) {
                relayRepository.updateRelay(
                    current.copy(
                        url = normalizedUrl,
                        isDmEnabled = true,
                        isDmActive = current.isEnabled,
                        isOnion = normalizedUrl.contains(".onion", ignoreCase = true),
                        // See applyRelayListToLocalConfig's matching comment — a merely-discovered
                        // relay that now carries an explicit role of the current user's own must
                        // stop being classified as "discovered" or it never surfaces in the DM
                        // section.
                        isDiscovered = false,
                        // Force-cleared, not just left as whatever `current` happened to carry —
                        // isReadEnabled/isWriteEnabled must exclusively reflect a genuine kind:10002
                        // declaration (see applyRelayListToLocalConfig's doc comment); a stale true
                        // inherited from e.g. a bootstrap default would otherwise leak this DM relay
                        // into the Inbox/Outbox sections too.
                        isReadEnabled = false,
                        isReadActive = false,
                        isWriteEnabled = false,
                        isWriteActive = false
                    )
                )
            }
        }

        // Same replace-not-add semantics as applyRelayListToLocalConfig, scoped to the DM role:
        // a relay that was DM-enabled but is absent from this fresh kind 10050 declaration is no
        // longer part of the user's DM relay list. Read/write roles (kind 10002, orthogonal) and
        // isDiscovered relays are left untouched.
        existingByUrl.values.toList().forEach { relay ->
            val normalizedUrl = normalizeRelayUrl(relay.url)
            if (relay.isDiscovered || normalizedUrl in dmUrls) return@forEach
            if (!relay.isDmEnabled) return@forEach

            if (relay.isReadEnabled || relay.isWriteEnabled || relay.isSearchEnabled || relay.isIndexEnabled) {
                relayRepository.updateRelay(relay.copy(isDmEnabled = false, isDmActive = false))
            } else {
                relayRepository.removeRelay(relay.id)
            }
        }
    }

    /**
     * Applies the user's kind 10007 search relay list to the local relay config. Same shape as
     * [applyDmRelayListToLocalConfig]: adds missing relays with isSearchEnabled=true and flips the
     * flag on existing ones, so RelayConfigScreen's Search section — sourced from the relay table
     * like Outbox/Inbox/DM — reflects the declaration reliably instead of only when
     * addDiscoveredRelays happens to insert a genuinely new row (the previous behavior: a
     * declared search relay that already existed for another reason never triggered any write,
     * so the list silently never appeared in the UI). Force-clears read/write (see
     * [applyDmRelayListToLocalConfig]'s doc comment); does not touch the DM role.
     */
    private suspend fun applySearchRelayListToLocalConfig(list: SearchRelaysList): Unit = relayConfigMutex.withLock {
        val urls = list.relayUrls.map(::normalizeRelayUrl).filter { it.isNotBlank() }.toSet()
        if (urls.isEmpty()) return

        val existing = relayRepository.getAllRelays().first()
        val existingByUrl = existing.associateBy { normalizeRelayUrl(it.url) }.toMutableMap()

        urls.forEachIndexed { index, normalizedUrl ->
            val current = existingByUrl[normalizedUrl]
            if (current == null) {
                val newRelay = Relay(
                    id = RelayIdGenerator.create(System.currentTimeMillis() + index),
                    url = normalizedUrl,
                    isEnabled = true,
                    isReadEnabled = false,
                    isReadActive = false,
                    isWriteEnabled = false,
                    isWriteActive = false,
                    isSearchEnabled = true,
                    isSearchActive = true,
                    isOnion = normalizedUrl.contains(".onion", ignoreCase = true)
                )
                relayRepository.addRelay(newRelay)
                existingByUrl[normalizedUrl] = newRelay
            } else if (!current.isSearchEnabled) {
                relayRepository.updateRelay(
                    current.copy(
                        url = normalizedUrl,
                        isSearchEnabled = true,
                        isSearchActive = current.isEnabled,
                        isOnion = normalizedUrl.contains(".onion", ignoreCase = true),
                        // See applyRelayListToLocalConfig's matching comment.
                        isDiscovered = false,
                        // See applyDmRelayListToLocalConfig's matching comment — force-cleared,
                        // not preserved, so a stale true never leaks this relay into Inbox/Outbox.
                        isReadEnabled = false,
                        isReadActive = false,
                        isWriteEnabled = false,
                        isWriteActive = false
                    )
                )
            }
        }

        // Same replace-not-add semantics as applyDmRelayListToLocalConfig, scoped to the search
        // role: a relay that was search-enabled but is absent from this fresh kind 10007
        // declaration is no longer part of the user's search relay list.
        existingByUrl.values.toList().forEach { relay ->
            val normalizedUrl = normalizeRelayUrl(relay.url)
            if (relay.isDiscovered || normalizedUrl in urls) return@forEach
            if (!relay.isSearchEnabled) return@forEach

            if (relay.isReadEnabled || relay.isWriteEnabled || relay.isDmEnabled || relay.isIndexEnabled) {
                relayRepository.updateRelay(relay.copy(isSearchEnabled = false, isSearchActive = false))
            } else {
                relayRepository.removeRelay(relay.id)
            }
        }
    }

    /**
     * Applies the user's kind 10086 index relay list to the local relay config. Same treatment as
     * [applySearchRelayListToLocalConfig], for the index role instead of search.
     */
    private suspend fun applyIndexRelayListToLocalConfig(list: IndexRelaysList): Unit = relayConfigMutex.withLock {
        val urls = list.relayUrls.map(::normalizeRelayUrl).filter { it.isNotBlank() }.toSet()
        if (urls.isEmpty()) return

        val existing = relayRepository.getAllRelays().first()
        val existingByUrl = existing.associateBy { normalizeRelayUrl(it.url) }.toMutableMap()

        urls.forEachIndexed { index, normalizedUrl ->
            val current = existingByUrl[normalizedUrl]
            if (current == null) {
                val newRelay = Relay(
                    id = RelayIdGenerator.create(System.currentTimeMillis() + index),
                    url = normalizedUrl,
                    isEnabled = true,
                    isReadEnabled = false,
                    isReadActive = false,
                    isWriteEnabled = false,
                    isWriteActive = false,
                    isIndexEnabled = true,
                    isIndexActive = true,
                    isOnion = normalizedUrl.contains(".onion", ignoreCase = true)
                )
                relayRepository.addRelay(newRelay)
                existingByUrl[normalizedUrl] = newRelay
            } else if (!current.isIndexEnabled) {
                relayRepository.updateRelay(
                    current.copy(
                        url = normalizedUrl,
                        isIndexEnabled = true,
                        isIndexActive = current.isEnabled,
                        isOnion = normalizedUrl.contains(".onion", ignoreCase = true),
                        // See applyRelayListToLocalConfig's matching comment.
                        isDiscovered = false,
                        // See applyDmRelayListToLocalConfig's matching comment — force-cleared,
                        // not preserved, so a stale true never leaks this relay into Inbox/Outbox.
                        isReadEnabled = false,
                        isReadActive = false,
                        isWriteEnabled = false,
                        isWriteActive = false
                    )
                )
            }
        }

        existingByUrl.values.toList().forEach { relay ->
            val normalizedUrl = normalizeRelayUrl(relay.url)
            if (relay.isDiscovered || normalizedUrl in urls) return@forEach
            if (!relay.isIndexEnabled) return@forEach

            if (relay.isReadEnabled || relay.isWriteEnabled || relay.isDmEnabled || relay.isSearchEnabled) {
                relayRepository.updateRelay(relay.copy(isIndexEnabled = false, isIndexActive = false))
            } else {
                relayRepository.removeRelay(relay.id)
            }
        }
    }

    /**
     * Auto-verify NIP-05 identifier for the profile if not yet verified.
     * Runs asynchronously in background to avoid blocking saveProfile().
     * SSOT: verification result is persisted back to UserRepository when complete.
     */
    private fun triggerNip05VerificationIfNeeded(profile: UserProfile) {
        val nip05 = profile.nip05?.takeIf { it.isNotBlank() } ?: return

        // Only verify if NotAvailable or Pending (avoid re-verifying Verified/Failed)
        if (profile.nip05VerificationState != Nip05VerificationState.NotAvailable &&
            profile.nip05VerificationState != Nip05VerificationState.Pending) {
            return
        }

        repoScope.launch {
            try {
                // Set state to Pending while verifying
                val pendingProfile = profile.copy(nip05VerificationState = Nip05VerificationState.Pending)
                userProfileDao.insertProfile(pendingProfile.toEntity())
                // Keep the in-memory cache (checked first by getProfile/getProfiles) in sync,
                // otherwise every later lookup keeps returning the stale NotAvailable snapshot
                // even after Room and profileFlow have moved on.
                profiles[profile.pubkey.lowercase()] = pendingProfile
                _profileFlow.tryEmit(pendingProfile)

                // Verify NIP-05 via Nip05Repository
                val verificationState = nip05Repository.verifyNip05(nip05, profile.pubkey)
                    .getOrNull() ?: Nip05VerificationState.Failed

                // Save the verified profile
                val verifiedProfile = profile.copy(nip05VerificationState = verificationState)
                userProfileDao.insertProfile(verifiedProfile.toEntity())
                profiles[profile.pubkey.lowercase()] = verifiedProfile
                _profileFlow.tryEmit(verifiedProfile)

                logger.d { "NIP-05 verification completed: $verificationState for nip05" }
            } catch (e: Exception) {
                logger.d { "NIP-05 verification failed: ${scrubThrowableMessageForLogs(e)}" }
            }
        }
    }
}

internal fun normalizeCurrentUserPubkey(rawPubkey: String?): String? {
    return rawPubkey
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() && it.length == 64 && it != "0".repeat(64) }
}

