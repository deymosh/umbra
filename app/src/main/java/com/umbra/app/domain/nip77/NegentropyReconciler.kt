package com.umbra.app.domain.nip77

/**
 * Result of processing one incoming Negentropy message.
 *
 * [outgoingMessage] is the reply to send back, or `null` when the initiator has determined the
 * protocol is complete (nothing more to ask the other side) — never `null` for the non-initiator
 * side, which always owes at least a reply. [haveIds]/[needIds] accumulate across every message
 * processed by the initiator: [haveIds] are ids this side has that the other side doesn't (so
 * should be published to it), [needIds] are ids the other side has that this side doesn't (so
 * should be fetched from it). Both are always empty on the non-initiator side — set reconciliation
 * result data is a property of the client's view, per the spec.
 */
internal data class NegentropyReconcileResult(
    val outgoingMessage: ByteArray?,
    val haveIds: List<String> = emptyList(),
    val needIds: List<String> = emptyList()
)

/**
 * Negentropy Protocol V1 range-based set reconciliation, operating over a [NegentropyStorageVector].
 * One instance reconciles one filter's worth of items against one relay — see
 * NegentropySyncOrchestrator for the per-relay fan-out and message round-trip driving.
 *
 * Deliberately does not implement the spec's optional frame-size-limit mechanism: Umbra's own
 * synced dataset (the signed-in user's own events — see NegentropySyncOrchestrator's scoping) is
 * bounded enough that unbounded-size reconciliation messages are an acceptable simplification, not
 * a correctness gap. A relay enforcing its own inbound message size limit would reject an
 * oversized NEG-MSG with NEG-ERR, which the orchestrator surfaces like any other sync failure.
 */
