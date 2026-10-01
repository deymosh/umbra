package com.umbra.app.domain.nip86

/**
 * NIP-86 relay management API methods, grouped by what they act on. The transport, request/
 * response shaping and JSON live in [RelayManagementRpc]; screens pick sections by which of
 * these a relay advertises via `supportedmethods`.
 */
enum class RelayManagementMethod(val wireName: String, val category: RelayManagementCategory) {
    // Capability probe — always the first call; never shown as a section itself.
    SUPPORTED_METHODS("supportedmethods", RelayManagementCategory.INFO),

    BAN_PUBKEY("banpubkey", RelayManagementCategory.PEOPLE),
    UNBAN_PUBKEY("unbanpubkey", RelayManagementCategory.PEOPLE),
    ALLOW_PUBKEY("allowpubkey", RelayManagementCategory.PEOPLE),
    UNALLOW_PUBKEY("unallowpubkey", RelayManagementCategory.PEOPLE),
    LIST_BANNED_PUBKEYS("listbannedpubkeys", RelayManagementCategory.PEOPLE),
    LIST_ALLOWED_PUBKEYS("listallowedpubkeys", RelayManagementCategory.PEOPLE),

    LIST_EVENTS_NEEDING_MODERATION("listeventsneedingmoderation", RelayManagementCategory.EVENTS),
    ALLOW_EVENT("allowevent", RelayManagementCategory.EVENTS),
    BAN_EVENT("banevent", RelayManagementCategory.EVENTS),
    UNALLOW_EVENT("unallowevent", RelayManagementCategory.EVENTS),
    UNBAN_EVENT("unbanevent", RelayManagementCategory.EVENTS),
    LIST_BANNED_EVENTS("listbannedevents", RelayManagementCategory.EVENTS),
    LIST_ALLOWED_EVENTS("listallowedevents", RelayManagementCategory.EVENTS),

    CHANGE_RELAY_NAME("changerelayname", RelayManagementCategory.RELAY),
    CHANGE_RELAY_DESCRIPTION("changerelaydescription", RelayManagementCategory.RELAY),
    CHANGE_RELAY_ICON("changerelayicon", RelayManagementCategory.RELAY),

    ALLOW_KIND("allowkind", RelayManagementCategory.KINDS),
    DISALLOW_KIND("disallowkind", RelayManagementCategory.KINDS),
    LIST_ALLOWED_KINDS("listallowedkinds", RelayManagementCategory.KINDS),
    LIST_DISALLOWED_KINDS("listdisallowedkinds", RelayManagementCategory.KINDS),

    BLOCK_IP("blockip", RelayManagementCategory.IPS),
    UNBLOCK_IP("unblockip", RelayManagementCategory.IPS),
    LIST_BLOCKED_IPS("listblockedips", RelayManagementCategory.IPS);
}

/** Which UI section a method belongs to; sections are hidden entirely when none are supported. */
enum class RelayManagementCategory {
    INFO,
    PEOPLE,
    EVENTS,
    RELAY,
    KINDS,
    IPS
}

/** Wire names are stable; lookups by name guard against relays sending unknown strings. */
fun relayManagementMethodByWireName(name: String): RelayManagementMethod? =
    RelayManagementMethod.entries.firstOrNull { it.wireName == name }

/** Methods a relay advertises, ignoring anything unrecognized or duplicative. */
fun parseSupportedMethods(names: List<String>): Set<RelayManagementMethod> =
    names.mapNotNull(::relayManagementMethodByWireName).toSet()
