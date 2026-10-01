package com.umbra.app.util

/**
 * The narrow slice of [ImagePrefetcher] [com.umbra.app.data.repository.UserRepositoryImpl] needs —
 * mirrors this codebase's existing narrow-interface precedent (`BackfillAnchorClearer`,
 * `OwnEventArchive`) so a plain JVM unit test can supply a small fake instead of the real class,
 * whose constructor eagerly captures a Coil [coil3.ImageLoader] and an Android
 * [android.content.Context] — neither obtainable in `testDebugUnitTest` (no Robolectric). The only
 * call site here is the fire-and-forget avatar prefetch after a profile save; [ImagePrefetcher]
 * itself is still directly constructor-injected wherever its full API is needed.
 */
interface AvatarPrefetcher {
    fun prefetchAsync(url: String, scopeTag: String)
}