internal class NegentropyReconciler(
    private val storage: NegentropyStorageVector,
    private val isInitiator: Boolean
) {
    /** Builds the initiator's first message, covering the full timestamp/ID universe. */
    fun initiate(): ByteArray {
        val writer = NegentropyMessageWriter()
        splitRange(0, storage.size, NegentropyBound(NEGENTROPY_INFINITY_TIMESTAMP), writer)
        return writer.render()
    }

    fun reconcile(query: ByteArray): NegentropyReconcileResult {
        val reader = NegentropyMessageReader(query)
        val writer = NegentropyMessageWriter()

        val protocolVersion = reader.readByte()
        if (protocolVersion < 0x60 || protocolVersion > 0x6F) {
            throw NegentropyProtocolException("invalid negentropy protocol version byte: $protocolVersion")
        }
        if (protocolVersion != NEGENTROPY_PROTOCOL_VERSION) {
            if (isInitiator) {
                throw NegentropyProtocolException(
                    "unsupported negentropy protocol version requested: ${protocolVersion - 0x60}"
                )
            }
            // Downgrade signal: a bare protocol-version byte, telling the initiator the highest
            // version this side supports.
            return NegentropyReconcileResult(writer.render())
        }

        val haveIds = mutableListOf<String>()
        val needIds = mutableListOf<String>()
        val storageSize = storage.size
        var prevBound = NegentropyBound(0L)
        var prevIndex = 0
        var pendingSkip = false

        fun flushPendingSkip() {
            if (pendingSkip) {
                pendingSkip = false
                writer.writeBound(prevBound)
                writer.writeVarint(NegentropyMode.SKIP)
            }
        }

        while (!reader.isAtEnd) {
            val currBound = reader.readBound()
            val mode = reader.readVarint()

            val lower = prevIndex
            val upper = storage.findLowerBound(prevIndex, storageSize, currBound)

            when (mode) {
                NegentropyMode.SKIP -> pendingSkip = true

                NegentropyMode.FINGERPRINT -> {
                    val theirFingerprint = reader.readBytes(NEGENTROPY_FINGERPRINT_SIZE)
                    val ourFingerprint = storage.fingerprint(lower, upper)
                    if (!theirFingerprint.contentEquals(ourFingerprint)) {
                        flushPendingSkip()
                        splitRange(lower, upper, currBound, writer)
                    } else {
                        pendingSkip = true
                    }
                }

                NegentropyMode.ID_LIST -> {
                    val numIds = reader.readVarint()
                    val theirIds = HashSet<String>(numIds.toInt())
                    repeat(numIds.toInt()) { theirIds.add(reader.readId()) }

                    if (isInitiator) {
                        pendingSkip = true
                        for (i in lower until upper) {
                            val id = storage.item(i).id
                            if (!theirIds.remove(id)) haveIds.add(id)
                        }
                        needIds.addAll(theirIds)
                    } else {
                        flushPendingSkip()
                        writer.writeBound(currBound)
                        writer.writeVarint(NegentropyMode.ID_LIST)
                        writer.writeVarint((upper - lower).toLong())
                        for (i in lower until upper) writer.writeId(storage.item(i).id)
                    }
                }

                else -> throw NegentropyProtocolException("unexpected mode: $mode")
            }

            prevIndex = upper
            prevBound = currBound
        }

        val outgoing = writer.render()
        // A lone protocol-version byte from the initiator means it found nothing left to ask for.
        val done = isInitiator && outgoing.size == 1
        return NegentropyReconcileResult(
            outgoingMessage = if (done) null else outgoing,
            haveIds = haveIds,
            needIds = needIds
        )
    }

    /** Splits [lower, upper) into either one IdList (small ranges) or NEGENTROPY_SPLIT_BUCKETS
     * Fingerprint sub-ranges (large ranges) — see the spec's "Algorithm" section for why bucket
     * count is implementation-defined; 16 matches every known reference implementation. */
    private fun splitRange(lower: Int, upper: Int, upperBound: NegentropyBound, writer: NegentropyMessageWriter) {
        val numElems = upper - lower
        if (numElems < NEGENTROPY_SPLIT_BUCKETS * 2) {
            writer.writeBound(upperBound)
            writer.writeVarint(NegentropyMode.ID_LIST)
            writer.writeVarint(numElems.toLong())
            for (i in lower until upper) writer.writeId(storage.item(i).id)
            return
        }

        val itemsPerBucket = numElems / NEGENTROPY_SPLIT_BUCKETS
        val bucketsWithExtra = numElems % NEGENTROPY_SPLIT_BUCKETS
        var curr = lower
        for (bucketIndex in 0 until NEGENTROPY_SPLIT_BUCKETS) {
            val bucketSize = itemsPerBucket + if (bucketIndex < bucketsWithExtra) 1 else 0
            val ourFingerprint = storage.fingerprint(curr, curr + bucketSize)
            curr += bucketSize

            val nextBound = if (curr == upper) {
                upperBound
            } else {
                minimalBound(storage.item(curr - 1), storage.item(curr))
            }

            writer.writeBound(nextBound)
            writer.writeVarint(NegentropyMode.FINGERPRINT)
            writer.writeBytes(ourFingerprint)
        }
    }

    /** Shortest bound that still separates [prev] (last item of one bucket) from [curr] (first
     * item of the next) — empty id prefix when their timestamps already differ. */
    private fun minimalBound(prev: NegentropyItem, curr: NegentropyItem): NegentropyBound {
        if (curr.timestamp != prev.timestamp) return NegentropyBound(curr.timestamp)
        var sharedPrefixBytes = 0
        val maxBytes = NEGENTROPY_ID_SIZE
        for (i in 0 until maxBytes) {
            val currByte = curr.id.substring(i * 2, i * 2 + 2)
            val prevByte = prev.id.substring(i * 2, i * 2 + 2)
            if (currByte != prevByte) break
            sharedPrefixBytes++
        }
        val prefixHexLen = (sharedPrefixBytes + 1) * 2
        return NegentropyBound(curr.timestamp, curr.id.substring(0, prefixHexLen))
    }

    companion object {
        private const val NEGENTROPY_SPLIT_BUCKETS = 16
    }
}
