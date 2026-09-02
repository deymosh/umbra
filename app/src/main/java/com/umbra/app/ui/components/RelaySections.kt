package com.umbra.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun <T> LazyListScope.relayRoleSection(
    title: String,
    actionLabel: String? = null,
    emptyTitle: String,
    items: List<T>,
    keyFactory: (T) -> Any,
    onAction: (() -> Unit)? = null,
    infoContent: (@Composable () -> Unit)? = null,
    itemContent: @Composable (T) -> Unit
) {
    item {
        SectionHeader(
            title = title,
            actionLabel = actionLabel,
            onAction = onAction,
            infoContent = infoContent
        )
    }

    if (items.isEmpty()) {
        item {
            EmptyState(
                title = emptyTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
        }
    } else {
        // Every row renders the same RelayCard shape, so a constant contentType lets Compose
        // reuse slots across section boundaries during scroll (same idea as notesFeedSection's
        // per-kind contentType, just a single shared type here since relay rows don't vary).
        items(items, key = keyFactory, contentType = { "relay_card" }) { row ->
            itemContent(row)
        }
    }
}
