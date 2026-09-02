package com.umbra.app

import android.app.Application
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.umbra.app.data.nostr.NostrSessionManager
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.usecase.TrimMemoryCachesUseCase
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class UmbraApp : Application(), SingletonImageLoader.Factory {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UmbraAppEntryPoint {
        @Named("tor")
        fun torOkHttpClient(): OkHttpClient
        fun imageLoader(): ImageLoader
        fun nostrSessionManager(): NostrSessionManager
        fun trimMemoryCachesUseCase(): TrimMemoryCachesUseCase
        fun videoCacheDataSourceProvider(): VideoCacheDataSourceProvider
    }

    override fun onCreate() {
        super.onCreate()
        Bech32Encoder.setLogger(UmbraLog.tag("UmbraBech32"))
        // Resolving NostrSessionManager here pulls in its whole @Singleton dependency graph
        // synchronously — most notably EncryptedUmbraDatabase (DatabaseModule), whose SQLCipher
        // openHelperFactory eagerly calls EncryptedDatabasePassphraseProvider.getOrCreatePassphrase(),
        // which touches Android Keystore-backed EncryptedSharedPreferences. Application.onCreate()
        // always runs on the main thread, and it runs *before* MainActivity even starts — so this
        // used to fully serialize ahead of the first frame MainActivity draws, which is exactly the
        // window the system splash screen (the app icon) stays visible for. A faster icon-to-feed
        // handoff is possible by skipping eager singleton construction here entirely, but
        // start() itself only launches internal coroutines regardless of which thread calls it, and
        // Hilt's @Singleton scope is synchronized, so resolving the graph here from a background
        // dispatch is a pure prewarm: by the time MainActivity's first composition needs these same
        // singletons (e.g. TorGateViewModel), they're either already built or well underway, instead
        // of the whole chain starting cold on the main thread exactly when the splash screen is
        // waiting on it.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val entryPoint = EntryPointAccessors.fromApplication(this@UmbraApp, UmbraAppEntryPoint::class.java)
            entryPoint.nostrSessionManager().start()
        }
        // VideoCacheProvider.getCache() does real disk I/O (opens/creates the SimpleCache
        // directory) inside a synchronized(this) double-checked-locking block. Its only call path
        // is a plain get() property read from FeedViewModel/ThreadViewModel/ProfileViewModel/
        // ComposerViewModel, so without this prewarm the first video played in the app would pay
        // that disk I/O on the main thread, right when the UI needs to be responsive. Runs in its
        // own launch (not folded into the nostrSessionManager() one above) so a slow disk-cache
        // open never delays session/feed startup.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val entryPoint = EntryPointAccessors.fromApplication(this@UmbraApp, UmbraAppEntryPoint::class.java)
            entryPoint.videoCacheDataSourceProvider().getCacheDataSourceFactory()
        }
        // Coil will be configured via newImageLoader()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val entryPoint = EntryPointAccessors.fromApplication(this, UmbraAppEntryPoint::class.java)
        entryPoint.torOkHttpClient()
        return entryPoint.imageLoader()
    }

    // TRIM_MEMORY_BACKGROUND (40) and above means the app is backgrounded AND the OS specifically
    // wants memory back (as opposed to UI_HIDDEN=20, merely "not visible right now," or the
    // foreground RUNNING_* levels 5/10/15) — proactively drop Coil's in-memory bitmap cache
    // rather than waiting for the OS to reclaim it under more severe pressure. Coil 2.x's
    // MemoryCache has no partial/percentage trim, only clear() — a media-heavy session is exactly
    // the case most likely to have a large image cache worth reclaiming here.
    //
    // TRIM_MEMORY_UI_HIDDEN (20)+ additionally triggers a light trim of the event/profile/list
    // caches TrimMemoryCachesUseCase covers (see that class); TRIM_MEMORY_BACKGROUND (40)+ makes
    // that trim aggressive. These previously never reacted to onTrimMemory at all — only Coil did.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            val entryPoint = EntryPointAccessors.fromApplication(this, UmbraAppEntryPoint::class.java)
            val aggressive = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            if (aggressive) {
                entryPoint.imageLoader().memoryCache?.clear()
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                entryPoint.trimMemoryCachesUseCase().invoke(aggressive)
            }
        }
    }
}
