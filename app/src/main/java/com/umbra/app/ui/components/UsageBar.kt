package com.umbra.app.ui.components

import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp


/**
 * A thin, threshold-colored progress bar for a used/max ratio (e.g. heap usage). Green below
 * [warningThreshold], amber up to [criticalThreshold], red above it.
 */
@Composable
fun UsageBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    warningThreshold: Float = 0.6f,
    criticalThreshold: Float = 0.8f
) {
    val color = when {
        fraction >= criticalThreshold -> MaterialTheme.colorScheme.error
        fraction >= warningThreshold -> UmbraTheme.colors.caution
        else -> UmbraTheme.colors.secure
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    )
}
