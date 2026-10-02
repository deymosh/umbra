package com.umbra.app.domain.usecase

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.notifications.NotificationGroup
import com.umbra.app.domain.notifications.groupNotifications
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.LightningRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * The signed-in user's grouped notifications, with their own mute list applied.
 *
 * `EventCrypto` (package `domain.crypto`, pure JVM) is the BIP-340 verifier zap-receipt
 * validation needs for the zap request's signature.
 *
 * Zap receipts are also checked against the signed-in user's own LNURL-pay `nostrPubkey` (the
 * spec's receipt-signer rule). That lookup goes over the network, so it never gates an emission:
 * notifications are grouped with no expected signer first, and regrouped once the lookup
 * succeeds. It is redone only when the user's lightning address changes; a failed lookup keeps
 * the offline checks rather than dropping anything.
 */
class ObserveNotificationsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val muteListRepository: MuteListRepository,
    private val userRepository: UserRepository,
    private val lightningRepository: LightningRepository
) {
    operator fun invoke(pubkey: String?): Flow<List<NotificationGroup>> {
        if (pubkey.isNullOrBlank()) return flowOf(emptyList())
        return combine(eventRepository.observeInbox(pubkey), muteListRepository.getMuteList(pubkey), receiptSigner(pubkey)) { events, muteList, signer ->
            // Replies and mentions also go when they carry a muted hashtag or word or sit in a
            // muted thread; muted people are handled per notification type by groupNotifications.
            val shown = if (muteList == null) events else events.filterNot { it.kind in TEXT_KINDS && muteList.hides(it) }
            groupNotifications(
                shown,
                muteList?.mutedPubkeys.orEmpty(),
                verifyEventSignature = EventCrypto::verifySignature,
                expectedReceiptSigner = signer
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun receiptSigner(pubkey: String): Flow<String?> =
        userRepository.observeProfile(pubkey)
            .map { profile -> profile?.lud16?.takeIf { it.isNotBlank() } ?: profile?.lud06?.takeIf { it.isNotBlank() } }
            .distinctUntilChanged()
            .transformLatest { address ->
                emit(null)
                if (address != null) {
                    val signer = runCatching { lightningRepository.resolvePayInfo(address).getOrNull()?.nostrPubkey }
                        .getOrNull()
                    if (signer != null) emit(signer)
                }
            }
            // observeProfile may stay silent until the profile is cached; never hold back the inbox.
            .onStart { emit(null) }
            .distinctUntilChanged()

    private companion object {
        val TEXT_KINDS = setOf(Event.KIND_TEXT_NOTE, Event.KIND_COMMENT)
    }
}
