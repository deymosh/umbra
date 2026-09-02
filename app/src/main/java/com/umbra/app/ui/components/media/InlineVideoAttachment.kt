package com.umbra.app.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.datasource.DataSource
import com.umbra.app.R
import com.umbra.app.data.media.SimplePlayer
import com.umbra.app.data.media.createExoPlayerForUrl
import com.umbra.app.data.media.createPlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val DEFAULT_INLINE_VIDEO_ASPECT_RATIO = 16f / 9f
// Mirrors ui/common/ViewportPrefetchFlowPolicy.kt's VIEWPORT_PREFETCH_QUIET_MS pattern: a feed
// item composed only briefly during a fast scroll (and disposed before this elapses) never opens
// a real Tor connection or buffers anything for it at all - the LaunchedEffect below is cancelled
// on dispose before prepare() ever runs.
private const val INLINE_VIDEO_PREPARE_SETTLE_MS = 400L
private val INLINE_VIDEO_CORNER = RoundedCornerShape(12.dp)

@Composable
fun SafeInlineVideoAttachment(
    url: String,
    torDataSourceFactory: DataSource.Factory,
    onOpenFullscreen: (positionMs: Long, isPlaying: Boolean, isMuted: Boolean) -> Unit,
    // See InlineVideoAttachment's `compact` param.
    compact: Boolean = false
) {
    InlineVideoAttachment(url, torDataSourceFactory, onOpenFullscreen, compact)
}

