package com.umbra.app.domain.nip77

/**
 * Sorted, immutable local index of items (id, timestamp) — the Vector storage engine from the
 * Negentropy spec. Umbra's own dataset (see NegentropySyncOrchestrator) is bounded enough that a
 * simple sorted list, re-fingerprinted per range on demand, is adequate; no tree-based storage
 * (BTreeMem/BTreeLMDB in the reference C++ implementation) is needed.
 */
internal class NegentropyStorageVector(items: Collection<NegentropyItem>) {
    private val sorted: List<NegentropyItem> = items.sortedWith(NEGENTROPY_ITEM_COMPARATOR)

    val size: Int get() = sorted.size

    fun item(index: Int): NegentropyItem = sorted[index]

    /** First index in [begin, end) whose item is not less than [bound] — i.e. the exclusive-upper-bound cut point. */
    fun findLowerBound(begin: Int, end: Int, bound: NegentropyBound): Int {
        var lo = begin
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (isBefore(sorted[mid], bound)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun fingerprint(begin: Int, end: Int): ByteArray {
        val ids = ArrayList<String>(end - begin)
        for (i in begin until end) ids.add(sorted[i].id)
        return NegentropyFingerprint.compute(ids)
    }

    private fun isBefore(item: NegentropyItem, bound: NegentropyBound): Boolean {
        if (item.timestamp != bound.timestamp) return item.timestamp < bound.timestamp
        // Same ordering semantics as the reference implementation's raw-byte lexical comparison:
        // a hex string's natural ordering already matches its underlying bytes' lexical ordering
        // (each byte maps 1:1 to two hex chars, high nibble first), including the "shorter string
        // that's a genuine prefix of the longer one sorts first" tie-break the spec relies on for
        // comparing a full 32-byte id against a shorter disambiguating id prefix.
        return item.id < bound.idPrefixHex
    }
}
