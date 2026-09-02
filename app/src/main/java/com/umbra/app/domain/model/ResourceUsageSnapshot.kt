package com.umbra.app.domain.model

/**
 * Live memory/cache occupancy snapshot for Settings > App Resource Usage. Every field is a
 * point-in-time read (JVM heap, native heap, Coil caches, in-memory event cache, local DB file
 * size) — nothing here is persisted or aggregated over time.
 */
data class ResourceUsageSnapshot(
    val jvmHeapUsedBytes: Long,
    val jvmHeapMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val deviceMemoryClassMb: Int,
    val isLargeHeap: Boolean,
    val imageMemoryCacheUsedBytes: Long?,
    val imageMemoryCacheMaxBytes: Long?,
    val imageDiskCacheUsedBytes: Long?,
    val imageDiskCacheMaxBytes: Long?,
    val eventCacheSize: Int,
    val eventCacheMaxSize: Int,
    val databaseFileBytes: Long,
    // Session-lifetime, non-owner in-memory caches that don't otherwise show up above — see
    // TrimMemoryCachesUseCase, which is what actually shrinks these under memory pressure.
    val profileCacheEntries: Int,
    val relayListCacheEntries: Int,
    val ownerListCacheEntries: Int
)
