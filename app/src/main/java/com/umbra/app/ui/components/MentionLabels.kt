package com.umbra.app.ui.components

/** Matches only the canonical `nostr:npub1…`/`nostr:nprofile1…` form the composer itself inserts. */
internal val MENTION_URI_REGEX = Regex("""nostr:(?:npub1[a-z0-9]+|nprofile1[a-z0-9]+)""", RegexOption.IGNORE_CASE)

/**
 * The "@displayName" (or truncated-bech32 fallback while the name is still resolving) shown in
 * place of a raw `nostr:npub1…`/`nostr:nprofile1…` [uri] by the composer's token pass
 * (ui/composer/ComposerTokens.kt), which feeds both the text field and its visible overlay.
 */
internal fun mentionLabelFor(uri: String, displayNameForPubkey: (String) -> String?): String {
    val pubkey = resolveProfileReference(uri)
    return pubkey?.let(displayNameForPubkey)?.let { "@$it" }
        ?: "@${uri.removePrefix("nostr:").take(10)}…"
}
