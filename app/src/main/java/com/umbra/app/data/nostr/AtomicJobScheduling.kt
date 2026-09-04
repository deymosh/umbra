package com.umbra.app.data.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Skip-if-already-active scheduling: if the job currently held by this reference has not yet
 * completed, [block] is not run and this returns false. Otherwise, [block] is launched on
 * [scope] and installed into this reference, returning true.
 *
 * The idle check is `!observed.isCompleted` rather than `observed.isActive`: a freshly-installed
 * candidate is not yet active until its own `start()` call returns, so a check keyed on active-
 * ness alone would leave a window, between this call's own `compareAndSet` succeeding and its
 * `start()` running, where a second concurrent caller reads that same not-yet-started candidate,
 * sees it as neither active nor completed-by-isActive's definition, and wins its own
 * compare-and-set against it — orphaning the first candidate and running two bodies instead of
 * one. `isCompleted` is false for the entire span from creation through actual completion
 * (started or not), so no such window exists.
 *
 * The candidate is created with [CoroutineStart.LAZY] and only ever `start()`ed after winning a
 * single `compareAndSet` against the value observed at the top of this call — never before. A
 * non-lazy launch would let the candidate begin running immediately, so a caller that then loses
 * the compare-and-set would have to cancel a job that may already have executed part of its
 * body; starting lazily and only on the winning path means a losing candidate is cancelled
 * before it can execute a single statement, so it never becomes an orphaned side effect.
 *
 * There is exactly one compare-and-set attempt — no retry loop, no spin. A caller that loses
 * simply reports that someone else already scheduled the work.
 */
internal fun AtomicReference<Job?>.launchIfIdle(
    scope: CoroutineScope,
    block: suspend CoroutineScope.() -> Unit
): Boolean {
    val observed = get()
    if (observed != null && !observed.isCompleted) return false
    val candidate = scope.launch(start = CoroutineStart.LAZY, block = block)
    return if (compareAndSet(observed, candidate)) {
        candidate.start()
        true
    } else {
        candidate.cancel()
        false
    }
}

/**
 * Cancel-and-replace scheduling: [block] always runs, and whatever job this reference previously
 * held is cancelled.
 *
 * The candidate is created lazily and only `start()`ed after the displaced job has been
 * cancelled, so within a single call to this function the cancel-the-old-before-the-new-runs
 * ordering holds: a non-lazy launch would let the replacement begin executing concurrently with
 * its own predecessor's cancellation instead of strictly after it.
 *
 * That ordering guarantee is per-call, not per-reference. `getAndSet` is the only atomic step;
 * the cancel and the subsequent `start()` are two separate, unsynchronized statements. If two
 * threads call this function on the same [AtomicReference] with no external synchronization, the
 * job installed by the first call can legitimately still be running (cooperative cancellation
 * only takes effect at its next suspension point) at the moment the second call's `start()`
 * executes, so the two bodies can execute concurrently for a window. Every current call site
 * (`EventIngestCache.insertDebounceJob`, `NostrSessionManager.userBackfillJob`/
 * `ownProfileBootstrapWatcherJob`) tolerates that window because the displaced work is itself
 * idempotent/re-derivable, not because this function prevents the overlap. A caller that cannot
 * tolerate the overlap must serialize its own calls to this function (e.g. via its own `Mutex`)
 * rather than relying on this function alone.
 */
internal fun AtomicReference<Job?>.launchReplacing(
    scope: CoroutineScope,
    block: suspend CoroutineScope.() -> Unit
) {
    val candidate = scope.launch(start = CoroutineStart.LAZY, block = block)
    val previous = getAndSet(candidate)
    previous?.cancel()
    candidate.start()
}
