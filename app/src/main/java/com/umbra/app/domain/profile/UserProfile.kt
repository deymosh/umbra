package com.umbra.app.domain.profile

import androidx.compose.runtime.Immutable
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip30.extractCustomEmojis
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * User Profile metadata (NIP-01 kind 0)
 * Contains public user information
 */
@Serializable
@Immutable
data class UserProfile(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val picture: String? = null,
    val about: String? = null,
    val nip05: String? = null,  // NIP-05 internet identifier
    val website: String? = null,
    val banner: String? = null,
    val lud06: String? = null,  // LNURL-pay
    val lud16: String? = null,  // Lightning address
    val lastUpdated: Long = 0,   // Unix timestamp of fetch
    val nip05VerificationState: Nip05VerificationState = Nip05VerificationState.NotAvailable,  // NIP-05 verification status
    // NIP-30: this kind-0 event's own `emoji` tags, as shortcode → https URL. `:shortcode:` in
    // name/display_name/about renders as that image — only from the profile's own tags, never
    // from another event's. A plain value map (not a CustomEmoji object) keeps UserProfile
    // @Serializable with the standard Kotlinx plugin; CustomEmojiMapConverters persist it into
    // the Room user_profiles row.
    val customEmojis: Map<String, String> = emptyMap()
) {
    /**
     * Get display name with fallback priority
     */
    fun getUserDisplayName(): String {
        val cleanDisplayName = displayName?.trim()
        val cleanName = name?.trim()

        return if (!cleanDisplayName.isNullOrBlank()) {
            cleanDisplayName
        } else if (!cleanName.isNullOrBlank()) {
            cleanName
        } else {
            pubkey.take(8)
        }
    }

    /**
     * Check if profile is verified (NIP-05)
     */
    fun isVerified(): Boolean {
        return !nip05.isNullOrBlank()
    }

    companion object {
        /**
         * Create from kind 0 event content (JSON). [createdAt] must be the event's own NIP-01
         * `created_at` (seconds) — kind:0 is a replaceable event, so "which copy is newer" has to
         * be judged by that protocol timestamp, not by local wall-clock arrival time. The same
         * kind:0 event commonly arrives from several relays independently (especially right after
         * login, when the outbox-bootstrap REQ can be answered by many relays near-simultaneously)
         * — using System.currentTimeMillis() here instead previously meant a slower relay
         * delivering a genuinely OLDER cached copy after a faster relay had already delivered a
         * NEWER one would stamp that old copy as "newer" (later wall-clock arrival) and silently
         * overwrite the correct, already-applied profile — see UserRepositoryImpl.saveProfile's
         * hasNewerData check, which trusts this field completely.
         *
         * [eventTags] is the event's raw tag list (optional — callers with only the content JSON
         * keep working): its NIP-30 `emoji` tags become [customEmojis], so `:shortcode:` in
         * name/display_name/about can render from the profile's own metadata event.
         */
        fun fromJSON(
            pubkey: String,
            jsonContent: String,
            createdAt: Long,
            eventTags: List<List<String>> = emptyList()
        ): UserProfile {
            return try {
                val json = JsonUtils.NostrJson.parseToJsonElement(jsonContent)
                    as? JsonObject ?: return UserProfile(pubkey, lastUpdated = createdAt)

                UserProfile(
                    pubkey = pubkey,
                    name = json["name"]?.let { (it as? JsonPrimitive)?.content },
                    displayName = json["display_name"]?.let { (it as? JsonPrimitive)?.content }
                        ?: json["displayName"]?.let { (it as? JsonPrimitive)?.content },
                    picture = json["picture"]?.let { (it as? JsonPrimitive)?.content }
                        ?: json["image"]?.let { (it as? JsonPrimitive)?.content },
                    about = json["about"]?.let { (it as? JsonPrimitive)?.content },
                    nip05 = json["nip05"]?.let { (it as? JsonPrimitive)?.content },
                    website = json["website"]?.let { (it as? JsonPrimitive)?.content },
                    banner = json["banner"]?.let { (it as? JsonPrimitive)?.content },
                    lud06 = json["lud06"]?.let { (it as? JsonPrimitive)?.content },
                    lud16 = json["lud16"]?.let { (it as? JsonPrimitive)?.content },
                    customEmojis = extractCustomEmojis(eventTags).mapValues { (_, e) -> e.url },
                    lastUpdated = createdAt
                )
            } catch (e: Exception) {
                UserProfile(pubkey, lastUpdated = createdAt)
            }
        }
    }
}


