package com.umbra.app.domain.repository

/** Unsent composer text, one slot per context ("new note", or a reply to a given note). */
interface DraftRepository {
    fun load(key: String): String?
    fun save(key: String, text: String)
    fun clear(key: String)
}
