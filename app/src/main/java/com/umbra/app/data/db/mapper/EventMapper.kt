package com.umbra.app.data.db.mapper

import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.entities.EventTagEntity
import com.umbra.app.data.db.pojo.NoteWithProfile
import com.umbra.app.data.db.entities.UserProfileEntity
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.model.NoteView
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

private const val MAX_CONTENT_SIZE = 65_536 // 64 KB
private const val MAX_TAGS_COUNT = 500
private const val MAX_TAG_VALUE_SIZE = 1_024
private const val TAGS_CACHE_MAX_SIZE = 8_000

/**
 * Tag names extracted into [EventTagEntity] for indexed reverse lookups.
 * Relay-hint positions (index ≥ 2) are intentionally excluded — only the
 * primary value (index 1) is stored.
 */
private val INDEXED_TAG_NAMES = setOf("e", "p", "t", "a", "d")

fun Event.toEntity(): EventEntity {
    val safeTags = tags.take(MAX_TAGS_COUNT).map { tag ->
        tag.map { value ->
            if (value.length > MAX_TAG_VALUE_SIZE) value.take(MAX_TAG_VALUE_SIZE) else value
        }
    }

    return EventEntity(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        content = if (content.length > MAX_CONTENT_SIZE) content.take(MAX_CONTENT_SIZE) else content,
        sig = sig,
        tagsJson = JsonUtils.CompactJson.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            buildJsonArray {
                safeTags.forEach { tag ->
                    add(buildJsonArray {
                        tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
            }
        )
    )
}

/**
 * Nostr event IDs commit to the full event content (NIP-01: id = hash of serialized fields
 * including tags), so a given [EventEntity.id]'s tagsJson never changes — safe to memoize forever.
 * This matters because `observeRecentEvents()` (backing the own-archive feed merge, plus
 * MuteListRepositoryImpl/PinListRepositoryImpl/ContactListRepositoryImpl, which each independently
 * collect it) is a Room Flow: it re-runs and re-maps every row on ANY write to the `events` table,
 * not just when that specific row's tags changed. Without this cache, one incoming note while
 * online would re-parse up to 4000 rows' tag JSON, four times over (once per collector). Keyed by
 * event id rather than the JSON string itself since id is fixed-length (cheap to hash) and the
 * NIP-01 invariant above makes the mapping safe.
 */
private val tagsCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, List<List<String>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<List<String>>>): Boolean =
            size > TAGS_CACHE_MAX_SIZE
    }
)

/** Shared by [EventEntity.toDomain] and [NoteWithProfile.toNoteView] — same `tagsJson` column shape. */
private fun parseTagsJson(eventId: String, tagsJson: String): List<List<String>> {
    tagsCache[eventId]?.let { return it }
    val parsed = runCatching {
        val arr = JsonUtils.NostrJson.parseToJsonElement(tagsJson).jsonArray
        arr.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
    }.getOrDefault(emptyList())
    tagsCache[eventId] = parsed
    return parsed
}

/**
 * Called from EventRepositoryImpl.clearAllData() on logout/wipe — the cache above can hold tag
 * data from other pubkeys' events (replies, reactions, control-kind events persisted alongside
 * the signed-in user's own archive), so it must not outlive the session it was populated for.
 */
fun clearEventTagsCache() {
    tagsCache.clear()
}

fun EventEntity.toDomain(): Event = Event(
    id = id,
    pubkey = pubkey,
    createdAt = createdAt,
    kind = kind,
    content = content,
    sig = sig,
    tags = parseTagsJson(id, tagsJson)
)

/**
 * Extract indexable tag rows from this event for persisting in [EventTagEntity].
 *
 * Only tags with a name in [INDEXED_TAG_NAMES] and at least a primary value
 * (tag[1]) are extracted. The row primary key is (event_id, tag_name, tag_index)
 * where tag_index is the position of the tag in the event's tags array —
 * this keeps the PK unique even when the same tag name appears multiple times.
 */
fun Event.toTagEntities(): List<EventTagEntity> {
    return tags.mapIndexedNotNull { tagIdx, tag ->
        val tagName = tag.getOrNull(0) ?: return@mapIndexedNotNull null
        val tagValue = tag.getOrNull(1) ?: return@mapIndexedNotNull null
        if (tagName !in INDEXED_TAG_NAMES) return@mapIndexedNotNull null
        val normalizedTagValue = if (tagName == "t") tagValue.lowercase() else tagValue
        EventTagEntity(
            eventId  = id,
            tagName  = tagName,
            tagValue = normalizedTagValue.take(MAX_TAG_VALUE_SIZE),
            tagIndex = tagIdx
        )
    }
}

/**
 * Map a Room POJO to the domain [NoteView].
 *
 * The author profile is reconstructed from JOIN-ed columns; it is null when
 * the profile has not yet been fetched from relays (authorName and authorPicture
 * are both null after a LEFT JOIN miss).
 */
fun NoteWithProfile.toNoteView(): NoteView {
    val hasProfile = authorName != null || authorDisplayName != null || authorPicture != null
    return NoteView(
        event = Event(
            id        = id,
            pubkey    = pubkey,
            createdAt = createdAt,
            kind      = kind,
            content   = content,
            sig       = sig,
            tags      = parseTagsJson(id, tagsJson)
        ),
        authorProfile = if (hasProfile) {
            val nip05VerificationState = runCatching {
                Nip05VerificationState.valueOf(authorNip05VerificationState ?: Nip05VerificationState.NotAvailable.name)
            }.getOrDefault(Nip05VerificationState.NotAvailable)
            UserProfile(
                pubkey       = pubkey,
                name         = authorName,
                displayName  = authorDisplayName,
                picture      = authorPicture,
                about        = authorAbout,
                nip05        = authorNip05,
                nip05VerificationState = nip05VerificationState
            )
        } else null,
        reactionCount = reactionCount,
        replyCount    = replyCount,
        repostCount   = repostCount
    )
}

fun UserProfile.toEntity(): UserProfileEntity = UserProfileEntity(
    pubkey = pubkey,
    name = name,
    displayName = displayName,
    picture = picture,
    banner = banner,
    about = about,
    nip05 = nip05,
    lud16 = lud16,
    lud06 = lud06,
    website = website,
    nip05VerificationState = nip05VerificationState.toString()
)

fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
    pubkey = pubkey,
    name = name,
    displayName = displayName,
    picture = picture,
    banner = banner,
    about = about,
    nip05 = nip05,
    lud16 = lud16,
    lud06 = lud06,
    website = website,
    lastUpdated = updatedAt / 1000,
    nip05VerificationState = runCatching {
        Nip05VerificationState.valueOf(nip05VerificationState)
    }.getOrDefault(Nip05VerificationState.NotAvailable)
)

