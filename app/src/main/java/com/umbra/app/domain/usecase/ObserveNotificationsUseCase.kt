package com.umbra.app.domain.usecase

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.notifications.NotificationGroup
import com.umbra.app.domain.notifications.groupNotifications
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.MuteListRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The signed-in user's grouped notifications, with their own mute list applied.
 *
 * `EventCrypto` is referenced directly rather than injected: it is an object in
 * `domain.crypto` (pure JVM, BouncyCastle), so domain code may use it without a data/ dependency,
 * and it owns the single BIP-340 verifier this groupNotifications call needs for zap-receipt
 * validation's injected signature check.
 */
class ObserveNotificationsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val muteListRepository: MuteListRepository
) {
    operator fun invoke(pubkey: String?): Flow<List<NotificationGroup>> {
        if (pubkey.isNullOrBlank()) return flowOf(emptyList())
        val mutes = muteListRepository.getMuteList(pubkey).map { it?.mutedPubkeys.orEmpty().map(String::lowercase).toSet() }
        return combine(eventRepository.observeInbox(pubkey), mutes) { events, muted ->
            groupNotifications(events, muted, verifyEventSignature = EventCrypto::verifySignature)
        }
    }
}
