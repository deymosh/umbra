package com.umbra.app.domain.model

/**
 * Snapshot of [com.umbra.app.data.repository.cache.EventLruCache]'s current occupancy, surfaced through
 * [com.umbra.app.domain.repository.EventRepository.getInMemoryCacheStats].
 */
data class EventCacheStats(val size: Int, val maxSize: Int)
