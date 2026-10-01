package com.umbra.app.ui.zap

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.model.NostrChannels
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.EventFilter
import com.umbra.app.domain.nip57.DEFAULT_ZAP_AMOUNTS_SATS
import com.umbra.app.domain.nip57.LnurlPayInfo
import com.umbra.app.domain.nipa3.KIND_PAYMENT_TARGETS
import com.umbra.app.domain.nipa3.PaymentTarget
import com.umbra.app.domain.nipa3.parsePaymentTargets
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.usecase.SendZapUseCase
import com.umbra.app.domain.usecase.ZapFailure
import com.umbra.app.domain.usecase.ZapOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who (and optionally which note) is being paid. */
@Immutable
data class ZapTarget(val recipientPubkey: String, val profile: UserProfile?, val event: Event? = null)

@Immutable
sealed interface ZapPhase {
    /** Looking up the recipient's LNURL endpoint over Tor. */
    data object Resolving : ZapPhase
    data object Ready : ZapPhase
    /** Amber signing, then the invoice request. */
    data object Working : ZapPhase
    data class InvoiceReady(val bolt11: String, val isZap: Boolean) : ZapPhase
    /** No LNURL endpoint at all — the sheet still lists NIP-A3 payment targets if any exist. */
    data object NoLightning : ZapPhase
    data class Failed(val reason: ZapFailure) : ZapPhase
}

@Immutable
data class ZapUiState(
    val target: ZapTarget? = null,
    val phase: ZapPhase = ZapPhase.Resolving,
    val payInfo: LnurlPayInfo? = null,
    val amountSats: Long = DEFAULT_ZAP_AMOUNTS_SATS[2],
    val comment: String = "",
    val paymentTargets: List<PaymentTarget> = emptyList(),
    /** True once the payment-targets subscription has reported back (possibly with none). */
    val targetsLoaded: Boolean = false
) {
    val canZap: Boolean get() = phase == ZapPhase.Ready && payInfo != null && amountSats > 0
    /** Lightning is available (endpoint resolved) and supports nostr receipts. */
    val supportsReceipts: Boolean get() = payInfo?.supportsZaps == true
    val minSats: Long get() = (payInfo?.minSendableMsat ?: 1_000) / 1_000
    val maxSats: Long get() = (payInfo?.effectiveMaxSendableMsat ?: 0) / 1_000
}

@HiltViewModel
class ZapViewModel @Inject constructor(
    private val sendZap: SendZapUseCase,
    private val eventRepository: EventRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ZapUiState())
    val state: StateFlow<ZapUiState> = _state.asStateFlow()

    // Separate so retrying Lightning never cancels the payment-target collection, and close()
    // stops both.
    private var lightningJob: Job? = null
    private var targetsJob: Job? = null
    private var paytoChannel: String? = null

    fun open(target: ZapTarget) {
        if (_state.value.target == target) return
        close()
        _state.update { ZapUiState(target = target) }
        lightningJob = viewModelScope.launch { resolveLightning(target) }
        // NIP-A3: the recipient's other payment targets, fetched once and read from cache.
        // Runs independently of the Lightning resolution so a slow or failed endpoint lookup
        // can never hold the targets back.
        val channel = NostrChannels.paymentTargets(target.recipientPubkey)
        paytoChannel = channel
        targetsJob = viewModelScope.launch {
            eventRepository.subscribeChannel(
                channel,
                listOf(EventFilter(authors = setOf(target.recipientPubkey), kinds = setOf(KIND_PAYMENT_TARGETS), limit = 1))
            )
            eventRepository.observeEventsByPubkeyAndKind(target.recipientPubkey, KIND_PAYMENT_TARGETS, 1)
                .collect { events ->
                    val targets = events.firstOrNull()?.let(::parsePaymentTargets).orEmpty()
                    _state.update { it.copy(paymentTargets = targets, targetsLoaded = true) }
                }
        }
    }

    /** Lightning lookup for the current target; retryable after an unreachable endpoint. */
    private suspend fun resolveLightning(target: ZapTarget) {
        _state.update { it.copy(phase = ZapPhase.Resolving) }
        var anyAttempted = false
        val lud16 = target.profile?.lud16?.takeIf { it.isNotBlank() }
        if (lud16 != null) {
            anyAttempted = true
            resolveAndApply(lud16)
            if (_state.value.payInfo != null) return
        }
        // lud16 missing or its endpoint wouldn't resolve: lud06 (an lnurl or a second address)
        // is still worth a try before declaring no Lightning at all.
        val lud06 = target.profile?.lud06?.takeIf { it.isNotBlank() && it != lud16 }
        if (lud06 != null) {
            anyAttempted = true
            resolveAndApply(lud06)
            if (_state.value.payInfo != null) return
        }
        if (_state.value.payInfo == null) {
            // Nothing was available to try means the recipient simply hasn't set Lightning; a
            // tried-but-unreachable endpoint stays retryable instead.
            _state.update {
                if (anyAttempted) it.copy(phase = ZapPhase.Failed(ZapFailure.ENDPOINT_UNREACHABLE))
                else it.copy(phase = ZapPhase.NoLightning)
            }
        }
    }

    private suspend fun resolveAndApply(addressOrLnurl: String) {
        sendZap.resolve(addressOrLnurl).fold(
            onSuccess = { info ->
                _state.update {
                    it.copy(
                        payInfo = info,
                        amountSats = it.amountSats.coerceIn(info.minSendableMsat / 1_000, (info.effectiveMaxSendableMsat / 1_000).coerceAtLeast(info.minSendableMsat / 1_000))
                    )
                }
            },
            onFailure = { /* fall through to the next candidate or the final phase */ }
        )
    }

    fun setAmount(sats: Long) = _state.update { it.copy(amountSats = sats.coerceAtLeast(0)) }

    fun setComment(comment: String) = _state.update { it.copy(comment = comment.take(MAX_COMMENT)) }

    fun zap() {
        val current = _state.value
        val info = current.payInfo ?: return
        val target = current.target ?: return
        if (!current.canZap) return
        _state.update { it.copy(phase = ZapPhase.Working) }
        viewModelScope.launch {
            val phase = when (val outcome = sendZap(info, target.recipientPubkey, current.amountSats, target.event, current.comment)) {
                is ZapOutcome.Invoice -> ZapPhase.InvoiceReady(outcome.bolt11, outcome.isZap)
                ZapOutcome.SignCancelled -> ZapPhase.Ready
                is ZapOutcome.Failed -> ZapPhase.Failed(outcome.reason)
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    /** Back from an error or a handed-off invoice to the amount picker. */
    fun retry() = _state.update {
        it.copy(phase = if (it.payInfo != null) ZapPhase.Ready else ZapPhase.Failed(ZapFailure.ENDPOINT_UNREACHABLE))
    }

    /** Re-resolve Lightning for the current target from scratch (Try again on an unreachable endpoint). */
    fun reload() {
        val target = _state.value.target ?: return
        lightningJob?.cancel()
        lightningJob = viewModelScope.launch { resolveLightning(target) }
    }

    fun close() {
        lightningJob?.cancel()
        lightningJob = null
        targetsJob?.cancel()
        targetsJob = null
        paytoChannel?.let(eventRepository::clearChannel)
        paytoChannel = null
        _state.update { ZapUiState() }
    }

    override fun onCleared() = close()

    private companion object {
        const val MAX_COMMENT = 280
    }
}
