package com.umbra.app.domain.nip01

import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventJsonParsingTest {

    @Test
    fun `given a well-formed event json when fromJson then round-trips every field`() {
        val json = """
            {
              "id": "abc123",
              "pubkey": "def456",
              "created_at": 1700000000,
              "kind": 1,
              "tags": [["e", "eventid"], ["p", "pubkeyid"]],
              "content": "hello world",
              "sig": "sig789"
            }
        """.trimIndent()

        val event = Event.fromJson(json)

        assertEquals(
            Event(
                id = "abc123",
                pubkey = "def456",
                createdAt = 1700000000L,
                kind = 1,
                tags = listOf(listOf("e", "eventid"), listOf("p", "pubkeyid")),
                content = "hello world",
                sig = "sig789"
            ),
            event
        )
    }

    @Test
    fun `given missing fields when fromJsonObject then defaults match the tolerant contract`() {
        val obj = JsonUtils.NostrJson.parseToJsonElement("{}") as JsonObject

        val event = Event.fromJsonObject(obj)

        assertEquals("", event.id)
        assertEquals("", event.pubkey)
        assertEquals(0L, event.createdAt)
        assertEquals(0, event.kind)
        assertTrue(event.tags.isEmpty())
        assertEquals("", event.content)
        assertEquals("", event.sig)
    }

    @Test
    fun `given wrong-typed fields when fromJsonObject then falls back to defaults instead of throwing`() {
        val obj = JsonObject(
            mapOf(
                "id" to JsonPrimitive(1),
                "created_at" to JsonPrimitive("not-a-number"),
                "kind" to JsonPrimitive("also-not-a-number"),
                "tags" to JsonPrimitive("not-an-array")
            )
        )

        val event = Event.fromJsonObject(obj)

        // JsonPrimitive(1).content is the string "1" — still a valid id, just numeric-looking.
        assertEquals("1", event.id)
        assertEquals(0L, event.createdAt)
        assertEquals(0, event.kind)
        assertTrue(event.tags.isEmpty())
    }

    @Test
    fun `given a tag entry that is not an array when fromJsonObject then that tag becomes empty`() {
        val obj = JsonObject(
            mapOf(
                "tags" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("e"), JsonPrimitive("id"))), JsonPrimitive("not-an-array")))
            )
        )

        val event = Event.fromJsonObject(obj)

        assertEquals(listOf(listOf("e", "id"), emptyList()), event.tags)
    }

    @Test
    fun `given non-json text when fromJson then returns null`() {
        assertNull(Event.fromJson("not json at all"))
    }

    @Test
    fun `given a json array instead of an object when fromJson then returns null`() {
        assertNull(Event.fromJson("""["EVENT", "sub", {}]"""))
    }

    @Test
    fun `given a json primitive instead of an object when fromJson then returns null`() {
        assertNull(Event.fromJson("\"just a string\""))
    }
}
