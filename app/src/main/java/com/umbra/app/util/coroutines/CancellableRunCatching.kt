package com.umbra.app.util.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Same shape as the stdlib `runCatching`, except a [CancellationException] is rethrown instead of
 * captured into the returned [Result]. `CancellationException` is a subtype of `Exception`/
 * `Throwable` in Kotlin, so the stdlib `runCatching` (and a bare `catch (e: Exception)`) silently
 * swallows it — turning structured cancellation into an ordinary, loggable-and-continue failure
 * instead of letting the coroutine actually unwind. Every call site in this codebase that wraps a
 * suspend call in `runCatching` inside a scope-launched coroutine should use this instead of the
 * stdlib version (see the `kotlin-coroutines-structured-concurrency` skill's "Swallowing
 * CancellationException" section — this is that fix, packaged once instead of repeated at every
 * call site).
 *
 * Marked `inline` (matching the stdlib `runCatching` it replaces) so [block] may call suspend
 * functions transparently when invoked from a suspend context, without this function itself
 * needing a `suspend` modifier.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
