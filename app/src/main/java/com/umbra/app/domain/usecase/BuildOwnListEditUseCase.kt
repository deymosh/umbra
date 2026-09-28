package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.applyListEdit
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Builds the next version of one of the user's replaceable lists (kind 3 contacts, 10000 mutes,
 * 10001 pins, 10003 bookmarks) as an edit of their latest published version: only [ListEdit]'s
 * values change, and every other tag plus the content (e.g. NIP-44 encrypted private mutes) is
 * carried over untouched. [fallbackValues] seeds the list only when no previous version exists.
 *
 * Meant to run inside a lazy `buildEventJson`, right before Amber signs, so two quick edits in a
 * row each build on the newest list rather than a stale snapshot.
 */
class BuildOwnListEditUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences
) {
    suspend operator fun invoke(kind: Int, edit: ListEdit, fallbackValues: Set<String> = emptySet()): String {
        val owner = userPreferences.getPublicKey()?.lowercase()
        val base = owner?.let { eventRepository.observeEventsByPubkeyAndKind(it, kind, limit = 1).first().firstOrNull() }
        val tags = applyListEdit(base, edit, fallbackValues)
        return NostrEventBuilder.listEvent(kind = kind, content = base?.content.orEmpty(), tags = tags)
    }
}
