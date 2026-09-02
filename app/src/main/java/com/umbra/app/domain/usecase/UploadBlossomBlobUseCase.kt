package com.umbra.app.domain.usecase

import android.util.Base64
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nipb7.BlossomBlobDescriptor
import com.umbra.app.domain.nipb7.blossomServerDomain
import com.umbra.app.domain.nipb7.sha256Hex
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.MediaUploadRepository

/** Outcome of [UploadBlossomBlobUseCase] — distinguishes a cancelled Amber signature from an
 * actual upload failure, since callers show a different message for each. */
sealed interface BlossomUploadResult {
    data class Success(val descriptor: BlossomBlobDescriptor) : BlossomUploadResult
    data object SignCancelled : BlossomUploadResult
    data class Failed(val error: Throwable) : BlossomUploadResult
}

private const val BLOSSOM_AUTH_TTL_SECONDS = 5 * 60L

/**
 * Shared Blossom upload flow (BUD-01/02/11): signs a scoped kind:24242 authorization event via
 * Amber, then calls [MediaUploadRepository.uploadBlob]. Factored out of
 * [com.umbra.app.ui.profile.EditProfileViewModel], which used to duplicate this exact sequence
 * once for the profile picture and once for the banner — every future upload call site
 * (composer attachments included) goes through this instead of repeating it a third time.
 */
class UploadBlossomBlobUseCase(
    private val mediaUploadRepository: MediaUploadRepository,
    private val amberSignerGateway: AmberSignerGateway,
    private val userPreferences: UserPreferences
) {
    suspend operator fun invoke(
        serverUrl: String,
        bytes: ByteArray,
        mimeType: String,
        description: String
    ): BlossomUploadResult {
        val expiration = System.currentTimeMillis() / 1000 + BLOSSOM_AUTH_TTL_SECONDS
        val authEventJson = NostrEventBuilder.blossomAuth(
            verb = "upload",
            sha256Hex = sha256Hex(bytes),
            expirationEpochSeconds = expiration,
            description = description,
            serverTags = listOf(blossomServerDomain(serverUrl))
        )
        val currentUserHex = userPreferences.getPublicKey()
        val signed = amberSignerGateway.signEvent(authEventJson, currentUserHex)
            ?: return BlossomUploadResult.SignCancelled

        val authorizationHeader = "Nostr " + Base64.encodeToString(
            signed.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return mediaUploadRepository.uploadBlob(
            serverUrl = serverUrl,
            bytes = bytes,
            mimeType = mimeType,
            authorizationHeaderValue = authorizationHeader
        ).fold(
            onSuccess = { BlossomUploadResult.Success(it) },
            onFailure = { BlossomUploadResult.Failed(it) }
        )
    }
}
