package com.umbra.app.data.db.mapper

import com.umbra.app.data.db.entities.FeedFilterEntity
import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

fun FeedFilterEntity.toDomain(): FeedFilter {
    return FeedFilter(
        id = id,
        name = name,
        hideNsfw = hideNsfw,
        mutedPubkeys = decodeStringSet(mutedPubkeysJson),
        excludedTags = decodeStringSet(excludedTagsJson),
        excludedHashtags = decodeStringSet(excludedHashtagsJson),
        excludedContentPrefixes = decodeStringSet(excludedContentPrefixesJson),
        isActive = isActive,
        scopeToFollows = scopeToFollows,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

fun FeedFilter.toEntity(): FeedFilterEntity {
    return FeedFilterEntity(
        id = id,
        name = name,
        hideNsfw = hideNsfw,
        mutedPubkeysJson = encodeStringSet(mutedPubkeys),
        excludedTagsJson = encodeStringSet(excludedTags),
        excludedHashtagsJson = encodeStringSet(excludedHashtags),
        excludedContentPrefixesJson = encodeStringSet(excludedContentPrefixes),
        isActive = isActive,
        scopeToFollows = scopeToFollows,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

private fun encodeStringSet(values: Set<String>): String =
    JsonUtils.CompactJson.encodeToString(values.toList())

private fun decodeStringSet(json: String): Set<String> =
    runCatching { JsonUtils.CompactJson.decodeFromString<List<String>>(json).toSet() }
        .getOrDefault(emptySet())

