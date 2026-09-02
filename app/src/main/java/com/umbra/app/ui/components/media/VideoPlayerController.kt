package com.umbra.app.ui.components.media

import android.content.Context
import com.umbra.app.data.media.SimplePlayer
import com.umbra.app.data.media.getPlaybackErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow

private const val VIDEO_PROGRESS_TICK_MS_PLAYING = 500L
private const val VIDEO_PROGRESS_TICK_MS_IDLE = 1_000L

internal fun shouldRunVideoProgressTicker(
    isPlaying: Boolean,
    isBuffering: Boolean,
    isUserSeeking: Boolean
): Boolean = isPlaying || isBuffering || isUserSeeking

internal fun videoProgressTickerDelayMs(isPlaying: Boolean): Long =
    if (isPlaying) VIDEO_PROGRESS_TICK_MS_PLAYING else VIDEO_PROGRESS_TICK_MS_IDLE

/**
 * The single source of truth for "only one video plays at a time" across every inline and
 * fullscreen video player composed at once - both InlineVideoAttachment and FullscreenVideoDialog
 * read/write this to pause every other player once one of them starts playing.
 */
internal object VideoPlaybackRegistry {
    val activeVideoId = MutableStateFlow<String?>(null)
}

/**
 * The LOG-3 anamorphic-pixel aspect-ratio fix, extracted as a pure function so it's directly
 * unit-testable without instantiating ExoPlayer. Returns null for a zero/negative dimension,
 * matching the guard the original listener used before updating its aspect-ratio state - a
 * caller must treat a null result as "no update", not as a fallback to some default ratio.
 */
internal fun computeVideoAspectRatio(width: Int, height: Int, pixelWidthHeightRatio: Float): Float? {
    if (width <= 0 || height <= 0) return null
    return (width.toFloat() * pixelWidthHeightRatio) / height.toFloat()
}

/**
 * Builds the ExoPlayer listener both InlineVideoAttachment and FullscreenVideoDialog wire into
 * their own `DisposableEffect(url) { player.addListener(listener); onDispose { ... } }` blocks -
 * the caller still owns that lifecycle (including `player.release()`); this only unifies listener
 * *construction*, which was ~150 lines of near-identical duplication between the two composables.
 *
 * Every callback here is byte-identical between the two current callers except
 * [onVideoSizeChanged]: the inline caller passes a real aspect-ratio callback backed by
 * [computeVideoAspectRatio] (the LOG-3 fix), while the fullscreen caller passes an explicit no-op,
 * since its player renders via `Modifier.fillMaxSize()` with no aspect-ratio-driven container to
 * update. Do not collapse this into one shared implementation - that would either silently add
 * pointless aspect-ratio computation to the fullscreen path or silently drop the LOG-3 fix from
 * the inline path, depending on which behavior "wins".
 */
internal fun createVideoPlayerListener(
    context: Context,
    player: SimplePlayer,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onBufferedPositionChanged: (Long) -> Unit,
    onPlaybackReady: () -> Unit,
    onIsPlayingChanged: (Boolean) -> Unit,
    onPlaybackError: (String) -> Unit,
    onVideoSizeChanged: (width: Int, height: Int, pixelWidthHeightRatio: Float) -> Unit
): SimplePlayer.Listener {
    // Captured into locals with distinct names before the anonymous object is built below -
    // Kotlin resolves an unqualified call inside an override body against the object's own
    // member functions first, so calling the outer parameter directly by its original name
    // from inside an override of the *same* name would recurse into the override itself
    // instead of invoking the caller-supplied lambda.
    val notifyPlayingChanged = onIsPlayingChanged
    val notifyVideoSizeChanged = onVideoSizeChanged
    return object : SimplePlayer.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            onBufferingChanged(
                playbackState == SimplePlayer.STATE_BUFFERING || playbackState == SimplePlayer.STATE_IDLE
            )
            onDurationChanged(player.duration.coerceAtLeast(0L))
            onPositionChanged(player.currentPosition.coerceAtLeast(0L))
            onBufferedPositionChanged(player.bufferedPosition)
            if (playbackState == SimplePlayer.STATE_READY) {
                onPlaybackReady()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notifyPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: Throwable) {
            onBufferingChanged(false)
            notifyPlayingChanged(false)
            onPlaybackError(getPlaybackErrorMessage(context, error))
        }

        override fun onVideoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float) {
            notifyVideoSizeChanged(width, height, pixelWidthHeightRatio)
        }
    }
}
