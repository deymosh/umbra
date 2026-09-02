package com.umbra.app.domain.nip51

data class PinList(
    val ownerPubkey: String,
    val pinnedEventIds: Set<String>,
    val updatedAt: Long
)
