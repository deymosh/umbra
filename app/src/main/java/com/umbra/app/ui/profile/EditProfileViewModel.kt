package com.umbra.app.ui.profile

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.R
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nipb7.DefaultBlossomServer
import com.umbra.app.domain.nipb7.preferredUploadServer
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.BlossomUploadResult
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.UploadBlossomBlobUseCase
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class MediaUploadTarget { PICTURE, BANNER }

/** Media picked + stripped, awaiting the user's [com.umbra.app.ui.components.MediaUploadDialog] confirmation. */
data class PendingMediaUpload(
    val bytes: ByteArray,
    val mimeType: String,
    val previewUri: Uri,
    val target: MediaUploadTarget,
    val selectedServer: String
)

@Immutable
data class EditProfileState(
    val name: String = "",
    val displayName: String = "",
    val about: String = "",
    val website: String = "",
    val nip05: String = "",
    val lud16: String = "",
    val picture: String = "",
    // "Other fields" — less commonly edited, tucked behind a collapsible section in the UI.
    val banner: String = "",
    val lud06: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isUploadingPicture: Boolean = false,
    val isUploadingBanner: Boolean = false,
    // Non-null only while the upload-configuration dialog itself should be showing — set once
    // stripping finishes, cleared on confirm/cancel. isUploadingPicture/isUploadingBanner stay
    // true for the whole flow (stripping + dialog open + actual network upload), matching the
    // existing picker-button-disabled/spinner-overlay affordance.
    val pendingUpload: PendingMediaUpload? = null,
    val availableUploadServers: List<String> = listOf(DefaultBlossomServer.URL),
    val savedSuccessfully: Boolean = false,
    val errorMessage: UiMessage? = null
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val uploadBlossomBlobUseCase: UploadBlossomBlobUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "UmbraEditProfileVM"
    }

    private val logger = UmbraLog.tag(TAG)

    private val _state = MutableStateFlow(EditProfileState())
    val state: StateFlow<EditProfileState> = _state.asStateFlow()

    init {
        loadCurrentProfile()
    }

    private fun loadCurrentProfile() {
        val pubkey = userPreferences.getPublicKey() ?: run {
            _state.update { it.copy(isLoading = false, errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return
        }
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                userRepository.getProfile(pubkey)
            }
            _state.update {
                it.copy(
                    name = profile?.name.orEmpty(),
                    displayName = profile?.displayName.orEmpty(),
                    about = profile?.about.orEmpty(),
                    website = profile?.website.orEmpty(),
                    nip05 = profile?.nip05.orEmpty(),
                    lud16 = profile?.lud16.orEmpty(),
                    picture = profile?.picture.orEmpty(),
                    banner = profile?.banner.orEmpty(),
                    lud06 = profile?.lud06.orEmpty(),
                    availableUploadServers = availableServersFor(pubkey),
                    isLoading = false
                )
            }
        }
    }

    /** BUD-03: the user's own server list (priority order) plus the app default as a fallback. */
    private fun availableServersFor(pubkey: String): List<String> {
        val ownServers = userRepository.getServerList(pubkey)?.servers.orEmpty()
        return (ownServers + DefaultBlossomServer.URL).distinct()
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value) }
    fun onAboutChange(value: String) = _state.update { it.copy(about = value) }
    fun onWebsiteChange(value: String) = _state.update { it.copy(website = value) }
    fun onNip05Change(value: String) = _state.update { it.copy(nip05 = value) }
    fun onLud16Change(value: String) = _state.update { it.copy(lud16 = value) }
    fun onPictureChange(value: String) = _state.update { it.copy(picture = value) }
    fun onBannerChange(value: String) = _state.update { it.copy(banner = value) }
    fun onLud06Change(value: String) = _state.update { it.copy(lud06 = value) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    fun saveProfile() {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return
        }
        val currentState = _state.value
        if (currentState.isSaving) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val eventJson = NostrEventBuilder.updateProfile(
                name = currentState.name.trim().takeIf { it.isNotBlank() },
                displayName = currentState.displayName.trim().takeIf { it.isNotBlank() },
                about = currentState.about.trim().takeIf { it.isNotBlank() },
                website = currentState.website.trim().takeIf { it.isNotBlank() },
                nip05 = currentState.nip05.trim().takeIf { it.isNotBlank() },
                lud16 = currentState.lud16.trim().takeIf { it.isNotBlank() },
                picture = currentState.picture.trim().takeIf { it.isNotBlank() },
                banner = currentState.banner.trim().takeIf { it.isNotBlank() },
                lud06 = currentState.lud06.trim().takeIf { it.isNotBlank() }
            )
            val currentUserHex = userPreferences.getPublicKey()
            val signed = amberSignerGateway.signEvent(eventJson, currentUserHex)
            if (signed == null) {
                _state.update { it.copy(isSaving = false, errorMessage = UiMessage.Res(R.string.error_amber_sign_cancelled)) }
                return@launch
            }
            publishProfile(signed)
        }
    }

    /**
     * Claims the upload slot before the (potentially slow — EXIF/video-metadata stripping runs
     * first) picked-media pipeline starts, so the "Uploading…" state covers stripping too, not
     * just the network call. Returns false if a picture upload is already in flight.
     */
    fun beginPictureUpload(): Boolean {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return false
        }
        if (_state.value.isUploadingPicture) return false
        _state.update { it.copy(isUploadingPicture = true) }
        return true
    }

    /** Only called when [MediaMetadataStripper] could not confirm the file was cleaned. */
    fun onPictureMetadataStripFailed() {
        _state.update {
            it.copy(isUploadingPicture = false, errorMessage = UiMessage.Res(R.string.error_picture_metadata_strip_failed))
        }
    }

    /** Stripping succeeded — show the upload dialog instead of uploading immediately. */
    fun onPictureReadyForDialog(bytes: ByteArray, mimeType: String, previewUri: Uri) {
        showUploadDialog(bytes, mimeType, previewUri, MediaUploadTarget.PICTURE)
    }

    /** Banner analog of [beginPictureUpload] — same upload slot/claim reasoning. */
    fun beginBannerUpload(): Boolean {
        if (!userPreferences.canSignWithAmber()) {
            _state.update { it.copy(errorMessage = UiMessage.Res(R.string.error_anonymous_read_only_publish)) }
            return false
        }
        if (_state.value.isUploadingBanner) return false
        _state.update { it.copy(isUploadingBanner = true) }
        return true
    }

    /** Only called when [MediaMetadataStripper] could not confirm the file was cleaned. */
    fun onBannerMetadataStripFailed() {
        _state.update {
            it.copy(isUploadingBanner = false, errorMessage = UiMessage.Res(R.string.error_picture_metadata_strip_failed))
        }
    }

    /** Stripping succeeded — show the upload dialog instead of uploading immediately. */
    fun onBannerReadyForDialog(bytes: ByteArray, mimeType: String, previewUri: Uri) {
        showUploadDialog(bytes, mimeType, previewUri, MediaUploadTarget.BANNER)
    }

    private fun showUploadDialog(bytes: ByteArray, mimeType: String, previewUri: Uri, target: MediaUploadTarget) {
        val pubkey = userPreferences.getPublicKey().orEmpty()
        val defaultServer = userRepository.getServerList(pubkey).preferredUploadServer()
        _state.update {
            it.copy(
                pendingUpload = PendingMediaUpload(bytes, mimeType, previewUri, target, defaultServer),
                availableUploadServers = availableServersFor(pubkey)
            )
        }
    }

    fun onUploadServerSelected(server: String) {
        _state.update { current ->
            current.pendingUpload?.let { current.copy(pendingUpload = it.copy(selectedServer = server)) } ?: current
        }
    }

    /** Aborts the in-flight upload flow entirely — clears both the dialog and the uploading flag. */
    fun cancelPendingUpload() {
        val target = _state.value.pendingUpload?.target ?: return
        _state.update {
            it.copy(
                pendingUpload = null,
                isUploadingPicture = if (target == MediaUploadTarget.PICTURE) false else it.isUploadingPicture,
                isUploadingBanner = if (target == MediaUploadTarget.BANNER) false else it.isUploadingBanner
            )
        }
    }

    fun confirmPendingUpload() {
        val pending = _state.value.pendingUpload ?: return
        _state.update { it.copy(pendingUpload = null) }
        viewModelScope.launch {
            val description = when (pending.target) {
                MediaUploadTarget.PICTURE -> "Upload profile picture"
                MediaUploadTarget.BANNER -> "Upload profile banner"
            }
            when (val result = uploadBlossomBlobUseCase(pending.selectedServer, pending.bytes, pending.mimeType, description)) {
                is BlossomUploadResult.Success -> _state.update {
                    when (pending.target) {
                        MediaUploadTarget.PICTURE -> it.copy(isUploadingPicture = false, picture = result.descriptor.url)
                        MediaUploadTarget.BANNER -> it.copy(isUploadingBanner = false, banner = result.descriptor.url)
                    }
                }
                is BlossomUploadResult.SignCancelled -> _state.update {
                    it.copy(
                        isUploadingPicture = if (pending.target == MediaUploadTarget.PICTURE) false else it.isUploadingPicture,
                        isUploadingBanner = if (pending.target == MediaUploadTarget.BANNER) false else it.isUploadingBanner,
                        errorMessage = UiMessage.Res(R.string.error_amber_sign_cancelled)
                    )
                }
                is BlossomUploadResult.Failed -> {
                    logger.d { "${pending.target} upload error: ${scrubThrowableMessageForLogs(result.error)}" }
                    _state.update {
                        it.copy(
                            isUploadingPicture = if (pending.target == MediaUploadTarget.PICTURE) false else it.isUploadingPicture,
                            isUploadingBanner = if (pending.target == MediaUploadTarget.BANNER) false else it.isUploadingBanner,
                            errorMessage = UiMessage.Res(R.string.error_picture_upload_failed)
                        )
                    }
                }
            }
        }
    }

    private fun publishProfile(signedEventJson: String) {
        viewModelScope.launch {
            publishSignedEventUseCase(signedEventJson)
                .onSuccess {
                    _state.update { it.copy(isSaving = false, savedSuccessfully = true) }
                }
                .onFailure { e ->
                    logger.d { "Publish profile error: ${scrubThrowableMessageForLogs(e)}" }
                    _state.update { it.copy(isSaving = false, errorMessage = UiMessage.Res(R.string.error_publish_failed)) }
                }
        }
    }
}
