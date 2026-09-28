package com.umbra.app.data.repository

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.ReadLaterRepository
import com.umbra.app.domain.util.JsonUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer

@Singleton
class ReadLaterRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : ReadLaterRepository {

    private val prefs by lazy { SecurePreferences(context, "read_later") }
    private val serializer = ListSerializer(Event.serializer())

    private val _items = MutableStateFlow(load())
    override val items: StateFlow<List<Event>> = _items.asStateFlow()

    override fun toggle(event: Event) = mutate { current ->
        if (current.any { it.id == event.id }) current.filterNot { it.id == event.id }
        else (listOf(event) + current).take(MAX_ITEMS)
    }

    override fun remove(eventId: String) = mutate { current -> current.filterNot { it.id == eventId } }

    private fun mutate(transform: (List<Event>) -> List<Event>) {
        _items.update(transform)
        prefs.putString(KEY, JsonUtils.NostrJson.encodeToString(serializer, _items.value))
    }

    private fun load(): List<Event> = prefs.getString(KEY)
        ?.let { runCatching { JsonUtils.NostrJson.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private companion object {
        const val KEY = "items"
        const val MAX_ITEMS = 300
    }
}
