package com.umbra.app.data.repository

import com.umbra.app.data.network.boundedForOneShotCall
import com.umbra.app.data.network.torGuardedCall
import com.umbra.app.domain.nipb7.BlossomBlobDescriptor
import com.umbra.app.domain.repository.MediaUploadRepository
import com.umbra.app.domain.util.JsonUtils
import com.umbra.app.util.logging.LogScrubber.scrubUrlForLogs
import com.umbra.app.util.logging.UmbraLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Named

class MediaUploadRepositoryImpl @Inject constructor(
    @Named("tor") torClient: OkHttpClient
) : MediaUploadRepository {

    companion object {
        private const val TAG = "UmbraMediaUploadRepo"
    }

    private val logger = UmbraLog.tag(TAG)

    // One-shot upload call — bounded the same way RelayInfoRepositoryImpl bounds its one-shot
    // NIP-11 GET, so a stalled Blossom server can't hang an IO thread forever. Write/read get a
    // longer allowance than a plain metadata GET since this call carries an image body. Reused
    // for every Blossom endpoint below (list/delete/mirror/head are all one-shot HTTP calls with
    // the same "don't hang an IO thread forever" reasoning) — this is the only OkHttpClient this
    // repository builds; still layered on the single injected @Named("tor") client.
    private val httpClient: OkHttpClient =
        torClient.boundedForOneShotCall(callTimeoutSeconds = 60, writeTimeoutSeconds = 45, readTimeoutSeconds = 30)

    override suspend fun uploadBlob(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        authorizationHeaderValue: String
    ): Result<BlossomBlobDescriptor> = torGuardedCall(logger, "Upload failed for ${scrubUrlForLogs(serverUrl)}") {
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/upload")
            .header("Authorization", authorizationHeaderValue)
            .put(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) error("Blossom upload HTTP ${response.code}: ${response.reasonHeader()}")
            if (responseBody.isBlank()) error("Blossom upload empty response")
            parseBlobDescriptor(responseBody)
        }
    }

    override suspend fun headUpload(
        serverUrl: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        authorizationHeaderValue: String?
    ): Result<Unit> = torGuardedCall(logger, "HEAD /upload failed for ${scrubUrlForLogs(serverUrl)}") {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/upload")
            .head()
            .header("X-SHA-256", sha256Hex)
            .header("X-Content-Type", mimeType)
            .header("X-Content-Length", sizeBytes.toString())
            .apply { authorizationHeaderValue?.let { header("Authorization", it) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Blossom HEAD /upload HTTP ${response.code}: ${response.reasonHeader()}")
        }
    }

    override suspend fun headBlob(
        serverUrl: String,
        sha256Hex: String,
        authorizationHeaderValue: String?
    ): Result<Unit> = torGuardedCall(logger, "HEAD blob failed for ${scrubUrlForLogs(serverUrl)}") {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/" + sha256Hex)
            .head()
            .apply { authorizationHeaderValue?.let { header("Authorization", it) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Blossom HEAD /$sha256Hex HTTP ${response.code}: ${response.reasonHeader()}")
        }
    }

    override suspend fun listBlobs(
        serverUrl: String,
        pubkeyHex: String,
        authorizationHeaderValue: String?
    ): Result<List<BlossomBlobDescriptor>> = torGuardedCall(logger, "List failed for ${scrubUrlForLogs(serverUrl)}") {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/list/" + pubkeyHex)
            .get()
            .apply { authorizationHeaderValue?.let { header("Authorization", it) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) error("Blossom list HTTP ${response.code}: ${response.reasonHeader()}")
            if (responseBody.isBlank()) return@use emptyList()
            parseBlobDescriptorList(responseBody)
        }
    }

    override suspend fun deleteBlob(
        serverUrl: String,
        sha256Hex: String,
        authorizationHeaderValue: String
    ): Result<Unit> = torGuardedCall(logger, "Delete failed for ${scrubUrlForLogs(serverUrl)}") {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/" + sha256Hex)
            .delete()
            .header("Authorization", authorizationHeaderValue)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Blossom delete HTTP ${response.code}: ${response.reasonHeader()}")
        }
    }

    override suspend fun mirrorBlob(
        serverUrl: String,
        sourceUrl: String,
        authorizationHeaderValue: String
    ): Result<BlossomBlobDescriptor> = torGuardedCall(logger, "Mirror failed for ${scrubUrlForLogs(serverUrl)}") {
        val bodyJson = buildJsonObject { put("url", sourceUrl) }.toString()
        val body = bodyJson.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/mirror")
            .header("Authorization", authorizationHeaderValue)
            .put(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) error("Blossom mirror HTTP ${response.code}: ${response.reasonHeader()}")
            if (responseBody.isBlank()) error("Blossom mirror empty response")
            parseBlobDescriptor(responseBody)
        }
    }

    /** BUD-01/BUD-02/BUD-04/BUD-06: `X-Reason` is a human-readable diagnostic only, never parsed for control flow. */
    private fun Response.reasonHeader(): String = header("X-Reason") ?: "no reason given"

    /** Package-visible (not `private`) so its JSON-parsing logic can be unit tested directly. */
    internal fun parseBlobDescriptor(rawJson: String): BlossomBlobDescriptor {
        val obj = JsonUtils.NostrJson.parseToJsonElement(rawJson) as? JsonObject ?: error("Invalid Blossom response")
        return obj.toBlobDescriptor()
    }

    /** Package-visible for the same reason as [parseBlobDescriptor] — BUD-12 `GET /list/<pubkey>` response. */
    internal fun parseBlobDescriptorList(rawJson: String): List<BlossomBlobDescriptor> {
        val array = JsonUtils.NostrJson.parseToJsonElement(rawJson) as? JsonArray ?: error("Invalid Blossom list response")
        return array.map { element ->
            (element as? JsonObject ?: error("Invalid Blossom list entry")).toBlobDescriptor()
        }
    }

    private fun JsonObject.toBlobDescriptor(): BlossomBlobDescriptor {
        val url = (this["url"] as? JsonPrimitive)?.content ?: error("Blossom response missing url")
        val sha256 = (this["sha256"] as? JsonPrimitive)?.content.orEmpty()
        val size = this["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val mimeType = (this["type"] as? JsonPrimitive)?.content
        return BlossomBlobDescriptor(url = url, sha256 = sha256, size = size, mimeType = mimeType)
    }
}
