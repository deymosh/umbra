package com.umbra.app.util.logging

import android.util.Log
import com.umbra.app.domain.logging.UmbraLogger
import com.umbra.app.util.LogScrubber

/**
 * Android-backed implementation of [UmbraLogger] for one bound tag.
 *
 * Instances are obtained via [UmbraLog.tag] — the `internal` constructor
 * keeps callers going through the factory rather than repeating the tag at
 * every call site (the exact repetition this utility exists to remove).
 */
class Logger internal constructor(private val tag: String) : UmbraLogger {
    override fun d(message: () -> String) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message())
    }

    override fun w(message: () -> String) {
        if (Log.isLoggable(tag, Log.WARN)) Log.w(tag, message())
    }

    override fun e(throwable: Throwable, message: () -> String) {
        if (Log.isLoggable(tag, Log.ERROR)) {
            Log.e(tag, "${message()}: ${LogScrubber.scrubThrowableMessageForLogs(throwable)}", throwable)
        }
    }
}