@Composable
fun InlineVideoAttachment(
    url: String,
    torDataSourceFactory: DataSource.Factory,
    onOpenFullscreen: (positionMs: Long, isPlaying: Boolean, isMuted: Boolean) -> Unit,
    // Caps the rendered height instead of the normal full aspect-ratio sizing — same reasoning as
    // ImageAttachment's `compact` param, for a note shown as context (e.g. the "replying to" card)
    // rather than as its own post. Without this, a tall/portrait video had no size ceiling at all.
    compact: Boolean = false
) {
    val context = LocalContext.current
    val playerId = remember(url) { "inline_${java.util.UUID.randomUUID()}" }
    var videoAspectRatio by remember(url) { mutableFloatStateOf(DEFAULT_INLINE_VIDEO_ASPECT_RATIO) }
    // Reset aspect ratio when URL changes to avoid showing distorted placeholder
    LaunchedEffect(url) {
        videoAspectRatio = DEFAULT_INLINE_VIDEO_ASPECT_RATIO
    }
    var isBuffering by remember(url) { mutableStateOf(true) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var isMuted by remember(url) { mutableStateOf(true) }
    var controlsVisible by remember(url) { mutableStateOf(true) }
    var currentPositionMs by remember(url) { mutableLongStateOf(0L) }
    var totalDurationMs by remember(url) { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember(url) { mutableLongStateOf(0L) }
    var isUserSeeking by remember(url) { mutableStateOf(false) }
    var seekPositionMs by remember(url) { mutableLongStateOf(0L) }
    var playbackErrorMessage by remember(url) { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                // heightIn before aspectRatio, same reasoning as ImageAttachment's compact sizing
                // — caps the height aspectRatio is allowed to compute into, so a tall/portrait
                // video is cropped to the cap instead of blowing out a compact card's height.
                if (compact) it.heightIn(max = COMPACT_MEDIA_ATTACHMENT_HEIGHT) else it
            }
            .aspectRatio(videoAspectRatio)
            .padding(vertical = 4.dp)
            .clip(INLINE_VIDEO_CORNER),
        shape = INLINE_VIDEO_CORNER,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        val player = remember(url) {
            createExoPlayerForUrl(context, torDataSourceFactory, url, autoPrepare = false)
        }

        // A note flicked past during fast scrolling gets composed only briefly and disposed
        // before this delay elapses - Compose cancels this coroutine on dispose, so prepare()
        // (the call that actually opens a Tor connection and starts buffering) never runs for it.
        // isBuffering already defaults to true, so the spinner shown during this window looks
        // identical to ordinary early buffering - no new visual state needed.
        LaunchedEffect(url) {
            delay(INLINE_VIDEO_PREPARE_SETTLE_MS)
            player.prepare()
        }

        LaunchedEffect(playerId) {
            VideoPlaybackRegistry.activeVideoId.collectLatest { activeId ->
                if (activeId != null && activeId != playerId && player.isPlaying) {
                    player.pause()
                }
            }
        }

        DisposableEffect(url) {
            val listener = createVideoPlayerListener(
                context = context,
                player = player,
                onBufferingChanged = { isBuffering = it },
                onDurationChanged = { totalDurationMs = it },
                onPositionChanged = { currentPositionMs = it },
                onBufferedPositionChanged = { bufferedPositionMs = it },
                onPlaybackReady = { playbackErrorMessage = null },
                onIsPlayingChanged = { isPlaying = it },
                onPlaybackError = { playbackErrorMessage = it },
                onVideoSizeChanged = { width, height, pixelWidthHeightRatio ->
                    computeVideoAspectRatio(width, height, pixelWidthHeightRatio)?.let {
                        videoAspectRatio = it
                    }
                }
            )
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        LaunchedEffect(url, isPlaying, isBuffering, isUserSeeking) {
            val resolvedDuration = player.duration.coerceAtLeast(0L)
            if (resolvedDuration != totalDurationMs) {
                totalDurationMs = resolvedDuration
            }
            if (!isUserSeeking) {
                val resolvedPosition = player.currentPosition.coerceAtLeast(0L)
                if (resolvedPosition != currentPositionMs) {
                    currentPositionMs = resolvedPosition
                }
            }
            bufferedPositionMs = player.bufferedPosition

            if (!shouldRunVideoProgressTicker(isPlaying, isBuffering, isUserSeeking)) {
                return@LaunchedEffect
            }

            while (shouldRunVideoProgressTicker(isPlaying, isBuffering, isUserSeeking)) {
                if (!isUserSeeking) {
                    val resolvedPosition = player.currentPosition.coerceAtLeast(0L)
                    if (resolvedPosition != currentPositionMs) {
                        currentPositionMs = resolvedPosition
                    }
                }
                val nextDuration = player.duration.coerceAtLeast(0L)
                if (nextDuration != totalDurationMs) {
                    totalDurationMs = nextDuration
                }
                bufferedPositionMs = player.bufferedPosition
                delay(videoProgressTickerDelayMs(isPlaying))
            }
        }

        LaunchedEffect(isPlaying, controlsVisible) {
            if (isPlaying && controlsVisible) {
                delay(2500)
                if (isPlaying) controlsVisible = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isPlaying) {
                    detectTapGestures {
                        controlsVisible = if (isPlaying) !controlsVisible else true
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    createPlayerView(viewContext, player).apply { useController = false }
                }
            )
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            VideoErrorOverlay(
                message = playbackErrorMessage,
                onRetry = {
                    playbackErrorMessage = null
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(url)
                    player.prepare()
                    VideoPlaybackRegistry.activeVideoId.value = playerId
                    player.play()
                }
            )

            if (controlsVisible || !isPlaying) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 2.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                IconButton(
                    onClick = {
                        isMuted = !isMuted
                        player.volume = if (isMuted) 0f else 1f
                    }
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) stringResource(R.string.video_unmute_action) else stringResource(R.string.video_mute_action),
                        tint = Color.White
                    )
                }

                IconButton(onClick = { onOpenFullscreen(player.currentPosition, player.isPlaying, isMuted) }) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = stringResource(R.string.video_fullscreen_action),
                        tint = Color.White
                    )
                }
            }
            }

            if (controlsVisible || !isPlaying) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    VideoTimeline(
                        currentPositionMs = if (isUserSeeking) seekPositionMs else currentPositionMs,
                        totalDurationMs = totalDurationMs,
                        bufferedPositionMs = bufferedPositionMs,
                        compact = true,
                        onSeekChanged = { value ->
                            if (!isUserSeeking) {
                                isUserSeeking = true
                            }
                            seekPositionMs = value
                        },
                        onSeekFinished = {
                            player.seekTo(seekPositionMs)
                            currentPositionMs = seekPositionMs
                            isUserSeeking = false
                            controlsVisible = true
                        }
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = formatVideoDuration(if (isUserSeeking) seekPositionMs else currentPositionMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                        Text(
                            text = formatVideoDuration(totalDurationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                val target = (player.currentPosition - 5_000L).coerceAtLeast(0L)
                                player.seekTo(target)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Default.FastRewind,
                                    contentDescription = stringResource(R.string.video_seek_back_5_action),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = {
                                if (isPlaying) {
                                    player.pause()
                                    controlsVisible = true
                                } else {
                                    if (player.playbackState == SimplePlayer.STATE_ENDED) {
                                        player.seekTo(0L)
                                    }
                                    VideoPlaybackRegistry.activeVideoId.value = playerId
                                    player.play()
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) {
                                        stringResource(R.string.video_pause_action)
                                    } else {
                                        stringResource(R.string.video_play_action)
                                    },
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = {
                                player.seekTo(player.currentPosition + 5_000L)
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Default.FastForward,
                                    contentDescription = stringResource(R.string.video_seek_forward_5_action),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
