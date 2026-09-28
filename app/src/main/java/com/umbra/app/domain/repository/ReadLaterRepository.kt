package com.umbra.app.domain.repository

import com.umbra.app.domain.nip01.Event
import kotlinx.coroutines.flow.StateFlow

/**
 * Notes saved to read later. Unlike NIP-51 bookmarks this is never published anywhere: the list
 * and the full notes stay encrypted on this device, readable offline.
 */
interface ReadLaterRepository {
    /** Newest-saved first. */
    val items: StateFlow<List<Event>>

    fun toggle(event: Event)

    fun remove(eventId: String)
}
