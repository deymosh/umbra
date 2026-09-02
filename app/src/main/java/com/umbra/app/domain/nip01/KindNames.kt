package com.umbra.app.domain.nip01

/**
 * Human-readable label for a Nostr event kind, scoped to what Umbra actually implements (see
 * [Event.Companion]'s KIND_* constants) rather than porting a full protocol-wide kind registry —
 * a kind Umbra doesn't have a constant for is one it never sends in a filter, so it only ever
 * needs the numeric fallback: a label per kind number, "Kind <n>" for anything unlisted, used by
 * the Relay Details / Active Subscriptions screens to render filter kind chips.
 */
private data class KindName(val label: String, val nip: String?)

object KindNames {
    private val names: Map<Int, KindName> = mapOf(
        Event.KIND_METADATA to KindName("Profile", "01"),
        Event.KIND_TEXT_NOTE to KindName("Note", "01"),
        Event.KIND_RELAY_RECOMMENDATION to KindName("Relay Recommendation", "01"),
        Event.KIND_CONTACT_LIST to KindName("Follow List", "02"),
        Event.KIND_ENCRYPTED_DM to KindName("Legacy DM", "04"),
        Event.KIND_EVENT_DELETION to KindName("Deletion", "09"),
        Event.KIND_REPOST to KindName("Repost", "18"),
        Event.KIND_REACTION to KindName("Reaction", "25"),
        Event.KIND_BADGE_AWARD to KindName("Badge Award", "58"),
        Event.KIND_CHAT_MESSAGE to KindName("Chat Message", "C7"),
        Event.KIND_GROUP_CHAT_THREADED_REPLY to KindName("Group Reply", "29"),
        Event.KIND_THREAD to KindName("Thread", "7D"),
        Event.KIND_GROUP_THREAD_REPLY to KindName("Group Thread Reply", "29"),
        Event.KIND_SEAL to KindName("Seal", "59"),
        Event.KIND_DIRECT_MESSAGE to KindName("DM", "17"),
        Event.KIND_FILE_MESSAGE to KindName("DM File", "17"),
        Event.KIND_CLIENT_AUTH to KindName("Relay Auth", "42"),
        Event.KIND_GENERIC_REPOST to KindName("Generic Repost", "18"),
        Event.KIND_WEBSITE_REACTION to KindName("Website Reaction", "25"),
        Event.KIND_PICTURE to KindName("Picture", "68"),
        Event.KIND_VIDEO_EVENT to KindName("Video", "71"),
        Event.KIND_SHORT_FORM_PORTRAIT_VIDEO_EVENT to KindName("Short Video", "71"),
        Event.KIND_LONG_FORM to KindName("Article", "23"),
        Event.KIND_PUBLIC_MESSAGE to KindName("Public Message", "A4"),
        Event.KIND_INTERNAL_REFERENCE to KindName("Internal Reference", null),
        Event.KIND_COMMENT to KindName("Comment", "22"),
        Event.KIND_ZAP_REQUEST to KindName("Zap Request", "57"),
        Event.KIND_ZAP_RECEIPT to KindName("Zap Receipt", "57"),
        Event.KIND_MUTED_USERS to KindName("Mute List", "51"),
        Event.KIND_PINNED_EVENTS to KindName("Pinned Notes", "51"),
        Event.KIND_RELAY_LIST_METADATA to KindName("Relay List", "65"),
        Event.KIND_DM_RELAY_LIST to KindName("DM Relay List", "17"),
        Event.KIND_BOOKMARK_LIST to KindName("Bookmarks", "51"),
        Event.KIND_COMMUNITIES_LIST to KindName("Communities List", "51"),
        Event.KIND_BLOCKED_RELAYS to KindName("Blocked Relays", "51"),
        Event.KIND_SEARCH_RELAYS to KindName("Search Relays", "51"),
        Event.KIND_INTERESTS_LIST to KindName("Interests List", "51"),
        // Not in a ratified NIP yet — see Event.KIND_INDEX_RELAYS.
        Event.KIND_INDEX_RELAYS to KindName("Index Relays", null),
        Event.KIND_BLOSSOM_AUTH to KindName("Blossom Auth", null),
        Event.KIND_BLOSSOM_SERVER_LIST to KindName("Blossom Servers", "B7")
    )

    /** Human label for [kind], falling back to "Kind <n>" for anything not in the table above. */
    fun labelFor(kind: Int): String = names[kind]?.label ?: "Kind $kind"
}
