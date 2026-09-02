package com.umbra.app.util.logging

/**
 * Factory for per-class tagged [Logger] instances. This is the single entry
 * point into the logging utility — callers obtain their own [Logger] bound
 * to one tag rather than passing a tag at every call site.
 */
object UmbraLog {
    fun tag(tag: String): Logger = Logger(tag)
}
