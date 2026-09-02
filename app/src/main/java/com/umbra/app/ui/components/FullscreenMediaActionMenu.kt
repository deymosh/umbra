package com.umbra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R

/**
 * One action in a [FullscreenMediaActionMenu] (download, copy URL, share, etc.).
 */
data class FullscreenMediaAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit
)

/**
 * Collapsible action menu for the fullscreen image/video viewers, anchored to a corner. Starts
 * collapsed to a single Settings-gear toggle (not the three-dot MoreVert glyph — visually too
 * thin/narrow a tap target here) so a tall/narrow (portrait) image — which already fills most of
 * the width near the top of the screen, right where a display cutout would be too — isn't fighting
 * a whole row of icon buttons for the same cramped space. Expanding swaps the toggle's icon to
 * Close (tap again to collapse) and reveals [actions] either as a vertical stack below the toggle
 * (portrait content) or a horizontal strip beside it (landscape content) — the toggle's own
 * position never moves, only what's shown next to it.
 *
 * There's deliberately no "dismiss the whole viewer" action in here — that's always reachable via
 * back, so a dedicated close icon here would be redundant with menu-open a second, more prominent
 * X right next to it.
 */
@Composable
fun FullscreenMediaActionMenu(
    actions: List<FullscreenMediaAction>,
    isContentPortrait: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val toggle: @Composable () -> Unit = {
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Settings,
                contentDescription = stringResource(
                    if (expanded) R.string.fullscreen_media_menu_collapse else R.string.fullscreen_media_menu_expand
                ),
                tint = Color.White
            )
        }
    }

    if (isContentPortrait) {
        Column(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            toggle()
            if (expanded) {
                actions.forEach { action -> ActionIconButton(action) }
            }
        }
    } else {
        Row(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (expanded) {
                actions.forEach { action -> ActionIconButton(action) }
            }
            toggle()
        }
    }
}

@Composable
private fun ActionIconButton(action: FullscreenMediaAction) {
    IconButton(onClick = action.onClick) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.contentDescription,
            tint = Color.White
        )
    }
}
