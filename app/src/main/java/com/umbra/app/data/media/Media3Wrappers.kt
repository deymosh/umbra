@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")
package com.umbra.app.data.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.database.StandaloneDatabaseProvider
import android.content.Context as AndroidContext
import com.umbra.app.R
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Centralizes all usage of media3 APIs marked as UnstableApi.
 * This file opts out to avoid spreading @UnstableApi annotations across the codebase.
 */

object VideoCacheProvider {
    private var cache: Cache? = null
    private const val CACHE_SIZE_BYTES = 256L * 1024L * 1024L // 256 MB
    private const val CACHE_DIR = "video_cache"

    fun getCache(context: Context): Cache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { cache = it }
        }
    }
}

@Singleton
class TorCacheDataSourceProvider @Inject constructor(
    @Named("tor") private val torOkHttpClient: OkHttpClient,
    private val appContext: Context
): VideoCacheDataSourceProvider {
    override fun getCacheDataSourceFactory(): DataSource.Factory {
        val cache = VideoCacheProvider.getCache(appContext)
        val upstreamFactory = OkHttpDataSource.Factory(torOkHttpClient)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

/**
 * Lightweight player abstraction so UI code doesn't need to opt-in
 * to media3's UnstableApi directly.
 */
interface SimplePlayer {
    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        // Match androidx.media3.common.Player state constants
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }

    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onPlayerError(error: Throwable)
        fun onVideoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float)
    }

    val currentPosition: Long
    val duration: Long
    /** How far ahead of [currentPosition] playback has buffered — drives the seek bar's buffered-region indicator. */
    val bufferedPosition: Long
    val isPlaying: Boolean
    val playbackState: Int
    var playWhenReady: Boolean
    var volume: Float

    fun setMediaItem(url: String)
    fun prepare()
    fun seekTo(posMs: Long)
    fun play()
    fun pause()
    fun stop()
    fun clearMediaItems()
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun release()
}

private class ExoSimplePlayer(private val exo: ExoPlayer): SimplePlayer {
    private val listenerMap = mutableMapOf<SimplePlayer.Listener, Player.Listener>()

    override val currentPosition: Long get() = exo.currentPosition.coerceAtLeast(0L)
    override val duration: Long get() = exo.duration.coerceAtLeast(0L)
    override val bufferedPosition: Long get() = exo.bufferedPosition.coerceAtLeast(0L)
    override val isPlaying: Boolean get() = exo.isPlaying
    override val playbackState: Int get() = exo.playbackState
    override var playWhenReady: Boolean
        get() = exo.playWhenReady
        set(value) { exo.playWhenReady = value }
    override var volume: Float
        get() = exo.volume
        set(value) { exo.volume = value }

    override fun setMediaItem(url: String) { exo.setMediaItem(MediaItem.fromUri(url)) }
    override fun prepare() { exo.prepare() }
    override fun seekTo(posMs: Long) { exo.seekTo(posMs) }
    override fun play() { exo.play() }
    override fun pause() { exo.pause() }
    override fun stop() { exo.stop() }
    override fun clearMediaItems() { exo.clearMediaItems() }
    override fun release() { exo.release() }

    override fun addListener(listener: SimplePlayer.Listener) {
        val mapped = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { listener.onPlaybackStateChanged(playbackState) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { listener.onIsPlayingChanged(isPlaying) }
            override fun onPlayerError(error: PlaybackException) { listener.onPlayerError(error) }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listener.onVideoSizeChanged(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }
        }
        listenerMap[listener] = mapped
        exo.addListener(mapped)
    }

    override fun removeListener(listener: SimplePlayer.Listener) {
        val mapped = listenerMap.remove(listener)
        if (mapped != null) exo.removeListener(mapped)
    }

    internal fun unwrap(): ExoPlayer = exo
}

// Well below ExoPlayer's default LoadControl (~50s max buffer) — feed videos can be composed
// several at a time (multiple visible/near-visible list items each building their own player,
// see NostrVideoComponents.kt), and several players each greedily buffering tens of seconds
// ahead would fight over Tor's already-scarce bandwidth, starving whichever one the user is
// actually watching plus every other Tor-routed request (relay traffic, images) sharing the same
// OkHttp client. A fresh instance per player (not shared across simultaneous ExoPlayer instances)
// to avoid any cross-player internal-state coupling.
private fun feedTunedLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 10_000,
            /* maxBufferMs = */ 15_000,
            /* bufferForPlaybackMs = */ 750,
            /* bufferForPlaybackAfterRebufferMs = */ 1_500
        )
        .build()

fun createExoPlayerForUrl(
    context: Context,
    torDataSourceFactory: DataSource.Factory,
    url: String,
    initialPositionMs: Long = 0L,
    initialPlayWhenReady: Boolean = false,
    initialMuted: Boolean = true,
    // false only for feed-inline playback (see InlineVideoAttachment's settle-window gate) -
    // every other caller (fullscreen, thread, profile, composer) reflects an explicit user
    // intent to view this video right now, so it should keep preparing/buffering immediately.
    autoPrepare: Boolean = true
): SimplePlayer {
    val exo = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(5_000)
        .setSeekForwardIncrementMs(5_000)
        .setMediaSourceFactory(DefaultMediaSourceFactory(torDataSourceFactory))
        .setLoadControl(feedTunedLoadControl())
        .build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (autoPrepare) prepare()
            if (initialPositionMs > 0L) seekTo(initialPositionMs)
            playWhenReady = initialPlayWhenReady
            volume = if (initialMuted) 0f else 1f
        }

    return ExoSimplePlayer(exo)
}

fun createPlayerView(context: Context, player: SimplePlayer): PlayerView {
    val view = PlayerView(context)
    // PlayerView's SurfaceView backing doesn't reliably resync its native buffer with layout
    // changes driven by Compose recomposition — most visibly here, InlineVideoAttachment resizes
    // its container via Modifier.aspectRatio(videoAspectRatio) once the real aspect ratio arrives
    // from onVideoSizeChanged, and without this the first frame stays anchored to the stale
    // (pre-resize) bounds instead of filling the resized player. media3-ui 1.10.0 added this exact
    // opt-in workaround for PlayerView-inside-AndroidView usage.
    view.setEnableComposeSurfaceSyncWorkaround(true)
    if (player is ExoSimplePlayer) {
        view.player = player.unwrap()
    }
    return view
}

fun getPlaybackErrorMessage(context: AndroidContext, error: Throwable): String {
    return if (error is PlaybackException) {
        describeVideoPlaybackError(context, error)
    } else {
        context.getString(R.string.video_error_generic)
    }
}
