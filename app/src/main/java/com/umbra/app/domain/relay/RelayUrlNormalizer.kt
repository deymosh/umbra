package com.umbra.app.domain.relay

fun normalizeRelayUrl(url: String): String =
    stripDefaultPort(normalizeOnionScheme(url.trim().trimEnd('/').lowercase()))

/**
 * Rewrites wss:// to ws:// for .onion hosts. An onion hidden service is already reached over the
 * end-to-end-encrypted Tor circuit, so TLS on top of it is conventionally not used — but relay
 * lists discovered from peers (NIP-65 outbox entries, relay hints) sometimes declare an onion
 * relay as wss:// anyway (a copy-pasted clearnet entry, or simply a misconfigured client), which
 * left the relay's *scheme* disagreeing with its own [Relay.isOnion] flag. Applied at this single
 * normalization choke point — every relay URL (discovery, NIP-65 sync, manual add/edit) passes
 * through here — so the fix-up is consistent everywhere instead of only at one call site.
 */
private fun normalizeOnionScheme(url: String): String {
    if (!url.startsWith("wss://")) return url
    val host = extractRelayHost(url) ?: return url
    if (!host.endsWith(".onion")) return url
    return "ws://" + url.removePrefix("wss://")
}

/**
 * Strips an explicit default port (:443 for wss://, :80 for ws://) from [url]'s authority, so a
 * relay declared with an explicit default port and the same relay declared without one collapse
 * to the same normalized identity. Without this, a peer's NIP-65 entry
 * "wss://relay.example.com:443" and a user-typed "wss://relay.example.com" are the same relay
 * server but different strings — every url-keyed identity check (the discovered-relay dedup in
 * selectNewDiscoverableRelayUrls, the "does this relay already exist" lookup in
 * RelayConfigViewModel.saveRelay, the DB's own unique index on the raw url column) treats them as
 * two different relays, so both a "discovered" row and the user's own outbox/inbox row can exist
 * for what is really one relay, each independently opening its own connection.
 *
 * Only touches the authority's trailing port token — a bracketed IPv6 host's own colons are
 * untouched unless the authority literally ends in ":443"/":80" after the scheme's default flips.
 */
private fun stripDefaultPort(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val defaultPort = when (url.substring(0, schemeEnd)) {
        "wss" -> ":443"
        "ws" -> ":80"
        else -> return url
    }
    val afterScheme = url.substring(schemeEnd + 3)
    val pathStart = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (pathStart >= 0) afterScheme.substring(0, pathStart) else afterScheme
    if (!authority.endsWith(defaultPort)) return url
    val strippedAuthority = authority.removeSuffix(defaultPort)
    if (strippedAuthority.isBlank()) return url
    val rest = if (pathStart >= 0) afterScheme.substring(pathStart) else ""
    return url.substring(0, schemeEnd + 3) + strippedAuthority + rest
}

/**
 * True when [url]'s host is a loopback/private/link-local address — i.e. a relay that is only
 * reachable on the device's own local network, not over Tor. Discovered relays (auto-added from
 * a peer's declared NIP-65 relay list — see UserRepositoryImpl.saveRelayList()) must never
 * include these: a peer's kind:10002 can legitimately (or maliciously) list an address like
 * ws://192.168.1.1:4848 or ws://127.0.0.1:7777, and auto-adding it would make the app try to
 * dial the *reader's own* local network through the Tor SOCKS proxy — at best a pointless
 * connection attempt, at worst a way to probe/fingerprint the reader's LAN. Deliberately checks
 * only IP-literal hosts (never resolves a hostname — that would mean a DNS lookup outside Tor,
 * which this app never does per its TOR-only constraint); a private IP hidden behind a hostname
 * is still reached exclusively through the Tor proxy like any other relay, same as today.
 */
fun isLocalNetworkRelayUrl(url: String): Boolean {
    val host = extractRelayHost(url) ?: return false
    return isLocalNetworkHost(host)
}

/**
 * Pure selection logic for UserRepositoryImpl.addDiscoveredRelaysFromList(): normalizes
 * [outboxRelayUrls], drops blanks, local-network hosts (see [isLocalNetworkRelayUrl]) and
 * anything already in [existingUrls], then caps the result at [budget] (the remaining room
 * under the session-wide discovered-relay ceiling).
 */
fun selectNewDiscoverableRelayUrls(
    outboxRelayUrls: List<String>,
    existingUrls: Set<String>,
    budget: Int
): List<String> {
    if (budget <= 0) return emptyList()
    return outboxRelayUrls
        .map(::normalizeRelayUrl)
        .filter { it.isNotBlank() && !isLocalNetworkRelayUrl(it) }
        .distinct()
        .filterNot { it in existingUrls }
        .take(budget)
}

private fun extractRelayHost(url: String): String? {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = url)
    val afterUserInfo = afterScheme.substringAfterLast('@')
    val authority = afterUserInfo.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank()) return null

    if (authority.startsWith('[')) {
        // Bracketed IPv6 literal, e.g. [::1]:4848
        val end = authority.indexOf(']')
        return if (end > 0) authority.substring(1, end) else null
    }

    val colonCount = authority.count { it == ':' }
    return when {
        colonCount == 0 -> authority
        colonCount == 1 -> authority.substringBefore(':') // host:port
        else -> authority // bare (unbracketed) IPv6 literal, no port
    }
}

private fun isLocalNetworkHost(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized == "localhost" || normalized.endsWith(".local")) return true

    val ipv4 = parseIpv4(normalized)
    if (ipv4 != null) return isPrivateIpv4(ipv4)

    return isPrivateIpv6(normalized)
}

private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (i in 0 until 4) {
        val octet = parts[i].toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        octets[i] = octet
    }
    return octets
}

private fun isPrivateIpv4(octets: IntArray): Boolean {
    val (a, b, _, _) = octets
    return when {
        a == 127 -> true // loopback
        a == 10 -> true // 10.0.0.0/8
        a == 172 && b in 16..31 -> true // 172.16.0.0/12
        a == 192 && b == 168 -> true // 192.168.0.0/16
        a == 169 && b == 254 -> true // 169.254.0.0/16 link-local
        a == 0 -> true // 0.0.0.0/8
        else -> false
    }
}

private operator fun IntArray.component4(): Int = this[3]

private fun isPrivateIpv6(host: String): Boolean {
    if (!host.contains(':')) return false // not an IPv6 literal
    val normalized = host.lowercase()
    return normalized == "::1" ||
        normalized == "::" ||
        normalized.startsWith("fe8") || // fe80::/10 link-local (fe80-febf)
        normalized.startsWith("fe9") ||
        normalized.startsWith("fea") ||
        normalized.startsWith("feb") ||
        normalized.startsWith("fc") || // fc00::/7 unique local (fc00-fdff)
        normalized.startsWith("fd") ||
        normalized.startsWith("::ffff:") && parseIpv4(normalized.substringAfter("::ffff:"))?.let(::isPrivateIpv4) == true
}
