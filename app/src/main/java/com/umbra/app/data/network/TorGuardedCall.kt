package com.umbra.app.data.network

import com.umbra.app.TorProxyConfig
import com.umbra.app.util.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scrubbed DEBUG-level failure log — the one line duplicated identically at every one-shot call's onFailure site. */
fun logNetworkFailure(logger: Logger, context: String, throwable: Throwable) {
    logger.d { "$context: ${scrubThrowableMessageForLogs(throwable)}" }
}

/**
 * Runs [block] on Dispatchers.IO after confirming TorProxyConfig.isReady, wrapped in runCatching,
 * logging failure via [logNetworkFailure]. Callers still build their own Request/parse their own
 * response inline — this only removes the guard+dispatch+runCatching+log boilerplate around that.
 */
suspend fun <T> torGuardedCall(logger: Logger, context: String, block: suspend () -> T): Result<T> =
    withContext(Dispatchers.IO) {
        if (!TorProxyConfig.isReady) {
            return@withContext Result.failure(IllegalStateException("Tor not ready"))
        }
        runCatching { block() }.onFailure { e -> logNetworkFailure(logger, context, e) }
    }
