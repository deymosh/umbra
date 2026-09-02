package com.umbra.app.domain.usecase

/**
 * Normalizes and bounds author pubkeys used for profile-hydration batching.
 */
class BuildHydrationAuthorSetUseCase {
    operator fun invoke(
        existing: Set<String>,
        incoming: Collection<String>,
        maxAuthors: Int
    ): Set<String> {
        if (maxAuthors <= 0) return emptySet()

        return (existing.asSequence() + incoming.asSequence())
            .map { it.lowercase() }
            .filter { it.length == 64 }
            .distinct()
            .take(maxAuthors)
            .toSet()
    }
}
