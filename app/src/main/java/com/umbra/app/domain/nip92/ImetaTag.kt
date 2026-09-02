package com.umbra.app.domain.nip92

/**
 * NIP-92 (`imeta`) media attachment metadata for a single URL. Fields beyond `url` are all
 * optional per spec — only `url` and mime type are commonly present in the wild.
 */
data class ImetaTag(
    val url: String,
    val mimeType: String? = null,
    val dimensions: MediaDimensions? = null,
    val blurhash: String? = null,
    val alt: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val fallbackUrls: List<String> = emptyList()
)

data class MediaDimensions(val width: Int, val height: Int) {
    /** Width/height as a Compose `aspectRatio()` modifier value. */
    val ratio: Float get() = width.toFloat() / height.toFloat()
}

/**
 * Serializes this [ImetaTag] into an outgoing `["imeta", "url ...", "m ...", ...]` tag for a
 * note being published — the inverse of [extractImetaTags]/[parseImetaFields]. Omits any field
 * that's null; [fallbackUrls] becomes one repeated `"fallback ..."` entry per URL, per spec.
 */
fun ImetaTag.toTag(): List<String> = buildList {
    add("imeta")
    add("url $url")
    mimeType?.let { add("m $it") }
    dimensions?.let { add("dim ${it.width}x${it.height}") }
    blurhash?.let { add("blurhash $it") }
    alt?.let { add("alt $it") }
    sha256?.let { add("x $it") }
    sizeBytes?.let { add("size $it") }
    fallbackUrls.forEach { add("fallback $it") }
}

/**
 * Parses NIP-92 `imeta` tags into a map keyed by URL (there SHOULD be only one `imeta` tag per
 * URL per spec). Each tag element after the first is a space-delimited "key value..." pair —
 * multi-word values (e.g. `alt`) are rejoined from the remaining tokens, and repeated
 * `fallback` entries are collected into a list.
 *
 * Only `http(s)` URLs are kept — same rationale as NIP-30 custom emoji: this metadata feeds an
 * image/video loader, so a non-http(s) scheme is rejected rather than handed to it. A tag
 * without a `url` field is dropped entirely (NIP-92: "MUST have a `url`").
 */
fun extractImetaTags(tags: List<List<String>>): Map<String, ImetaTag> {
    return tags.asSequence()
        .filter { tag -> tag.getOrNull(0) == "imeta" }
        .mapNotNull { tag -> parseImetaFields(tag.drop(1)) }
        .associateBy { it.url }
}

private fun parseImetaFields(fields: List<String>): ImetaTag? {
    var url: String? = null
    var mimeType: String? = null
    var dimensions: MediaDimensions? = null
    var blurhash: String? = null
    var alt: String? = null
    var sha256: String? = null
    var sizeBytes: Long? = null
    val fallbackUrls = mutableListOf<String>()

    for (field in fields) {
        val (key, value) = splitKeyValue(field) ?: continue
        when (key) {
            "url" -> url = value
            "m" -> mimeType = value
            "dim" -> dimensions = parseDimensions(value)
            "blurhash" -> blurhash = value
            "alt" -> alt = value
            "x" -> sha256 = value
            "size" -> sizeBytes = value.toLongOrNull()
            "fallback" -> fallbackUrls.add(value)
        }
    }

    val safeUrl = url ?: return null
    if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) return null

    return ImetaTag(
        url = safeUrl,
        mimeType = mimeType,
        dimensions = dimensions,
        blurhash = blurhash,
        alt = alt,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        fallbackUrls = fallbackUrls
    )
}

private fun splitKeyValue(field: String): Pair<String, String>? {
    val spaceIndex = field.indexOf(' ')
    if (spaceIndex <= 0) return null
    val key = field.substring(0, spaceIndex)
    val value = field.substring(spaceIndex + 1).trim()
    if (value.isBlank()) return null
    return key to value
}

private fun parseDimensions(raw: String): MediaDimensions? {
    val parts = raw.split('x', 'X')
    if (parts.size != 2) return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return MediaDimensions(width, height)
}
