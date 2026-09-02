package com.umbra.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NotesTimelineContainer(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listHorizontalPadding: Dp = 0.dp,
    listVerticalPadding: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    topOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    // Not a BoxScope receiver: this container owns BottomCenter alignment for the overlay (see
    // below) so it can measure the overlay's real rendered height — including the caller's own
    // padding and the system nav bar inset from QuickActionBottomBar's navigationBarsPadding() —
    // and reserve exactly that much extra bottom list padding. A fixed guess here previously let
    // list content (e.g. the last relay card on ProfileScreen's Relays tab) render underneath
    // the floating bar.
    bottomOverlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var bottomOverlayHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = listHorizontalPadding, vertical = listVerticalPadding),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding() + bottomOverlayHeight
            ),
            verticalArrangement = verticalArrangement,
            content = content
        )

        topOverlay()

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    bottomOverlayHeight = with(density) { size.height.toDp() }
                }
        ) {
            bottomOverlay()
        }
    }
}
