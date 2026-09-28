package com.umbra.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.theme.UmbraTheme

/**
 * One glanceable answer to "am I private and connected?": the onion's colour is the Tor state
 * (secure / connecting / down) and the number beside it is how many relays are live. Replaces two
 * separate badges whose meanings users had to learn independently.
 */
@Composable
fun NetworkStatusPill(
    isTorConnected: Boolean,
    isTorStarting: Boolean,
    relayCount: Int,
    relaysConnected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val torTint by animateColorAsState(
        targetValue = when {
            isTorConnected -> UmbraTheme.colors.secure
            isTorStarting -> UmbraTheme.colors.caution
            else -> MaterialTheme.colorScheme.error
        },
        label = "torTint"
    )
    val description = when {
        !isTorConnected && isTorStarting -> stringResource(R.string.network_status_starting_cd)
        !isTorConnected -> stringResource(R.string.network_status_offline_cd)
        relaysConnected -> stringResource(R.string.network_status_secure_cd, relayCount)
        else -> stringResource(R.string.network_status_relays_offline_cd)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, torTint.copy(alpha = 0.35f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = UmbraIcons.Onion,
            contentDescription = null,
            tint = torTint,
            modifier = Modifier.size(16.dp)
        )
        if (relayCount > 0) {
            Text(
                text = if (relaysConnected) relayCount.toString() else "–",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = if (relaysConnected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}
