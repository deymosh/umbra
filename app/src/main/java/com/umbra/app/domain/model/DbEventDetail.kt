package com.umbra.app.domain.model

/**
 * Raw encrypted-DB row projection for the DB inspector (developer-only) — deliberately not the
 * regular [com.umbra.app.domain.nip01.Event]: this is meant to show exactly what's stored (raw
 * `tagsJson`, no parsed tags), not a cleaned-up domain view.
 */
data class DbEventDetail(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val createdAt: Long,
    val content: String,
    val tagsJson: String,
    val sig: String
)
