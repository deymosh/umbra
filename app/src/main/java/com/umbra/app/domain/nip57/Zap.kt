package com.umbra.app.domain.nip57

/**
 * LUD-06 pay endpoint details, plus the LUD-12/NIP-57 extensions Umbra uses. Amounts are in
 * millisatoshis, as on the wire.
 */
data class LnurlPayInfo(
    val callback: String,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val allowsNostr: Boolean,
    val nostrPubkey: String?,
    val commentAllowed: Int,
    /** The bech32 `lnurl1…` of the endpoint this came from, echoed back in the zap request. */
    val lnurl: String
) {
    /** NIP-57: a zap needs the endpoint to accept nostr requests and name the receipt signer. */
    val supportsZaps: Boolean get() = allowsNostr && !nostrPubkey.isNullOrBlank()

    fun accepts(amountMsat: Long): Boolean = amountMsat in minSendableMsat..maxSendableMsat
}

/**
 * The LNURL-pay endpoint for a lightning address (LUD-16 `name@domain`), or null when it isn't
 * one. Onion domains are addressed over plain http: the Tor circuit itself is the encryption.
 */
fun lightningAddressToPayUrl(address: String): String? {
    val trimmed = address.trim().lowercase()
    val at = trimmed.indexOf('@')
    if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.lastIndex) return null
    val name = trimmed.substring(0, at)
    val domain = trimmed.substring(at + 1)
    if (!name.all { it.isLetterOrDigit() || it in "-_.+" }) return null
    if (!domain.contains('.') || !domain.all { it.isLetterOrDigit() || it in "-.:" }) return null
    val scheme = if (domain.endsWith(".onion")) "http" else "https"
    return "$scheme://$domain/.well-known/lnurlp/$name"
}

/** Quick-pick zap amounts in sats, smallest first. */
val DEFAULT_ZAP_AMOUNTS_SATS: List<Long> = listOf(21, 100, 500, 1_000, 5_000, 21_000)
