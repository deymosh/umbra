package com.umbra.app.domain.nip36

import com.umbra.app.domain.nip01.Event

/** NIP-36 sensitive-content marker. [reason] is the tag's optional second value. */
data class ContentWarning(val reason: String?)

/** Returns null if [event] has no `content-warning` tag at all. */
fun extractContentWarning(event: Event): ContentWarning? {
    val tag = event.tags.firstOrNull { it.getOrNull(0) == "content-warning" } ?: return null
    return ContentWarning(reason = tag.getOrNull(1)?.takeIf { it.isNotBlank() })
}

/** Builds a `["content-warning", reason?]` tag to attach to an outgoing event. */
fun contentWarningTag(reason: String? = null): List<String> {
    val trimmedReason = reason?.trim()?.takeIf { it.isNotBlank() }
    return if (trimmedReason != null) listOf("content-warning", trimmedReason) else listOf("content-warning")
}
