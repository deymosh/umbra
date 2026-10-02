package com.umbra.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Calls [onLoadMore] once the last visible row is within [threshold] rows of the end of a list
 * holding [itemCount] rows — including a list too short to fill the screen, which never scrolls
 * and so would otherwise never ask for an older page.
 */
@Composable
fun LoadMoreEffect(
    listState: LazyListState,
    itemCount: Int,
    enabled: Boolean,
    threshold: Int = 4,
    onLoadMore: () -> Unit
) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val nearEnd by remember(listState, itemCount, enabled, threshold) {
        derivedStateOf {
            if (!enabled || itemCount == 0) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 1 - threshold
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) currentOnLoadMore()
    }
}
