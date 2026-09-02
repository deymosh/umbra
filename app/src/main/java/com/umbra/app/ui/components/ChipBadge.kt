package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ChipBadge(
    modifier: Modifier = Modifier,
    text: String,
    backgroundColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    onClick: (() -> Unit)? = null,
    // Collapse controls (e.g. "Show less") point the chevron back the other way, so the icon
    // itself signals expand-vs-collapse instead of every clickable chip looking identical.
    collapses: Boolean = false,
    // A chevron implies "tap to go somewhere" (RelayTelemetryCard's subscriptions chip, the
    // collapse controls above) — wrong affordance for a chip whose tap actually deletes it (e.g.
    // an excluded-tag/hashtag chip in the feed filter editor). removable swaps in an X instead.
    removable: Boolean = false
) {
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        // A clickable chip is otherwise visually identical to a static one (same shape/color as
        // every other telemetry chip next to it) — nothing signals it's tappable. The trailing
        // chevron is the same "this leads somewhere" affordance relay rows already use elsewhere
        // in this screen (see RelayCard's own ChevronRight).
        if (onClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
                Icon(
                    imageVector = when {
                        removable -> Icons.Default.Close
                        collapses -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
                        else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
