package com.umbra.app.ui.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits [Unit] immediately, then again on a fixed interval, purely to force a downstream
 * `combine`/`mapLatest` chain to re-evaluate a wall-clock-dependent filter (e.g.
 * [com.umbra.app.domain.nip01.Event.isFromFuture]) that no Room/DB change would otherwise
 * re-trigger. Without the periodic re-emission, an event hidden for being future-dated stays
 * hidden past its own timestamp until some unrelated write happens to re-emit the flow it's
 * filtered in. The immediate first emission matters just as much: every call site combines this
 * with other flows via `combine()`, which withholds all output until *every* source has emitted
 * at least once — without it, nothing downstream (the whole feed, a profile's notes, a thread's
 * replies) can render until the first interval elapses, and `WhileSubscribed`/per-screen
 * ViewModel lifecycles mean that wait can keep resetting instead of ever completing.
 */
fun futureEventRecheckTicker(intervalMs: Long = 30_000L): Flow<Unit> = flow {
    emit(Unit)
    while (true) {
        delay(intervalMs)
        emit(Unit)
    }
}
