package com.umbra.app.domain.nip25

import com.umbra.app.domain.nip30.CustomEmoji

/**
 * A user-editable reaction choice offered by the reaction picker — either a plain Unicode emoji
 * or an image-backed NIP-30 custom emoji. Both kinds live in the same user-owned, add/removable
 * list (see ReactionEmojiRepository) — including the shipped defaults, which are just normal
 * seeded entries, not a fixed app-side set.
 */
sealed class ReactionEmoji {
    abstract val key: String

    data class Unicode(val emoji: String) : ReactionEmoji() {
        override val key: String get() = emoji
    }

    data class Custom(val emoji: CustomEmoji) : ReactionEmoji() {
        override val key: String get() = emoji.shortcode
    }
}

/** Seeded into a fresh install's reaction list — a normal, user-editable starting point. */
val DEFAULT_REACTION_EMOJIS: List<ReactionEmoji> =
    listOf("👍", "❤️", "😂", "😮", "😢", "🔥").map { ReactionEmoji.Unicode(it) }
