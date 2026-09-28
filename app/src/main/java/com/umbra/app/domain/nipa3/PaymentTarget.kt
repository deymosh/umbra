package com.umbra.app.domain.nipa3

import com.umbra.app.domain.nip01.Event

/** NIP-A3 replaceable event listing where a user accepts payments. */
const val KIND_PAYMENT_TARGETS = 10133

/** One `["payto", type, address]` entry. [type] is always lowercase per spec. */
data class PaymentTarget(val type: String, val address: String) {
    /**
     * The URI a wallet app understands: a network's own scheme where one is conventional
     * (`bitcoin:`, `lightning:`, …), otherwise RFC 8905 `payto://type/address`.
     */
    fun toUri(): String = when (type) {
        in DEDICATED_SCHEMES -> "$type:$address"
        else -> "payto://$type/$address"
    }

    /** Human label for known types; unknown ones show their raw type. */
    val label: String get() = KNOWN_LABELS[type] ?: type

    private companion object {
        val DEDICATED_SCHEMES = setOf("bitcoin", "lightning", "ethereum", "litecoin", "monero", "bitcoincash", "zcash", "solana", "nano")
        val KNOWN_LABELS = mapOf(
            "bitcoin" to "Bitcoin",
            "lightning" to "Lightning",
            "bip352" to "Silent payments",
            "bip353" to "Bitcoin DNS address",
            "bitcoincash" to "Bitcoin Cash",
            "cashme" to "Cash App",
            "ethereum" to "Ethereum",
            "litecoin" to "Litecoin",
            "monero" to "Monero",
            "nano" to "Nano",
            "paypal" to "PayPal",
            "revolut" to "Revolut",
            "solana" to "Solana",
            "tron" to "Tron",
            "venmo" to "Venmo",
            "zcash" to "Zcash"
        )
    }
}

/** Parses the `payto` tags of a kind-10133 event; anything else yields an empty list. */
fun parsePaymentTargets(event: Event): List<PaymentTarget> {
    if (event.kind != KIND_PAYMENT_TARGETS) return emptyList()
    return event.tags.asSequence()
        .filter { it.size >= 3 && it[0] == "payto" }
        .mapNotNull { tag ->
            val type = tag[1].trim().lowercase()
            val address = tag[2].trim()
            if (type.isEmpty() || address.isEmpty() || type.any { it.isWhitespace() }) null
            else PaymentTarget(type, address)
        }
        .distinct()
        .toList()
}
