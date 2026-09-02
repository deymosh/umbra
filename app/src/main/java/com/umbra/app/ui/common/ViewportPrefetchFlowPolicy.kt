package com.umbra.app.ui.common

import kotlinx.coroutines.delay

internal const val VIEWPORT_PREFETCH_QUIET_MS = 500L

internal suspend fun awaitViewportPrefetchQuietWindow(
    visibleCount: Int,
    quietWindowMs: Long = VIEWPORT_PREFETCH_QUIET_MS
): Boolean {
    if (visibleCount <= 0) return false
    delay(quietWindowMs)
    return true
}
