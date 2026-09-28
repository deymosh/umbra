package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** What the signed-in user has published, counted from their own encrypted local archive. */
data class OwnActivity(val notes: Int, val reactions: Int, val reposts: Int)

class ObserveOwnActivityUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences
) {
    operator fun invoke(): Flow<OwnActivity?> {
        val me = userPreferences.getPublicKey()?.takeIf { userPreferences.canSignWithAmber() } ?: return flowOf(null)
        return combine(
            eventRepository.observeCountEventsByPubkeyAndKind(me, Event.KIND_TEXT_NOTE),
            eventRepository.observeCountEventsByPubkeyAndKind(me, Event.KIND_REACTION),
            eventRepository.observeCountEventsByPubkeyAndKind(me, Event.KIND_REPOST)
        ) { notes, reactions, reposts -> OwnActivity(notes, reactions, reposts) }
    }
}
