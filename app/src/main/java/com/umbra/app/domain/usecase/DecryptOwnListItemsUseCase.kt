package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip51.isLegacyNip04Content
import com.umbra.app.domain.nip51.looksLikeNip44Payload
import com.umbra.app.domain.nip51.parsePrivateTags
import com.umbra.app.domain.preferences.UserPreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The private items (NIP-51) of one of the signed-in user's lists, decrypted by the external
 * signer. [interactive] false only uses a permission the user already granted, so opening a
 * screen never pops the signer; true may ask, for when the user acted. Results are remembered per
 * ciphertext for the session, since the same content is decrypted again on every edit.
 *
 * Returns an empty list for a list with no private content, and null when the content can't be
 * read (no permission yet, refused, NIP-04, or not a tag array).
 */
@Singleton
class DecryptOwnListItemsUseCase @Inject constructor(
    private val nip44Gateway: Nip44Gateway,
    private val userPreferences: UserPreferences
) {
    private val decrypted = ConcurrentHashMap<String, List<List<String>>>()

    suspend operator fun invoke(content: String, interactive: Boolean): List<List<String>>? {
        if (content.isBlank()) return emptyList()
        decrypted[content]?.let { return it }
        if (isLegacyNip04Content(content) || !looksLikeNip44Payload(content)) return null
        val me = userPreferences.getPublicKey()?.takeIf { userPreferences.canSignWithAmber() } ?: return null
        val plaintext = if (interactive) {
            nip44Gateway.nip44Decrypt(content, me, me)
        } else {
            nip44Gateway.tryNip44DecryptInBackground(content, me, me)
        } ?: return null
        val tags = parsePrivateTags(plaintext) ?: return null
        if (decrypted.size >= MAX_REMEMBERED) decrypted.clear()
        decrypted[content] = tags
        return tags
    }

    /** Forgets everything decrypted, e.g. on logout. */
    fun clear() = decrypted.clear()

    /** Records [tags] as the plaintext of [content] Umbra itself just encrypted. */
    internal fun remember(content: String, tags: List<List<String>>) {
        decrypted[content] = tags
    }

    private companion object {
        const val MAX_REMEMBERED = 64
    }
}
