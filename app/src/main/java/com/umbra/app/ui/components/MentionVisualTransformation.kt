package com.umbra.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Matches only the canonical `nostr:npub1…`/`nostr:nprofile1…` form the composer itself inserts. */
internal val MENTION_URI_REGEX = Regex("""nostr:(?:npub1[a-z0-9]+|nprofile1[a-z0-9]+)""", RegexOption.IGNORE_CASE)

/**
 * The "@displayName" (or truncated-bech32 fallback while the name is still resolving) shown in
 * place of a raw `nostr:npub1…`/`nostr:nprofile1…` [uri] — shared by [MentionVisualTransformation]
 * (composer's visible overlay) and the composer's `OutputTransformation` (the actual text-field
 * content shown to the invisible input layer), which must stay byte-for-byte identical or the
 * caret drifts from the rendered glyphs.
 */
internal fun mentionLabelFor(uri: String, displayNameForPubkey: (String) -> String?): String {
    val pubkey = resolveProfileReference(uri)
    return pubkey?.let(displayNameForPubkey)?.let { "@$it" }
        ?: "@${uri.removePrefix("nostr:").take(10)}…"
}

/**
 * Renders `nostr:npub1…`/`nostr:nprofile1…` spans in a composer's editable text as "@displayName"
 * (or a truncated bech32 fallback while the name is still resolving) — the underlying
 * [TextFieldValue] text is untouched, so the event actually published still carries the real
 * `nostr:` URI (see [NostrEventBuilder]'s `mentionTags()`, which scans content for exactly this
 * pattern). Every original index inside a collapsed mention maps to the label's start, and every
 * transformed index inside the label maps back to the mention's end — so the caret can't land
 * mid-URI, and backspacing from just after the label deletes the whole atomic token in one step.
 */
class MentionVisualTransformation(
    private val mentionColor: Color,
    private val displayNameForPubkey: (String) -> String?
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val matches = MENTION_URI_REGEX.findAll(original).toList()
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val transformedBuilder = AnnotatedString.Builder()
        val origToTransformed = IntArray(original.length + 1)
        val transformedToOrig = mutableListOf<Int>()

        fun appendLiteral(from: Int, to: Int) {
            for (i in from until to) {
                origToTransformed[i] = transformedBuilder.length
                transformedToOrig.add(i)
            }
            transformedBuilder.append(original.substring(from, to))
        }

        var cursor = 0
        for (match in matches) {
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start > cursor) appendLiteral(cursor, start)

            val label = mentionLabelFor(match.value, displayNameForPubkey)
            val labelStart = transformedBuilder.length
            transformedBuilder.pushStyle(SpanStyle(color = mentionColor))
            transformedBuilder.append(label)
            transformedBuilder.pop()

            for (i in start until endExclusive) origToTransformed[i] = labelStart
            repeat(label.length) { transformedToOrig.add(endExclusive) }
            cursor = endExclusive
        }
        if (cursor < original.length) appendLiteral(cursor, original.length)

        origToTransformed[original.length] = transformedBuilder.length
        transformedToOrig.add(original.length)

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                origToTransformed[offset.coerceIn(0, original.length)]

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOrig[offset.coerceIn(0, transformedToOrig.size - 1)]
        }

        return TransformedText(transformedBuilder.toAnnotatedString(), offsetMapping)
    }
}
