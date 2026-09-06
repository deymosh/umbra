package com.umbra.app.ui.composer

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.media.VideoCacheDataSourceProvider
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip01.NostrEventBuilder
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip55.AmberSignerGateway
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.nip92.MediaDimensions
import com.umbra.app.domain.nipb7.DefaultBlossomServer
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.BlossomUploadResult
import com.umbra.app.domain.usecase.PublishSignedEventUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.domain.usecase.UploadBlossomBlobUseCase
import com.umbra.app.domain.util.TrackingTokenSanitizer
import com.umbra.app.R
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.ui.components.extractMentionedProfileRefs
import com.umbra.app.ui.components.extractQuotedEventRefs
import com.umbra.app.util.logging.LogScrubber.scrubThrowableMessageForLogs
import com.umbra.app.util.logging.UmbraLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "UmbraComposerVM"
private const val CONTENT_RESOLVE_DEBOUNCE_MS = 400L
private const val MENTION_QUERY_DEBOUNCE_MS = 250L
private const val MENTION_SUGGESTION_LIMIT = 8
private const val DRAFT_EVENT_ID = "composer-draft"

/** Start index (in the raw text) of an in-progress "@query" the caret is currently inside. */
internal data class MentionQuery(val startIndex: Int, val query: String)

/**
 * Scans backward from [caret] in [text] for an in-progress "@query" mention: the "@" must start
 * the text or follow whitespace (so an email-like "user@host" mid-word never triggers this), and
 * the run between "@" and the caret must contain no whitespace. Returns null when the caret isn't
 * inside such a run — including right after an already-inserted `nostr:npub1…/nprofile1…` URI,
 * since those never contain a bare "@" in the raw text (only [MentionVisualTransformation]'s
 * rendering shows them as "@name").
 */
internal fun detectMentionQuery(text: String, caret: Int): MentionQuery? {
    if (caret <= 0 || caret > text.length) return null
    var i = caret - 1
    while (i >= 0 && text[i] != '@' && !text[i].isWhitespace()) i--
    if (i < 0 || text[i] != '@') return null
    if (i > 0 && !text[i - 1].isWhitespace()) return null
    val query = text.substring(i + 1, caret)
    if (query.contains('@')) return null
    return MentionQuery(i, query)
}

/**
 * A gallery/keyboard-picked image (or GIF), already metadata-stripped, awaiting the user's
 * [com.umbra.app.ui.components.MediaUploadDialog] confirmation before it actually uploads.
 * [width]/[height]/[blurHash] are best-effort (null if the picked bytes couldn't be decoded) —
 * still uploadable either way, just without those two `imeta` fields on the resulting tag.
 */
data class ComposerPendingUpload(
    val bytes: ByteArray,
    val mimeType: String,
    val previewUri: Uri,
    val width: Int?,
    val height: Int?,
    val blurHash: String?,
    val altText: String = "",
    val selectedServer: String
)

