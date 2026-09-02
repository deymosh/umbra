package com.umbra.app.ui.components.media

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.datasource.DataSource
import com.umbra.app.R
import com.umbra.app.data.media.SimplePlayer
import com.umbra.app.data.media.createExoPlayerForUrl
import com.umbra.app.data.media.createPlayerView
import com.umbra.app.ui.components.FullscreenMediaAction
import com.umbra.app.ui.components.FullscreenMediaActionMenu
import com.umbra.app.ui.components.ImmersiveSystemBarsEffect
import com.umbra.app.ui.components.LocalMediaLoadPriorityGate
import com.umbra.app.ui.components.enqueueVideoDownload
import com.umbra.app.ui.components.shareVideoUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun FullscreenVideoDialogOptIn(
    videoUrl: String,
    torDataSourceFactory: DataSource.Factory,
    initialPositionMs: Long,
    initialPlayWhenReady: Boolean,
    initialMuted: Boolean,
    onDismiss: () -> Unit,
    onStateCaptured: (positionMs: Long, wasPlaying: Boolean) -> Unit
) {
    FullscreenVideoDialog(
        videoUrl = videoUrl,
        torDataSourceFactory = torDataSourceFactory,
        initialPositionMs = initialPositionMs,
        initialPlayWhenReady = initialPlayWhenReady,
        initialMuted = initialMuted,
        onDismiss = onDismiss,
        onStateCaptured = onStateCaptured
    )
}

