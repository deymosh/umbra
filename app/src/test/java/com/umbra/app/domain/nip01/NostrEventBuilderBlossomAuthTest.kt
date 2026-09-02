package com.umbra.app.domain.nip01

import com.umbra.app.domain.util.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventBuilderBlossomAuthTest {

    private fun tags(json: String): List<List<String>> =
        (JsonUtils.NostrJson.parseToJsonElement(json) as JsonObject)["tags"]!!
            .jsonArray
            .map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }

    @Test
    fun `given no server tags when building blossom auth then omits server tags`() {
        val json = NostrEventBuilder.blossomAuth(
            verb = "upload",
            sha256Hex = "abcd",
            expirationEpochSeconds = 1000L
        )

        val parsedTags = tags(json)
        assertTrue(parsedTags.none { it.firstOrNull() == "server" })
        assertEquals(listOf("t", "upload"), parsedTags.first { it.first() == "t" })
        assertEquals(listOf("x", "abcd"), parsedTags.first { it.first() == "x" })
        assertEquals(listOf("expiration", "1000"), parsedTags.first { it.first() == "expiration" })
    }

    @Test
    fun `given server tags when building blossom auth then scopes token to each domain`() {
        val json = NostrEventBuilder.blossomAuth(
            verb = "delete",
            sha256Hex = "abcd",
            expirationEpochSeconds = 1000L,
            serverTags = listOf("cdn.example.com", "cdn2.example.com")
        )

        val serverTags = tags(json).filter { it.first() == "server" }
        assertEquals(listOf(listOf("server", "cdn.example.com"), listOf("server", "cdn2.example.com")), serverTags)
    }
}
