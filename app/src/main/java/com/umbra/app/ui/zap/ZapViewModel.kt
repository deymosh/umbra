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
    data class Failed(val reason: ZapFailure) : ZapPhase
}

@Immutable
data class ZapUiState(
    val target: ZapTarget? = null,
    val phase: ZapPhase = ZapPhase.Resolving,
    val payInfo: LnurlPayInfo? = null,
    val amountSats: Long = DEFAULT_ZAP_AMOUNTS_SATS[2],
    val comment: String = "",
    val paymentTargets: List<PaymentTarget> = emptyList()
) {
    val canZap: Boolean get() = phase == ZapPhase.Ready && payInfo != null && amountSats > 0
    /** Lightning is available (endpoint resolved) and supports nostr receipts. */
    val supportsReceipts: Boolean get() = payInfo?.supportsZaps == true
    val minSats: Long get() = (payInfo?.minSendableMsat ?: 1_000) / 1_000
    val maxSats: Long get() = (payInfo?.maxSendableMsat ?: 0) / 1_000
}

@HiltViewModel
class ZapViewModel @Inject constructor(
    private val sendZap: SendZapUseCase,
    private val eventRepository: EventRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ZapUiState())
    val state: StateFlow<ZapUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var paytoChannel: String? = null

    fun open(target: ZapTarget) {
        if (_state.value.target == target) return
        close()
        _state.value = ZapUiState(target = target)
        val lightning = target.profile?.lud16?.takeIf { it.isNotBlank() } ?: target.profile?.lud06
        loadJob = viewModelScope.launch {
            launch {
                val phase = if (lightning.isNullOrBlank()) {
                    ZapPhase.Failed(ZapFailure.NO_LIGHTNING_ADDRESS)
                } else {
                    sendZap.resolve(lightning).fold(
                        onSuccess = { info ->
                            _state.update { it.copy(payInfo = info, amountSats = it.amountSats.coerceIn(info.minSendableMsat / 1_000, maxOf(info.minSendableMsat, info.maxSendableMsat) / 1_000)) }
                            ZapPhase.Ready
                        },
                        onFailure = { ZapPhase.Failed(ZapFailure.ENDPOINT_UNREACHABLE) }
                    )
                }
                _state.update { it.copy(phase = phase) }
            }
            // NIP-A3: the recipient's other payment targets, fetched once and read from cache.
            val channel = NostrChannels.paymentTargets(target.recipientPubkey)
            paytoChannel = channel
            eventRepository.subscribeChannel(
                channel,
                listOf(EventFilter(authors = setOf(target.recipientPubkey), kinds = setOf(KIND_PAYMENT_TARGETS), limit = 1))
            )
            eventRepository.observeEventsByPubkeyAndKind(target.recipientPubkey, KIND_PAYMENT_TARGETS, 1)
                .collect { events ->
                    val targets = events.firstOrNull()?.let(::parsePaymentTargets).orEmpty()
                    _state.update { it.copy(paymentTargets = targets) }
                }
        }
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

    fun close() {
        loadJob?.cancel()
        loadJob = null
        paytoChannel?.let(eventRepository::clearChannel)
        paytoChannel = null
        _state.value = ZapUiState()
    }

    override fun onCleared() = close()

    private companion object {
        const val MAX_COMMENT = 280
    }
}
