package com.umbra.app.ui.common

import androidx.compose.runtime.Immutable

@Immutable
data class ImmutableMapSnapshot<K, V>(private val backing: Map<K, V> = emptyMap()) {
    operator fun get(key: K): V? = backing[key]
    val size: Int get() = backing.size
    val keys: Set<K> get() = backing.keys
    fun isEmpty(): Boolean = backing.isEmpty()
    fun isNotEmpty(): Boolean = backing.isNotEmpty()
    fun toMap(): Map<K, V> = backing
    operator fun plus(pair: Pair<K, V>): ImmutableMapSnapshot<K, V> = ImmutableMapSnapshot(backing + pair)
    operator fun plus(other: Map<K, V>): ImmutableMapSnapshot<K, V> = ImmutableMapSnapshot(backing + other)
}

fun <K, V> Map<K, V>.toImmutableSnapshot(): ImmutableMapSnapshot<K, V> = ImmutableMapSnapshot(this)

/**
 * Merges [additions] in, evicting the oldest entries (by insertion order) once the combined size
 * exceeds [maxSize] — keeps a viewport-driven resolved-quote/profile cache bounded instead of
 * growing for the entire lifetime of a long scroll session.
 */
internal fun <K, V> ImmutableMapSnapshot<K, V>.mergeBounded(
    additions: Map<K, V>,
    maxSize: Int = 300
): ImmutableMapSnapshot<K, V> {
    if (additions.isEmpty()) return this
    val merged = LinkedHashMap<K, V>(toMap())
    merged.putAll(additions)
    if (merged.size > maxSize) {
        val overflow = merged.size - maxSize
        val iterator = merged.keys.iterator()
        repeat(overflow) {
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }
    return merged.toImmutableSnapshot()
}

/**
 * Same rationale as [ImmutableMapSnapshot]: a plain `List<T>` composable parameter is unstable
 * per Compose's stability inference regardless of content, so Compose can never skip
 * recomposition based on it — even when the same (or an equal) list is passed again. Wrapping it
 * as an `@Immutable`-annotated value type restores skippability.
 */
@Immutable
data class ImmutableListSnapshot<T>(@PublishedApi internal val backing: List<T> = emptyList()) {
    val size: Int get() = backing.size
    fun isEmpty(): Boolean = backing.isEmpty()
    fun isNotEmpty(): Boolean = backing.isNotEmpty()
    fun toList(): List<T> = backing
    operator fun contains(element: T): Boolean = backing.contains(element)

    // inline so a @Composable call inside the lambda (e.g. ChipBadge per item) is legal, same as
    // kotlin.collections.forEach's own inline contract.
    inline fun forEach(action: (T) -> Unit) = backing.forEach(action)
}

fun <T> List<T>.toImmutableSnapshot(): ImmutableListSnapshot<T> = ImmutableListSnapshot(this)