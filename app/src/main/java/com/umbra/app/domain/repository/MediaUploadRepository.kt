package com.umbra.app.domain.repository

import com.umbra.app.domain.nipb7.BlossomBlobDescriptor

interface MediaUploadRepository {
    /**
     * Uploads raw bytes to a Blossom server (BUD-02) over the TOR-proxied client.
     * [authorizationHeaderValue] is an already Amber-signed, base64-encoded kind-24242
     * auth event (`"Nostr <base64>"`) — this repository performs no signing itself.
     */
    suspend fun uploadBlob(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        authorizationHeaderValue: String
    ): Result<BlossomBlobDescriptor>

    /**
     * BUD-06 preflight: `HEAD /upload` — asks the server whether an upload matching this
     * metadata would be accepted, without sending any bytes. Purely an optimization; callers
     * MAY skip it and go straight to [uploadBlob]. [authorizationHeaderValue] is optional since
     * BUD-11 authorization is itself optional per-server policy.
     */
    suspend fun headUpload(
        serverUrl: String,
        sha256Hex: String,
        mimeType: String,
        sizeBytes: Long,
        authorizationHeaderValue: String? = null
    ): Result<Unit>

    /**
     * BUD-01: `HEAD /<sha256>` — checks whether a blob exists on [serverUrl] without downloading
     * it. Used by the BUD-03 client-retrieval fallback to probe candidate mirrors before
     * switching a broken media URL over to one of them.
     */
    suspend fun headBlob(
        serverUrl: String,
        sha256Hex: String,
        authorizationHeaderValue: String? = null
    ): Result<Unit>

    /**
     * BUD-12 (unrecommended, optional): `GET /list/<pubkey>` — blobs a given pubkey has stored
     * on [serverUrl]. Servers are not required to implement this; callers must tolerate failure.
     */
    suspend fun listBlobs(
        serverUrl: String,
        pubkeyHex: String,
        authorizationHeaderValue: String? = null
    ): Result<List<BlossomBlobDescriptor>>

    /**
     * BUD-12: `DELETE /<sha256>`. Per BUD-11, the authorization token for this call SHOULD be
     * scoped with a `server` tag — an unscoped delete token can be replayed against any other
     * server holding the same blob.
     */
    suspend fun deleteBlob(
        serverUrl: String,
        sha256Hex: String,
        authorizationHeaderValue: String
    ): Result<Unit>

    /**
     * BUD-04: `PUT /mirror` — asks [serverUrl] to copy a blob it doesn't yet have from
     * [sourceUrl] (typically another Blossom server that already has it), instead of the client
     * re-uploading the bytes itself.
     */
    suspend fun mirrorBlob(
        serverUrl: String,
        sourceUrl: String,
        authorizationHeaderValue: String
    ): Result<BlossomBlobDescriptor>
}
