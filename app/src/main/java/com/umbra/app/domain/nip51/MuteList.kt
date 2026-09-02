package com.umbra.app.domain.nip51

data class MuteList(
    val ownerPubkey: String,
    val mutedPubkeys: Set<String>,
    val updatedAt: Long
)
