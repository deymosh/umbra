package com.umbra.app.domain.model

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.nip92.toTag
import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventBuilderTest {

    private val json = JsonUtils.NostrJson

    private fun parseObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun tagArrays(obj: JsonObject): List<JsonArray> = obj.getValue("tags").jsonArray.map { it.jsonArray }

    private fun sampleEvent(
        id: String = "a".repeat(64),
        pubkey: String = "b".repeat(64),
        tags: List<List<String>> = emptyList()
    ): Event {
        return Event(
            id = id,
            pubkey = pubkey,
            createdAt = 42L,
            kind = Event.KIND_TEXT_NOTE,
            tags = tags,
            content = "text",
            sig = "c".repeat(128)
        )
    }

    @Test
    fun `given target event when building reaction then returns kind7 with e and p tags`() {
        val target = sampleEvent(id = "1".repeat(64), pubkey = "2".repeat(64))
        val obj = parseObject(NostrEventBuilder.reaction(target))

        assertEquals(Event.KIND_REACTION, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("+", obj.getValue("content").jsonPrimitive.content)

        val tags = tagArrays(obj)
        assertEquals(listOf("e", target.id), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("p", target.pubkey), tags[1].map { it.jsonPrimitive.content })
    }

    @Test
    fun `given a unicode emoji when building reaction then content is that emoji and no emoji tag is added`() {
        val target = sampleEvent(id = "1".repeat(64), pubkey = "2".repeat(64))
        val obj = parseObject(NostrEventBuilder.reaction(target, content = "🔥"))

        assertEquals("🔥", obj.getValue("content").jsonPrimitive.content)
        assertEquals(2, tagArrays(obj).size)
    }

    @Test
    fun `given a custom emoji when building reaction then content is the shortcode and an emoji tag is added`() {
        val target = sampleEvent(id = "1".repeat(64), pubkey = "2".repeat(64))
        val emoji = CustomEmoji(shortcode = "umbra", url = "https://example.com/umbra.png")
        val obj = parseObject(NostrEventBuilder.reaction(target, content = ":umbra:", emoji = emoji))

        assertEquals(":umbra:", obj.getValue("content").jsonPrimitive.content)
        val tags = tagArrays(obj)
        assertEquals(3, tags.size)
        assertEquals(
            listOf("emoji", "umbra", "https://example.com/umbra.png"),
            tags[2].map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `given reply target when building text note then includes reply tags`() {
        val replyTo = sampleEvent(id = "3".repeat(64), pubkey = "4".repeat(64))
        val obj = parseObject(NostrEventBuilder.textNote("hello", replyTo))
        val tags = tagArrays(obj)

        assertEquals(Event.KIND_TEXT_NOTE, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("hello", obj.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("e", replyTo.id, "", "reply"), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("p", replyTo.pubkey), tags[1].map { it.jsonPrimitive.content })
    }

    @Test
    fun `given imeta tags when building text note then appends one imeta tag per attachment`() {
        val imeta = ImetaTag(url = "https://nostr.download/abcd.jpg", mimeType = "image/jpeg", sha256 = "abcd")
        val obj = parseObject(NostrEventBuilder.textNote("check this out", imetaTags = listOf(imeta)))
        val tags = tagArrays(obj)

        val imetaTag = tags.first { it.firstOrNull()?.jsonPrimitive?.content == "imeta" }
        assertEquals(imeta.toTag(), imetaTag.map { it.jsonPrimitive.content })
    }

    @Test
    fun `given sensitive reason when building text note then appends content-warning tag`() {
        val obj = parseObject(NostrEventBuilder.textNote("nsfw", sensitiveReason = "graphic"))
        val tags = tagArrays(obj)

        assertEquals(listOf("content-warning", "graphic"), tags.last().map { it.jsonPrimitive.content })
    }

    @Test
    fun `given no sensitive reason when building text note then omits content-warning tag`() {
        val obj = parseObject(NostrEventBuilder.textNote("plain"))
        val tags = tagArrays(obj)

        assertTrue(tags.none { it.firstOrNull()?.jsonPrimitive?.content == "content-warning" })
    }

    @Test
    fun `given imeta and sensitive reason when building reply then appends both after mention tags`() {
        val replyTo = sampleEvent(id = "3".repeat(64), pubkey = "4".repeat(64))
        val imeta = ImetaTag(url = "https://nostr.download/abcd.jpg", mimeType = "image/jpeg")
        val obj = parseObject(
            NostrEventBuilder.reply(
                "hello",
                replyTo,
                imetaTags = listOf(imeta),
                sensitiveReason = ""
            )
        )
        val tags = tagArrays(obj)

        val imetaTag = tags.first { it.firstOrNull()?.jsonPrimitive?.content == "imeta" }
        assertEquals(imeta.toTag(), imetaTag.map { it.jsonPrimitive.content })
        assertEquals(listOf("content-warning"), tags.last().map { it.jsonPrimitive.content })
    }

    @Test
    fun `given direct root reply when building reply then uses single root e tag`() {
        val root = sampleEvent(
            id = "5".repeat(64),
            pubkey = "6".repeat(64),
            tags = listOf(listOf("e", "5".repeat(64), "", "root"))
        )

        val obj = parseObject(NostrEventBuilder.reply("reply", root, "wss://relay.example"))
        val tags = tagArrays(obj)

        val eTags = tags.filter { it.first().jsonPrimitive.content == "e" }
        assertEquals(1, eTags.size)
        assertEquals("root", eTags[0][3].jsonPrimitive.content)
        assertEquals(root.pubkey.lowercase(), eTags[0][4].jsonPrimitive.content)
    }

    @Test
    fun `given nested thread when building reply then adds root and reply e tags`() {
        val rootId = "7".repeat(64)
        val parent = sampleEvent(
            id = "8".repeat(64),
            pubkey = "9".repeat(64),
            tags = listOf(listOf("e", rootId, "", "root"), listOf("p", "a".repeat(64)))
        )

        val obj = parseObject(NostrEventBuilder.reply("nested", parent, "wss://relay.example"))
        val tags = tagArrays(obj)

        val eTags = tags.filter { it.first().jsonPrimitive.content == "e" }
        assertEquals(2, eTags.size)
        assertEquals("root", eTags[0][3].jsonPrimitive.content)
        assertEquals(rootId, eTags[0][1].jsonPrimitive.content)
        assertEquals("reply", eTags[1][3].jsonPrimitive.content)
        assertEquals(parent.id, eTags[1][1].jsonPrimitive.content)
    }

    @Test
    fun `given mixed pubkeys when building contact list then normalizes filters and sorts`() {
        val input = setOf("B".repeat(64), "a".repeat(64), "bad", "A".repeat(64))
        val obj = parseObject(NostrEventBuilder.contactList(input))
        val tags = tagArrays(obj)

        assertEquals(Event.KIND_CONTACT_LIST, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals(2, tags.size)
        assertEquals(listOf("p", "a".repeat(64)), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("p", "b".repeat(64)), tags[1].map { it.jsonPrimitive.content })
    }

    @Test
    fun `given mixed pubkeys when building mute list then normalizes filters and sorts`() {
        val input = setOf("B".repeat(64), "a".repeat(64), "bad", "A".repeat(64))
        val obj = parseObject(NostrEventBuilder.muteList(input))
        val tags = tagArrays(obj)

        assertEquals(Event.KIND_MUTED_USERS, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("", obj.getValue("content").jsonPrimitive.content)
        assertEquals(2, tags.size)
        assertEquals(listOf("p", "a".repeat(64)), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("p", "b".repeat(64)), tags[1].map { it.jsonPrimitive.content })
    }

    @Test
    fun `given mixed event ids when building pin list then normalizes filters and sorts`() {
        val input = setOf("B".repeat(64), "a".repeat(64), "bad", "A".repeat(64))
        val obj = parseObject(NostrEventBuilder.pinList(input))
        val tags = tagArrays(obj)

        assertEquals(Event.KIND_PINNED_EVENTS, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("", obj.getValue("content").jsonPrimitive.content)
        assertEquals(2, tags.size)
        assertEquals(listOf("e", "a".repeat(64)), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("e", "b".repeat(64)), tags[1].map { it.jsonPrimitive.content })
    }

    @Test
    fun `given padded relay auth inputs when building then trims and writes expected tags`() {
        val obj = parseObject(NostrEventBuilder.relayAuth("  challenge  ", "  wss://relay.example  "))
        val tags = tagArrays(obj)

        assertEquals(Event.KIND_CLIENT_AUTH, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("", obj.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("relay", "wss://relay.example"), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("challenge", "challenge"), tags[1].map { it.jsonPrimitive.content })
    }

    @Test
    fun `given target event when building repost then wraps source event and protocol tags`() {
        val target = sampleEvent(id = "d".repeat(64), pubkey = "e".repeat(64), tags = listOf(listOf("t", "nostr")))
        val obj = parseObject(NostrEventBuilder.repost(target))
        val tags = tagArrays(obj)

        assertEquals(Event.KIND_REPOST, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals(listOf("e", target.id, ""), tags[0].map { it.jsonPrimitive.content })
        assertEquals(listOf("p", target.pubkey), tags[1].map { it.jsonPrimitive.content })

        val embedded = parseObject(obj.getValue("content").jsonPrimitive.content)
        assertEquals(target.id, embedded.getValue("id").jsonPrimitive.content)
        assertEquals(target.pubkey, embedded.getValue("pubkey").jsonPrimitive.content)
        assertTrue(embedded.containsKey("tags"))
    }

    @Test
    fun `given text note with outer whitespace when building then content is trimmed`() {
        val obj = parseObject(NostrEventBuilder.textNote("\n\n  hello world  \t"))

        assertEquals("hello world", obj.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `given text note with tracking params when building then tracking params are removed`() {
        val obj = parseObject(
            NostrEventBuilder.textNote(
                "look https://www.youtube.com/watch?v=abc123&utm_source=mail&si=token"
            )
        )

        assertEquals(
            "look https://www.youtube.com/watch?v=abc123",
            obj.getValue("content").jsonPrimitive.content
        )
    }

    @Test
    fun `given reply with outer whitespace when building then content is trimmed`() {
        val parent = sampleEvent(id = "f".repeat(64), pubkey = "1".repeat(64))
        val obj = parseObject(NostrEventBuilder.reply("  \n reply body \n ", parent))

        assertEquals("reply body", obj.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `given profile fields when updateProfile then kind is 0 and content contains fields`() {
        val obj = parseObject(
            NostrEventBuilder.updateProfile(
                name = "alice",
                displayName = "Alice",
                about = "Hello from Nostr",
                website = "https://alice.example?utm_source=news",
                nip05 = "alice@example.com",
                lud16 = "alice@getalby.com",
                picture = null
            )
        )

        assertEquals(Event.KIND_METADATA, obj.getValue("kind").jsonPrimitive.content.toInt())
        val content = parseObject(obj.getValue("content").jsonPrimitive.content)
        assertEquals("alice", content.getValue("name").jsonPrimitive.content)
        assertEquals("Alice", content.getValue("display_name").jsonPrimitive.content)
        assertEquals("Hello from Nostr", content.getValue("about").jsonPrimitive.content)
        assertEquals("https://alice.example", content.getValue("website").jsonPrimitive.content)
        assertEquals("alice@example.com", content.getValue("nip05").jsonPrimitive.content)
        assertEquals("alice@getalby.com", content.getValue("lud16").jsonPrimitive.content)
        assertTrue(tagArrays(obj).isEmpty())
    }

    @Test
    fun `given blank profile fields when updateProfile then blank fields are omitted from content`() {
        val obj = parseObject(
            NostrEventBuilder.updateProfile(
                name = "bob",
                displayName = "  ",
                about = null,
                website = "",
                nip05 = null,
                lud16 = null,
                picture = null
            )
        )

        assertEquals(Event.KIND_METADATA, obj.getValue("kind").jsonPrimitive.content.toInt())
        val content = parseObject(obj.getValue("content").jsonPrimitive.content)
        assertEquals("bob", content.getValue("name").jsonPrimitive.content)
        assertFalse(content.containsKey("display_name"))
        assertFalse(content.containsKey("about"))
        assertFalse(content.containsKey("website"))
    }

    @Test
    fun `given updateProfile when called then tags array is empty`() {
        val obj = parseObject(
            NostrEventBuilder.updateProfile(
                name = "carol",
                displayName = null,
                about = null,
                website = null,
                nip05 = null,
                lud16 = null,
                picture = null
            )
        )

        assertTrue(tagArrays(obj).isEmpty())
    }

    @Test
    fun `given content mentioning a profile when building text note then adds p tag`() {
        val mentionedPubkey = "1".repeat(64)
        val npub = Bech32Encoder.encodeNpub(mentionedPubkey)
        val obj = parseObject(NostrEventBuilder.textNote("hey nostr:$npub check this out"))

        val pTags = tagArrays(obj).filter { it[0].jsonPrimitive.content == "p" }
        assertEquals(1, pTags.size)
        assertEquals(mentionedPubkey, pTags.first()[1].jsonPrimitive.content)
    }

    @Test
    fun `given content quoting an event when building text note then adds q tag`() {
        val quotedId = "2".repeat(64)
        val note = Bech32Encoder.encodeNote(quotedId)
        val obj = parseObject(NostrEventBuilder.textNote("check nostr:$note out"))

        val qTags = tagArrays(obj).filter { it[0].jsonPrimitive.content == "q" }
        assertEquals(1, qTags.size)
        assertEquals(quotedId, qTags.first()[1].jsonPrimitive.content)
    }

    @Test
    fun `given content quoting a nevent with relay and author when building text note then adds full 4-element q tag`() {
        val quotedId = "5".repeat(64)
        val authorPubkey = "6".repeat(64)
        val nevent = Bech32Encoder.encodeNevent(
            hexEventId = quotedId,
            relayUrls = listOf("wss://relay.example"),
            hexAuthorPubkey = authorPubkey
        )
        val obj = parseObject(NostrEventBuilder.textNote("check nostr:$nevent out"))

        val qTags = tagArrays(obj).filter { it[0].jsonPrimitive.content == "q" }
        assertEquals(1, qTags.size)
        assertEquals(
            listOf("q", quotedId, "wss://relay.example", authorPubkey),
            qTags.first().map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `given content quoting a bare note1 when building text note then adds a 2-element q tag`() {
        val quotedId = "7".repeat(64)
        val note = Bech32Encoder.encodeNote(quotedId)
        val obj = parseObject(NostrEventBuilder.textNote("check nostr:$note out"))

        val qTags = tagArrays(obj).filter { it[0].jsonPrimitive.content == "q" }
        assertEquals(1, qTags.size)
        assertEquals(listOf("q", quotedId), qTags.first().map { it.jsonPrimitive.content })
    }

    @Test
    fun `given reply mentioning the same author when building reply then does not duplicate p tag`() {
        val parent = sampleEvent(id = "3".repeat(64), pubkey = "4".repeat(64))
        val npub = Bech32Encoder.encodeNpub(parent.pubkey)
        val obj = parseObject(NostrEventBuilder.reply("thanks nostr:$npub", parent))

        val pTags = tagArrays(obj).filter { it[0].jsonPrimitive.content == "p" }
        assertEquals(1, pTags.size) // one p tag, not two, despite being both a participant and a mention
    }

    @Test
    fun `given plain content with no mentions when building text note then adds no p or q tags`() {
        val obj = parseObject(NostrEventBuilder.textNote("just a regular note, no mentions here"))

        assertTrue(tagArrays(obj).none { it[0].jsonPrimitive.content == "p" || it[0].jsonPrimitive.content == "q" })
    }

    @Test
    fun `given title and content when building forum thread then kind11 with title tag`() {
        val obj = parseObject(NostrEventBuilder.forumThread(title = "GM", content = "Good morning"))

        assertEquals(Event.KIND_THREAD, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("Good morning", obj.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("title", "GM"), tagArrays(obj).first().map { it.jsonPrimitive.content })
    }

    @Test
    fun `given top level comment when building then root and parent tags match the target`() {
        val article = Event(
            id = "1".repeat(64),
            pubkey = "2".repeat(64),
            createdAt = 1L,
            kind = Event.KIND_LONG_FORM,
            tags = listOf(listOf("d", "my-article")),
            content = "article body",
            sig = "c".repeat(128)
        )
        val obj = parseObject(NostrEventBuilder.comment(root = article, content = "Great post!"))

        assertEquals(Event.KIND_COMMENT, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("Great post!", obj.getValue("content").jsonPrimitive.content)

        val tags = tagArrays(obj).associate { it[0].jsonPrimitive.content to it.map { p -> p.jsonPrimitive.content } }
        val expectedAddress = "${Event.KIND_LONG_FORM}:${article.pubkey}:my-article"
        assertEquals(listOf("A", expectedAddress), tags.getValue("A"))
        assertEquals(listOf("K", Event.KIND_LONG_FORM.toString()), tags.getValue("K"))
        assertEquals(listOf("P", article.pubkey), tags.getValue("P"))
        // top-level comment: parent scope (lowercase) mirrors root exactly
        assertEquals(listOf("a", expectedAddress), tags.getValue("a"))
        assertEquals(listOf("k", Event.KIND_LONG_FORM.toString()), tags.getValue("k"))
        assertEquals(listOf("p", article.pubkey), tags.getValue("p"))
    }

    @Test
    fun `given reply to a comment when building then root and parent scopes differ`() {
        val article = Event(
            id = "3".repeat(64),
            pubkey = "4".repeat(64),
            createdAt = 1L,
            kind = 1063, // NIP-94 file — a regular (non-addressable), non-kind-1 event
            tags = emptyList(),
            content = "file caption",
            sig = "c".repeat(128)
        )
        val existingComment = Event(
            id = "5".repeat(64),
            pubkey = "6".repeat(64),
            createdAt = 2L,
            kind = Event.KIND_COMMENT,
            tags = emptyList(),
            content = "first comment",
            sig = "c".repeat(128)
        )
        val obj = parseObject(NostrEventBuilder.comment(root = article, replyTo = existingComment, content = "I agree"))

        val tags = tagArrays(obj).groupBy { it[0].jsonPrimitive.content }
        assertEquals(article.id, tags.getValue("E").first()[1].jsonPrimitive.content)
        assertEquals(existingComment.id, tags.getValue("e").first()[1].jsonPrimitive.content)
        assertEquals(Event.KIND_COMMENT.toString(), tags.getValue("k").first()[1].jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given kind1 root when building comment then throws`() {
        val note = sampleEvent(id = "7".repeat(64))
        NostrEventBuilder.comment(root = note, content = "should not be allowed")
    }

    @Test
    fun `given single regular event when deleting then includes e and k tags but no a tag`() {
        val target = sampleEvent(id = "5".repeat(64))
        val obj = parseObject(NostrEventBuilder.deleteEvent(target, reason = "oops"))

        assertEquals(Event.KIND_EVENT_DELETION, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("oops", obj.getValue("content").jsonPrimitive.content)

        val tags = tagArrays(obj)
        assertEquals(listOf(listOf("e", target.id)), tags.filter { it[0].jsonPrimitive.content == "e" }.map { it.map { p -> p.jsonPrimitive.content } })
        assertEquals(listOf(listOf("k", Event.KIND_TEXT_NOTE.toString())), tags.filter { it[0].jsonPrimitive.content == "k" }.map { it.map { p -> p.jsonPrimitive.content } })
        assertTrue(tags.none { it[0].jsonPrimitive.content == "a" })
    }

    @Test
    fun `given addressable event when deleting then includes matching a tag`() {
        val identifier = "my-article"
        val target = Event(
            id = "6".repeat(64),
            pubkey = "7".repeat(64),
            createdAt = 42L,
            kind = Event.KIND_LONG_FORM,
            tags = listOf(listOf("d", identifier)),
            content = "text",
            sig = "c".repeat(128)
        )
        val obj = parseObject(NostrEventBuilder.deleteEvent(target))

        val aTag = tagArrays(obj).first { it[0].jsonPrimitive.content == "a" }.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "${Event.KIND_LONG_FORM}:${target.pubkey}:$identifier"), aTag)
    }

    @Test
    fun `given multiple events when deleting then batches e tags and dedupes k tags`() {
        val first = sampleEvent(id = "8".repeat(64))
        val second = sampleEvent(id = "9".repeat(64))
        val obj = parseObject(NostrEventBuilder.deleteEvent(listOf(first, second)))

        val tags = tagArrays(obj)
        val eTags = tags.filter { it[0].jsonPrimitive.content == "e" }.map { it[1].jsonPrimitive.content }
        val kTags = tags.filter { it[0].jsonPrimitive.content == "k" }.map { it[1].jsonPrimitive.content }

        assertEquals(listOf(first.id, second.id), eTags)
        assertEquals(listOf(Event.KIND_TEXT_NOTE.toString()), kTags) // both kind 1 -> deduped to one k tag
    }

    @Test
    fun `given receivers when building public message then tags each as p and omits e tags`() {
        val receiver = "d".repeat(64)
        val obj = parseObject(NostrEventBuilder.publicMessage("hello there", listOf(receiver)))

        assertEquals(Event.KIND_PUBLIC_MESSAGE, obj.getValue("kind").jsonPrimitive.content.toInt())
        val tags = tagArrays(obj)
        assertEquals(listOf(listOf("p", receiver)), tags.map { it.map { p -> p.jsonPrimitive.content } })
        assertTrue(tags.none { it[0].jsonPrimitive.content == "e" })
    }

    @Test
    fun `given duplicate receivers when building public message then dedupes p tags`() {
        val receiver = "D".repeat(64)
        val obj = parseObject(NostrEventBuilder.publicMessage("hi", listOf(receiver, receiver.lowercase())))

        val pTags = tagArrays(obj).filter { it[0].jsonPrimitive.content == "p" }
        assertEquals(1, pTags.size)
    }

    @Test
    fun `given no quote when building chat message then has no q tag`() {
        val obj = parseObject(NostrEventBuilder.chatMessage("GM"))

        assertEquals(Event.KIND_CHAT_MESSAGE, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertTrue(tagArrays(obj).none { it[0].jsonPrimitive.content == "q" })
    }

    @Test
    fun `given quoted event when building chat message then adds q tag with id and pubkey`() {
        val parent = sampleEvent()
        val obj = parseObject(NostrEventBuilder.chatMessage("yes", quoting = parent, quotingRelayUrl = "wss://relay.example"))

        val qTag = tagArrays(obj).first { it[0].jsonPrimitive.content == "q" }.map { it.jsonPrimitive.content }
        assertEquals(listOf("q", parent.id, "wss://relay.example", parent.pubkey.lowercase()), qTag)
    }

    @Test
    fun `given notes and articles when building bookmark list then emits e and a tags`() {
        val noteId = "1".repeat(64)
        val obj = parseObject(NostrEventBuilder.bookmarkList(setOf(noteId), setOf("30023:${"2".repeat(64)}:my-article")))

        assertEquals(Event.KIND_BOOKMARK_LIST, obj.getValue("kind").jsonPrimitive.content.toInt())
        val tags = tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } }
        assertTrue(tags.contains(listOf("e", noteId)))
        assertTrue(tags.contains(listOf("a", "30023:${"2".repeat(64)}:my-article")))
    }

    @Test
    fun `given community addresses when building communities list then emits a tags`() {
        val address = "34550:${"3".repeat(64)}:my-community"
        val obj = parseObject(NostrEventBuilder.communitiesList(setOf(address)))

        assertEquals(Event.KIND_COMMUNITIES_LIST, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals(listOf(listOf("a", address)), tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } })
    }

    @Test
    fun `given relays when building blocked relays list then emits relay tags`() {
        val obj = parseObject(NostrEventBuilder.blockedRelaysList(setOf("wss://bad.relay")))

        assertEquals(Event.KIND_BLOCKED_RELAYS, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals(listOf(listOf("relay", "wss://bad.relay")), tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } })
    }

    @Test
    fun `given relays when building search relays list then emits relay tags`() {
        val obj = parseObject(NostrEventBuilder.searchRelaysList(setOf("wss://search.relay")))

        assertEquals(Event.KIND_SEARCH_RELAYS, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals(listOf(listOf("relay", "wss://search.relay")), tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } })
    }

    @Test
    fun `given hashtags and sets when building interests list then emits t and a tags`() {
        val address = "30015:${"4".repeat(64)}:my-set"
        val obj = parseObject(NostrEventBuilder.interestsList(setOf("Nostr"), setOf(address)))

        assertEquals(Event.KIND_INTERESTS_LIST, obj.getValue("kind").jsonPrimitive.content.toInt())
        val tags = tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } }
        assertTrue(tags.contains(listOf("t", "nostr")))
        assertTrue(tags.contains(listOf("a", address)))
    }

    @Test
    fun `given role split when building relay list then emits correct marker per role`() {
        val obj = parseObject(
            NostrEventBuilder.relayList(
                writeOnly = setOf("wss://write.only"),
                readOnly = setOf("wss://read.only"),
                both = setOf("wss://both.relay")
            )
        )

        assertEquals(Event.KIND_RELAY_LIST_METADATA, obj.getValue("kind").jsonPrimitive.content.toInt())
        val tags = tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } }
        assertTrue(tags.contains(listOf("r", "wss://both.relay")))
        assertTrue(tags.contains(listOf("r", "wss://write.only", "write")))
        assertTrue(tags.contains(listOf("r", "wss://read.only", "read")))
    }

    @Test
    fun `given empty role sets when building relay list then emits no tags`() {
        val obj = parseObject(NostrEventBuilder.relayList(emptySet(), emptySet(), emptySet()))

        assertEquals(Event.KIND_RELAY_LIST_METADATA, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertTrue(tagArrays(obj).isEmpty())
    }

    @Test
    fun `given relays when building dm relay list then emits relay tags`() {
        val obj = parseObject(NostrEventBuilder.dmRelayList(setOf("wss://dm.relay")))

        assertEquals(Event.KIND_DM_RELAY_LIST, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals(listOf(listOf("relay", "wss://dm.relay")), tagArrays(obj).map { it.map { p -> p.jsonPrimitive.content } })
    }

    @Test
    fun `given ciphertext when building encrypted search relays list then content is verbatim and tags empty`() {
        val obj = parseObject(NostrEventBuilder.searchRelaysListEncrypted("2:some-nip44-ciphertext"))

        assertEquals(Event.KIND_SEARCH_RELAYS, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("2:some-nip44-ciphertext", obj.getValue("content").jsonPrimitive.content)
        assertTrue(tagArrays(obj).isEmpty())
    }

    @Test
    fun `given ciphertext when building encrypted index relays list then content is verbatim and tags empty`() {
        val obj = parseObject(NostrEventBuilder.indexRelaysListEncrypted("2:another-nip44-ciphertext"))

        assertEquals(Event.KIND_INDEX_RELAYS, obj.getValue("kind").jsonPrimitive.content.toInt())
        assertEquals("2:another-nip44-ciphertext", obj.getValue("content").jsonPrimitive.content)
        assertTrue(tagArrays(obj).isEmpty())
    }
}
