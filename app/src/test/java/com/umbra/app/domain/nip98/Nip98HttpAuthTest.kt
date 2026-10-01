package com.umbra.app.domain.nip98

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Nip98HttpAuthTest {

    private fun tagsOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["tags"]!!.jsonArray
            .map { arr -> arr.jsonArray.map { it.jsonPrimitive.content } }

    @Test
    fun buildsTagsAndKindAndEmptyContent() {
        val body = "{\"method\":\"listbannedpubkeys\"}".toByteArray()
        val unsigned = Nip98HttpAuth.buildUnsignedEvent(
            url = "https://relay.example.com",
            method = "POST",
            body = body,
            nowEpochSeconds = 1_750_000_000L
        )
        val obj = Json.parseToJsonElement(unsigned).jsonObject
        assertEquals(27235, obj["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals(1_750_000_000L, obj["created_at"]!!.jsonPrimitive.content.toLong())
        assertEquals("", obj["content"]!!.jsonPrimitive.content)
        assertEquals("", obj["pubkey"]!!.jsonPrimitive.content)
        assertEquals("", obj["sig"]!!.jsonPrimitive.content)

        val tags = tagsOf(unsigned)
        assertEquals(listOf("u", "https://relay.example.com"), tags[0])
        assertEquals(listOf("method", "POST"), tags[1])
        assertEquals(2, tags[2].size)
        assertEquals("payload", tags[2][0])
    }

    @Test
    fun payloadTagIsSha256OfBody() {
        val body = "hello".toByteArray()
        val unsigned = Nip98HttpAuth.buildUnsignedEvent("https://relay.example.com", "POST", body, 0L)
        val tags = tagsOf(unsigned)
        // sha256("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            tags[2][1]
        )
    }

    @Test
    fun nullBodyHashesEmptyBytes() {
        val unsigned = Nip98HttpAuth.buildUnsignedEvent("https://relay.example.com", "POST", null, 0L)
        val tags = tagsOf(unsigned)
        // sha256 of zero bytes
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            tags[2][1]
        )
    }

    @Test
    fun headerEncodesSignedJsonAsBase64WithNostrScheme() {
        val signed = "{\"id\":\"abc\"}"
        val header = Nip98HttpAuth.authorizationHeader(signed)
        assertTrue(header.startsWith("Nostr "))
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8)
        assertEquals(signed, decoded)
    }
}
