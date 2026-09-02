package com.umbra.app.data.repository

import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUploadRepositoryImplTest {

    // Never used to make a real call in these tests — parseBlobDescriptor() is pure JSON
    // parsing — but the class requires one to construct.
    private val repository = MediaUploadRepositoryImpl(OkHttpClient())

    @Test
    fun `given full blossom response when parsing then extracts every field`() {
        val json = """
            {"url":"https://blossom.example/abcd1234.jpg","sha256":"abcd1234","size":2048,"type":"image/jpeg"}
        """.trimIndent()

        val descriptor = repository.parseBlobDescriptor(json)

        assertEquals("https://blossom.example/abcd1234.jpg", descriptor.url)
        assertEquals("abcd1234", descriptor.sha256)
        assertEquals(2048L, descriptor.size)
        assertEquals("image/jpeg", descriptor.mimeType)
    }

    @Test
    fun `given response missing optional fields when parsing then defaults them`() {
        val json = """{"url":"https://blossom.example/abcd1234.jpg"}"""

        val descriptor = repository.parseBlobDescriptor(json)

        assertEquals("https://blossom.example/abcd1234.jpg", descriptor.url)
        assertEquals("", descriptor.sha256)
        assertEquals(0L, descriptor.size)
        assertNull(descriptor.mimeType)
    }

    @Test(expected = IllegalStateException::class)
    fun `given response missing url when parsing then throws`() {
        repository.parseBlobDescriptor("""{"sha256":"abcd1234","size":2048}""")
    }

    @Test(expected = IllegalStateException::class)
    fun `given non object response when parsing then throws`() {
        repository.parseBlobDescriptor("""["not", "an", "object"]""")
    }

    @Test(expected = SerializationException::class)
    fun `given malformed json when parsing then throws`() {
        repository.parseBlobDescriptor("not json at all")
    }

    @Test
    fun `given list response with multiple blobs when parsing then extracts every descriptor`() {
        val json = """
            [
                {"url":"https://blossom.example/aaaa.jpg","sha256":"aaaa","size":10,"type":"image/jpeg"},
                {"url":"https://blossom.example/bbbb.png","sha256":"bbbb","size":20,"type":"image/png"}
            ]
        """.trimIndent()

        val descriptors = repository.parseBlobDescriptorList(json)

        assertEquals(2, descriptors.size)
        assertEquals("aaaa", descriptors[0].sha256)
        assertEquals("bbbb", descriptors[1].sha256)
    }

    @Test
    fun `given empty list response when parsing then returns empty list`() {
        assertEquals(emptyList<Any>(), repository.parseBlobDescriptorList("[]"))
    }

    @Test(expected = IllegalStateException::class)
    fun `given list response that is not an array when parsing then throws`() {
        repository.parseBlobDescriptorList("""{"url":"https://blossom.example/abcd.jpg"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun `given list entry missing url when parsing then throws`() {
        repository.parseBlobDescriptorList("""[{"sha256":"abcd","size":10}]""")
    }
}
