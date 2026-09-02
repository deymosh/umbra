package com.umbra.app.domain.nipb7

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlossomServerUrlTest {

    @Test
    fun `given https url with path when extracting domain then returns bare lowercase host`() {
        assertEquals("cdn.example.com", blossomServerDomain("https://cdn.example.com/"))
    }

    @Test
    fun `given mixed case host when extracting domain then lowercases it`() {
        assertEquals("cdn.example.com", blossomServerDomain("https://CDN.Example.com"))
    }

    @Test
    fun `given unparseable url when extracting domain then falls back to schemeless trim`() {
        assertEquals("not a url", blossomServerDomain("not a url"))
    }

    // Examples taken verbatim from BUD-03 "Client Retrieval Implementation".
    private val hash = "b1674191a88ec5cdd733e4240a81803105dc412d6c6708d53ab94fc248f4f553"

    @Test
    fun `given blossom url with extension when extracting sha256 then returns hash`() {
        assertEquals(hash, extractBlobSha256FromUrl("https://blossom.example.com/$hash.pdf"))
    }

    @Test
    fun `given blossom url without extension when extracting sha256 then returns hash`() {
        assertEquals(hash, extractBlobSha256FromUrl("https://cdn.example.com/$hash"))
    }

    @Test
    fun `given non blossom url with hash embedded in path when extracting sha256 then returns hash`() {
        assertEquals(
            hash,
            extractBlobSha256FromUrl(
                "https://cdn.example.com/user/ec4425ff5e9446080d2f70440188e3ca5d6da8713db7bdeef73d0ed54d9093f0/media/$hash.pdf"
            )
        )
    }

    @Test
    fun `given url with no hex hash when extracting sha256 then returns null`() {
        assertNull(extractBlobSha256FromUrl("https://example.com/not-a-hash.jpg"))
    }

    @Test
    fun `given non blossom url when building fallback candidates then returns only the original`() {
        val url = "https://example.com/not-a-hash.jpg"
        assertEquals(listOf(url), blossomFallbackCandidates(url, authorServerList = null))
    }

    @Test
    fun `given hash url with no author server list when building fallback candidates then falls back to default only`() {
        val url = "https://broken-domain.example.com/$hash.pdf"

        val candidates = blossomFallbackCandidates(url, authorServerList = null)

        assertEquals(
            listOf(url, DefaultBlossomServer.URL.trimEnd('/') + "/$hash.pdf"),
            candidates
        )
    }

    @Test
    fun `given hash url with author server list when building fallback candidates then tries author servers in order before default`() {
        val url = "https://broken-domain.example.com/$hash.pdf"
        val authorList = UserServerList(
            pubkey = "a".repeat(64),
            servers = listOf("https://cdn.self.hosted", "https://cdn.satellite.earth/")
        )

        val candidates = blossomFallbackCandidates(url, authorList)

        assertEquals(
            listOf(
                url,
                "https://cdn.self.hosted/$hash.pdf",
                "https://cdn.satellite.earth/$hash.pdf",
                DefaultBlossomServer.URL.trimEnd('/') + "/$hash.pdf"
            ),
            candidates
        )
    }

    @Test
    fun `given hash url with no file extension when building fallback candidates then omits extension`() {
        val url = "https://cdn.example.com/$hash"

        val candidates = blossomFallbackCandidates(url, authorServerList = null)

        assertEquals(listOf(url, DefaultBlossomServer.URL.trimEnd('/') + "/$hash"), candidates)
    }

    @Test
    fun `given duplicate candidate urls when building fallback candidates then dedupes`() {
        val url = DefaultBlossomServer.URL.trimEnd('/') + "/$hash.pdf"

        assertEquals(listOf(url), blossomFallbackCandidates(url, authorServerList = null))
    }

    @Test
    fun `given https url with trailing slash when normalizing then strips it`() {
        assertEquals("https://cdn.example.com", normalizeBlossomServerUrl("https://cdn.example.com/"))
    }

    @Test
    fun `given http url when normalizing then accepts it`() {
        assertEquals("http://cdn.example.com", normalizeBlossomServerUrl("http://cdn.example.com"))
    }

    @Test
    fun `given url with no scheme when normalizing then returns null`() {
        assertNull(normalizeBlossomServerUrl("cdn.example.com"))
    }

    @Test
    fun `given blank input when normalizing then returns null`() {
        assertNull(normalizeBlossomServerUrl("   "))
    }

    @Test
    fun `given scheme with no host when normalizing then returns null`() {
        assertNull(normalizeBlossomServerUrl("https://"))
    }
}
