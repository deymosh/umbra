package com.umbra.app.data.repository

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import com.umbra.app.domain.repository.DraftRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Drafts are the user's own unpublished words, so they live in encrypted preferences. */
@Singleton
class DraftRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : DraftRepository {
    private val prefs by lazy { SecurePreferences(context, "composer_drafts") }

    override fun load(key: String): String? = prefs.getString(key)?.takeIf { it.isNotBlank() }

    override fun save(key: String, text: String) {
        if (text.isBlank()) prefs.remove(key) else prefs.putString(key, text)
    }

    override fun clear(key: String) = prefs.remove(key)
}
