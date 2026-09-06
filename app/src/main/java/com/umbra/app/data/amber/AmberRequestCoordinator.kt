package com.umbra.app.data.amber

import android.content.ActivityNotFoundException
import android.content.Intent
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry of Amber's batched-response format — see [AmberRequestCoordinator.deliverResult]'s
 * doc comment. Field names/shape mirror Amber's own wire format exactly (same as a single
 * response's top-level extras, just nested), not a Umbra-side invention.
 */
@Serializable
private data class BatchedAmberResult(
    val id: String? = null,
    val result: String? = null,
    val event: String? = null,
    val `package`: String? = null,
    val rejected: Boolean? = null
)

/**
 * App-wide dispatcher for interactive (Activity-Intent) Amber requests — the single place that
 * knows how to launch one and correlate its result, used by every screen/ViewModel/background
 * coordinator that needs Amber's UI:
 *
 * - ONE launcher registered at a time (see [AppSessionEffects]), not one per screen/operation —
 *   registered/unregistered as the hosting Composable comes and goes, so it keeps working
 *   regardless of which screen the user is currently on (the previous per-screen-launcher design
 *   meant a request silently never got a screen to launch from unless that exact screen was
 *   open).
 * - Every request gets a random call id embedded in the outgoing Intent's `"id"` extra (per
 *   NIP-55's own spec, an optional field Amber echoes back so multiple in-flight requests can be
 *   told apart) plus `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`, so a second request
 *   launched while Amber's own approval Activity is already on top gets delivered to that same
 *   instance via onNewIntent() instead of stacking a second one — letting Amber combine/queue its
 *   own approval UI instead of two independent full-screen navigations.
 * - Results correlate by that id through a plain concurrent map of [CompletableDeferred], not a
 *   FIFO queue — so an arbitrary number of requests can be genuinely concurrent (search+index
 *   decrypt, a sign_event, a publish encrypt, all at once) without one waiting on another or a
 *   response landing on the wrong waiter. A response whose id has no matching entry (duplicate,
 *   unknown, or arrives after this request's own timeout already gave up) is dropped silently.
 * - [FOREGROUND_TIMEOUT_MS] bounds the wait — if Amber never responds (dismissed without any
 *   result Intent, e.g. system back-press before its UI even loads, which carries no "id" to
 *   correlate at all), the caller gets null instead of hanging forever.
 */
@Singleton
class AmberRequestCoordinator @Inject constructor() {
    companion object {
        private const val TAG = "UmbraAmberCoordinator"
        private const val EXTRA_ID = "id"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_REJECTED = "rejected"
        // Amber's batched-response extra — see deliverResult's doc comment.
        private const val EXTRA_RESULTS = "results"
        private const val FOREGROUND_TIMEOUT_MS = 30_000L
    }

    private val logger = UmbraLog.tag(TAG)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Intent?>>()

    @Volatile
    private var launcher: ((Intent) -> Unit)? = null

    /** Call when the launcher becomes available (Activity/Compose surface mounted). */
    fun registerLauncher(launcher: (Intent) -> Unit) {
        this.launcher = launcher
    }

    /** Call when the registering surface is torn down — no-ops if a different launcher already replaced it. */
    fun unregisterLauncher(launcher: (Intent) -> Unit) {
        if (this.launcher === launcher) this.launcher = null
    }

    fun hasLauncher(): Boolean = launcher != null

    /**
     * Fire-and-forget launch — no result awaited, no call id attached (e.g. opening Amber's Play
     * Store listing, where there's nothing meaningful to correlate a response to). Returns false
     * if no launcher is currently registered or the target Activity can't be found.
     */
    fun launch(intent: Intent): Boolean {
        val launch = launcher ?: return false
        return try {
            launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            logger.d { "Amber not found for fire-and-forget launch: ${scrubThrowableMessageForLogs(e)}" }
            false
        }
    }

