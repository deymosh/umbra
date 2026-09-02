package com.umbra.app.domain.model

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.isTimestampFromFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventModelBehaviorTest {

    private fun eventWithTags(tags: List<List<String>>, kind: Int = Event.KIND_TEXT_NOTE, content: String = "hello"): Event {
        return Event(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1L,
            kind = kind,
            tags = tags,
            content = content,
            sig = "c".repeat(128)
        )
    }

    private fun eventWithCreatedAt(createdAt: Long): Event {
        return Event(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = createdAt,
            kind = Event.KIND_TEXT_NOTE,
            tags = emptyList(),
            content = "hello",
            sig = "c".repeat(128)
        )
    }

    @Test
    fun `given_eventWithExplicitAndLegacyMarkers_when_detecting_then_correctlyIdentifies`() {
        val explicit = eventWithTags(listOf(listOf("e", "id1", "", "reply")))
        val legacy = eventWithTags(listOf(listOf("e", "id2")))
        val noReply = eventWithTags(listOf(listOf("p", "pub")))

        assertTrue(explicit.isReply())
        assertTrue(legacy.isReply())
        assertFalse(noReply.isReply())
    }

    @Test
    fun `given_eventWithExplicitMarkers_when_resolving_then_prioritizesExplicitTags`() {
        val event = eventWithTags(
            listOf(
                listOf("e", "mention-id", "", "mention"),
                listOf("e", "root-id", "", "root"),
                listOf("e", "reply-id", "", "reply")
            )
        )

        assertEquals("root-id", event.getRootEventId())
        assertEquals("reply-id", event.getParentEventId())
    }

    @Test
    fun `given_eventWithMentionAndFallbackTags_when_resolving_then_skipsMentions`() {
        val event = eventWithTags(
            listOf(
                listOf("e", "mention-id", "", "mention"),
                listOf("e", "root-fallback"),
                listOf("e", "parent-fallback")
            )
        )

        assertEquals("root-fallback", event.getRootEventId())
        assertEquals("parent-fallback", event.getParentEventId())
    }

    @Test
    fun `given_eventWithMixedCaseAndPrefixTags_when_matching_then_caseInsensitiveAndPrefixAware`() {
        val event = eventWithTags(
            listOf(
                listOf("t", "UmbraTag"),
                listOf("P", "ABC123"),
                listOf("github-action", "value")
            )
        )

        assertTrue(event.hasAnyHashtag(setOf("umbra")))
        assertTrue(event.hasAnyTagValue(setOf("abc123")))
        assertTrue(event.hasAnyTagNamePrefix(setOf("git")))
        assertFalse(event.hasAnyTagValue(setOf("missing")))
    }

    @Test
    fun `given_eventWithSlashAndPrefixedTags_when_detecting_then_findsNonStandard`() {
        val slashTag = eventWithTags(listOf(listOf("proto/name", "x")))
        val prefixedTag = eventWithTags(listOf(listOf("gnostr-org", "x")))
        val standardTag = eventWithTags(listOf(listOf("p", "x"), listOf("e", "y")))

        assertTrue(slashTag.hasNonStandardTagNames())
        assertTrue(prefixedTag.hasNonStandardTagNames())
        assertFalse(standardTag.hasNonStandardTagNames())
    }

    @Test
    fun `given_eventWithVaryingKindsContentAndTags_when_filtering_then_appliesToCorrectEvents`() {
        val useful = eventWithTags(tags = emptyList(), content = "normal content")
        val excludedByHashtag = eventWithTags(tags = listOf(listOf("t", "xitchat")))
        val excludedByContent = eventWithTags(tags = emptyList(), content = "nlogpost: proxy")
        val excludedByTagName = eventWithTags(tags = listOf(listOf("matrix-bridge", "x")))
        val reply = eventWithTags(tags = listOf(listOf("e", "id")))
        val wrongKind = eventWithTags(tags = emptyList(), kind = Event.KIND_REACTION)

        assertTrue(useful.isUsefulClientNote())
        assertTrue(useful.isTopLevelFeedNote())

        assertFalse(excludedByHashtag.isUsefulClientNote())
        assertFalse(excludedByContent.isUsefulClientNote())
        assertFalse(excludedByTagName.isUsefulClientNote())
        assertFalse(reply.isTopLevelFeedNote())
        assertFalse(wrongKind.isUsefulClientNote())
    }

    @Test
    fun `given_notesMatchingDefaultExcludes_when_overridingWithEmptySets_then_defaultsNoLongerApply`() {
        val excludedByHashtag = eventWithTags(tags = listOf(listOf("t", "xitchat")))
        val excludedByContent = eventWithTags(tags = emptyList(), content = "nlogpost: proxy")
        val excludedByTagName = eventWithTags(tags = listOf(listOf("matrix-bridge", "x")))

        // The hardcoded FilterDefaults baseline is only the *default* argument value — a caller
        // with the user's own (fully editable) filter settings, like FeedViewModel/
        // EventRepositoryImpl now do, can pass empty sets to make these notes visible again.
        assertTrue(
            excludedByHashtag.isUsefulClientNote(
                excludedHashtags = emptySet(),
                excludedTagNamePrefixes = emptySet(),
                excludedContentPrefixes = emptySet()
            )
        )
        assertTrue(
            excludedByContent.isUsefulClientNote(
                excludedHashtags = emptySet(),
                excludedTagNamePrefixes = emptySet(),
                excludedContentPrefixes = emptySet()
            )
        )
        assertTrue(
            excludedByTagName.isUsefulClientNote(
                excludedHashtags = emptySet(),
                excludedTagNamePrefixes = emptySet(),
                excludedContentPrefixes = emptySet()
            )
        )
    }

    @Test
    fun `given_slashInTagName_when_overridingExcludesWithEmptySet_then_stillExcludedAsStructurallyInvalid`() {
        // Unlike the hardcoded prefix list, a "/" in a tag name is always structurally
        // non-standard NIP-01 — never user-overridable, regardless of excludedTagNamePrefixes.
        val slashTagName = eventWithTags(tags = listOf(listOf("gnostr-org/gnostr", "x")))

        assertTrue(slashTagName.hasNonStandardTagNames(excludedTagNamePrefixes = emptySet()))
        assertFalse(
            slashTagName.isUsefulClientNote(
                excludedHashtags = emptySet(),
                excludedTagNamePrefixes = emptySet(),
                excludedContentPrefixes = emptySet()
            )
        )
    }

    @Test
    fun `given_customExcludedContentPrefix_when_checkingUsefulClientNote_then_excludesMatchingContent`() {
        val matches = eventWithTags(tags = emptyList(), content = "customprefix: hello")
        val noMatch = eventWithTags(tags = emptyList(), content = "hello world")

        assertFalse(matches.isUsefulClientNote(excludedContentPrefixes = setOf("customprefix:")))
        assertTrue(noMatch.isUsefulClientNote(excludedContentPrefixes = setOf("customprefix:")))
    }

    @Test
    fun `given_eventsAtVariousOffsetsFromNow_when_checkingFuture_then_onlyFlagsBeyondTolerance`() {
        val nowSecs = System.currentTimeMillis() / 1000L
        val tolerance = 120L

        val now = eventWithCreatedAt(nowSecs)
        val justPast = eventWithCreatedAt(nowSecs - 60L)
        val justInsideTolerance = eventWithCreatedAt(nowSecs + tolerance - 1L)
        val justBeyondTolerance = eventWithCreatedAt(nowSecs + tolerance + 5L)
        val farFuture = eventWithCreatedAt(nowSecs + 365L * 24 * 60 * 60L)

        assertFalse(now.isFromFuture(tolerance))
        assertFalse(justPast.isFromFuture(tolerance))
        assertFalse(justInsideTolerance.isFromFuture(tolerance))
        assertTrue(justBeyondTolerance.isFromFuture(tolerance))
        assertTrue(farFuture.isFromFuture(tolerance))
    }

    @Test
    fun `given_bareTimestampsAtVariousOffsetsFromNow_when_checkingFuture_then_onlyFlagsBeyondTolerance`() {
        val nowSecs = System.currentTimeMillis() / 1000L
        val tolerance = 120L

        assertFalse(isTimestampFromFuture(nowSecs, tolerance))
        assertFalse(isTimestampFromFuture(nowSecs - 60L, tolerance))
        assertFalse(isTimestampFromFuture(nowSecs + tolerance - 1L, tolerance))
        assertTrue(isTimestampFromFuture(nowSecs + tolerance + 5L, tolerance))
        assertTrue(isTimestampFromFuture(nowSecs + 365L * 24 * 60 * 60L, tolerance))
    }

    @Test
    fun `given_eventWithMultipleTags_when_querying_then_returnsExpectedValues`() {
        val event = eventWithTags(
            listOf(
                listOf("e", "event-1"),
                listOf("p", "pub-1"),
                listOf("p", "pub-2")
            )
        )

        assertEquals("event-1", event.getTagValue("e"))
        assertEquals(listOf("pub-1", "pub-2"), event.getTagValues("p"))
        assertEquals(listOf("pub-1", "pub-2"), event.getMentionedPubkeys())
        assertNull(event.getTagValue("missing"))
    }
}
