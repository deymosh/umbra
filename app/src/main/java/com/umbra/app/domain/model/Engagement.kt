package com.umbra.app.domain.model

import androidx.compose.runtime.Immutable
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip18.extractRepostTarget
import com.umbra.app.domain.nip18.isRepostKind
import com.umbra.app.domain.nip25.isDislikeReactionContent
import com.umbra.app.domain.nip57.ZapReceiptValidation
import com.umbra.app.domain.nip57.validateZapReceipt

/** How one event engages with a note it points at. */
enum class EngagementType { REACTION, REPLY, REPOST, ZAP }

/** One event's contribution to one target note: a count of one, plus the sats for a zap. */
data class EngagementLink(val targetId: String, val type: EngagementType, val sats: Long = 0)

/** Everything known to engage with one note. */
@Immutable
data class EngagementCounts(
    val reactions: Int = 0,
    val replies: Int = 0,
    val reposts: Int = 0,
    val zapSats: Long = 0
) {
    operator fun plus(other: EngagementCounts) = EngagementCounts(
        reactions = reactions + other.reactions,
        replies = replies + other.replies,
        reposts = reposts + other.reposts,
        zapSats = zapSats + other.zapSats
    )

    /** Never below zero, so a removal racing an eviction can't drive a count negative. */
    operator fun minus(other: EngagementCounts) = EngagementCounts(
        reactions = (reactions - other.reactions).coerceAtLeast(0),
        replies = (replies - other.replies).coerceAtLeast(0),
        reposts = (reposts - other.reposts).coerceAtLeast(0),
        zapSats = (zapSats - other.zapSats).coerceAtLeast(0)
    )

    val isEmpty: Boolean get() = reactions == 0 && replies == 0 && reposts == 0 && zapSats == 0L
}

/**
 * The single rule for what [event] counts toward, used by every count Umbra shows (feed,
 * profile, thread):
 * - a reaction counts toward its NIP-25 target (the last `e` tag); a dislike ("-") counts nowhere;
 * - a reply counts toward every note it replies to — its NIP-10 root and parent, never a
 *   `mention` — and a NIP-22 comment likewise toward its parent `e` and root `E`;
 * - a repost counts toward the note it reposts;
 * - a zap receipt counts its sats toward the note it paid for, and only when it validates
 *   (NIP-57 Appendix F, see [validateZapReceipt]) — an unvalidated receipt's amount is just a tag
 *   anyone can write. [verifySignature] is the BIP-340 verifier the validation needs.
 */
fun engagementLinksOf(event: Event, verifySignature: (Event) -> Boolean): List<EngagementLink> = when {
    event.kind == Event.KIND_REACTION -> {
        if (isDislikeReactionContent(event.content)) {
            emptyList()
        } else {
            event.getTagValues("e").lastOrNull { it.isNotBlank() }
                ?.let { listOf(EngagementLink(it.lowercase(), EngagementType.REACTION)) }
                .orEmpty()
        }
    }
    event.kind == Event.KIND_TEXT_NOTE ->
        event.replyTargetIds().map { EngagementLink(it.lowercase(), EngagementType.REPLY) }
    // A NIP-22 comment replies to its parent (lowercase `e`) within the thread of its root
    // (uppercase `E`), counted toward both like a NIP-10 reply's parent and root.
    event.kind == Event.KIND_COMMENT ->
        (event.getTagValues("e") + event.getTagValues("E"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .distinct()
            .map { EngagementLink(it, EngagementType.REPLY) }
    isRepostKind(event.kind) ->
        extractRepostTarget(event).eventId?.takeIf { it.isNotBlank() }
            ?.let { listOf(EngagementLink(it.lowercase(), EngagementType.REPOST)) }
            .orEmpty()
    event.kind == Event.KIND_ZAP_RECEIPT -> {
        val zap = validateZapReceipt(event, verifySignature) as? ZapReceiptValidation.Valid
        val target = zap?.targetEventId
        if (zap == null || target == null) {
            emptyList()
        } else {
            listOf(EngagementLink(target, EngagementType.ZAP, sats = zap.amountMsat / 1_000))
        }
    }
    else -> emptyList()
}

/** Folds [links] into per-target counts. */
fun Iterable<EngagementLink>.toEngagementCounts(): Map<String, EngagementCounts> {
    val out = HashMap<String, EngagementCounts>()
    forEach { link -> out[link.targetId] = (out[link.targetId] ?: EngagementCounts()) + link.toCounts() }
    return out
}

internal fun EngagementLink.toCounts(): EngagementCounts = when (type) {
    EngagementType.REACTION -> EngagementCounts(reactions = 1)
    EngagementType.REPLY -> EngagementCounts(replies = 1)
    EngagementType.REPOST -> EngagementCounts(reposts = 1)
    EngagementType.ZAP -> EngagementCounts(zapSats = sats)
}