    /**
     * Delivers an ActivityResult back to whichever [launchAndAwait] call is still waiting for it
     * — called once from the single registered launcher's own result callback, regardless of
     * resultCode (a rejected-but-answered request can still carry a matchable id; a true system
     * cancel with null data cannot, and is safely dropped here — the waiting call times out on
     * its own rather than hanging).
     *
     * Amber doesn't always answer one request per response Intent: if a second interactive
     * request lands (via onNewIntent) while Amber's approval screen is already showing one, it
     * combines both into a single approval and answers them together via a top-level `"results"`
     * extra — a JSON array of per-request `{id, result, event, package, rejected}` objects — with
     * no top-level `"id"`/`"result"` of its own. Only checking for a single top-level id (as
     * before) silently dropped the *entire* batched response whenever this happened: neither
     * waiter ever saw a result, both just timed out. This batching shape isn't covered by the
     * NIP-55 spec itself — reverse-engineered against Amber's actual observed behavior.
     */
    fun deliverResult(data: Intent?) {
        val batched = data?.getStringExtra(EXTRA_RESULTS)
        if (batched != null) {
            deliverBatchedResults(batched)
            return
        }

        val id = data?.getStringExtra(EXTRA_ID)
        if (id == null) {
            logger.d { "Amber result carried no id extra — cannot correlate, dropped" }
            return
        }
        val deferred = pending.remove(id)
        if (deferred == null) {
            logger.d { "No pending request for Amber result id=$id (duplicate, unknown, or already timed out)" }
            return
        }
        deferred.complete(data)
    }

    private fun deliverBatchedResults(resultsJson: String) {
        val items = runCatching { JsonUtils.NostrJson.decodeFromString<List<BatchedAmberResult>>(resultsJson) }
            .getOrElse { e ->
                logger.d { "Malformed Amber batched results, dropped: ${scrubThrowableMessageForLogs(e)}" }
                return
            }
        items.forEach { item ->
            val id = item.id
            if (id == null) {
                logger.d { "Amber batched result entry carried no id — dropped" }
                return@forEach
            }
            val deferred = pending.remove(id)
            if (deferred == null) {
                logger.d { "No pending request for Amber batched result id=$id (duplicate, unknown, or already timed out)" }
                return@forEach
            }
            // Reconstructed as a plain single-result Intent so every existing extractor
            // (AmberConnector.extract*FromResult, all of which only read these same top-level
            // extras) keeps working unmodified regardless of whether Amber answered this request
            // solo or batched with another.
            deferred.complete(
                Intent().apply {
                    putExtra(EXTRA_ID, item.id)
                    item.result?.let { putExtra(EXTRA_RESULT, it) }
                    item.event?.let { putExtra(EXTRA_EVENT, it) }
                    item.`package`?.let { putExtra(EXTRA_PACKAGE, it) }
                    putExtra(EXTRA_REJECTED, item.rejected == true)
                }
            )
        }
    }

    /**
     * Builds an Intent via [buildIntent], tags it with a fresh call id + single-top/clear-top
     * flags, launches it through the currently registered launcher, and suspends until either a
     * correlated result arrives (see [deliverResult]) or [timeoutMs] elapses. Returns null when
     * there's no launcher registered right now, the target Activity can't be found, or the wait
     * times out — callers treat null exactly like "Amber didn't answer," same as before.
     */
    suspend fun launchAndAwait(timeoutMs: Long = FOREGROUND_TIMEOUT_MS, buildIntent: () -> Intent): Intent? {
        val launch = launcher ?: return null
        val callId = UUID.randomUUID().toString()
        val intent = buildIntent().apply {
            putExtra(EXTRA_ID, callId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val deferred = CompletableDeferred<Intent?>()
        pending[callId] = deferred
        return try {
            try {
                launch(intent)
            } catch (e: ActivityNotFoundException) {
                logger.d { "Amber not found launching request: ${scrubThrowableMessageForLogs(e)}" }
                return null
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(callId)
        }
    }
}
