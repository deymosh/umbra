package com.umbra.app.domain.feed

/**
 * Shared defaults for feed filters.
 * Centralized so other domain classes can reference the same canonical sets.
 */
object FilterDefaults {
    val DEFAULT_EXCLUDED_HASHTAGS: Set<String> = setOf("xitchat", "claudie", "constitute", "kacti_broadcast", "sp_4c43bd1d")

    // Tag name prefixes typical of protocols embedding Nostr (e.g. gnostr).
    // These are tag-name prefixes (first element of each tag) that indicate
    // protocol noise and should be treated as excluded tag names by BLOCK.
    val DEFAULT_EXCLUDED_TAG_NAME_PREFIXES: Set<String> = setOf(
        "gnostr", "github", "repo", "workflow", "runner", "matrix", "nitter"
    )

    // Content prefixes indicating the note is actually a proxy/announcer for other content
    // (e.g. long-form posts). These make a kind-1 note not useful as a client note.
    val DEFAULT_EXCLUDED_CONTENT_PREFIXES: Set<String> = setOf("nlogpost:", "ncomment:")
}


