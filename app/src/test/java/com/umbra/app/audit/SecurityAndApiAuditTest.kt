package com.umbra.app.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAndApiAuditTest {

    @Test
    fun `given_codebase_when_checkingOkHttpUsage_then_onlyInNetworkModule`() {
        val matches = SourceAuditTestUtils.findLineTokenMatches("OkHttpClient.Builder(")

        assertEquals(
            "OkHttpClient.Builder must only appear in NetworkModule",
            listOf("app/src/main/java/com/umbra/app/data/di/NetworkModule.kt"),
            matches.map { it.relativePath }.distinct()
        )
    }

    @Test
    fun `given_codebase_when_checkingImageLoaderUsage_then_onlyInNetworkModule`() {
        val matches = SourceAuditTestUtils.findLineTokenMatches("ImageLoader.Builder(")

        assertEquals(
            "ImageLoader.Builder must only appear in NetworkModule",
            listOf("app/src/main/java/com/umbra/app/data/di/NetworkModule.kt"),
            matches.map { it.relativePath }.distinct()
        )
    }

    @Test
    fun `given_forbiddenNetworkApis_when_scanning_then_notUsed`() {
        val forbiddenTokens = listOf(
            "DownloadManager",
            "HttpURLConnection",
            "URLConnection",
            "InetAddress.getByName("
        )

        forbiddenTokens.forEach { token ->
            val matches = SourceAuditTestUtils.findLineTokenMatches(token)
            assertTrue("Forbidden API token found: $token in $matches", matches.isEmpty())
        }
    }

    @Test
    fun `given_unstableApiAnnotations_when_scanning_then_containedInMedia3Wrappers`() {
        val unstableAnnotations = listOf(
            "@UnstableApi",
            "@OptIn(UnstableApi::class)",
            "@file:Suppress(\"UnstableApiUsage\")",
            "@file:Suppress(\"UnstableApiUsage\", \"UnsafeOptInUsageError\")"
        )

        val matches = unstableAnnotations.flatMap { token ->
            SourceAuditTestUtils.findLineTokenMatches(token)
        }

        assertTrue("Expected at least one UnstableApi annotation", matches.isNotEmpty())
        assertEquals(
            "UnstableApi annotations must be isolated in Media3Wrappers.kt",
            listOf("app/src/main/java/com/umbra/app/data/media/Media3Wrappers.kt"),
            matches.map { it.relativePath }.distinct()
        )
    }
}
