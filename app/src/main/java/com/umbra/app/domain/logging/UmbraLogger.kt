package com.umbra.app.domain.logging

/**
 * Pure-Kotlin logging port for `domain/` callers.
 *
 * Domain code must depend only on this interface — never on the concrete,
 * Android-backed implementation in `util.logging.Logger`. Every message
 * parameter is a deferred lambda so that message construction (including any
 * manual scrubbing) only happens when the level is actually loggable, the
 * same guarantee the previous inline `Log.isLoggable` guards provided.
 */
interface UmbraLogger {
    fun d(message: () -> String)
    fun w(message: () -> String)
    fun e(throwable: Throwable, message: () -> String)
}

/**
 * No-op default. Used by callers (e.g. [com.umbra.app.domain.nip19.Bech32Encoder])
 * before a real logger is wired at app startup, and by plain JUnit tests that
 * construct domain classes directly without going through Hilt.
 */
object NoOpUmbraLogger : UmbraLogger {
    override fun d(message: () -> String) {}
    override fun w(message: () -> String) {}
    override fun e(throwable: Throwable, message: () -> String) {}
}
