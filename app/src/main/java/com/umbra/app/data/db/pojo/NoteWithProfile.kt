package com.umbra.app.data.db.pojo

import androidx.room.ColumnInfo

/**
 * Room POJO populated by [com.umbra.app.data.db.dao.EventDao.observeProfileNotes]
 * and related queries.
 *
 * The SQL query uses a LEFT JOIN on [user_profiles] (author profile, nullable when
 * not yet fetched) and LEFT JOINs through [event_tags] → [events] to aggregate
 * engagement counts in a single pass — no round-trips, no in-memory filtering.
 *
 * Column aliases in the @Query SQL must match the [ColumnInfo] names below exactly.
 * All event columns use their actual SQLite column names (or aliases where needed);
 * all author/count columns use distinct aliases to avoid ambiguity.
 */
data class NoteWithProfile(

    // ── Event (events table) ─────────────────────────────────────────────────

    val id: String,
    val pubkey: String,
    /** Aliased from [created_at] to avoid camelCase/snake_case mismatch. */
    @ColumnInfo(name = "createdAt")          val createdAt: Long,
    val kind: Int,
    val content: String,
    val sig: String,
    val tagsJson: String,

    // ── Author profile (user_profiles LEFT JOIN) — null when not yet cached ──

    @ColumnInfo(name = "authorName")         val authorName: String?,
    @ColumnInfo(name = "authorDisplayName")  val authorDisplayName: String?,
    @ColumnInfo(name = "authorPicture")      val authorPicture: String?,
    @ColumnInfo(name = "authorAbout")        val authorAbout: String?,
    @ColumnInfo(name = "authorNip05")        val authorNip05: String?,
    @ColumnInfo(name = "authorNip05VerificationState") val authorNip05VerificationState: String?,

    // ── Engagement counts (aggregated via event_tags → events JOIN) ───────────

    @ColumnInfo(name = "reactionCount")      val reactionCount: Int,
    @ColumnInfo(name = "replyCount")         val replyCount: Int,
    @ColumnInfo(name = "repostCount")        val repostCount: Int
)
