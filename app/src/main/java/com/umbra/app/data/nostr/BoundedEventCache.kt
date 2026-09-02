package com.umbra.app.data.nostr

import com.umbra.app.domain.nip01.Event

/**
 * Bounded id -> Event LRU cache, evicting the least-recently-used entry once [maxSize] is
 * exceeded. Used by [UmbraNostrClient] to skip reconstructing an `Event` (tag-list mapping, field
 * extraction) for a duplicate delivery of an event already seen from another relay — see
 * `handleEventMessage`. Deliberately a fast-path cache local to the WebSocket layer, distinct
 * from `EventRepositoryImpl`'s own longer-lived event storage.
 */
internal class BoundedEventCache(private val maxSize: Int) {
    private val map = object : LinkedHashMap<String, Event>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Event>?): Boolean =
            size > maxSize
    }

    @Synchronized
    fun getOrPut(id: String, compute: () -> Event): Event = map.getOrPut(id, compute)

    /** Read-only lookup (still counts as a use for LRU ordering, same as [getOrPut] on a hit). */
    @Synchronized
    fun get(id: String): Event? = map[id]

    @Synchronized
    fun size(): Int = map.size
}
