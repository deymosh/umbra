package com.umbra.app.util.logging

object LogScrubber {
    private val urlRegex = Regex("""\b(?:wss?|https?)://[^\s'\")>]+""", RegexOption.IGNORE_CASE)
    private val nostrSignerRegex = Regex("""nostrsigner:[^\s'\")>]+""", RegexOption.IGNORE_CASE)
    private val hostPortRegex = Regex("""\b(?:\d{1,3}(?:\.\d{1,3}){3}|localhost)(?::\d{2,5})\b""")
    private val nostrEntityRegex = Regex("""\b(?:npub|nsec|note|nprofile|nevent)1[023456789acdefghjklmnpqrstuvwxyz]+\b""", RegexOption.IGNORE_CASE)
    private val hex64Regex = Regex("""\b[a-fA-F0-9]{64}\b""")

    fun scrubUrlForLogs(url: String?): String {
        if (url.isNullOrBlank()) return "[url]"
        val scheme = url.substringBefore("://", missingDelimiterValue = "url").lowercase()
        return when (scheme) {
            "ws", "wss", "http", "https" -> "$scheme://[redacted]"
            else -> scrubMessageForLogs(url)
        }
    }

    fun scrubEndpointForLogs(host: String?, port: Int?): String {
        if (host.isNullOrBlank() || port == null) return "[endpoint]"
        return "[endpoint]"
    }

    fun scrubPubkeyForLogs(pubkey: String?): String {
        if (pubkey.isNullOrBlank()) return "[pubkey]"
        return pubkey.take(8) + "..."
    }

    fun scrubThrowableMessageForLogs(throwable: Throwable): String =
        scrubMessageForLogs(throwable.message ?: throwable.javaClass.simpleName)

    /**
     * Returns a [Throwable] safe to pass to `android.util.Log.e(tag, msg, throwable)`.
     *
     * `Log.e`'s three-arg overload appends its own stack-trace text to the printed line via
     * `Throwable.printStackTrace()`, whose first line is the throwable's own unscrubbed
     * `toString()` (class name + raw message) — repeated for every exception in the `cause`
     * chain. That text never passes through [scrubMessageForLogs], so handing the original
     * throwable to `Log.e` reprints exactly the content [scrubThrowableMessageForLogs] was
     * used to redact. The returned throwable keeps the real stack frames (harmless — just
     * class/method/file/line) but carries a scrubbed message and no cause, so nothing
     * unscrubbed reaches the printed trace.
     */
    fun scrubThrowableForLogs(throwable: Throwable): Throwable =
        RuntimeException("${throwable.javaClass.simpleName}: ${scrubThrowableMessageForLogs(throwable)}").apply {
            stackTrace = throwable.stackTrace
        }

    fun scrubMessageForLogs(message: String?): String {
        if (message.isNullOrBlank()) return "unknown"

        return message
            .replace(urlRegex, "[url]")
            .replace(nostrSignerRegex, "nostrsigner:[redacted]")
            .replace(hostPortRegex, "[endpoint]")
            .replace(nostrEntityRegex, "[nostr-id]")
            .replace(hex64Regex, "[hex]")
    }
}