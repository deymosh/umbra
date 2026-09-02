package com.umbra.app.data.di

import android.content.Context
import android.os.Build
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.umbra.app.TorProxyConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // ✓ Single OkHttpClient instance for entire app
    // ✓ All HTTP, WebSocket, image, and video traffic goes through the current TOR proxy config
    // ✓ .onion hostnames never touch system DNS
    // ✓ No trust-all TLS overrides
    // ✓ TOR gate checked before any network request
    // ✓ verifySignature fails closed
    // ✓ DNS resolution is delegated to the SOCKS proxy itself (see note below) — no local Dns needed

    /**
     * Provides the single OkHttpClient instance for all network traffic, enforcing TOR proxy usage.
     * This is the ONLY allowed place for OkHttpClient.Builder() in the codebase (see SECURITY_AUDIT.md).
     * All other code must use @Named("tor") OkHttpClient injected by Hilt.
     *
     * No custom Dns is set here: when a request's route uses Proxy.Type.SOCKS (always true for this
     * client), OkHttp's RouteSelector never calls Dns.lookup() — it hands the raw hostname to the SOCKS
     * proxy via an unresolved InetSocketAddress, so TOR performs the resolution remotely. A local Dns
     * implementation (e.g. a caching layer) would simply never be invoked for this client.
     */
    @Provides
    @Singleton
    @Named("tor")
    fun provideTorOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // OkHttp's Dispatcher defaults to maxRequests=64 / maxRequestsPerHost=5 — fine for a
            // typical REST client, but every relay WebSocket is a long-lived "call" from the
            // Dispatcher's point of view (it never completes until closed), and this single
            // client is shared with Coil/Media3 per the no-second-client rule above. With
            // isDiscovered relays now able to grow well past the old default, the 64-slot ceiling
            // was silently starving both relays beyond the 64th connection *and* every image/video
            // request queued behind them — indefinitely, since WebSockets don't free their slot.
            // Sized with headroom above MAX_TOTAL_DISCOVERED_RELAYS (see UserRepositoryImpl,
            // itself a sanity ceiling rather than an expected relay count) plus the user's own
            // relays and concurrent media fetches — a relay-only dispatcher config could get away
            // with a lower ceiling (relay-traffic-only); here it's relay + image + video all
            // sharing one client.
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 1500
                    maxRequestsPerHost = 32
                }
            )
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    val address = InetSocketAddress(TorProxyConfig.host, TorProxyConfig.port)
                    return mutableListOf(Proxy(Proxy.Type.SOCKS, address))
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    // Relay/socket failures are handled by the caller's retry policy.
                }
            })
            .connectTimeout(30, TimeUnit.SECONDS)  // TOR connections can be slow
            .readTimeout(0, TimeUnit.SECONDS)      // No timeout on reads
            .writeTimeout(30, TimeUnit.SECONDS)
            // Disabled (0) by default in OkHttp. An idle relay WebSocket can be silently dropped
            // by a Tor exit/NAT without this client noticing until a write fails — by then it may
            // have been dead a while. 120s, empirically tuned against real-device relay behavior.
            .pingInterval(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @Named("tor") torOkHttpClient: OkHttpClient
    ): ImageLoader {
        // Coil's disk cache calls FileSystem.delete() inline the instant a write pushes it over
        // its size limit — on whatever thread triggered that write, which under heavy feed
        // scrolling is a live image write. DeferredDeleteFileSystem moves the actual delete onto
        // this background scope so eviction never stalls scroll IO. One scope for the process
        // lifetime, matching the ImageLoader singleton it's captured by.
        val diskCacheDeleteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024L * 1024L)
                    .fileSystem(DeferredDeleteFileSystem(scope = diskCacheDeleteScope))
                    .build()
            }
            // Coil 3 has no respectCacheHeaders toggle: Cache-Control headers are only honored at
            // all if the optional coil-network-cache-control artifact is on the classpath, which
            // it deliberately isn't here — so the local disk cache is already authoritative by
            // default, same effect Coil 2's .respectCacheHeaders(false) used to require explicitly.
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // ImageLoader.Builder has no okHttpClient() method in Coil 3 -- networking moved to
                // this separate, pluggable fetcher. Registered explicitly (rather than relying on
                // coil-network-okhttp's ServiceLoader auto-registration, which would construct its
                // own plain, non-TOR OkHttpClient) so image loads route through the same TOR-only
                // client as everything else; explicitly-added components are checked before
                // ServiceLoader-discovered ones, so this always wins.
                add(OkHttpNetworkFetcherFactory(torOkHttpClient))
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpDataSourceFactory(
        @Named("tor") torOkHttpClient: OkHttpClient
    ): OkHttpDataSource.Factory {
        return OkHttpDataSource.Factory(torOkHttpClient)
    }
}
