package com.umbra.app.domain.notifications

import com.umbra.app.domain.lightning.parseBolt11
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class NotificationType { REPLY, MENTION, REACTION, REPOST, ZAP }

/**
 * One row in the notifications list. Reactions, reposts and zaps on the same note are grouped
 * ("Alice and 3 others liked your note"); replies and mentions stay individual because their
 * content is the point.
 */
data class NotificationGroup(
    val key: String,
    val type: NotificationType,
    /** Newest first, deduplicated. */
    val actorPubkeys: List<String>,
    val latestAt: Long,
    /** The note that was reacted to / zapped / reposted, when known. */
    val targetEventId: String?,
    /** The reply or mention itself, for REPLY/MENTION. */
    val event: Event? = null,
    /** Distinct reaction contents ("+", "🔥", ":shortcode:") for REACTION. */
    val reactions: List<String> = emptyList(),
    val zapTotalSats: Long = 0,
    /** Newest zap's message, if any. */
    val zapComment: String? = null
)

/** A NIP-57 receipt's payer, amount and message, read from its embedded zap request. */
data class ZapReceipt(val senderPubkey: String?, val amountSats: Long, val comment: String?, val targetEventId: String?)

fun parseZapReceipt(event: Event): ZapReceipt? {
    if (event.kind != Event.KIND_ZAP_RECEIPT) return null
    val amountMsat = event.getTagValue("bolt11")?.let(::parseBolt11)?.amountMsat
    val request = event.getTagValue("description")?.let {
        runCatching { JsonUtils.NostrJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }
    // The receipt is signed by the recipient's wallet server; the zap request inside names the payer.
    val sender = request?.get("pubkey")?.jsonPrimitive?.content?.lowercase()
        ?: event.getTagValue("P")?.lowercase()
    val comment = request?.get("content")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    return ZapReceipt(
        senderPubkey = sender,
        amountSats = (amountMsat ?: 0) / 1_000,
        comment = comment,
        targetEventId = event.getTagValue("e")
    )
}

/**
 * Turns raw inbox events into notification rows, newest first. [mutedPubkeys] are dropped
 * entirely (muting is user-owned state), and a reaction to someone else's note that merely
 * p-tags the user is still shown — Nostr has no reliable way to tell those apart without the
 * target note in hand.
 */
fun groupNotifications(events: List<Event>, mutedPubkeys: Set<String>): List<NotificationGroup> {
    val groups = LinkedHashMap<String, MutableList<Pair<Event, String>>>()
    val singles = mutableListOf<NotificationGroup>()

    for (event in events.distinctBy { it.id }) {
        when (event.kind) {
            Event.KIND_TEXT_NOTE, Event.KIND_COMMENT -> {
                if (event.pubkey.lowercase() in mutedPubkeys) continue
                val type = if (event.isReply() || event.kind == Event.KIND_COMMENT) NotificationType.REPLY else NotificationType.MENTION
                singles += NotificationGroup(
                    key = "${type.name}:${event.id}",
                    type = type,
                    actorPubkeys = listOf(event.pubkey.lowercase()),
                    latestAt = event.createdAt,
                    targetEventId = event.getParentEventId(),
                    event = event
                )
            }
            Event.KIND_REACTION -> {
                if (event.pubkey.lowercase() in mutedPubkeys) continue
                val target = event.getTagValues("e").lastOrNull() ?: continue
                groups.getOrPut("${NotificationType.REACTION.name}:$target") { mutableListOf() } += event to event.pubkey.lowercase()
            }
            Event.KIND_REPOST, Event.KIND_GENERIC_REPOST -> {
                if (event.pubkey.lowercase() in mutedPubkeys) continue
                val target = event.getTagValue("e") ?: continue
                groups.getOrPut("${NotificationType.REPOST.name}:$target") { mutableListOf() } += event to event.pubkey.lowercase()
            }
            Event.KIND_ZAP_RECEIPT -> {
                val receipt = parseZapReceipt(event) ?: continue
                val sender = receipt.senderPubkey ?: continue
                if (sender in mutedPubkeys) continue
                val key = "${NotificationType.ZAP.name}:${receipt.targetEventId ?: "profile"}"
                groups.getOrPut(key) { mutableListOf() } += event to sender
            }
        }
    }

    val grouped = groups.map { (key, entries) ->
        val type = NotificationType.valueOf(key.substringBefore(':'))
        val sorted = entries.sortedByDescending { it.first.createdAt }
        val zaps = if (type == NotificationType.ZAP) sorted.mapNotNull { parseZapReceipt(it.first) } else emptyList()
        NotificationGroup(
            key = key,
            type = type,
            actorPubkeys = sorted.map { it.second }.distinct(),
            latestAt = sorted.first().first.createdAt,
            targetEventId = key.substringAfter(':').takeIf { it != "profile" },
            reactions = if (type == NotificationType.REACTION) sorted.map { it.first.content.ifBlank { "+" } }.distinct() else emptyList(),
            zapTotalSats = zaps.sumOf { it.amountSats },
            zapComment = zaps.firstNotNullOfOrNull { it.comment }
        )
    }

    return (grouped + singles).sortedByDescending { it.latestAt }
}
