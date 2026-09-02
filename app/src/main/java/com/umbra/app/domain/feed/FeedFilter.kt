package com.umbra.app.domain.feed

import androidx.compose.runtime.Immutable

@Immutable
data class FeedFilter(
    val id: String,
    val name: String,
    val hideNsfw: Boolean = true,
    val mutedPubkeys: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val excludedHashtags: Set<String> = emptySet(),
    // Note-content prefixes to exclude (e.g. "nlogpost:" — a long-form-post proxy note, not
    // useful as its own client note). Same "default, but fully user-editable" treatment as
    // excludedTags/excludedHashtags — see FilterDefaults.DEFAULT_EXCLUDED_CONTENT_PREFIXES.
    val excludedContentPrefixes: Set<String> = emptySet(),
    val isActive: Boolean = false,
    // When true, this filter restricts the feed to the logged-in user's NIP-02 follows
    // (both the Room-query and the relay REQ authors are scoped) instead of showing
    // notes from every connected relay.
    val scopeToFollows: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

object DefaultFeedFilters {
    // The sole filter seeded for a new install — unscoped (every connected relay, not just
    // follows) with the standard noise-reduction excludes. There is no "Home"/follows-only
    // filter seeded by default anymore; scopeToFollows is just a regular, user-editable field
    // on any filter (including this one) rather than something baked into a distinct built-in.
    val DEFAULT = FeedFilter(
        id = "feed_default",
        name = "Default",
        hideNsfw = true,
        mutedPubkeys = emptySet(),
        excludedTags = FilterDefaults.DEFAULT_EXCLUDED_TAG_NAME_PREFIXES,
        excludedHashtags = FilterDefaults.DEFAULT_EXCLUDED_HASHTAGS,
        excludedContentPrefixes = FilterDefaults.DEFAULT_EXCLUDED_CONTENT_PREFIXES,
        isActive = false,
        scopeToFollows = false
    )

    fun create(name: String): FeedFilter = FeedFilter(
        id = "filter_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(9999)}",
        name = name,
        hideNsfw = true,
        mutedPubkeys = emptySet(),
        excludedTags = emptySet(),
        excludedHashtags = emptySet(),
        excludedContentPrefixes = emptySet(),
        isActive = false
    )
}

// Zero active filters means nothing to filter *against*, not "hide everything" — DEFAULT is the
// fallback both the relay-side REQ scoping and any UI-side visibility check should use in that
// case, so zero active filters shows the general default feed rather than nothing. Shared by every
// caller that needs one FeedFilter out of the user's current active set (FeedViewModel's own feed
// query/relay scoping, and NostrSessionManager's app-level baseline subscription) so they can't
// drift into computing two different "current feed filter" answers from the same active-filter list.
fun mergeActiveFeedFilters(filters: List<FeedFilter>): FeedFilter {
    if (filters.isEmpty()) return DefaultFeedFilters.DEFAULT

    val combinedMuted = filters.flatMap { it.mutedPubkeys }.toSet()
    val combinedExcludedTags = filters.flatMap { it.excludedTags }.toSet()
    val combinedExcludedHashtags = filters.flatMap { it.excludedHashtags }.toSet()
    val combinedExcludedContentPrefixes = filters.flatMap { it.excludedContentPrefixes }.toSet()

    return FeedFilter(
        id = "merged_active",
        name = if (filters.size == 1) filters.first().name else "Active Filters",
        hideNsfw = filters.all { it.hideNsfw },
        mutedPubkeys = combinedMuted,
        excludedTags = combinedExcludedTags,
        excludedHashtags = combinedExcludedHashtags,
        excludedContentPrefixes = combinedExcludedContentPrefixes,
        isActive = true,
        // OR, not AND: any active filter can opt into follows-scoping independently of the
        // others, so requiring every active filter to agree would make a single
        // scopeToFollows filter unreachable whenever it's active alongside an unscoped one.
        scopeToFollows = filters.any { it.scopeToFollows }
    )
}
