package com.umbra.app.domain.usecase

import com.umbra.app.domain.crypto.EventCrypto
import com.umbra.app.domain.notifications.NotificationGroup
import com.umbra.app.domain.notifications.groupNotifications
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.LightningRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.UserRepository
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
 *
 * The signed-in user's own LNURL-pay `nostrPubkey` is resolved once per process (in-memory
 * cache) so zap receipts can be checked against the spec's MUST-level receipt-signer rule. It
 * is resolved off the collector thread, never per emission, and any failure (no lud16/lud06,
 * unreachable endpoint) just passes null — validation then falls back to the offline checks
 * without blocking or dropping any notification.
 */
class ObserveNotificationsUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val muteListRepository: MuteListRepository,
    private val userRepository: UserRepository,
    private val lightningRepository: LightningRepository
) {
    /** pubkey of the LNURL-pay endpoint the signed-in user's receipts must be signed by. */
    @Volatile
    private var cachedReceiptSigner: String? = null
    private var signerResolved = false

    operator fun invoke(pubkey: String?): Flow<List<NotificationGroup>> {
        if (pubkey.isNullOrBlank()) return flowOf(emptyList())
        val mutes = muteListRepository.getMuteList(pubkey).map { it?.mutedPubkeys.orEmpty().map(String::lowercase).toSet() }
        return combine(eventRepository.observeInbox(pubkey), mutes) { events, muted ->
            val signer = resolveReceiptSigner(pubkey)
            groupNotifications(
                events,
                muted,
                verifyEventSignature = EventCrypto::verifySignature,
                expectedReceiptSigner = signer
            )
        }
    }

    /** Once-per-process lookup: cache the result (including failure) for all later emissions. */
    private suspend fun resolveReceiptSigner(pubkey: String): String? {
        if (signerResolved) return cachedReceiptSigner
        val signer = runCatching {
            val profile = userRepository.getProfile(pubkey)
            val candidate = profile?.lud16?.takeIf { it.isNotBlank() } ?: profile?.lud06?.takeIf { it.isNotBlank() }
            candidate?.let { lightningRepository.resolvePayInfo(it).getOrNull()?.nostrPubkey }
        }.getOrNull()
        cachedReceiptSigner = signer
        signerResolved = true
        return signer
    }
}