@Composable
fun FullscreenVideoDialog(
    videoUrl: String,
    torDataSourceFactory: DataSource.Factory,
    initialPositionMs: Long,
    initialPlayWhenReady: Boolean,
    initialMuted: Boolean,
    onDismiss: () -> Unit,
    onStateCaptured: (positionMs: Long, wasPlaying: Boolean) -> Unit
) {
    val context = LocalContext.current
    val mediaLoadPriorityGate = LocalMediaLoadPriorityGate.current
    val scope = rememberCoroutineScope()
    val playerId = remember { "fullscreen_${java.util.UUID.randomUUID()}" }
    var isBuffering by remember(videoUrl) { mutableStateOf(true) }
    var isPlaying by remember(videoUrl) { mutableStateOf(false) }
    var isMuted by remember(videoUrl) { mutableStateOf(initialMuted) }
    var controlsVisible by remember(videoUrl) { mutableStateOf(true) }
    var currentPositionMs by remember(videoUrl) { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
    var totalDurationMs by remember(videoUrl) { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember(videoUrl) { mutableLongStateOf(0L) }
    var isUserSeeking by remember(videoUrl) { mutableStateOf(false) }
    var seekPositionMs by remember(videoUrl) { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
    var playbackErrorMessage by remember(videoUrl) { mutableStateOf<String?>(null) }
    var actionMenuExpanded by remember(videoUrl) { mutableStateOf(false) }

    DisposableEffect(mediaLoadPriorityGate) {
        val priorityLease = mediaLoadPriorityGate.beginInteractiveLoad()
        onDispose { priorityLease.close() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false: without it the dialog window is auto-fitted by the OS
        // to avoid the status/navigation bars, so fillMaxSize() below fills that *shrunk* area,
        // not the true screen — the visible "gap" at the top in portrait, and at the top and one
        // side in landscape (wherever the system bar landed for that rotation), plus everything
        // manually padded for those same insets below (systemBarsPadding)
        // getting double-compensated on top of a window the OS already moved. With this false,
        // the window genuinely covers the whole screen and those padding modifiers become the
        // only (correct, single) place insets are accounted for.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        ImmersiveSystemBarsEffect()

        val player = remember(videoUrl, initialPositionMs, initialPlayWhenReady) {
            createExoPlayerForUrl(context, torDataSourceFactory, videoUrl, initialPositionMs, initialPlayWhenReady, initialMuted)
        }

        LaunchedEffect(playerId) {
            // Claim the active slot immediately so any inline player pauses on fullscreen open
            VideoPlaybackRegistry.activeVideoId.value = playerId
            VideoPlaybackRegistry.activeVideoId.collectLatest { activeId ->
                if (activeId != null && activeId != playerId && player.isPlaying) {
                    player.pause()
                }
            }
        }

        DisposableEffect(videoUrl) {
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
                // Deliberate no-op: FullscreenVideoDialog renders via fillMaxSize() with no
                // aspect-ratio-driven container to update, unlike InlineVideoAttachment's real
                // computeVideoAspectRatio-backed callback.
                onVideoSizeChanged = { _, _, _ -> }
            )
            player.addListener(listener)
            onDispose {
                onStateCaptured(player.currentPosition, player.isPlaying)
                player.removeListener(listener)
                player.release()
            }
        }

        LaunchedEffect(videoUrl, isPlaying, isBuffering, isUserSeeking) {
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
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
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
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
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
                        player.setMediaItem(videoUrl)
                        player.prepare()
                        VideoPlaybackRegistry.activeVideoId.value = playerId
                        player.play()
                    }
                )

                if (controlsVisible || !isPlaying) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // systemBarsPadding (status+nav, all sides), not just Bottom —
                            // landscape can put the nav bar on a side instead, and this
                            // centered-width column still needs its start/end kept clear of it.
                            // Sides with no inset just get 0 padding, a no-op in portrait.
                            // Deliberately NOT safeDrawingPadding: that also reserves space for
                            // the display cutout, but a cutout inset applies uniformly along its
                            // whole edge regardless of how far this bottom-anchored bar actually
                            // is from where the cutout (a front camera, physically fixed at the
                            // opposite edge from this bar) sits — it was cutting this bar short on
                            // one side for no real reason. See FullscreenMediaActionMenu for the
                            // corner controls that genuinely do need cutout awareness.
                            .systemBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        VideoTimeline(
                            currentPositionMs = if (isUserSeeking) seekPositionMs else currentPositionMs,
                            totalDurationMs = totalDurationMs,
                            bufferedPositionMs = bufferedPositionMs,
                            compact = false,
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
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                            Text(
                                text = formatVideoDuration(totalDurationMs),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            val target = (player.currentPosition - 5_000L).coerceAtLeast(0L)
                            player.seekTo(target)
                        }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = stringResource(R.string.video_seek_back_5_action),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
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
                        }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) {
                                    stringResource(R.string.video_pause_action)
                                } else {
                                    stringResource(R.string.video_play_action)
                                },
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(onClick = { player.seekTo(player.currentPosition + 5_000L) }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = stringResource(R.string.video_seek_forward_5_action),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                if (controlsVisible || !isPlaying) {
                    FullscreenMediaActionMenu(
                        // Always the vertical dropdown layout regardless of the video's aspect
                        // ratio — FullscreenMediaActionMenu still supports the horizontal layout
                        // for a future caller that wants it, this call site just no longer opts
                        // into it.
                        isContentPortrait = true,
                        expanded = actionMenuExpanded,
                        onExpandedChange = { actionMenuExpanded = it },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            // systemBarsPadding (status+nav), not safeDrawingPadding — see the
                            // matching comment on the image dialog's own FullscreenMediaActionMenu
                            // placement for why (the cutout inset was pushing this further than
                            // needed; a fullscreen media viewer has no reason to reserve cutout
                            // space here).
                            .systemBarsPadding()
                            .padding(12.dp),
                        actions = listOf(
                            FullscreenMediaAction(
                                icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isMuted) {
                                    stringResource(R.string.video_unmute_action)
                                } else {
                                    stringResource(R.string.video_mute_action)
                                },
                                onClick = {
                                    isMuted = !isMuted
                                    player.volume = if (isMuted) 0f else 1f
                                }
                            ),
                            FullscreenMediaAction(
                                icon = Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.video_download_action),
                                onClick = {
                                    scope.launch {
                                        val saved = enqueueVideoDownload(context, videoUrl)
                                        val messageId = if (saved) {
                                            R.string.video_download_completed
                                        } else {
                                            R.string.video_download_failed
                                        }
                                        Toast.makeText(context, context.getString(messageId), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ),
                            FullscreenMediaAction(
                                icon = Icons.Default.Share,
                                contentDescription = stringResource(R.string.video_share_action),
                                onClick = { shareVideoUrl(context, videoUrl) }
                            )
                        )
                    )
                }
            }
        }
    }
}
