package com.umbra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.theme.UmbraTheme

/**
 * The feed's floating navigation: a quiet pill of destinations around one bright compose button.
 * Home is always the current destination here (this bar only lives on the feed), so it carries the
 * selected treatment and doubles as "back to top".
 */
@Composable
fun QuickActionBottomBar(
    modifier: Modifier = Modifier,
    onGoTop: () -> Unit,
    onCompose: () -> Unit,
    onRelays: () -> Unit,
    onSettings: () -> Unit,
    onFilters: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val pillShape = CircleShape
        Surface(
            shape = pillShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shadowElevation = 12.dp,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, pillShape)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem(
                    icon = Icons.Rounded.Home,
                    contentDescription = stringResource(R.string.go_to_top),
                    selected = true,
                    onClick = onGoTop
                )
                NavItem(
                    icon = Icons.Outlined.Hub,
                    contentDescription = stringResource(R.string.menu_relays),
                    onClick = onRelays
                )
                ComposeButton(onClick = onCompose)
                if (onFilters != null) {
                    NavItem(
                        icon = Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.menu_feed_filters),
                        onClick = onFilters
                    )
                }
                NavItem(
                    icon = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    onClick = onSettings
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
                } else {
                    Modifier
                }
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** The compose action as a lit eclipse: corona-colored disc with a soft outer glow. */
@Composable
private fun ComposeButton(onClick: () -> Unit) {
    val corona = UmbraTheme.colors.corona
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .size(54.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.6f to corona.copy(alpha = 0.45f),
                        1f to Color.Transparent,
                        center = center,
                        radius = size.minDimension / 2f + 10.dp.toPx()
                    ),
                    radius = size.minDimension / 2f + 10.dp.toPx()
                )
            }
            .clip(CircleShape)
            .background(corona)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = stringResource(R.string.compose_note_cd),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}
