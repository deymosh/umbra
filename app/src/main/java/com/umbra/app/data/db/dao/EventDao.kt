package com.umbra.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.data.db.pojo.EventIdTimestamp
import com.umbra.app.data.db.pojo.NoteWithProfile
import com.umbra.app.data.repository.NegentropyEventSource
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao : NegentropyEventSource {
    @Query("SELECT * FROM events ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int = 500): Flow<List<EventEntity>>

    @Query(
        """
        SELECT *
        FROM events
        WHERE kind = :kind
          AND pubkey = :pubkey
          AND (
                (:identifier = '' AND (
                    NOT EXISTS (
                        SELECT 1 FROM event_tags dt
                        WHERE dt.event_id = events.id
                          AND dt.tag_name = 'd'
                    )
                    OR EXISTS (
                        SELECT 1 FROM event_tags dt
                        WHERE dt.event_id = events.id
                          AND dt.tag_name = 'd'
                          AND dt.tag_value = ''
                    )
                ))
                OR
                (:identifier != '' AND EXISTS (
                    SELECT 1 FROM event_tags dt
                    WHERE dt.event_id = events.id
                      AND dt.tag_name = 'd'
                      AND dt.tag_value = :identifier
                ))
          )
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun getLatestAddressableEvent(kind: Int, pubkey: String, identifier: String): EventEntity?

    // Prunes older revisions of a replaceable/parameterized-replaceable event (NIP-01/33) for a
    // single (kind, pubkey[, d-tag]) slot, keeping only the newest by created_at (ties broken by
    // lowest id, matching Event.winsReplaceableRace). @Upsert conflict-resolves by `id` only, and
    // every revision of a replaceable event has a distinct id, so without this, old revisions
    // accumulate in `events`/`event_tags` forever instead of being superseded the way the
    // in-memory EventLruCache already handles via ReplaceableEventKey/winsReplaceableRace. Same
    // d-tag EXISTS/NOT EXISTS matching as getLatestAddressableEvent above, applied twice: once to
    // scope which rows in this slot are candidates for deletion, once inside the subquery to find
    // the single winning id to keep. event_tags rows for anything deleted here cascade-delete via
    // EventTagEntity's ForeignKey(onDelete = CASCADE) — no separate tag cleanup needed.
    @Query(
        """
        DELETE FROM events
        WHERE kind = :kind
          AND pubkey = :pubkey
          AND (
                (:identifier = '' AND (
                    NOT EXISTS (
                        SELECT 1 FROM event_tags dt
                        WHERE dt.event_id = events.id
                          AND dt.tag_name = 'd'
                    )
                    OR EXISTS (
                        SELECT 1 FROM event_tags dt
                        WHERE dt.event_id = events.id
                          AND dt.tag_name = 'd'
                          AND dt.tag_value = ''
                    )
                ))
                OR
                (:identifier != '' AND EXISTS (
                    SELECT 1 FROM event_tags dt
                    WHERE dt.event_id = events.id
                      AND dt.tag_name = 'd'
                      AND dt.tag_value = :identifier
                ))
          )
          AND id != (
              SELECT e2.id
              FROM events e2
              WHERE e2.kind = :kind
                AND e2.pubkey = :pubkey
                AND (
                      (:identifier = '' AND (
                          NOT EXISTS (
                              SELECT 1 FROM event_tags dt2
                              WHERE dt2.event_id = e2.id
                                AND dt2.tag_name = 'd'
                          )
                          OR EXISTS (
                              SELECT 1 FROM event_tags dt2
                              WHERE dt2.event_id = e2.id
                                AND dt2.tag_name = 'd'
                                AND dt2.tag_value = ''
                          )
                      ))
                      OR
                      (:identifier != '' AND EXISTS (
                          SELECT 1 FROM event_tags dt2
                          WHERE dt2.event_id = e2.id
                            AND dt2.tag_name = 'd'
                            AND dt2.tag_value = :identifier
                      ))
                )
              ORDER BY e2.created_at DESC, e2.id ASC
              LIMIT 1
          )
        """
    )
    suspend fun deleteSupersededReplaceableEvents(kind: Int, pubkey: String, identifier: String): Int

    @Upsert
    suspend fun insertEvents(events: List<EventEntity>)

    @Upsert
    suspend fun insertEvent(event: EventEntity)

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEventById(eventId: String)

    // Events by author + kind (uses composite index)
    @Query("SELECT * FROM events WHERE pubkey = :pubkey AND kind = :kind ORDER BY created_at DESC LIMIT :limit")
    fun observeEventsByPubkeyAndKind(pubkey: String, kind: Int, limit: Int = 100): Flow<List<EventEntity>>

    // Own NIP-18 reposts (kind 6/16), for EventRepositoryImpl.observeProfileNotes's self branch —
    // deliberately a plain, uncorrelated row fetch, NOT folded into observeProfileNotes below:
    // that query's `engagement` CTE computes counts keyed to each returned row's own id, which is
    // correct for a text note but meaningless for a repost row (nobody reacts to the repost
    // wrapper — what matters is the *reposted target's* engagement, resolved separately once the
    // target is known). 6, 16 as literals mirrors this file's own eng.kind IN (1, 6, 7) below
    // (Event.KIND_REPOST / Event.KIND_GENERIC_REPOST — @Query can't reference Kotlin constants).
    @Query("SELECT * FROM events WHERE pubkey = :pubkey AND kind IN (6, 16) ORDER BY created_at DESC LIMIT :limit")
    fun observeOwnReposts(pubkey: String, limit: Int): Flow<List<EventEntity>>

    // Reactive total count by author + kind (used by profile header count)
    @Query("SELECT COUNT(*) FROM events WHERE pubkey = :pubkey AND kind = :kind")
    fun observeCountEventsByPubkeyAndKind(pubkey: String, kind: Int): Flow<Int>

    // Batch fetch by IDs (for thread loading)
    @Query("SELECT * FROM events WHERE id IN (:ids)")
    override suspend fun getEventsByIds(ids: List<String>): List<EventEntity>

    // Reverse lookup by indexed e-tags (thread replies / engagement references)
    @Query(
        """
        SELECT DISTINCT e.*
        FROM events e
        JOIN event_tags et ON et.event_id = e.id
        WHERE et.tag_name = 'e'
          AND et.tag_value IN (:targetIds)
        ORDER BY e.created_at ASC
        """
    )
    suspend fun getEventsReferencingIds(targetIds: List<String>): List<EventEntity>

    // Single event fetch (for thread anchor)
    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): EventEntity?

    // Get oldest event timestamp for a specific author + kind.
    @Query("SELECT MIN(created_at) FROM events WHERE pubkey = :pubkey AND kind = :kind")
    suspend fun getOldestTimestampByPubkeyAndKind(pubkey: String, kind: Int): Long?

    // ── DB inspector (developer-only) — read-only browse/search, no different from the queries
    // above other than being parameterized by optional filters for an ad-hoc UI search box.

    @Query("SELECT COUNT(*) FROM events")
    suspend fun countEvents(): Int

    @Query(
        """
        SELECT * FROM events
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:pubkey IS NULL OR pubkey = :pubkey)
          AND (:contentQuery IS NULL OR content LIKE '%' || :contentQuery || '%')
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchEvents(
        kind: Int?,
        pubkey: String?,
        contentQuery: String?,
        limit: Int,
        offset: Int
    ): List<EventEntity>

    /**
     * Returns the oldest cached note timestamp for events that reference the user (INBOX_NOTES).
     * Only counts notes from other users (author != pubkey)
     */
    @Query("""
        SELECT MIN(e.created_at)
        FROM events e
        JOIN event_tags et ON et.event_id = e.id
        WHERE e.kind = 1
          AND et.tag_name = 'p'
          AND et.tag_value = :pubkey
          AND e.pubkey != :pubkey
    """)
    suspend fun getOldestInboxNoteTimestamp(pubkey: String): Long?

    /**
     * Returns the oldest cached reaction timestamp for events that reference the user (part of INBOX_NOTES' interactions filter).
     * Only counts reactions from other users (author != pubkey)
     */
    @Query("""
        SELECT MIN(e.created_at)
        FROM events e
        JOIN event_tags et ON et.event_id = e.id
        WHERE e.kind = 7
          AND et.tag_name = 'p'
          AND et.tag_value = :pubkey
          AND e.pubkey != :pubkey
    """)
    suspend fun getOldestInboxReactionTimestamp(pubkey: String): Long?

    // Get newest event timestamp (for since filter on reconnect)
    @Query("SELECT MAX(created_at) FROM events WHERE kind = :kind")
    suspend fun getNewestTimestampByKind(kind: Int): Long?

    // NIP-77: cheap (id, created_at) projection feeding EventRepositoryImpl.scheduleNegentropySync()'s
    // localItems snapshot for NegentropySyncOrchestrator.sync() — see its doc comment. Deliberately
    // skips tags/content/sig, which sync's local index has no use for. [kinds] must match whatever kind set the sync's
    // relay-side EventFilter uses — shouldPersistEvent() persists ANY kind unconditionally for the
    // signed-in user's own pubkey, so this table can hold kinds beyond what a normal subscription
    // would ever request; leaving this unscoped would let a NIP-77 sync reconcile (and pull in)
    // every kind the user ever published anywhere, not just what Umbra actually supports.
    @Query("SELECT id, created_at FROM events WHERE pubkey = :pubkey AND kind IN (:kinds)")
    suspend fun getEventIdsAndTimestampsByPubkey(pubkey: String, kinds: Set<Int>): List<EventIdTimestamp>

    // ── SSoT JOIN queries — Room re-emits on ANY change to events, user_profiles or event_tags ──

    /**
     * Observe all notes by a single author (profile screen).
     * Includes engagement counts like [observeFeedNotes].
     */
    @Query("""
        WITH base AS (
            SELECT
                e.id,
                e.pubkey,
                e.created_at AS createdAt,
                e.kind,
                e.content,
                e.sig,
                e.tagsJson
            FROM events e
            WHERE e.pubkey = :pubkey
              AND e.kind = :kind
            ORDER BY e.created_at DESC
            LIMIT :limit
        ),
        engagement AS (
            SELECT
                dedup.targetId AS targetId,
                SUM(CASE WHEN dedup.kind = 7 THEN 1 ELSE 0 END) AS reactionCount,
                SUM(CASE WHEN dedup.kind = 1 THEN 1 ELSE 0 END) AS replyCount,
                SUM(CASE WHEN dedup.kind = 6 THEN 1 ELSE 0 END) AS repostCount
            FROM (
                SELECT DISTINCT
                    et.tag_value AS targetId,
                    eng.id AS engagementEventId,
                    eng.kind AS kind
                FROM event_tags et
                JOIN events eng ON eng.id = et.event_id
                WHERE et.tag_name = 'e'
                  AND eng.kind IN (1, 6, 7)
                  AND et.tag_value IN (SELECT id FROM base)
            ) dedup
            GROUP BY dedup.targetId
        )
        SELECT
            base.id,
            base.pubkey,
            base.createdAt,
            base.kind,
            base.content,
            base.sig,
            base.tagsJson,
            p.name AS authorName,
            p.displayName AS authorDisplayName,
            p.picture AS authorPicture,
            p.about AS authorAbout,
            p.nip05 AS authorNip05,
            p.nip05VerificationState AS authorNip05VerificationState,
            COALESCE(engagement.reactionCount, 0) AS reactionCount,
            COALESCE(engagement.replyCount, 0) AS replyCount,
            COALESCE(engagement.repostCount, 0) AS repostCount
        FROM base
        LEFT JOIN user_profiles p ON p.pubkey = base.pubkey
        LEFT JOIN engagement ON engagement.targetId = base.id
        ORDER BY base.createdAt DESC
    """)
    fun observeProfileNotes(pubkey: String, kind: Int, limit: Int): Flow<List<NoteWithProfile>>
}
