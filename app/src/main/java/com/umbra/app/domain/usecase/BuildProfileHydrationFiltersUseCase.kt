package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter

/**
 * Builds canonical profile hydration filters.
 *
 * Base hydration kinds (any pubkey, owner or not): 0, 3, 10000, 10002, 10050, 10063.
 *
 * All six are replaceable/parameterized-replaceable per NIP-01 (kind 0, kind 3, and the
 * 10000-19999 range) — a relay stores at most one event per (pubkey, kind). A filter batching
 * N authors x kinds.size can therefore legitimately match up to N*kinds.size distinct stored
 * events, and NIP-01's `limit` is a flat cap on the combined, `created_at`-sorted result set
 * with no per-kind fairness guarantee. A limit smaller than authors.size * kinds.size lets
 * frequently updated kinds (0, 3) crowd out rarely updated ones (10002, 10050, 10063) for most
 * authors in the batch — e.g. receiving a followed user's kind-0 metadata but never their
 * kind-10002 relay list, even though both exist. [perAuthorLimit] is honored as a floor, not a
 * ceiling: the filter's actual limit is always at least authors.size * kinds.size so no kind is
 * starved.
 *
 * kind:10063 (BUD-03 Blossom server list) is included here rather than treated as owner-only:
 * the client-retrieval fallback (see MediaUploadRepository/BlossomServerUrl) needs to look up
 * ANY author's server list when their media URL breaks, not just the signed-in user's own.
 *
 * 10007 (search relays) and 10086 (index relays) are added only when [includeOwnerOnlyKinds] is
 * true — they configure *this client's* own search/discovery behavior and are meaningless for
 * anyone else's profile, so they must never be requested for a non-owner pubkey. Pass true only
 * from the signed-in user's own bootstrap/backfill; every other hydration path (referenced/
 * quoted/mentioned authors, viewing someone else's profile) must pass false.
 *
 * [restrictToKinds], when non-null, replaces the applicable kind set entirely instead of adding
 * to it — used by callers that already know (via [DetermineMissingHydrationKindsUseCase]) which
 * of the applicable kinds are still actually missing locally, so a relay that already told us
 * about e.g. kind:0 isn't asked about it again on every later hydration pass for the same pubkey.
 * Still intersected against [applicableKinds] defensively, in case a caller passes something
 * outside it (e.g. an owner-only kind for a non-owner request).
 *
 * [since], when non-null, is applied to every built filter. Leave it null for one-shot "give me
 * the current state" fetches (the normal case: these kinds are replaceable, so a relay always
 * has exactly one latest event per (pubkey, kind) regardless of when it was published). Pass a
 * timestamp only for a standing subscription that already has the current state and is watching
 * for a *later* update — without it, every re-subscribe on such a channel makes relays resend the
 * latest known event for every already-watched author, not just newly added ones.
 */
class BuildProfileHydrationFiltersUseCase {
    companion object {
        private val BASE_HYDRATION_KINDS = setOf(
            Event.KIND_METADATA,
            Event.KIND_CONTACT_LIST,
            Event.KIND_MUTED_USERS,
            Event.KIND_RELAY_LIST_METADATA,
            Event.KIND_DM_RELAY_LIST,
            Event.KIND_BLOSSOM_SERVER_LIST
        )
        private val OWNER_ONLY_HYDRATION_KINDS = setOf(
            Event.KIND_SEARCH_RELAYS,
            Event.KIND_INDEX_RELAYS
        )
        private const val DEFAULT_GLOBAL_LIMIT = 120

        /** The full kind set a hydration request for [includeOwnerOnlyKinds] would use. */
        fun applicableKinds(includeOwnerOnlyKinds: Boolean): Set<Int> =
            if (includeOwnerOnlyKinds) BASE_HYDRATION_KINDS + OWNER_ONLY_HYDRATION_KINDS else BASE_HYDRATION_KINDS
    }

    operator fun invoke(
        authors: Set<String>,
        perAuthorLimit: Int,
        includeGlobalFilter: Boolean = false,
        globalLimit: Int = DEFAULT_GLOBAL_LIMIT,
        includeOwnerOnlyKinds: Boolean = false,
        restrictToKinds: Set<Int>? = null,
        since: Long? = null
    ): List<EventFilter> {
        val fullKinds = applicableKinds(includeOwnerOnlyKinds)
        val kinds = restrictToKinds?.intersect(fullKinds) ?: fullKinds
        if (kinds.isEmpty()) return emptyList()

        val normalizedAuthors = authors.asSequence()
            .map { it.lowercase() }
            .filter { it.length == 64 }
            .toSet()

        val filters = mutableListOf<EventFilter>()
        if (includeGlobalFilter) {
            filters += EventFilter(
                kinds = kinds,
                limit = globalLimit,
                since = since
            )
        }

        if (normalizedAuthors.isNotEmpty()) {
            val minimumSafeLimit = normalizedAuthors.size * kinds.size
            filters += EventFilter(
                kinds = kinds,
                authors = normalizedAuthors,
                limit = maxOf(perAuthorLimit.coerceAtLeast(1), minimumSafeLimit),
                since = since
            )
        }

        return filters
    }
}

