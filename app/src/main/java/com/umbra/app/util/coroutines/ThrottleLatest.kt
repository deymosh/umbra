package com.umbra.app.util.coroutines

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * Deliver-first-immediately-then-throttle-bursts: the first value a collector sees is emitted
 * instantly (no initial blank delay — the screen-scoped ViewModels that use this would
 * otherwise render empty for a whole flush window on every fresh open), and every value
 * after that is held for [intervalMs] with only the LATEST one surviving the hold.
 *
 * Semantics (both verified with runTest virtual time in ThrottleLatestTest):
 * - first emission after each collection start: immediate;
 * - afterwards at most one emission per [intervalMs] window;
 * - a value arriving while the previous one is still being held cancels that hold
 *   (collectLatest) and restarts it with the newer value — so what lands when the window
 *   closes is always the newest, never an older snapshot that trailed behind.
 *
 * Replaces RelayConfigViewModel's hand-rolled producer+delay-flush-loop pairs
 * (observeRelays/observeRelayRequests/observeRelayIssues), whose pending buffers and Mutexes
 * existed only to bridge a plain-collect coroutine and a timer coroutine touching the same
 * mutable state. As a single flow operator there is no shared pending state at all — the
 * whole throttle lives in one coroutine on the collector's own dispatcher.
 *
 * This is NOT conflated dropping: every distinct item is eventually delivered, either
 * immediately (first after each emitted window) or as the surviving latest of its window —
 * coalesced relayIssues are preserved, and superseded relayRequests/list snapshots are
 * represented by the newest one, which is exactly the intent those call sites documented.
 */
internal fun <T> Flow<T>.throttleLatest(intervalMs: Long): Flow<T> = flow {
    var throttling = false
    collectLatest { value ->
        if (throttling) {
            // Hold window: superseded by each newer upstream value (collectLatest cancels
            // this suspend and restarts with it), so the survivor is always the latest.
            delay(intervalMs)
        }
        throttling = true
        emit(value)
    }
}
