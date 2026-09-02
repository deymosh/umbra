package com.umbra.app.data.nostr

import android.content.Context
import com.umbra.app.data.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The narrow slice of [BackfillAnchorStore] [EventRepositoryImpl] needs — mirrors this codebase's
 * existing narrow-interface precedent (`NegentropyEventSource`, `OwnEventArchive`) so a plain JVM
 * unit test can supply a small fake instead of a real [BackfillAnchorStore], whose constructor
 * eagerly builds a `SecurePreferences` instance backed by the Android Keystore and a real
 * [android.content.Context] — neither obtainable in `testDebugUnitTest` (no Robolectric).
 * [EventRepositoryImpl.clearBackfillAnchors] is this class's only call site, and it calls nothing
 * but [clear] — see [com.umbra.app.data.repository.EventRepositoryIngestionIntegrationTest]'s
 * harness comment for where this seam was found necessary.
 */
interface BackfillAnchorClearer {
    fun clear(pubkey: String)
}

/**
 * Durable watermark of how far back [NostrSessionManager]'s user-history backfill has already
 * walked, per signed-in pubkey and category. Only timestamps are stored here — never event
 * content — so this doesn't conflict with AUDIT.md's "everyone else's content lives only in the
 * in-memory EventLruCache" rule: it's the same kind of small scalar bookkeeping UserPreferences
 * already keeps for the session, just scoped to backfill progress.
 *
 * Without this, the inbox anchor (which reads from that in-memory-only cache, since that content
 * is never persisted) had nothing durable to fall back on. Every cold start reset it to "now",
 * silently re-walking the same recent window forever and never reaching older history a previous
 * session had already gotten past — which is what made some interactions look permanently
 * missing rather than just slow to arrive.
 */
@Singleton
class BackfillAnchorStore @Inject constructor(
    @ApplicationContext context: Context
) : BackfillAnchorClearer {
    companion object {
        const val CATEGORY_OUTBOX = "outbox"
        // Inbox notes and reactions/reposts share one merged subscription/backfill lane (see
        // NostrChannels.INBOX_NOTES), so they share one anchor too.
        const val CATEGORY_INBOX = "inbox"
        private val CATEGORIES = listOf(CATEGORY_OUTBOX, CATEGORY_INBOX)
    }

    private val prefs = SecurePreferences(context, "backfill_anchors")

    fun get(pubkey: String, category: String): Long? =
        prefs.getString(key(pubkey, category))?.toLongOrNull()

    fun set(pubkey: String, category: String, timestampSeconds: Long) {
        prefs.putString(key(pubkey, category), timestampSeconds.toString())
    }

    /** Called on logout/account switch — a new session's backfill starts fresh for a new pubkey. */
    override fun clear(pubkey: String) {
        CATEGORIES.forEach { prefs.remove(key(pubkey, it)) }
    }

    private fun key(pubkey: String, category: String) = "$pubkey:$category"
}
