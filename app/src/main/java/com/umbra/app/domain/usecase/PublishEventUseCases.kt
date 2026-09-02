package com.umbra.app.domain.usecase

import com.umbra.app.domain.logging.UmbraLogger
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.repository.BroadcastRepository
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.util.LogScrubber.scrubThrowableMessageForLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.umbra.app.domain.nip01.NostrEventBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import com.umbra.app.domain.util.JsonUtils

/**
 * Parses a signed event JSON (returned by AMBER) into an [Event] object and
 * publishes it to connected relays.
 *
 * Called after the AMBER ActivityResult callback delivers the signed JSON.
 */
class PublishSignedEventUseCase(
    private val eventRepository: EventRepository,
    private val broadcastRepository: BroadcastRepository,
    private val logger: UmbraLogger
) {
    suspend operator fun invoke(signedEventJson: String): Result<Event> =
        withContext(Dispatchers.Default) {
            runCatching {
                val event = parseSignedEventJson(signedEventJson)

                require(event.id.isNotBlank()) { "Signed event has no id" }
                require(event.sig.isNotBlank()) { "Signed event has no signature" }

                val targetRelays = eventRepository.publishEvent(event).getOrThrow()
                broadcastRepository.trackPublish(event, targetRelays)

                logger.d { "Published signed event ${event.id.take(8)} kind=${event.kind}" }
                event
            }.onFailure { e ->
                logger.d { "Failed to publish signed event: ${scrubThrowableMessageForLogs(e)}" }
            }
        }

    private fun parseSignedEventJson(signedEventJson: String): Event =
        parseSignedEvent(signedEventJson)
}

/**
 * Publishes a signed AUTH event to a specific relay (NIP-42).
 * Separate use case to keep PublishSignedEventUseCase single-purpose.
 */
class PublishAuthEventUseCase(
    private val eventRepository: EventRepository,
    private val logger: UmbraLogger
) {
    suspend operator fun invoke(signedEventJson: String, relayUrl: String): Result<Event> =
        withContext(Dispatchers.Default) {
            runCatching {
                val event = parseSignedEvent(signedEventJson)

                require(event.id.isNotBlank()) { "Signed AUTH event has no id" }
                require(event.sig.isNotBlank()) { "Signed AUTH event has no signature" }

                eventRepository.publishAuthEvent(relayUrl, event).getOrThrow()
                event
            }.onFailure { e ->
                logger.d { "Failed to publish signed AUTH event: ${scrubThrowableMessageForLogs(e)}" }
            }
        }
}

private fun parseSignedEvent(signedEventJson: String): Event {
    val parsed = JsonUtils.NostrJson.parseToJsonElement(signedEventJson) as? JsonObject
        ?: error("Signed event is not a JSON object")

    return Event(
            id      = parsed["id"]?.jsonPrimitive?.content.orEmpty(),
            pubkey  = parsed["pubkey"]?.jsonPrimitive?.content.orEmpty(),
            createdAt = parsed["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            kind    = parsed["kind"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: Event.KIND_TEXT_NOTE,
            tags    = (parsed["tags"] as? JsonArray)?.map { tag ->
                (tag as? JsonArray)?.map { v ->
                    (v as? JsonPrimitive)?.content.orEmpty()
                } ?: emptyList()
            } ?: emptyList(),
            content = parsed["content"]?.jsonPrimitive?.content.orEmpty(),
            sig     = parsed["sig"]?.jsonPrimitive?.content.orEmpty()
        )
}

/**
 * Validates ownership and deletes a Nostr event (NIP-09 deletion request + local DB cleanup).
 * Builds the signed-ready NIP-09 delete event JSON for Amber to sign.
 *
 * @param currentUserPubkey The hex pubkey of the logged-in user.
 * @param event             The event to delete.
 * @return Result containing the deletion event JSON string to be signed via Amber,
 *         or failure if the current user does not own the event.
 */
class DeleteNoteUseCase {
    operator fun invoke(event: Event, currentUserPubkey: String): Result<String> =
        runCatching {
            val normalizedOwner = currentUserPubkey.lowercase()
            require(event.pubkey.equals(normalizedOwner, ignoreCase = true)) {
                "Cannot delete: event owned by ${event.pubkey.take(8)}, not current user"
            }
            NostrEventBuilder.deleteEvent(event)
        }
}

/**
 * Performs the local cache/archive cleanup after a [DeleteNoteUseCase]-built deletion has been
 * signed and sent to relays — a separate use case (rather than a second method on
 * [DeleteNoteUseCase]) since it's a distinct step callers invoke at a different point in the
 * delete flow, after the Amber sign round-trip completes.
 */
class RemoveDeletedNoteFromCacheUseCase(
    private val eventRepository: EventRepository
) {
    suspend operator fun invoke(eventId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { eventRepository.deleteEvent(eventId) }
        }
}

