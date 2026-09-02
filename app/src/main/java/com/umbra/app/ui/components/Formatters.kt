package com.umbra.app.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for relative time formatting (e.g., "2 hours ago")
 */
object TimeFormatter {
    // SimpleDateFormat is expensive to construct and not thread-safe; every note row calls
    // formatShortDate/formatFullTime on each recomposition (no remember at the call site in
    // NoteHeader/EventCard), so a fresh instance per call adds real allocation churn to a feed
    // with hundreds of visible rows. ThreadLocal keeps one instance per thread since all call
    // sites today are Composables (main thread) but nothing here should assume that stays true.
    private val fullTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("MMM dd, h:mm a", Locale.US) }
    private val shortDateFormat = ThreadLocal.withInitial { SimpleDateFormat("MMM dd", Locale.US) }

    /**
     * Format unix timestamp as relative time
     * @param unixSeconds Unix timestamp in seconds
     * @return Relative time string like "2 hours ago", "just now", etc.
     */
    fun formatRelativeTime(unixSeconds: Long): String {
        val nowSeconds = System.currentTimeMillis() / 1000
        val secondsAgo = nowSeconds - unixSeconds

        return when {
            secondsAgo < 0 -> "in future"
            secondsAgo < 10 -> "just now"
            secondsAgo < 60 -> "${secondsAgo}s ago"
            secondsAgo < 3600 -> "${secondsAgo / 60}m ago"
            secondsAgo < 86400 -> "${secondsAgo / 3600}h ago"
            secondsAgo < 604800 -> "${secondsAgo / 86400}d ago"
            secondsAgo < 2592000 -> "${secondsAgo / 604800}w ago"
            else -> "${secondsAgo / 2592000}mo ago"
        }
    }

    /**
     * Format unix timestamp as full date/time
     * @param unixSeconds Unix timestamp in seconds
     * @return Formatted date string like "Oct 10, 2:35 PM"
     */
    fun formatFullTime(unixSeconds: Long): String {
        val date = Date(unixSeconds * 1000)
        return fullTimeFormat.get()!!.format(date)
    }

    /**
     * Format unix timestamp as short date
     * @param unixSeconds Unix timestamp in seconds
     * @return Formatted date string like "Oct 10"
     */
    fun formatShortDate(unixSeconds: Long): String {
        val date = Date(unixSeconds * 1000)
        return shortDateFormat.get()!!.format(date)
    }
}

/**
 * Truncate public key for display
 */
fun String.truncatePublicKey(start: Int = 6, end: Int = 4): String {
    return if (this.length <= start + end + 2) this else "${take(start)}...${takeLast(end)}"
}

/**
 * Format bytes as human-readable size
 */
fun Long.formatBytes(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }

    return String.format(Locale.US, "%.2f %s", size, units[unitIndex])
}
