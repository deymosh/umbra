package com.umbra.app.domain.nip68

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip36.ContentWarning
import com.umbra.app.domain.nip36.extractContentWarning
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.nip92.extractImetaTags

/**
 * NIP-68 picture-first post: a kind-20 event where the images live entirely in `imeta` tags
 * (never inline in `.content`, unlike a plain text note with a pasted URL).
 */
data class PictureEvent(
    val title: String?,
    val images: List<ImetaTag>,
    val description: String,
    val contentWarning: ContentWarning?
)

/** Returns null if [event] is not kind 20. */
fun extractPictureEvent(event: Event): PictureEvent? {
    if (event.kind != Event.KIND_PICTURE) return null
    val title = event.tags.firstOrNull { it.getOrNull(0) == "title" }?.getOrNull(1)
    return PictureEvent(
        title = title,
        images = extractImetaTags(event.tags).values.toList(),
        description = event.content,
        contentWarning = extractContentWarning(event)
    )
}
