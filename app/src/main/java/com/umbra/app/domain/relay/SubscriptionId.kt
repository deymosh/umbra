package com.umbra.app.domain.relay

import kotlin.random.Random

private const val SUBSCRIPTION_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/**
 * Wire-level Nostr subscription id (the string sent verbatim in a REQ/COUNT message). NIP-01 only
 * requires a non-empty string — deliberately random and content-free rather than embedding
 * Umbra's internal purpose taxonomy (e.g. the old "outbox-notes-a1b2c3" scheme), which otherwise
 * hands every relay a readable label for what each subscription is for. Internal bookkeeping keys
 * (channel ids, [SubscriptionType]) never need to leave the device.
 */
fun randomSubscriptionId(length: Int = 6): String =
    (1..length)
        .map { SUBSCRIPTION_ID_ALPHABET[Random.nextInt(SUBSCRIPTION_ID_ALPHABET.length)] }
        .joinToString("")
