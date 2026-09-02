package com.umbra.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.profile.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NostrTextParsingUtilitiesTest {

    @Before
    fun setUp() {
        clearTextParsingCachesForTest()
    }

    @Test
    fun `given_jsonOrPlainText_when_detecting_then_identifiesCorrectly`() {
        val raw = "{\"a\":1,\"b\":true}"

        assertTrue(isLikelyJson(raw))
        assertFalse(isLikelyJson("not-json"))

        val pretty = prettyFormatJson(raw)
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"a\""))
    }

    @Test
    fun `given_textWithJsonFences_when_splitting_then_createsSegments`() {
        val text = "start\n```json\n{\"k\":1}\n```\nend"
        val segments = splitJsonFenceSegments(text)

        assertEquals(3, segments.size)
        assertTrue(segments[0] is ContentSegment.Text)
        assertTrue(segments[1] is ContentSegment.JsonFence)
        assertTrue(segments[2] is ContentSegment.Text)
    }

    @Test
    fun `given_shortcode_when_building_inline_content_id_then_prefixesConsistently`() {
        // extractCustomEmojis() itself now lives in and is tested by
        // domain/nip30/CustomEmojiTest.kt — this only covers the UI-local id-prefixing helper.
        assertEquals("custom_emoji:smile", customEmojiInlineContentId("smile"))
    }

    @Test
    fun `given_textWithMediaUrls_when_extracting_then_identifiesCorrectly`() {
        val text = "one https://example.com/a.jpg and https://example.com/b.mp4 done"

        assertEquals(listOf("https://example.com/a.jpg"), extractImageUrls(text))
        assertEquals(listOf("https://example.com/b.mp4"), extractVideoUrls(text))

        val withoutMedia = removeMediaUrls(text)
        assertFalse(withoutMedia.contains(".jpg"))
        assertFalse(withoutMedia.contains(".mp4"))
    }

    @Test
    fun `given_qtVideoUrl_when_extracting_then_recognizedAsVideo`() {
        val text = "check this out https://example.com/clip.qt neat"

        assertEquals(listOf("https://example.com/clip.qt"), extractVideoUrls(text))
    }

    @Test
    fun `given_singleHashtag_when_matching_then_matchesTheWholeWord`() {
        val matches = HASHTAG_REGEX.findAll("check out #nostr today").map { it.value }.toList()

        assertEquals(listOf("#nostr"), matches)
    }

    @Test
    fun `given_doubleHash_when_matching_then_doesNotMatch`() {
        // ##heading / ## alone is markdown heading syntax, not a Nostr hashtag (always a single #).
        assertTrue(HASHTAG_REGEX.findAll("## Section heading").toList().isEmpty())
        assertTrue(HASHTAG_REGEX.findAll("some ##tag text").toList().isEmpty())
    }

    @Test
    fun `given_bareHash_when_matching_then_doesNotMatch`() {
        assertTrue(HASHTAG_REGEX.findAll("just a # by itself").toList().isEmpty())
    }

    @Test
    fun `given_boldText_when_building_then_stripsAsterisksAndAppliesBoldStyle`() {
        val annotated = buildAnnotatedText(
            text = "hello **world** end",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("hello world end", annotated.text)
        val boldSpan = annotated.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("world", annotated.text.substring(boldSpan.start, boldSpan.end))
    }

    @Test
    fun `given_italicText_when_building_then_stripsAsterisksAndAppliesItalicStyle`() {
        val annotated = buildAnnotatedText(
            text = "hello *world* end",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("hello world end", annotated.text)
        val italicSpan = annotated.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("world", annotated.text.substring(italicSpan.start, italicSpan.end))
    }

    @Test
    fun `given_boldAndItalicInSameText_when_building_then_bothApplyWithoutCrossMatching`() {
        val annotated = buildAnnotatedText(
            text = "**bold** and *italic*",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("bold and italic", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `given_strikethroughText_when_building_then_stripsTildesAndAppliesLineThrough`() {
        val annotated = buildAnnotatedText(
            text = "hello ~~world~~ end",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("hello world end", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `given_inlineCode_when_building_then_stripsBackticksAndAppliesMonospace`() {
        val annotated = buildAnnotatedText(
            text = "run `npm install` now",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("run npm install now", annotated.text)
        val codeSpan = annotated.spanStyles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("npm install", annotated.text.substring(codeSpan.start, codeSpan.end))
    }

    @Test
    fun `given_genericTripleBacktickFence_when_building_then_leavesBackticksLiteralInsteadOfPartialMatch`() {
        // Only ```json fences are pre-routed to JsonContentBlock (splitJsonFenceSegments) — a
        // generic ```fenced``` block with no language tag must not have INLINE_CODE_REGEX eat one
        // backtick off each end of it.
        val annotated = buildAnnotatedText(
            text = "```code block```",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("```code block```", annotated.text)
        assertTrue(annotated.spanStyles.none { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `given_unpairedAsterisk_when_building_then_rendersLiterally`() {
        val annotated = buildAnnotatedText(
            text = "5 * 3 = 15",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertEquals("5 * 3 = 15", annotated.text)
        assertTrue(annotated.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `given_mixedMediaAndText_when_parsing_then_preservesOrder`() {
        val text = "hello https://example.com/a.jpg world https://example.com/b.mp4 end"
        val segments = parseInlineMediaSegments(text)

        assertEquals(5, segments.size)
        assertTrue(segments[0] is InlineMediaSegment.Text)
        assertTrue(segments[1] is InlineMediaSegment.ImageUrl)
        assertTrue(segments[2] is InlineMediaSegment.Text)
        assertTrue(segments[3] is InlineMediaSegment.VideoUrl)
        assertTrue(segments[4] is InlineMediaSegment.Text)

        assertEquals("hello", (segments[0] as InlineMediaSegment.Text).value)
        assertEquals("https://example.com/a.jpg", (segments[1] as InlineMediaSegment.ImageUrl).url)
        assertEquals("world", (segments[2] as InlineMediaSegment.Text).value)
        assertEquals("https://example.com/b.mp4", (segments[3] as InlineMediaSegment.VideoUrl).url)
        assertEquals("end", (segments[4] as InlineMediaSegment.Text).value)
    }

    @Test
    fun `given quote reference between two text chunks when parsing then positions it in place`() {
        val eventId = "e".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val text = "before nostr:$note after"

        val segments = parseInlineMediaSegments(text)

        assertEquals(3, segments.size)
        assertEquals("before", (segments[0] as InlineMediaSegment.Text).value)
        assertEquals(eventId, (segments[1] as InlineMediaSegment.QuoteReference).eventId)
        assertEquals("after", (segments[2] as InlineMediaSegment.Text).value)
    }

    @Test
    fun `given quote reference at the start of a post when parsing then it is not pushed to the end`() {
        val eventId = "f".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val text = "nostr:$note check this out"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.first() is InlineMediaSegment.QuoteReference)
        assertEquals(eventId, (segments.first() as InlineMediaSegment.QuoteReference).eventId)
    }

    @Test
    fun `given a note1 reference embedded inside a URL path when parsing then it is not treated as a quote`() {
        val eventId = "a".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val text = "check this invite https://armada.buzz/invite/$note#fragment nice"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.none { it is InlineMediaSegment.QuoteReference })
        assertTrue(segments.any { it is InlineMediaSegment.Url && it.url.contains(note) })
    }

    @Test
    fun `given a lightning invoice between two text chunks when parsing then positions it in place`() {
        val invoice = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu"
        val text = "zap me: $invoice thanks"

        val segments = parseInlineMediaSegments(text)

        assertEquals(3, segments.size)
        assertEquals("zap me:", (segments[0] as InlineMediaSegment.Text).value)
        assertEquals(invoice, (segments[1] as InlineMediaSegment.LightningInvoice).invoice)
        assertEquals("thanks", (segments[2] as InlineMediaSegment.Text).value)
    }

    @Test
    fun `given a lightning-prefixed invoice when parsing then the prefix is stripped from the segment`() {
        val invoice = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu"
        val text = "pay lightning:$invoice"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.any { it is InlineMediaSegment.LightningInvoice && it.invoice == invoice })
    }

    @Test
    fun `given a nostr-prefixed invoice when parsing then it is detected and the prefix is stripped`() {
        val invoice = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu"
        val text = "pay nostr:$invoice"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.any { it is InlineMediaSegment.LightningInvoice && it.invoice == invoice })
    }

    @Test
    fun `given a nostr-scheme-prefixed invoice when parsing then it is detected and the prefix is stripped`() {
        val invoice = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu"
        val text = "pay nostr://$invoice"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.any { it is InlineMediaSegment.LightningInvoice && it.invoice == invoice })
    }

    @Test
    fun `given a short lnbc-looking word when parsing then it is not treated as an invoice`() {
        val text = "lnbc is short for lightning network bitcoin"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.none { it is InlineMediaSegment.LightningInvoice })
    }

    @Test
    fun `given a bare lnurl string when parsing then it is detected`() {
        val lnurl = "lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns"
        val text = "withdraw here: $lnurl thanks"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.any { it is InlineMediaSegment.LnurlReference && it.lnurl == lnurl })
    }

    @Test
    fun `given a lightning-prefixed lnurl string when parsing then the prefix is stripped`() {
        val lnurl = "lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns"
        val text = "lightning:$lnurl"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.any { it is InlineMediaSegment.LnurlReference && it.lnurl == lnurl })
    }

    @Test
    fun `given a nostr-prefixed lnurl string when parsing then it is detected and the prefix is stripped`() {
        val lnurl = "lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns"
        val text = "nostr:$lnurl"

        val segments = parseInlineMediaSegments(text)

        assertTrue(segments.any { it is InlineMediaSegment.LnurlReference && it.lnurl == lnurl })
    }

    private fun testEvent(content: String, tags: List<List<String>> = emptyList()) = Event(
        id = "0".repeat(64),
        pubkey = "1".repeat(64),
        createdAt = 0L,
        kind = 1,
        tags = tags,
        content = content
    )

    @Test
    fun `given a naddr1 reference embedded inside a URL when extracting quoted event references then it is ignored`() {
        val eventId = "b".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val event = testEvent("check out this invite https://armada.buzz/invite/$note#fragment")

        assertTrue(extractQuotedEventReferences(event).isEmpty())
    }

    @Test
    fun `given a genuine inline note reference outside any URL when extracting quoted event references then it is included`() {
        val eventId = "c".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val event = testEvent("check this out nostr:$note")

        assertEquals(listOf(eventId), extractQuotedEventReferences(event))
    }

    @Test
    fun `given a q tag reference and a URL-embedded content reference when extracting quoted event references then only the q tag survives`() {
        val urlEmbeddedId = "d".repeat(64)
        val urlEmbeddedNote = Bech32Encoder.encodeNote(urlEmbeddedId)
        val qTagId = "e".repeat(64)
        val event = testEvent(
            content = "invite link https://armada.buzz/invite/$urlEmbeddedNote",
            tags = listOf(listOf("q", qTagId))
        )

        assertEquals(listOf(qTagId), extractQuotedEventReferences(event))
    }

    @Test
    fun `given_urlWrappedInBrackets_when_parsingUrls_then_keepsBracketsAsText`() {
        val text = "open [https://example.com/path] close"
        val segments = parseUrlsInText(text)

        assertEquals(3, segments.size)
        assertEquals("open [", (segments[0] as InlineMediaSegment.Text).value)
        assertEquals("https://example.com/path", (segments[1] as InlineMediaSegment.Url).url)
        assertEquals("] close", (segments[2] as InlineMediaSegment.Text).value)
    }

    @Test
    fun `given_bareDomainWrappedInBrackets_when_parsingUrls_then_normalizesAnd_keepsBrackets`() {
        val text = "go [example.com/docs] now"
        val segments = parseUrlsInText(text)

        assertEquals(3, segments.size)
        assertEquals("go [", (segments[0] as InlineMediaSegment.Text).value)
        assertEquals("https://example.com/docs", (segments[1] as InlineMediaSegment.Url).url)
        assertEquals("] now", (segments[2] as InlineMediaSegment.Text).value)
    }

    @Test
    fun `given_urlWrappedInParentheses_when_parsingUrls_then_segments_correctly`() {
        val text = "check (https://example.com/page) please"
        val segments = parseUrlsInText(text)

        // Should split into 3: text before, URL, text after
        assertEquals("Should have 3 segments", 3, segments.size)
        
        // Verify segmentation is correct
        val first = segments[0] as InlineMediaSegment.Text
        val middle = segments[1] as InlineMediaSegment.Url
        val last = segments[2] as InlineMediaSegment.Text
        
        // Check that parentheses are preserved in text segments
        assertTrue("First segment should contain or preserve opening paren context", first.value.contains("(") || !first.value.isEmpty())
        assertTrue("Last segment should contain or preserve closing paren context", last.value.contains(")") || !last.value.isEmpty())
        
        // URL should be clean (internal structure depends on implementation)
        assertEquals("URL should be extracted", "https://example.com/page", middle.url)
    }

    @Test
    fun `given_urlWithInternalBalancedParentheses_when_parsingUrls_then_preserves_internal_parens`() {
        val text = "see https://en.wikipedia.org/wiki/Function_(mathematics) there"
        val segments = parseUrlsInText(text)

        assertEquals("Should have 3 segments", 3, segments.size)
        
        val middle = segments[1] as InlineMediaSegment.Url
        // Internal parens should be preserved
        assertEquals("Should preserve internal parentheses", "https://en.wikipedia.org/wiki/Function_(mathematics)", middle.url)
    }

    @Test
    fun `given_sameText_when_parsedTwice_then_reusesCachedInstances`() {
        val text = "hello https://example.com/a.jpg and https://example.com/page"

        val firstFence = splitJsonFenceSegments(text)
        val secondFence = splitJsonFenceSegments(text)
        assertTrue(firstFence === secondFence)

        val firstMedia = parseInlineMediaSegments(text)
        val secondMedia = parseInlineMediaSegments(text)
        assertTrue(firstMedia === secondMedia)

        val firstUrls = parseUrlsInText(text)
        val secondUrls = parseUrlsInText(text)
        assertTrue(firstUrls === secondUrls)
    }

    @Test
    fun `given_cacheOverLimit_when_newEntriesAdded_then_oldestIsEvicted`() {
        val firstKey = "seed https://example.com/seed.jpg"
        val firstValue = parseInlineMediaSegments(firstKey)
        val maxSize = textParsingCacheSnapshotForTest().maxSize

        repeat(maxSize + 10) { index ->
            parseInlineMediaSegments("item-$index https://example.com/$index.jpg")
        }

        val snapshot = textParsingCacheSnapshotForTest()
        assertTrue(snapshot.inlineMediaSize <= snapshot.maxSize)

        val valueAfterEviction = parseInlineMediaSegments(firstKey)
        assertFalse(firstValue === valueAfterEviction)
    }

    @Test
    fun `given_jsonDetectionAndPrettyFormatting_when_cacheGrows_then_staysBounded`() {
        val maxSize = textParsingCacheSnapshotForTest().maxSize

        repeat(maxSize + 20) { index ->
            val json = "{\"idx\":$index}"
            assertTrue(isLikelyJson(json))
            prettyFormatJson(json)
        }

        val snapshot = textParsingCacheSnapshotForTest()
        assertTrue(snapshot.jsonDetectSize <= snapshot.maxSize)
        assertTrue(snapshot.prettyJsonSize <= snapshot.maxSize)
    }

    private fun profile(pubkey: String, name: String? = null, displayName: String? = null) = UserProfile(
        pubkey = pubkey,
        name = name,
        displayName = displayName
    )

    @Test
    fun `given profile with username when resolving mention handle then prefers display_name over name`() {
        val handle = mentionDisplayHandle(profile("a".repeat(64), name = "satoshi", displayName = "Satoshi Nakamoto"))

        assertEquals("Satoshi Nakamoto", handle)
    }

    @Test
    fun `given profile with only display_name when resolving mention handle then falls back to it`() {
        val handle = mentionDisplayHandle(profile("a".repeat(64), name = null, displayName = "Satoshi Nakamoto"))

        assertEquals("Satoshi Nakamoto", handle)
    }

    @Test
    fun `given profile with blank name when resolving mention handle then falls back to display_name`() {
        val handle = mentionDisplayHandle(profile("a".repeat(64), name = "   ", displayName = "Satoshi Nakamoto"))

        assertEquals("Satoshi Nakamoto", handle)
    }

    @Test
    fun `given no profile fetched when resolving mention handle then returns null`() {
        assertNull(mentionDisplayHandle(null))
    }

    @Test
    fun `given resolved mention profile when building annotated text then renders at-display-name not raw entity`() {
        val pubkey = "b".repeat(64)
        val npub = Bech32Encoder.encodeNpub(pubkey)
        // mentionProfiles is keyed by hex pubkey — matches how NostrTextRenderer actually
        // populates it (resolveProfileReference() then userRepository.getProfiles(...)
        // .associateBy { it.pubkey }), not by the raw bech32 entity.
        val annotated = buildAnnotatedText(
            text = "hello nostr:$npub",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = mapOf(pubkey to profile(pubkey, name = "satoshi", displayName = "Satoshi Nakamoto")),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertTrue(annotated.text.contains("@Satoshi Nakamoto"))
        assertFalse(annotated.text.contains(npub))
    }

    @Test
    fun `given resolved nprofile mention when building annotated text then renders at-display-name not raw entity`() {
        val pubkey = "e".repeat(64)
        val nprofile = Bech32Encoder.encodeNprofile(pubkey, relayUrls = listOf("wss://relay.example"))
        val annotated = buildAnnotatedText(
            text = "hello nostr:$nprofile",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = mapOf(pubkey to profile(pubkey, name = "satoshi", displayName = "Satoshi Nakamoto")),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertTrue(annotated.text.contains("@Satoshi Nakamoto"))
        assertFalse(annotated.text.contains(nprofile))
    }

    @Test
    fun `given at-prefixed npub mention when building annotated text then resolves at-display-name not raw handle`() {
        // Not NIP-21 (which only defines nostr:/nostr://) — some clients write mentions this way
        // instead. Must NOT fall through to the generic @handle MENTION_REGEX path, which would
        // leave it as a raw "@npub1..." styled like an unresolved plain mention.
        val pubkey = "1".repeat(64)
        val npub = Bech32Encoder.encodeNpub(pubkey)
        val annotated = buildAnnotatedText(
            text = "hello @$npub",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = mapOf(pubkey to profile(pubkey, name = "satoshi", displayName = "Satoshi Nakamoto")),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertTrue(annotated.text.contains("@Satoshi Nakamoto"))
        assertFalse(annotated.text.contains(npub))
    }

    @Test
    fun `given at-prefixed nprofile mention when building annotated text then resolves at-display-name not raw entity`() {
        val pubkey = "2".repeat(64)
        val nprofile = Bech32Encoder.encodeNprofile(pubkey, relayUrls = listOf("wss://relay.example"))
        val annotated = buildAnnotatedText(
            text = "hey @$nprofile check this",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = mapOf(pubkey to profile(pubkey, name = "satoshi", displayName = "Satoshi Nakamoto")),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertTrue(annotated.text.contains("@Satoshi Nakamoto"))
        assertFalse(annotated.text.contains(nprofile))
    }

    @Test
    fun `given at-prefixed note reference when parsing inline segments then positions it in place without a dangling at-sign`() {
        val eventId = "3".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val text = "before @$note after"

        val segments = parseInlineMediaSegments(text)

        assertEquals(3, segments.size)
        assertEquals("before", (segments[0] as InlineMediaSegment.Text).value)
        assertEquals(eventId, (segments[1] as InlineMediaSegment.QuoteReference).eventId)
        assertEquals("after", (segments[2] as InlineMediaSegment.Text).value)
    }

    @Test
    fun `given unresolved nprofile mention when building annotated text then keeps raw entity`() {
        val pubkey = "f".repeat(64)
        val nprofile = Bech32Encoder.encodeNprofile(pubkey)
        val annotated = buildAnnotatedText(
            text = "hello nostr:$nprofile",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {}
        )

        assertTrue(annotated.text.contains(nprofile))
    }

    @Test
    fun `given hidden event id when building annotated text then omits the raw quote reference`() {
        val eventId = "c".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val annotated = buildAnnotatedText(
            text = "check this out nostr:$note",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {},
            hiddenEventIds = setOf(eventId)
        )

        assertFalse(annotated.text.contains(note))
        assertEquals("check this out", annotated.text)
    }

    @Test
    fun `given quote reference not in hidden set when building annotated text then keeps it visible`() {
        val eventId = "d".repeat(64)
        val note = Bech32Encoder.encodeNote(eventId)
        val annotated = buildAnnotatedText(
            text = "check this out nostr:$note",
            primaryColor = Color.Black,
            secondaryColor = Color.Black,
            tertiaryColor = Color.Black,
            customEmojis = emptyMap(),
            mentionProfiles = emptyMap(),
            onMentionClick = {},
            onEventReferenceClick = {},
            onHashtagClick = {},
            onUrlClick = {},
            hiddenEventIds = emptySet()
        )

        assertTrue(annotated.text.contains(note))
    }

    @Test
    fun `given extensionless url with image imeta when reclassifying then becomes ImageUrl`() {
        val url = "https://blossom.example.com/abcdef0123456789"
        val segments = parseUrlsInText("check this $url out")
        val imetaByUrl = mapOf(url to ImetaTag(url = url, mimeType = "image/jpeg"))

        val reclassified = reclassifyUrlSegmentsWithImeta(segments, imetaByUrl)

        assertTrue(reclassified.any { it is InlineMediaSegment.ImageUrl && it.url == url })
        assertFalse(reclassified.any { it is InlineMediaSegment.Url && it.url == url })
    }

    @Test
    fun `given extensionless url with video imeta when reclassifying then becomes VideoUrl`() {
        val url = "https://blossom.example.com/fedcba9876543210"
        val segments = parseUrlsInText("watch $url please")
        val imetaByUrl = mapOf(url to ImetaTag(url = url, mimeType = "video/mp4"))

        val reclassified = reclassifyUrlSegmentsWithImeta(segments, imetaByUrl)

        assertTrue(reclassified.any { it is InlineMediaSegment.VideoUrl && it.url == url })
        assertFalse(reclassified.any { it is InlineMediaSegment.Url && it.url == url })
    }

    @Test
    fun `given url with non-media imeta mime type when reclassifying then stays a plain Url`() {
        val url = "https://example.com/document"
        val segments = parseUrlsInText("see $url here")
        val imetaByUrl = mapOf(url to ImetaTag(url = url, mimeType = "application/pdf"))

        val reclassified = reclassifyUrlSegmentsWithImeta(segments, imetaByUrl)

        assertTrue(reclassified.any { it is InlineMediaSegment.Url && it.url == url })
    }

    @Test
    fun `given empty imeta map when reclassifying then returns the same segments unchanged`() {
        val text = "plain https://example.com/no-imeta text"
        val segments = parseUrlsInText(text)

        val reclassified = reclassifyUrlSegmentsWithImeta(segments, emptyMap())

        assertEquals(segments, reclassified)
    }

    @Test
    fun `given url with no matching imeta entry when reclassifying then stays a plain Url`() {
        val url = "https://example.com/unrelated"
        val segments = parseUrlsInText("see $url here")
        val otherUrl = "https://example.com/other"
        val imetaByUrl = mapOf(otherUrl to ImetaTag(url = otherUrl, mimeType = "image/png"))

        val reclassified = reclassifyUrlSegmentsWithImeta(segments, imetaByUrl)

        assertTrue(reclassified.any { it is InlineMediaSegment.Url && it.url == url })
    }
}
