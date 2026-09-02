package com.umbra.app.domain.model

import androidx.compose.runtime.Immutable
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.profile.UserProfile
/**
 * Domain projection of a Nostr text-note enriched with its author profile and pre-computed
 * engagement counts. ViewModels consume [NoteView] directly from
 * [com.umbra.app.domain.repository.EventRepository] flows — no in-memory map lookups or
 * separate profile fetches needed at the ViewModel layer.
 *
 * Profile hydration happens one of two ways, depending on which [EventRepository] flow
 * produced this [NoteView]:
 * - Most flows (home/feed `observeFeedNotes`, `ThreadViewModel`'s thread graph, and
 *   `observeProfileNotes`' non-signed-in-user branch) call `userRepository.getProfiles(...)`
 *   explicitly and stitch the result in before emitting — updates propagate because the
 *   producing flow itself re-runs (`combine`/`distinctUntilChanged`/etc.), not via any
 *   database-level join.
 * - Only `observeProfileNotes`' signed-in-user branch uses a real Room JOIN
 *   (`EventDao.observeProfileNotes`, `LEFT JOIN user_profiles`, via
 *   `NoteWithProfile.toNoteView()`) that re-runs automatically on `user_profiles` changes.
 */
@Immutable
data class NoteView(
    // Always the original note/target — never the NIP-18 kind-6/16 repost wrapper event itself,
    // so every existing consumer of NoteView keeps working unchanged whether or not a note
    // happens to be reposted. [repostedByPubkey] carries the "this arrived via a repost" fact.
    val event: Event,
    /** null when the author's kind-0 metadata has not yet been fetched/cached. */
    val authorProfile: UserProfile?,
    val reactionCount: Int,
    val replyCount: Int,
    val repostCount: Int,
    /** Reposter's pubkey (NIP-18 kind 6/16 event author) — null when this note wasn't reposted. */
    val repostedByPubkey: String? = null,
    /** null when the author's kind-0 metadata has not yet been fetched/cached. */
    val repostedByProfile: UserProfile? = null,
    /**
     * The repost event's own created_at — feed display/sort position uses this over [event]'s
     * own timestamp when present, matching Twitter/Amethyst's "repost bumps the note to the top"
     * behavior.
     */
    val repostedAt: Long? = null,
    /**
     * The full NIP-18 kind-6/16 repost event itself — null when this note wasn't reposted. Its
     * overflow menu (see RepostBanner/EventCard) needs the actual event, not just its id, so it
     * can offer the exact same actions a normal note's menu does (copy content/json, pin, delete).
     */
    val repostEvent: Event? = null
)
