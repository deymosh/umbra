package com.umbra.app.domain.nip01

import androidx.compose.runtime.Immutable
import com.umbra.app.domain.feed.FilterDefaults
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Nostr Event model (NIP-01)
 * Base protocol for all events in the Nostr network
 *
 * Event structure:
 * - id: SHA256 hash of the event
 * - pubkey: Public key of event creator
 * - created_at: Unix timestamp of creation
 * - kind: Event type (0=metadata, 1=text note, 2=relay recommendation, 3=contact list, etc.)
 * - tags: Array of tags (replies, mentions, hashtags, etc.)
 * - content: Event content (text, serialized JSON, etc.)
 * - sig: Schnorr signature
 */
@Serializable
@Immutable
data class Event(
    // Event unique identifier (SHA256 hash)
    val id: String,

    // Public key of the event creator (hex encoded)
    val pubkey: String,

    // Unix timestamp of event creation
    val createdAt: Long,

    // Event kind (NIP-01: 0=metadata, 1=text note, 3=contact list, etc.)
    val kind: Int,

    // Event tags (for replies, mentions, hashtags, etc.)
    val tags: List<List<String>> = emptyList(),

    // Event content (text, JSON, etc.)
    val content: String = "",

    // Schnorr signature (hex encoded)
    val sig: String = "",

    // User-friendly author name (cached)
    val authorName: String? = null,

    // Whether we are following this author
    val isFollowing: Boolean = false
) {
    /**
     * Add relay hint to an event tag (NIP-01, NIP-65)
     * Relay hints suggest where to find referenced content
     *
     * @param tagName Tag name (e.g., "e", "p", "a")
     * @param tagValue Tag value (event ID, pubkey, etc)
     * @param relayUrl Optional relay URL hint
     * @return Tag as list to add to event.tags
     */
    companion object {
        // Event kind constants (NIP-01 and extensions)
        const val KIND_METADATA = 0
        const val KIND_TEXT_NOTE = 1
        const val KIND_RELAY_RECOMMENDATION = 2
        const val KIND_CONTACT_LIST = 3
        const val KIND_ENCRYPTED_DM = 4
        const val KIND_EVENT_DELETION = 5
        const val KIND_REPOST = 6       // NIP-18
        const val KIND_REACTION = 7     // NIP-25
        const val KIND_BADGE_AWARD = 8  // NIP-58
        const val KIND_CHAT_MESSAGE = 9 // NIP-C7
        const val KIND_GROUP_CHAT_THREADED_REPLY = 10 // NIP-29 (deprecated)
        const val KIND_THREAD = 11 // NIP-7D
        const val KIND_GROUP_THREAD_REPLY = 12 // NIP-29 (deprecated)
        const val KIND_SEAL = 13 // NIP-59
        const val KIND_DIRECT_MESSAGE = 14 // NIP-17
        const val KIND_FILE_MESSAGE = 15 // NIP-17
        const val KIND_CLIENT_AUTH = 22242 // NIP-42
        const val KIND_GENERIC_REPOST = 16 // NIP-18
        const val KIND_WEBSITE_REACTION = 17 // NIP-25
        const val KIND_PICTURE = 20 // NIP-68
        const val KIND_VIDEO_EVENT = 21 // NIP-71
        const val KIND_SHORT_FORM_PORTRAIT_VIDEO_EVENT = 22 // NIP-71
        const val KIND_LONG_FORM = 30023   // NIP-23 (Articles) — was incorrectly 23; no NIP uses kind 23
        const val KIND_PUBLIC_MESSAGE = 24 // NIP-A4
        const val KIND_INTERNAL_REFERENCE = 30 // NKBIP-03
        const val KIND_COMMENT = 1111 // NIP-22
        const val KIND_ZAP_REQUEST = 9734      // NIP-57
        const val KIND_ZAP_RECEIPT = 9735      // NIP-57
        const val KIND_MUTED_USERS = 10000    // NIP-51
        const val KIND_PINNED_EVENTS = 10001  // NIP-51
        const val KIND_RELAY_LIST_METADATA = 10002 // NIP-65 / NIP-51
        const val KIND_DM_RELAY_LIST = 10050       // NIP-17 / NIP-51 — preferred DM receive relays
        const val KIND_BOOKMARK_LIST = 10003       // NIP-51 — uncategorized notes/articles to save
        const val KIND_COMMUNITIES_LIST = 10004    // NIP-51 / NIP-72 — communities the user belongs to
        const val KIND_BLOCKED_RELAYS = 10006      // NIP-51 — relays clients should never connect to
        const val KIND_SEARCH_RELAYS = 10007       // NIP-51 — relays to use for search queries
        // Not yet in the ratified NIP-51 list (nostr-protocol/nips), but same "relay" tag shape —
        // used by other clients as relays a client should query when browsing/indexing content,
        // distinct from KIND_SEARCH_RELAYS' NIP-50-search-specific use.
        const val KIND_INDEX_RELAYS = 10086
        const val KIND_INTERESTS_LIST = 10015      // NIP-51 — hashtags/interest sets the user follows
        const val KIND_BLOSSOM_AUTH = 24242        // Blossom (BUD-01) — HTTP upload/list/delete authorization
        const val KIND_BLOSSOM_SERVER_LIST = 10063 // Blossom (BUD-03) — user's Blossom server list
        // Deprecated: moved to FilterDefaults to centralize defaults

        fun createTag(tagName: String, tagValue: String, relayUrl: String? = null): List<String> {
            return if (relayUrl != null) {
                listOf(tagName, tagValue, relayUrl)
            } else {
                listOf(tagName, tagValue)
            }
        }

        /**
         * Create a reply tag structure (NIP-10)
         */
        fun createReplyTag(eventId: String, relayUrl: String? = null): List<String> {
            return createTag("e", eventId, relayUrl)
        }

        /**
         * Create a mention tag (NIP-10, NIP-08)
         */
        fun createMentionTag(pubkey: String, relayUrl: String? = null): List<String> {
            return createTag("p", pubkey, relayUrl)
        }

        /**
         * Best-effort NIP-01 parse from a JSON object already known to represent an event —
         * tolerant of missing/malformed fields (defaults id/pubkey/content/sig to "",
         * createdAt/kind to 0, tags to emptyList()) rather than throwing. A relay is untrusted
         * input; EventCrypto.verifyEvent (data/ layer, unreachable from domain/) is what actually
         * rejects a malformed/forged event downstream — this parse step must not be the thing
         * that crashes the message-handling pipeline over a single bad relay frame.
         */
        fun fromJsonObject(obj: JsonObject): Event = Event(
            id = (obj["id"] as? JsonPrimitive)?.content ?: "",
            pubkey = (obj["pubkey"] as? JsonPrimitive)?.content ?: "",
            createdAt = (obj["created_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            kind = (obj["kind"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            tags = (obj["tags"] as? JsonArray)?.map { tag ->
                (tag as? JsonArray)?.map { t -> (t as? JsonPrimitive)?.content ?: "" } ?: emptyList()
            } ?: emptyList(),
            content = (obj["content"] as? JsonPrimitive)?.content ?: "",
            sig = (obj["sig"] as? JsonPrimitive)?.content ?: ""
        )

        /** Parses a raw JSON string — null only when it isn't valid JSON or isn't a JSON object. */
        fun fromJson(json: String): Event? =
            (runCatching { JsonUtils.NostrJson.parseToJsonElement(json) }.getOrNull() as? JsonObject)
                ?.let(::fromJsonObject)
    }

    /**
     * Extract tag value by name
     * @param name Tag name (e.g., "e", "p", "t")
     * @return First matching tag value or null
     */
    fun getTagValue(name: String): String? {
        return tags.find { it.isNotEmpty() && it[0] == name }?.getOrNull(1)
    }

    /**
     * Extract all tag values by name
     * @param name Tag name (e.g., "p" for all mentions)
     * @return List of matching tag values
     */
    fun getTagValues(name: String): List<String> {
        return tags.filter { it.isNotEmpty() && it[0] == name }
            .mapNotNull { it.getOrNull(1) }
    }

    /**
     * Check if this event is a reply to another event
     */
    fun isReply(): Boolean {
        val eTags = tags.filter { it.isNotEmpty() && it[0] == "e" }
        if (eTags.isEmpty()) return false

        val markers = eTags.mapNotNull { resolveETagMarker(it) }
        if (markers.any { it == "reply" || it == "root" }) return true

        // Legacy/unmarked e-tags are commonly used for replies in kind 1 notes.
        return eTags.any { resolveETagMarker(it) == null }
    }

    /**
     * Get root event ID (oldest "e" tag)
     */
    fun getRootEventId(): String? {
        val eTags = tags.filter { it.isNotEmpty() && it[0] == "e" }
        val explicitRoot = eTags.firstOrNull { resolveETagMarker(it) == "root" }?.getOrNull(1)
        if (!explicitRoot.isNullOrBlank()) return explicitRoot

        return eTags.firstOrNull { resolveETagMarker(it) != "mention" }?.getOrNull(1)
    }

    /**
     * Get parent event ID (last "e" tag)
     */
    fun getParentEventId(): String? {
        val eTags = tags.filter { it.isNotEmpty() && it[0] == "e" }
        val explicitReply = eTags.lastOrNull { resolveETagMarker(it) == "reply" }?.getOrNull(1)
        if (!explicitReply.isNullOrBlank()) return explicitReply

        // Legacy fallback: latest non-mention e-tag.
        return eTags.lastOrNull { resolveETagMarker(it) != "mention" }?.getOrNull(1)
    }

    private fun resolveETagMarker(eTag: List<String>): String? {
        val candidate3 = eTag.getOrNull(3)?.trim()?.lowercase()
        if (candidate3 == "reply" || candidate3 == "root" || candidate3 == "mention") {
            return candidate3
        }

        // Some clients emit 3-element e-tags where the 3rd field is the marker.
        val candidate2 = eTag.getOrNull(2)?.trim()?.lowercase()
        if (candidate2 == "reply" || candidate2 == "root" || candidate2 == "mention") {
            return candidate2
        }

        return null
    }

    /**
     * Get all mentioned pubkeys
     */
    fun getMentionedPubkeys(): List<String> {
        return getTagValues("p")
    }

    /**
     * Get all hashtags
     */
    fun getHashtags(): List<String> {
        return tags.filter { it.isNotEmpty() && it[0] == "t" }
            .mapNotNull { it.getOrNull(1)?.lowercase() }
            .distinct()
    }

    fun hasAnyHashtag(candidates: Set<String>): Boolean {
        if (candidates.isEmpty()) return false
        val normalized = candidates.mapTo(HashSet(candidates.size)) { it.lowercase() }
        // Match when any hashtag equals or starts with a candidate (case-insensitive).
        return getHashtags().any { hashtag ->
            normalized.any { candidate -> hashtag.startsWith(candidate) }
        }
    }

    /**
     * Check if any tag *value* (second element of each tag) matches any candidate.
     * This treats tags generically (any tag name) and compares the value case-insensitively.
     */
    fun hasAnyTagValue(candidates: Set<String>): Boolean {
        if (candidates.isEmpty()) return false
        val normalized = candidates.mapTo(HashSet(candidates.size)) { it.lowercase() }
        return tags.asSequence()
            .mapNotNull { it.getOrNull(1)?.lowercase() }
            .any { it in normalized }
    }

    /**
     * Check if any tag *name* (first element) equals or starts with any candidate.
     * Comparison is case-insensitive; this implements the semantics for `excludedTags`.
     */
    fun hasAnyTagNamePrefix(candidates: Set<String>): Boolean {
        if (candidates.isEmpty()) return false
        val normalized = candidates.mapTo(HashSet(candidates.size)) { it.lowercase() }
        return tags.asSequence()
            .mapNotNull { it.getOrNull(0)?.lowercase() }
            .any { tagName -> normalized.any { candidate -> tagName.startsWith(candidate) } }
    }

    /**
     * True when the event has a tag whose name (element 0) doesn't follow the standard NIP-01
     * format: it contains "/" (a path namespace, always structurally non-standard) or starts with
     * a known external-protocol tag-name prefix (e.g. "gnostr-org/gnostr", "gnostr-test-matrix").
     *
     * [excludedTagNamePrefixes] defaults to the built-in hygiene baseline
     * (FilterDefaults.DEFAULT_EXCLUDED_TAG_NAME_PREFIXES) for callers with no access to the user's
     * live filter settings (e.g. ProfileScreen). A caller that does have the user's active
     * FeedFilter should pass its excludedTags here — or an empty set if it already re-checks
     * exclusion itself downstream (see FeedViewModel.matchesFilter) — so this default stays fully
     * user-overridable rather than unconditionally enforced. The "/" check always applies
     * regardless of the override: it's structural NIP-01 validity, not a content preference.
     */
    fun hasNonStandardTagNames(
        excludedTagNamePrefixes: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_TAG_NAME_PREFIXES
    ): Boolean {
        return tags.any { tag ->
            val name = tag.getOrNull(0) ?: return@any false
            val lowerName = name.lowercase()
            name.contains('/') || excludedTagNamePrefixes.any { lowerName.startsWith(it) }
        }
    }

    /**
     * True when a kind-1 note looks like standard client content and not protocol noise/proxy.
     * See [hasNonStandardTagNames] for how [excludedHashtags]/[excludedTagNamePrefixes]/
     * [excludedContentPrefixes] default to the built-in hygiene baseline but are meant to be
     * overridden by a caller with the user's own (fully editable) filter settings.
     */
    fun isUsefulClientNote(
        excludedHashtags: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_HASHTAGS,
        excludedTagNamePrefixes: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_TAG_NAME_PREFIXES,
        excludedContentPrefixes: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_CONTENT_PREFIXES
    ): Boolean {
        if (kind != KIND_TEXT_NOTE) return false
        if (hasAnyHashtag(excludedHashtags)) return false
        if (hasNonStandardTagNames(excludedTagNamePrefixes)) return false
        if (excludedContentPrefixes.any { content.startsWith(it, ignoreCase = true) }) return false
        return true
    }

    fun isTopLevelFeedNote(
        excludedHashtags: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_HASHTAGS,
        excludedTagNamePrefixes: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_TAG_NAME_PREFIXES,
        excludedContentPrefixes: Set<String> = FilterDefaults.DEFAULT_EXCLUDED_CONTENT_PREFIXES
    ): Boolean {
        if (!isUsefulClientNote(excludedHashtags, excludedTagNamePrefixes, excludedContentPrefixes)) return false
        if (isReply()) return false
        return true
    }

    /**
     * True when [createdAt] is ahead of the device clock — a relay can serve any `created_at` it
     * likes (NIP-01 doesn't bound it), and nothing else in the ingestion/verification path rejects
     * one ahead of "now". Zero tolerance by design: the feed must never show a future-dated note
     * before its own timestamp arrives, so no clock-drift allowance is made here — see
     * [com.umbra.app.ui.common.futureEventRecheckTicker] for how a note hidden by this check gets
     * re-evaluated and shown once its time passes. Deliberately not folded into [isTopLevelFeedNote]
     * or event verification/ingestion — this is a feed-display concern, not a correctness or
     * storage one. Applied to every passively-streamed note list (home feed, profile notes, thread
     * replies) but not to an anchor a user explicitly navigated to (a permalink/thread root), so
     * directly-requested content is never hidden as "not found".
     */
    fun isFromFuture(toleranceSeconds: Long = 0L): Boolean {
        return isTimestampFromFuture(createdAt, toleranceSeconds)
    }
}

/**
 * Same future-timestamp check as [Event.isFromFuture], usable against a bare timestamp (e.g. a
 * repost's own `repostedAt`) that isn't attached to an [Event] instance.
 */
fun isTimestampFromFuture(createdAt: Long, toleranceSeconds: Long = 0L): Boolean {
    return createdAt > (System.currentTimeMillis() / 1000L) + toleranceSeconds
}

/**
 * Filter for querying events from relays (NIP-01)
 * Used to subscribe to specific events
 */
@Serializable
data class EventFilter(
    // Event IDs to include
    val ids: Set<String> = emptySet(),

    // Author public keys
    val authors: Set<String> = emptySet(),

    // Event kinds
    val kinds: Set<Int> = emptySet(),

    // Since timestamp (Unix seconds)
    val since: Long? = null,

    // Until timestamp (Unix seconds)
    val until: Long? = null,

    // Limit number of results
    val limit: Int = 100,

    // Tag filters (e.g., #t contains hashtag, #e for event reactions)
    val tagFilters: Map<String, Set<String>> = emptyMap(),

    // Search query (optional, relay-dependent NIP-50)
    val search: String? = null,

    // Exclude replies (NIP-10)
    val excludeReplies: Boolean = false,

    // Exclude reposts (NIP-18)
    val excludeReposts: Boolean = false,

    // Only get events from these relays
    val relayUrls: Set<String> = emptySet()
) {
    /**
     * Create filter for text note feed
     */
    companion object {
        fun textNoteFeed(authors: Set<String> = emptySet(), limit: Int = 100): EventFilter {
            return EventFilter(
                kinds = setOf(Event.KIND_TEXT_NOTE),
                authors = authors,
                limit = limit
            )
        }

        fun reactions(eventId: String): EventFilter {
            return EventFilter(
                kinds = setOf(Event.KIND_REACTION),
                tagFilters = mapOf("e" to setOf(eventId)),
                limit = 100
            )
        }

        fun replies(eventId: String): EventFilter {
            return EventFilter(
                kinds = setOf(Event.KIND_TEXT_NOTE),
                tagFilters = mapOf("e" to setOf(eventId)),
                limit = 50
            )
        }
    }
}

/**
 * Nostr subscription request
 * Sent to relay to subscribe to events
 */
data class SubscriptionRequest(
    // Unique subscription ID
    val subscriptionId: String,

    // Filters for matching events
    val filters: List<EventFilter>
)

/**
 * Relay WebSocket message types
 */
sealed class RelayMessage {
    // Incoming event from relay
    data class EventMessage(val event: Event) : RelayMessage()

    // Relay acknowledgment
    data class NoticeMessage(val message: String) : RelayMessage()

    // End of stored events
    data class EndOfStoredEventsMessage(val subscriptionId: String) : RelayMessage()

    // Outgoing event from client
    data class PublishEventMessage(val event: Event) : RelayMessage()

    // Subscribe to events
    data class SubscribeMessage(val request: SubscriptionRequest) : RelayMessage()

    // Unsubscribe from subscription
    data class UnsubscribeMessage(val subscriptionId: String) : RelayMessage()

    // Error message
    data class ErrorMessage(val message: String) : RelayMessage()
}


