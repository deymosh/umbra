package com.umbra.app.util.coroutines

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Deliver-first-immediately-then-throttle-bursts: the first value a collector sees is emitted
 * instantly (no initial blank delay — the screen-scoped ViewModels that use this would
 * otherwise render empty for a whole flush window on every fresh open), and every value
 * after that is throttled to at most one emission per [intervalMs] window, keeping the
 * LATEST value of the window.
 *
 * Semantics (verified with runTest virtual time in ThrottleLatestTest):
 * - the first emission after collection starts is immediate;
 * - after that, at most one emission per interval; a burst arriving inside an open window
 *   is represented by its newest value, never by an older snapshot that trailed behind;
 * - under a continuous stream faster than the interval, emissions still settle to the
 *   interval cadence — the newest snapshot keeps flowing, nothing is held back forever.
 *
 * Replaces RelayConfigViewModel's hand-rolled producer+delay-flush-loop pairs
 * (observeRelays/observeRelayRequests/observeRelayIssues), whose pending buffers and Mutexes
 * existed only to bridge a plain-collect coroutine and a timer coroutine touching the same
 * mutable state (on the same Main scope, so the locks never contended). As a single flow
 * operator there is no shared pending state at all: the upstream's latest value lives in one
 * CONFLATED channel (inherently thread-safe, latest-wins by construction) and the interval
 * pacing is plain [delay] — which is what makes it behave correctly under runTest's virtual
 * time the same way the deleted flush loops did.
 *
 * This is not conflated dropping: every upstream item is eventually represented downstream,
 * either immediately (a value arriving while no window is open) or as the survivor of its
 * window — discrete per-issue relayIssues are all preserved across successive windows, and
 * superseded full-snapshot relayRequests/relay lists collapse into the newest one, which is
 * exactly the intent the deleted call-site comments documented.
 */
internal fun <T> Flow<T>.throttleLatest(intervalMs: Long): Flow<T> = channelFlow {
    // Latest-wins hold cell: CONFLATED keeps only the most recent value a received-but-not
    // yet drained send chain left behind, so any number of upstream bursts in a window
    // collapse to one readable newest value with no locks or plain-var races.
    val hold = Channel<T>(capacity = Channel.CONFLATED)
    launch {
        try {
            collect { hold.send(it) }
        } finally {
            hold.close()
        }
    }

    var emittedFirst = false
    while (true) {
        val value = try {
            hold.receive()
        } catch (_: ClosedReceiveChannelException) {
            break
        }
        if (!emittedFirst) {
            emittedFirst = true
            send(value)
            continue
        }
        // Open the window: values arriving while this delay runs replace the held one
        // (CONFLATED), so the post-window drain below reads the newest, not this one.
        delay(intervalMs)
        val newest = hold.tryReceive().getOrNull() ?: value
        send(newest)
    }
}

/**
 * Batch-preserving sibling of [throttleLatest] for DISCRETE-ITEM upstreams where every value
 * must survive coalescing (relayIssues: dropping one would silently discard that relay's
 * history instead of superseding it). Emits List batches: the first arrival is flushed
 * instantly, then at most one batch per [intervalMs] window containing everything that
 * arrived since — no values dropped, no plain mutable buffer, the interval pacing is the
 * same [delay]-based plan the deleted producer/timer pairs used (virtual-time friendly).
 */
internal fun <T> Flow<T>.throttleLatestBatch(intervalMs: Long): Flow<List<T>> = channelFlow {
    // UNLIMITED buffer: thread-safe queue holding every value until its window drains; a
    // replica of the deleted pendingIssues list, minus the Mutex (same scope, no contention).
    val hold = Channel<T>(capacity = Channel.UNLIMITED)
    launch {
        try {
            collect { hold.send(it) }
        } finally {
            hold.close()
        }
    }

    while (true) {
        // Block until at least one value arrives — a quiet stream emits nothing, matching
        // the deleted loop's "empty pending list is a no-op" tick behavior.
        val first = try {
            hold.receive()
        } catch (_: ClosedReceiveChannelException) {
            break
        }
        val batch = mutableListOf(first)
        // Everything that arrived inside the (possibly zero-length) window so far.
        while (true) {
            hold.tryReceive().getOrNull()?.let { batch += it } ?: break
        }
        send(batch.toList())
        // Close the window: the next batch holds until the interval elapses, absorbing
        // whatever lands meanwhile (the next iteration's tryReceive drain).
        delay(intervalMs)
    }
}

