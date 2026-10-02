package com.umbra.app.data.repository

import com.umbra.app.domain.repository.LightningRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Who may sign zap receipts for each recipient: the `nostrPubkey` their LNURL-pay endpoint
 * publishes (NIP-57 Appendix F). Zap totals only count receipts signed by that key, since any
 * other receipt is just an event anyone could have written.
 *
 * Looking a key up means fetching the recipient's lightning address over Tor, so it happens
 * lazily and only for recipients whose zaps are about to be shown: [signerFor] answers from
 * memory and queues anything unknown through [request]. Results expire so a changed wallet is
 * picked up; a failed lookup (Tor down, endpoint unreachable, no profile cached yet) is retried
 * sooner than a settled answer. Nothing is persisted.
 */
internal class ZapReceiptSignerDirectory(
    private val scope: CoroutineScope,
    private val userRepository: UserRepository,
    private val lightningRepository: LightningRepository,
    private val onResolved: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val signer: String?, val expiresAt: Long)

    /** A lookup's state: settled with the key (or with none published), or not known yet. */
    sealed interface Lookup {
        data class Known(val signer: String?) : Lookup
        data object Unknown : Lookup
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)
    private val logger = UmbraLog.tag("ZapSignerDirectory")

    fun signerFor(recipient: String): Lookup {
        val entry = entries[recipient.lowercase()] ?: return Lookup.Unknown
        return if (entry.expiresAt <= clock()) Lookup.Unknown else Lookup.Known(entry.signer)
    }

    /** Starts a lookup for each of [recipients] not already known or in flight. */
    fun request(recipients: Collection<String>) {
        recipients.forEach { raw ->
            val recipient = raw.lowercase()
            if (signerFor(recipient) is Lookup.Known) return@forEach
            if (!inFlight.add(recipient)) return@forEach
            scope.launch {
                try {
                    permits.withPermit { resolve(recipient) }
                } finally {
                    inFlight.remove(recipient)
                }
            }
        }
    }

    fun clear() {
        entries.clear()
    }

    private suspend fun resolve(recipient: String) {
        val profile = runCatching { userRepository.getProfile(recipient) }.getOrNull()
        val address = profile?.lud16?.takeIf { it.isNotBlank() } ?: profile?.lud06?.takeIf { it.isNotBlank() }
        val now = clock()
        val entry = when {
            // No profile cached yet: try again once it may have arrived.
            profile == null -> Entry(null, now + RETRY_MS)
            // A profile with no lightning address can't have received a real zap.
            address == null -> Entry(null, now + SETTLED_MS)
            else -> lightningRepository.resolvePayInfo(address).fold(
                onSuccess = { info -> Entry(info.nostrPubkey, now + SETTLED_MS) },
                onFailure = {
                    logger.d { "LNURL lookup failed: ${it.javaClass.simpleName}" }
                    Entry(null, now + RETRY_MS)
                }
            )
        }
        val previous = entries.put(recipient, entry)
        if (entry.signer != null && entry.signer != previous?.signer) onResolved()
        trim()
    }

    private fun trim() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries.sortedBy { it.value.expiresAt }
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.key, it.value) }
    }

    private companion object {
        const val MAX_CONCURRENT_LOOKUPS = 2
        const val MAX_ENTRIES = 1_000
        const val SETTLED_MS = 6 * 60 * 60 * 1_000L
        const val RETRY_MS = 2 * 60 * 1_000L
    }
}
