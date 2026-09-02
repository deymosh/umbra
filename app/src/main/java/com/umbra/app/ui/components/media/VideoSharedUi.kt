package com.umbra.app.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import java.util.Locale

@Composable
internal fun VideoErrorOverlay(
    message: String?,
    onRetry: () -> Unit
) {
    message?.let { resolvedMessage ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(
                    Color.Black.copy(alpha = 0.7f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = resolvedMessage,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.video_retry_action),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
internal fun VideoTimeline(
    currentPositionMs: Long,
    totalDurationMs: Long,
    bufferedPositionMs: Long,
    compact: Boolean,
    onSeekChanged: (Long) -> Unit,
    onSeekFinished: () -> Unit
) {
    val clampedDuration = totalDurationMs.coerceAtLeast(0L)
    val progress = if (clampedDuration > 0L) {
        currentPositionMs.coerceIn(0L, clampedDuration).toFloat() / clampedDuration.toFloat()
    } else {
        0f
    }
    val bufferedFraction = if (clampedDuration > 0L) {
        bufferedPositionMs.coerceIn(0L, clampedDuration).toFloat() / clampedDuration.toFloat()
    } else {
        0f
    }
    var timelineSize by remember(clampedDuration) { mutableStateOf(IntSize.Zero) }
    val touchHeight = if (compact) 20.dp else 24.dp
    val barHeight = if (compact) 2.dp else 3.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .onSizeChanged { timelineSize = it }
            .pointerInput(clampedDuration, timelineSize) {
                detectTapGestures { tapOffset ->
                    if (clampedDuration <= 0L || timelineSize.width <= 0) return@detectTapGestures
                    val fraction = (tapOffset.x / timelineSize.width.toFloat()).coerceIn(0f, 1f)
                    onSeekChanged((fraction * clampedDuration).toLong())
                    onSeekFinished()
                }
            }
            .pointerInput(clampedDuration, timelineSize) {
                detectDragGestures(
                    onDragEnd = { onSeekFinished() },
                    onDragCancel = { onSeekFinished() }
                ) { change, _ ->
                    if (clampedDuration <= 0L || timelineSize.width <= 0) return@detectDragGestures
                    change.consume()
                    val fraction = (change.position.x / timelineSize.width.toFloat()).coerceIn(0f, 1f)
                    onSeekChanged((fraction * clampedDuration).toLong())
                }
            }
            .height(touchHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                .height(barHeight)
                .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(barHeight)
                .background(Color.White, RoundedCornerShape(50))
        )
    }
}

internal fun formatVideoDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