data class ComposerState(
    val currentUserPubkey: String? = null,
    val currentUserProfile: UserProfile? = null,
    val replyToEvent: Event? = null,
    val replyToProfile: UserProfile? = null,
    val isReplyMode: Boolean = false,
    val resolvedQuotedEvents: Map<String, Event> = emptyMap(),
    val quotedAuthorProfiles: Map<String, UserProfile> = emptyMap(),
    val mentionSuggestions: List<UserProfile> = emptyList(),
    val canSign: Boolean = false,
    val isPublishing: Boolean = false,
    val removedTrackingToken: Boolean = false,
    // Attachment upload flow — see ComposerPendingUpload's doc comment.
    val pendingUpload: ComposerPendingUpload? = null,
    val isUploadingAttachment: Boolean = false,
    val availableUploadServers: List<String> = listOf(DefaultBlossomServer.URL),
    val attachments: List<ImetaTag> = emptyList(),
    // NIP-36: note-level, not per-attachment — set from the upload dialog's sensitive toggle,
    // any attachment marking it sensitive marks the whole note.
    val sensitiveContent: Boolean = false,
    val attachmentError: UiMessage? = null
) {
    /** Synthetic in-progress note driving the live EventCard preview — content only, see EventCard. */
    fun draftEvent(content: String): Event = Event(
        id = DRAFT_EVENT_ID,
        pubkey = currentUserPubkey.orEmpty(),
        createdAt = System.currentTimeMillis() / 1000L,
        kind = Event.KIND_TEXT_NOTE,
        tags = emptyList(),
        content = content.trim(),
        sig = ""
    )
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ComposerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val amberSignerGateway: AmberSignerGateway,
    private val publishSignedEventUseCase: PublishSignedEventUseCase,
    private val trackReferencedAuthorUseCase: TrackReferencedAuthorUseCase,
    private val uploadBlossomBlobUseCase: UploadBlossomBlobUseCase,
    private val videoCacheDataSourceProvider: VideoCacheDataSourceProvider
) : ViewModel() {

    val mediaCacheDataSourceFactory get() = videoCacheDataSourceProvider.getCacheDataSourceFactory()
    val userRepositoryPublic: UserRepository get() = userRepository

    private val replyToEventId: String? = savedStateHandle.get<String>("replyTo")
    private val quoteEventId: String? = savedStateHandle.get<String>("quote")

    val textState = TextFieldState()

    private val logger = UmbraLog.tag(TAG)

    private val _state = MutableStateFlow(ComposerState(isReplyMode = replyToEventId != null))
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    private val _published = MutableSharedFlow<Unit>()
    val published: SharedFlow<Unit> = _published.asSharedFlow()

    init {
        val pubkey = userPreferences.getPublicKey()
        _state.update {
            it.copy(
                canSign = userPreferences.canSignWithAmber(),
                currentUserPubkey = pubkey,
                availableUploadServers = availableServersFor(pubkey)
            )
        }

        if (pubkey != null) {
            viewModelScope.launch {
                userRepository.observeProfile(pubkey).collectLatest { profile ->
                    _state.update { it.copy(currentUserProfile = profile) }
                }
            }
        }

        if (replyToEventId != null) {
            viewModelScope.launch {
                val target = eventRepository.fetchEventById(replyToEventId)
                _state.update { it.copy(replyToEvent = target) }
                target?.let { event ->
                    val profile = userRepository.getProfile(event.pubkey)
                    _state.update { it.copy(replyToProfile = profile) }
                }
            }
        }

        if (quoteEventId != null) {
            viewModelScope.launch {
                val target = eventRepository.fetchEventById(quoteEventId) ?: return@launch
                val relayHints = eventRepository.getRelayHints(target.pubkey)
                val nevent = Bech32Encoder.encodeNevent(
                    hexEventId = target.id,
                    relayUrls = relayHints,
                    hexAuthorPubkey = target.pubkey,
                    kind = target.kind
                )
                textState.edit {
                    replace(0, 0, "\n\nnostr:$nevent")
                    selection = TextRange(0)
                }
            }
        }

        viewModelScope.launch {
            snapshotFlow { textState.text }
                .collectLatest { text ->
                    val sanitization = TrackingTokenSanitizer.sanitizeTextWithResult(text.toString())
                    if (sanitization.sanitizedText != text.toString()) {
                        textState.edit {
                            replace(0, length, sanitization.sanitizedText)
                        }
                    }
                    _state.update { it.copy(removedTrackingToken = sanitization.removedTrackingTokens) }
                }
        }

        viewModelScope.launch {
            snapshotFlow { textState.text }
                .debounce(CONTENT_RESOLVE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { content ->
                    resolvePreviewReferences(content.toString())
                }
        }

        viewModelScope.launch {
            snapshotFlow { textState.text to textState.selection }.debounce(MENTION_QUERY_DEBOUNCE_MS).collectLatest { (text, selection) ->
                val query = detectMentionQuery(text.toString(), selection.end)?.query
                if (query.isNullOrEmpty()) {
                    _state.update { it.copy(mentionSuggestions = emptyList()) }
                } else {
                    val results = runCatching {
                        userRepository.searchLocalProfiles(query, MENTION_SUGGESTION_LIMIT)
                    }.getOrElse { emptyList() }
                    _state.update { it.copy(mentionSuggestions = results) }
                }
            }
        }
    }

    /** BUD-03: the user's own server list (priority order) plus the app default as a fallback. */
    private fun availableServersFor(pubkey: String?): List<String> {
        val ownServers = pubkey?.let { userRepository.getServerList(it)?.servers }.orEmpty()
        return (ownServers + DefaultBlossomServer.URL).distinct()
    }

    fun selectMention(profile: UserProfile) {
        val currentText = textState.text.toString()
        val cursorPosition = textState.selection.end
        val match = detectMentionQuery(currentText, cursorPosition) ?: return
        val relayHints = userRepository.getRelayList(profile.pubkey)?.getOutboxRelays().orEmpty()
        val uri = "nostr:" + Bech32Encoder.encodeNprofile(profile.pubkey, relayHints)
        val insertion = "$uri "

        textState.edit {
            replace(match.startIndex, cursorPosition, insertion)
        }

        _state.update {
            it.copy(
                mentionSuggestions = emptyList(),
                quotedAuthorProfiles = it.quotedAuthorProfiles + (profile.pubkey.lowercase() to profile)
            )
        }
    }

    private suspend fun resolvePreviewReferences(content: String) {
        if (content.isBlank()) return
        val draft = _state.value.draftEvent(content)

        val quoteRefs = extractQuotedEventRefs(draft).filterNot { it.id in _state.value.resolvedQuotedEvents }
        val newQuotes = mutableMapOf<String, Event>()
        for (ref in quoteRefs) {
            eventRepository.fetchEventById(ref.id, relayHints = ref.relays)?.let { newQuotes[ref.id] = it }
        }

        val mentionRefs = extractMentionedProfileRefs(draft)
            .filterNot { it.pubkey.lowercase() in _state.value.quotedAuthorProfiles }
        val newProfiles = mutableMapOf<String, UserProfile>()
        for (ref in mentionRefs) {
            trackReferencedAuthorUseCase(ref.pubkey, ref.relays)
            userRepository.getProfile(ref.pubkey)?.let { newProfiles[ref.pubkey.lowercase()] = it }
        }

        if (newQuotes.isNotEmpty() || newProfiles.isNotEmpty()) {
            _state.update {
                it.copy(
                    resolvedQuotedEvents = it.resolvedQuotedEvents + newQuotes,
                    quotedAuthorProfiles = it.quotedAuthorProfiles + newProfiles
                )
            }
        }
    }

    fun getQuotedEvent(id: String): Event? = _state.value.resolvedQuotedEvents[id]
    fun getQuotedEventAuthorProfile(pubkey: String): UserProfile? = _state.value.quotedAuthorProfiles[pubkey.lowercase()]
    fun displayNameForPubkey(pubkey: String): String? =
        _state.value.quotedAuthorProfiles[pubkey.lowercase()]?.getUserDisplayName()

    // ---- Attachments ----

    /** Called by the Screen once a picked (gallery or keyboard-inserted) image finishes stripping. */
    fun onMediaReadyForDialog(bytes: ByteArray, mimeType: String, previewUri: Uri, width: Int?, height: Int?, blurHash: String?) {
        val defaultServer = _state.value.availableUploadServers.firstOrNull() ?: DefaultBlossomServer.URL
        _state.update {
            it.copy(
                pendingUpload = ComposerPendingUpload(
                    bytes = bytes,
                    mimeType = mimeType,
                    previewUri = previewUri,
                    width = width,
                    height = height,
                    blurHash = blurHash,
                    selectedServer = defaultServer
                )
            )
        }
    }

    fun onUploadServerSelected(server: String) {
        _state.update { current -> current.pendingUpload?.let { current.copy(pendingUpload = it.copy(selectedServer = server)) } ?: current }
    }

    fun onAttachmentAltTextChange(altText: String) {
        _state.update { current -> current.pendingUpload?.let { current.copy(pendingUpload = it.copy(altText = altText)) } ?: current }
    }

    fun onSensitiveContentChange(sensitive: Boolean) = _state.update { it.copy(sensitiveContent = sensitive) }

    /** Only called when [MediaMetadataStripper] could not confirm a picked/keyboard-inserted file was cleaned. */
    fun onAttachmentStripFailed() {
        _state.update { it.copy(attachmentError = UiMessage.Res(R.string.error_picture_metadata_strip_failed)) }
    }

    fun clearAttachmentError() = _state.update { it.copy(attachmentError = null) }

    fun cancelAttachmentUpload() = _state.update { it.copy(pendingUpload = null) }

    fun confirmAttachmentUpload() {
        val pending = _state.value.pendingUpload ?: return
        _state.update { it.copy(isUploadingAttachment = true) }
        viewModelScope.launch {
            when (val result = uploadBlossomBlobUseCase(pending.selectedServer, pending.bytes, pending.mimeType, "Upload note attachment")) {
                is BlossomUploadResult.Success -> {
                    val descriptor = result.descriptor
                    val imeta = ImetaTag(
                        url = descriptor.url,
                        mimeType = descriptor.mimeType ?: pending.mimeType,
                        dimensions = if (pending.width != null && pending.height != null) {
                            MediaDimensions(pending.width, pending.height)
                        } else {
                            null
                        },
                        blurhash = pending.blurHash,
                        alt = pending.altText.trim().takeIf { it.isNotBlank() },
                        sha256 = descriptor.sha256.takeIf { it.isNotBlank() },
                        sizeBytes = descriptor.size.takeIf { it > 0 }
                    )
                    appendAttachmentUrl(imeta)
                    _state.update { it.copy(pendingUpload = null) }
                }
                is BlossomUploadResult.SignCancelled -> {
                    _state.update { it.copy(pendingUpload = null) }
                }
                is BlossomUploadResult.Failed -> {
                    logger.d { "Attachment upload error: ${scrubThrowableMessageForLogs(result.error)}" }
                    _state.update { it.copy(pendingUpload = null) }
                }
            }
            _state.update { it.copy(isUploadingAttachment = false) }
        }
    }

    private fun appendAttachmentUrl(imeta: ImetaTag) {
        textState.edit {
            val existingText = toString()
            val separator = if (existingText.isBlank() || existingText.endsWith("\n") || existingText.endsWith(" ")) "" else "\n"
            append(separator + imeta.url)
        }
        _state.update { current ->
            current.copy(
                attachments = current.attachments + imeta
            )
        }
    }

    fun publish() {
        val current = _state.value
        val body = textState.text.toString().trim()
        if (body.isBlank() || !current.canSign || current.isPublishing) return

        // An attachment's URL might have been hand-edited or deleted out of the text after
        // upload — only tag imeta/content-warning for what's actually still in the note.
        val liveAttachments = current.attachments.filter { body.contains(it.url) }
        val sensitiveReason = if (current.sensitiveContent && liveAttachments.isNotEmpty()) "" else null

        _state.update { it.copy(isPublishing = true) }
        viewModelScope.launch {
            val eventJson = current.replyToEvent?.let {
                NostrEventBuilder.reply(body, it, imetaTags = liveAttachments, sensitiveReason = sensitiveReason)
            } ?: NostrEventBuilder.textNote(body, imetaTags = liveAttachments, sensitiveReason = sensitiveReason)
            val signed = try {
                amberSignerGateway.signEvent(eventJson, current.currentUserPubkey)
            } catch (e: Exception) {
                logger.d { "Error requesting signed event: ${scrubThrowableMessageForLogs(e)}" }
                null
            }
            if (signed != null) {
                publishSignedEventUseCase(signed)
                _published.emit(Unit)
            }
            _state.update { it.copy(isPublishing = false) }
        }
    }
}
