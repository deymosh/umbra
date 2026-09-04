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
 * cancelled, preserving the cancel-the-old-before-the-new-runs ordering every existing call site
 * already depends on. A non-lazy launch would let the replacement begin executing concurrently
 * with its predecessor's cancellation instead of strictly after it.
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
