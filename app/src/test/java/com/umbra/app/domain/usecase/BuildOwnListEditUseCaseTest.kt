package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.encodePrivateTags
import com.umbra.app.domain.nip51.parsePrivateTags
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.testutil.fakes.FakeEventRepository
import com.umbra.app.testutil.fakes.FakeNip44Gateway
import com.umbra.app.testutil.fakes.FakeUserPreferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildOwnListEditUseCaseTest {

    private val me = "a".repeat(64)
    private val publicNote = "1".repeat(64)
    private val privateNote = "2".repeat(64)
    private val newNote = "3".repeat(64)
    private val nip44 = FakeNip44Gateway()

    private fun bookmarks(content: String, vararg ids: String) = Event(
        id = "b".repeat(64),
        pubkey = me,
        createdAt = 10,
        kind = Event.KIND_BOOKMARK_LIST,
        tags = ids.map { listOf("e", it) },
        content = content,
        sig = "s".repeat(128)
    )

    private fun subject(base: Event?): BuildOwnListEditUseCase {
        val prefs = FakeUserPreferences(initialPubkey = me)
        return BuildOwnListEditUseCase(
            FakeEventRepository(recentEvents = listOfNotNull(base)),
            prefs,
            DecryptOwnListItemsUseCase(nip44, prefs),
            nip44
        )
    }

    private data class Built(val publicIds: List<String>, val content: String)

    private fun parse(json: String): Built {
        val obj = JsonUtils.NostrJson.parseToJsonElement(json) as JsonObject
        val tags = (obj["tags"] as JsonArray).map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
        return Built(tags.filter { it[0] == "e" }.map { it[1] }, obj["content"]!!.jsonPrimitive.content)
    }

    private suspend fun privateIdsOf(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val plaintext = nip44.nip44Decrypt(content, me, me)!!
        return parsePrivateTags(plaintext)!!.filter { it[0] == "e" }.map { it[1] }
    }

    private val existingPrivate get() = nip44.encode(encodePrivateTags(listOf(listOf("e", privateNote))))

    @Test
    fun `given a private add when building then the item goes into the encrypted content only`() = runTest {
        val built = parse(subject(bookmarks(existingPrivate, publicNote))(Event.KIND_BOOKMARK_LIST, ListEdit("e", add = setOf(newNote)), privately = true))

        assertEquals(listOf(publicNote), built.publicIds)
        assertEquals(listOf(privateNote, newNote), privateIdsOf(built.content))
    }

    @Test
    fun `given a public add of a private item when building then it moves to the public tags`() = runTest {
        val built = parse(subject(bookmarks(existingPrivate, publicNote))(Event.KIND_BOOKMARK_LIST, ListEdit("e", add = setOf(privateNote))))

        assertEquals(listOf(publicNote, privateNote), built.publicIds)
        assertEquals("", built.content)
    }

    @Test
    fun `given a removal when building then the item leaves both sides`() = runTest {
        val base = bookmarks(existingPrivate, publicNote)

        val removePrivate = parse(subject(base)(Event.KIND_BOOKMARK_LIST, ListEdit("e", remove = setOf(privateNote))))
        val removePublic = parse(subject(base)(Event.KIND_BOOKMARK_LIST, ListEdit("e", remove = setOf(publicNote))))

        assertEquals(listOf(publicNote), removePrivate.publicIds)
        assertEquals("", removePrivate.content)
        assertEquals(emptyList<String>(), removePublic.publicIds)
        assertEquals(base.content, removePublic.content)
    }

    @Test
    fun `given the signer refuses when making a public edit then private content is kept as it was`() = runTest {
        val base = bookmarks(existingPrivate, publicNote)
        nip44.refuse = true

        val built = parse(subject(base)(Event.KIND_BOOKMARK_LIST, ListEdit("e", add = setOf(newNote))))

        assertEquals(listOf(publicNote, newNote), built.publicIds)
        assertEquals(base.content, built.content)
    }

    @Test(expected = IllegalStateException::class)
    fun `given the signer refuses when making a private edit then building fails`() = runTest {
        nip44.refuse = true
        subject(bookmarks(existingPrivate, publicNote))(Event.KIND_BOOKMARK_LIST, ListEdit("e", add = setOf(newNote)), privately = true)
    }

    @Test
    fun `given a new set when adding to it then it is created with its d tag and title`() = runTest {
        val json = subject(null)(
            Event.KIND_BOOKMARK_SET, ListEdit("e", add = setOf(newNote)), identifier = "trips", newTitle = "Trips"
        )
        val obj = JsonUtils.NostrJson.parseToJsonElement(json) as JsonObject
        val tags = (obj["tags"] as JsonArray).map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }

        assertEquals(listOf(listOf("d", "trips"), listOf("title", "Trips"), listOf("e", newNote)), tags)
    }

    @Test
    fun `given a rename when building then the signer is never asked`() = runTest {
        val existing = bookmarks(existingPrivate, publicNote).copy(
            kind = Event.KIND_BOOKMARK_SET,
            tags = listOf(listOf("d", "trips"), listOf("title", "Trips"), listOf("e", publicNote))
        )

        val built = parse(subject(existing)(Event.KIND_BOOKMARK_SET, ListEdit("e"), identifier = "trips", newTitle = "Holidays"))

        assertEquals(0, nip44.interactiveDecrypts)
        assertEquals(existing.content, built.content)
        assertEquals(listOf(publicNote), built.publicIds)
    }

    @Test
    fun `given a follow list with an old relay map when editing then the signer is never asked`() = runTest {
        val contacts = Event(
            id = "c".repeat(64), pubkey = me, createdAt = 1, kind = Event.KIND_CONTACT_LIST,
            tags = listOf(listOf("p", publicNote)), content = """{"wss://relay.example":{"read":true}}""", sig = "s".repeat(128)
        )

        val json = subject(contacts)(Event.KIND_CONTACT_LIST, ListEdit("p", add = setOf(newNote)))

        assertEquals(0, nip44.interactiveDecrypts)
        assertTrue(json.contains("wss://relay.example"))
    }
}
