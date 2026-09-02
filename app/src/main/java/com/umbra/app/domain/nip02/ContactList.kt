package com.umbra.app.domain.nip02

data class ContactList(
    val ownerPubkey: String,
    val followedPubkeys: Set<String>,
    val updatedAt: Long
)


