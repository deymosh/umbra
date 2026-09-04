package com.umbra.app.ui.common

import com.umbra.app.domain.feed.FeedFilter
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.FeedRepository
import com.umbra.app.domain.repository.MuteListRepository
import com.umbra.app.domain.repository.PinListRepository
import com.umbra.app.domain.usecase.BuildEventShareUrlUseCase
import com.umbra.app.domain.usecase.DeleteNoteUseCase
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.RemoveDeletedNoteFromCacheUseCase
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared sign/publish/repository-mutation primitives for the interaction-action methods
 * (like, repost, mute, pin, delete, share, get-JSON) that both FeedViewModel and ProfileViewModel
 * otherwise hand-maintain as near-duplicates. Each ViewModel manually constructs and owns its own
 * instance of this class (never a singleton, never Hilt-injected).
 *
 * This class deliberately centralizes ONLY the plumbing that is genuinely identical between the
 * two callers: the Amber sign-then-publish round trip, the mute/pin repository calls, and
 * JSON/share-URL formatting. It never calls `canSignWithAmber()` itself — the two callers show
 * different error messages (or none at all) when a write is blocked, so that guard and its
 * UI-state side effect stay on each caller's own wrapper method, one guard per call path. It also
 * never decides WHEN to mutate relative to a sign confirmation — every mutation this class
 * performs, for every caller, commits only after Amber confirms the signature; there is no
 * optimistic-apply-then-rollback path anywhere in this coordinator.
 */
internal class InteractionActionsCoordinator(
    private val userPreferences: UserPreferences,
    private val muteListRepository: MuteListRepository,
    private val pinListRepository: PinListRepository,
    private val feedRepository: FeedRepository,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val deleteNoteUseCase: DeleteNoteUseCase,
    private val removeDeletedNoteFromCacheUseCase: RemoveDeletedNoteFromCacheUseCase,
    private val buildEventShareUrlUseCase: BuildEventShareUrlUseCase,
    private val scope: CoroutineScope
) {
    private val logger = UmbraLog.tag("InteractionActionsCoordinator")

    fun canSignEvents(): Boolean = userPreferences.canSignWithAmber()

    fun getEventJson(event: Event): String =
        JsonUtils.PrettyJson.encodeToString(Event.serializer(), event)

    suspend fun buildShareUrl(eventId: String): String = buildEventShareUrlUseCase(eventId)

    /**
     * Signs [eventJson] via Amber, then either runs [onSigned] (an optimistic UI-state update
     * meant to be committed only once the signature is actually confirmed) followed by publishing
     * the result, or runs [onRejected] on a null/failed sign. Fire-and-forget: launches its own
     * coroutine on [scope] rather than suspending the caller.
     *
     * Delegates to the [buildEventJson] overload below with an eager (already-fixed) event, so a
     * caller whose event content cannot go stale during the Amber wait (e.g. [deleteEvent]'s
     * delete-request JSON, which targets a fixed event id regardless of timing) never pays for the
     * lazy-rebuild machinery it doesn't need.
     */
    fun requestSignAndPublish(
        eventJson: String,
        currentUserHex: String?,
        onSigned: suspend () -> Unit = {},
        onRejected: suspend () -> Unit = {}
    ) = requestSignAndPublish(buildEventJson = { eventJson }, currentUserHex, onSigned, onRejected)

    /**
     * Same Amber sign-then-publish round trip as the [eventJson] overload above, except
     * [buildEventJson] is invoked lazily, right before signing rather than by the caller ahead of
     * time. Use this for any event whose content is derived from a caller-owned list/set that
     * could itself change while Amber's approval prompt is pending (an unbounded wait): rebuilding
     * from the live list at sign time — instead of a snapshot taken before the wait started — keeps
     * two overlapping same-kind actions (e.g. muting two different users back to back) from
     * silently reverting each other's already-published change on relays.
     */
    fun requestSignAndPublish(
        buildEventJson: suspend () -> String,
        currentUserHex: String?,
        onSigned: suspend () -> Unit = {},
        onRejected: suspend () -> Unit = {}
    ) {
        scope.launch {
            val signedEvent = try {
                amberSignerGateway.signEvent(buildEventJson(), currentUserHex)
            } catch (e: Exception) {
                logger.d { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }
                null
            }
            if (signedEvent != null) {
                onSigned()
                publishSignedEvent(signedEvent)
            } else {
                onRejected()
            }
        }
    }

    /**
     * Broadcasts [signedEventJson] to relays. [onFailure] lets each caller apply its own
     * error-message side effect (or none) on a publish failure.
     */
    fun publishSignedEvent(signedEventJson: String, onFailure: suspend (Throwable) -> Unit = {}) {
        scope.launch {
            publishSignedEventUseCase(signedEventJson).onFailure { e ->
                logger.d { "Error publishing event: ${scrubThrowableMessageForLogs(e)}" }
                onFailure(e)
            }
        }
    }

    suspend fun applyMuteChange(target: String, mute: Boolean): Result<Unit> =
        if (mute) muteListRepository.mute(target) else muteListRepository.unmute(target)

    /**
     * Mirrors a mute/unmute into the caller-resolved, currently-active [FeedFilter]'s local
     * `mutedPubkeys`, so the Room-backed feed query reflects it immediately (offline-safe) instead
     * of waiting on the NIP-51 mute-list publish to round-trip.
     *
     * [resolveActiveFilter] is caller-supplied rather than a single fixed lookup here so this
     * coordinator never has to decide what "active" means for a given screen — that stays the
     * caller's call. Both current callers happen to supply the same resolution today (the first
     * entry of the live active-filters list), but the parameter still exists to let a future
     * caller resolve differently without changing this signature.
     */
    suspend fun mirrorMuteIntoActiveFilter(
        target: String,
        mute: Boolean,
        resolveActiveFilter: suspend () -> FeedFilter?
    ) {
        runCatching {
            val currentFilter = resolveActiveFilter() ?: return@runCatching
            val updated = if (mute) {
                currentFilter.mutedPubkeys + target
            } else {
                currentFilter.mutedPubkeys - target
            }
            feedRepository.updateMutedAuthors(currentFilter.id, updated)
        }
    }

    suspend fun applyPinChange(eventId: String, pin: Boolean): Result<Unit> =
        if (pin) pinListRepository.pin(eventId) else pinListRepository.unpin(eventId)

    /**
     * Orchestrates a NIP-09 delete: builds the delete request and fires the sign-and-publish round
     * trip, running both [onDeleteConfirmed] (the caller's visible-state removal) and the local
     * cache/archive removal only once Amber has actually confirmed the signature — from inside
     * [requestSignAndPublish]'s `onSigned` callback, before the signed event is published.
     * [onCacheRemoveFailure] fires if that cache/archive removal itself fails. A rejected or failed
     * sign runs neither: nothing is applied ahead of confirmation, so there is nothing to roll
     * back. Neither current caller gates this on `canSignWithAmber()`; this primitive does not add
     * one.
     */
    fun deleteEvent(
        event: Event,
        currentUserHex: String,
        onDeleteConfirmed: () -> Unit = {},
        onCacheRemoveFailure: () -> Unit = {}
    ) {
        val eventJson = deleteNoteUseCase(event, currentUserHex).getOrElse { return }
        requestSignAndPublish(
            eventJson,
            currentUserHex,
            onSigned = {
                onDeleteConfirmed()
                removeDeletedNoteFromCacheUseCase(event.id).onFailure { onCacheRemoveFailure() }
            }
        )
    }
}
