package com.umbra.app.domain.repository

import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip51.IndexRelaysList
import com.umbra.app.domain.nip51.SearchRelaysList
import com.umbra.app.domain.nip65.RelayListMetadata
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.profile.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Repository contract for managing user profile and relay-list caches.
 */
interface UserRepository {
    suspend fun getProfile(pubkey: String): UserProfile?
    suspend fun getProfiles(pubkeys: List<String>): List<UserProfile>

    /** [getProfiles] pre-keyed by lowercased pubkey — the shape every caller needs it in. */
    suspend fun getProfilesByPubkey(pubkeys: Collection<String>): Map<String, UserProfile> =
        getProfiles(pubkeys.toList()).associateBy { it.pubkey.lowercase() }
    /** True if [pubkey] is the signed-in user's own pubkey. */
    fun isSignedInUser(pubkey: String): Boolean
    /**
     * Local-cache-only prefix search over name/displayName, for @-mention autocomplete while
     * composing — never triggers a relay fetch. [query] is the text typed after "@" (already
     * stripped of the "@" itself).
     */
    suspend fun searchLocalProfiles(query: String, limit: Int = 8): List<UserProfile>
    fun saveProfile(profile: UserProfile)
    // Room-backed, so it converges on the latest state (including async NIP-05 verification
    // results) regardless of whether a collector was listening at the moment of the write —
    // unlike profileFlow, which only reaches subscribers who are actively collecting.
    fun observeProfile(pubkey: String): Flow<UserProfile?>
    fun getRelayList(pubkey: String): RelayListMetadata?
    fun saveRelayList(relayList: RelayListMetadata)
    /**
     * Auto-adds any not-yet-configured relay from [relayUrls] as a read-only "discovered" relay
     * — same mechanism [saveRelayList] already uses for a followed author's outbox relays, just
     * fed from relay hints instead (NIP-19 nprofile1/nevent1's TLV type 1, or naddr1's), so a
     * link/mention/quote pointing at content we don't have yet stands a chance of actually being
     * findable instead of only ever checking relays we already happened to have configured.
     * Best-effort and asynchronous — a relay added here isn't connected in time to help whatever
     * fetch triggered this call; it helps subsequent ones.
     */
    fun discoverRelayHints(relayUrls: List<String>)
    fun getDmRelayList(pubkey: String): DmRelayList?
    fun saveDmRelayList(dmRelayList: DmRelayList)
    /** BUD-03 kind:10063 — the Blossom servers [pubkey] uploads their blobs to, priority-ordered. */
    fun getServerList(pubkey: String): UserServerList?
    /** Last-write-wins by [UserServerList.lastUpdated], same staleness guard as [saveRelayList]. */
    fun saveServerList(serverList: UserServerList)
    /**
     * Caches [list] (last-write-wins by [SearchRelaysList.updatedAt], same staleness guard as
     * [saveRelayList]). For the current user, applies it as a first-class Relay role
     * (isSearchEnabled/isSearchActive — see UserRepositoryImpl.applySearchRelayListToLocalConfig)
     * so RelayConfigScreen's Search section reflects it. For any other tracked author, feeds
     * every relay URL into the same discovered-relay pool [discoverRelayHints] uses — a
     * search-relay declaration from any user is still a real relay worth having reachable.
     */
    fun saveSearchRelaysList(list: SearchRelaysList)
    /** Same treatment as [saveSearchRelaysList], for kind:10086 instead of kind:10007. */
    fun saveIndexRelaysList(list: IndexRelaysList)
    /**
     * Reactive view of the cached search-relays list for [pubkey] — used by
     * RelayConfigViewModel to notice a fresh [SearchRelaysList.encryptedContent] worth decrypting
     * (via Amber's nip44_decrypt) as soon as it arrives, not just at the moment the screen opened.
     */
    fun observeSearchRelaysList(pubkey: String): Flow<SearchRelaysList?>
    /** Same as [observeSearchRelaysList], for kind:10086. */
    fun observeIndexRelaysList(pubkey: String): Flow<IndexRelaysList?>
    /**
     * Applies a successfully nip44_decrypt-ed search-relays list for the *current* user — the
     * merged public-tag ∪ decrypted-private-tag URL set — as the same first-class Relay role
     * [saveSearchRelaysList] already applies for public-only declarations. No-ops if [pubkey]
     * isn't the current user (decryption is only ever attempted for one's own list in the first
     * place, but this stays defensive rather than trusting the caller).
     */
    suspend fun applyDecryptedSearchRelays(pubkey: String, relayUrls: Set<String>)
    /** Same as [applyDecryptedSearchRelays], for kind:10086. */
    suspend fun applyDecryptedIndexRelays(pubkey: String, relayUrls: Set<String>)
    /**
     * True once [encryptedContent] (a NIP-44 ciphertext from a kind:10007/10086 event, i.e.
     * [SearchRelaysList.encryptedContent]/[IndexRelaysList.encryptedContent]) has already been
     * successfully decrypted and applied this session — lets RelayConfigViewModel skip
     * re-prompting Amber for content it's already resolved. Needed because
     * [observeSearchRelaysList]/[observeIndexRelaysList] replay their latest cached value to any
     * new collector (a StateFlow), so a ViewModel recreated by leaving and reopening the relay
     * screens would otherwise see the same already-handled content as "never seen before" every
     * time, re-prompting Amber for nothing.
     */
    fun wasRelayListContentApplied(encryptedContent: String): Boolean
    /** Marks [encryptedContent] as resolved — see [wasRelayListContentApplied]. */
    fun markRelayListContentApplied(encryptedContent: String)
    fun clearAll()
    val profileFlow: SharedFlow<UserProfile>
    suspend fun isProfileFresh(pubkey: String): Boolean

    /**
     * Prunes cached profiles/relay-lists/DM-relay-lists/server-lists for anyone but the signed-in
     * user that haven't been updated in a while. Normally runs on a 24h timer; also invoked
     * on-demand under real OS memory pressure (see `UmbraApp.onTrimMemory`) so a long session
     * doesn't have to wait up to 24h for its first sweep. Default is a no-op.
     */
    suspend fun pruneStaleData() {}

    /** Number of cached profiles — see ResourceUsageRepositoryImpl. */
    fun cachedProfileCount(): Int = 0

    /** Number of cached relay-list/DM-relay-list/server-list entries combined — see ResourceUsageRepositoryImpl. */
    fun cachedRelayListCount(): Int = 0
}

