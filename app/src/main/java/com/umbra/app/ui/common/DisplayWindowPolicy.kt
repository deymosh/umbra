package com.umbra.app.ui.common

/**
 * Shared ceiling for the sliding render windows the feed-like ViewModels keep: a row count the
 * backing query is re-subscribed with to reveal progressively older rows as the user scrolls.
 *
 * Every growth step re-runs the backing query's full select/sort, so the window is bounded rather
 * than allowed to grow toward the size of the cache — nobody reads thousands of rows in one
 * sitting, and a window that only ever grows only ever adds cost. Callers reset the window to
 * their initial size once the user is back at the top.
 */
internal const val DISPLAY_WINDOW_MAX_LIMIT = 1500

/** [current] grown by one [pageSize], clamped to [max]. */
internal fun nextDisplayLimit(current: Int, pageSize: Int, max: Int = DISPLAY_WINDOW_MAX_LIMIT): Int =
    (current + pageSize).coerceAtMost(max)
