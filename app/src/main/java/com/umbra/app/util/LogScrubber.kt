package com.umbra.app.util

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