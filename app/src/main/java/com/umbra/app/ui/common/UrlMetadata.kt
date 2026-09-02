package com.umbra.app.ui.common

import androidx.compose.runtime.Immutable

/**
 * Metadata extracted from URL preview (Open Graph tags)
 * Thread-safe and immutable for Compose state
 */
@Immutable
data class UrlMetadata(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val host: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val hasMetadata: Boolean
        get() = !title.isNullOrBlank() || !description.isNullOrBlank() || !imageUrl.isNullOrBlank()
}
