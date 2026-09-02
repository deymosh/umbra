package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.EventFilter

/**
 * Builds chunked profile hydration requests using the canonical filter builder.
 */
class BuildProfileHydrationRequestsUseCase(
    private val buildProfileHydrationFiltersUseCase: BuildProfileHydrationFiltersUseCase
) {
    operator fun invoke(
        authors: Collection<String>,
        chunkSize: Int,
        perAuthorLimit: Int,
        includeGlobalFilter: Boolean = false,
        globalLimit: Int = 120,
        includeOwnerOnlyKinds: Boolean = false,
        restrictToKinds: Set<Int>? = null,
        since: Long? = null
    ): List<EventFilter> {
        val normalized = authors.asSequence()
            .map { it.lowercase() }
            .filter { it.length == 64 }
            .distinct()
            .toList()

        if (normalized.isEmpty()) {
            return if (includeGlobalFilter) {
                buildProfileHydrationFiltersUseCase(
                    authors = emptySet(),
                    perAuthorLimit = perAuthorLimit,
                    includeGlobalFilter = true,
                    globalLimit = globalLimit,
                    includeOwnerOnlyKinds = includeOwnerOnlyKinds,
                    restrictToKinds = restrictToKinds,
                    since = since
                )
            } else {
                emptyList()
            }
        }

        return normalized
            .chunked(chunkSize.coerceAtLeast(1))
            .flatMapIndexed { index, chunk ->
                buildProfileHydrationFiltersUseCase(
                    authors = chunk.toSet(),
                    perAuthorLimit = perAuthorLimit,
                    includeGlobalFilter = includeGlobalFilter && index == 0,
                    globalLimit = globalLimit,
                    includeOwnerOnlyKinds = includeOwnerOnlyKinds,
                    restrictToKinds = restrictToKinds,
                    since = since
                )
            }
    }
}

