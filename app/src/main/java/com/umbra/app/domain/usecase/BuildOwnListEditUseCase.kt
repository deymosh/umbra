package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.applyListEdit
import com.umbra.app.domain.nip51.encodePrivateTags
import com.umbra.app.domain.nip51.newListSetTags
import com.umbra.app.domain.nip51.retitledListSetTags
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.EventRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Builds the next version of one of the user's replaceable lists (kind 3 contacts, 10000 mutes,
 * 10001 pins, 10003 bookmarks, 10015 interests, and NIP-51 sets) as an edit of their latest
 * published version: only [ListEdit]'s values change, and every other tag is carried over
 * untouched. [fallbackValues] seeds the list only when no previous version exists.
 *
 * NIP-51 private items live encrypted in `content`. With [privately], the edit's added values go
 * there instead of into the public tags; either way an added value is taken out of the other
 * side, so it moves rather than appearing twice, and a removed value leaves both. Private items
 * this edit doesn't touch are kept. When the content can't be decrypted (permission refused,
 * NIP-04), a public edit leaves it exactly as it was, and a private one fails rather than
 * overwrite items it couldn't read.
 *
 * Meant to run inside a lazy `buildEventJson`, right before the signer signs, so two quick edits
 * in a row each build on the newest list rather than a stale snapshot. The signer may be asked to
 * decrypt and encrypt first; that's the user's own action, so it may prompt.
 */
class BuildOwnListEditUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val userPreferences: UserPreferences,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase,
    private val nip44Gateway: Nip44Gateway
) {
    /**
     * [identifier] picks one set (kind 30000-39999) by its `d` tag instead of the kind's single
     * list; a set that doesn't exist yet is created, titled [newTitle]. [newTitle] on an existing
     * set renames it.
     */
    suspend operator fun invoke(
        kind: Int,
        edit: ListEdit,
        fallbackValues: Set<String> = emptySet(),
        privately: Boolean = false,
        identifier: String? = null,
        newTitle: String? = null
    ): String {
        val owner = userPreferences.getPublicKey()?.lowercase()
        val base = owner?.let { latest(it, kind, identifier) }
        val content = base?.content.orEmpty()
        val moved = edit.remove + edit.add
        val publicEdit = if (privately) ListEdit(edit.tagName, remove = moved) else edit
        val privateEdit = if (privately) edit else ListEdit(edit.tagName, remove = moved)

        // A follow list has no private items (its content is at most an old relay map), and an
        // edit that changes no items (a rename) has no reason to ask the signer anything.
        val privateTags = if (kind == Event.KIND_CONTACT_LIST || moved.isEmpty()) {
            null
        } else {
            decryptOwnListItems(content, interactive = true)
        }
        val newContent = when {
            privateTags == null && privately -> throw IllegalStateException("Private items could not be read")
            privateTags == null -> content
            else -> {
                val updated = applyListEdit(privateTags, privateEdit)
                when {
                    updated == privateTags -> content
                    updated.isEmpty() -> ""
                    else -> encrypt(updated, owner)
                }
            }
        }
        val edited = applyListEdit(base, publicEdit, if (privately) emptySet() else fallbackValues)
        val tags = when {
            identifier == null -> edited
            base == null -> newListSetTags(identifier, newTitle ?: identifier) + edited
            newTitle != null -> retitledListSetTags(edited, newTitle)
            else -> edited
        }
        return NostrEventBuilder.listEvent(kind = kind, content = newContent, tags = tags)
    }

    private suspend fun latest(owner: String, kind: Int, identifier: String?): Event? =
        if (identifier == null) {
            eventRepository.observeEventsByPubkeyAndKind(owner, kind, limit = 1).first().firstOrNull()
        } else {
            eventRepository.observeEventsByPubkeyAndKind(owner, kind, limit = MAX_SETS).first()
                .filter { it.getTagValue("d").orEmpty() == identifier }
                .maxByOrNull { it.createdAt }
        }

    private companion object {
        const val MAX_SETS = 500
    }

    private suspend fun encrypt(tags: List<List<String>>, owner: String?): String {
        val me = owner ?: throw IllegalStateException("Not signed in")
        val ciphertext = nip44Gateway.nip44Encrypt(encodePrivateTags(tags), me, me)
            ?: throw IllegalStateException("Private items could not be encrypted")
        decryptOwnListItems.remember(ciphertext, tags)
        return ciphertext
    }
}
